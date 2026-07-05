# Architecture

## Tech stack
- Java 21 (LTS; virtual threads via `spring.threads.virtual.enabled=true`
  suit the blocking, slow-I/O nature of LLM calls without reaching for
  WebFlux)
- Spring Boot 3.x, Maven
- Spring AI — ChatClient API for all model interaction
- Postgres, schema managed by Flyway migrations
  (`src/main/resources/db/migration/`)
- Docker Compose for local Postgres (Spring Boot's built-in
  docker-compose support)
- Testcontainers for Postgres in integration tests
- htmx + Thymeleaf frontend — stays in the Spring ecosystem, and htmx's
  SSE extension will make Phase 3 streaming close to free on the client
- Providers: Groq as default (via the OpenAI-compatible starter, base
  URL override), plus one additional provider (OpenAI or Anthropic,
  native starter) wired in via Spring profiles
  (`application-groq.yml`, `application-anthropic.yml`, etc.)
  rather than a single shared properties file — structured output
  support and option names are not identical across providers, so
  switching providers means re-verifying prompts and parsing, not just
  flipping a property.
- Bucket4j for rate limiting, backed by Caffeine with a TTL (per-session
  buckets are keyed on a cookie value, so an unbounded map would grow
  forever — do not use a plain `ConcurrentHashMap` here)

## Package layout (by feature, not by layer)

```
com.yourname.namegen
├── NamegenApplication.java
├── name/
│   ├── Name.java, Race.java, Gender.java, NameSource.java, NameStatus.java
│   ├── NameRepository.java
│   ├── NameInsertDao.java        # native insert path, see "Inserts" below
│   ├── NameService.java          # orchestrates curated + pool + toggle
│   ├── NameController.java       # htmx-facing endpoints
│   └── dto/                      # NameRequest, NameSuggestion records
├── generation/
│   ├── GenerationMode.java       # STANDARD, REFINEMENT
│   ├── NameGenerationService.java  # ChatClient calls, few-shot prompt build
│   ├── DeduplicationService.java   # pre-filter; DB constraint is backstop
│   ├── QualityGateService.java     # length/charset/blocklist checks
│   ├── PoolReplenishmentService.java  # @Async, threshold-triggered,
│   │                                  # owns the stampede guard and the
│   │                                  # global LLM budget check
│   ├── GenerationLog.java, GenerationLogRepository.java
├── favorite/
│   ├── Favorite.java, FavoriteRepository.java
│   ├── FavoriteController.java, FavoriteService.java  # handles lazy
│   │                                  # insert-on-favorite for refined names
├── report/
│   ├── NameReport.java, NameReportRepository.java, NameReportController.java
├── session/
│   └── SessionIdFilter.java      # server-issued cookie, mints if absent
├── ratelimit/
│   └── RateLimitFilter.java      # applied selectively — see Rate limiting
├── config/
│   ├── ChatClientConfig.java     # per-provider beans, ChatOptions per use case
│   ├── AsyncConfig.java          # explicit executor + exception handler
│   └── PromptTemplateConfig.java # loads .st classpath resources
└── web/                          # Thymeleaf controllers/fragments
```

Resources:
```
src/main/resources/
├── prompts/
│   └── name-generation-v1.st     # version encoded in filename — see
│                                  # "Prompt versioning" below
├── db/migration/
│   └── V1__init.sql
└── templates/                    # Thymeleaf views/fragments
```

## Data model

### `names`
Single table for curated and AI-generated names, including favorited
refinement names (see "Refinement and favorites" below).

| column | notes |
|---|---|
| id | bigserial PK |
| display_name | as shown to users |
| normalized_name | trim + lowercase + Unicode NFC, used for dedup |
| race | `varchar` + check constraint, **not** a native Postgres enum — see rationale below |
| gender | `varchar` + check constraint: MASCULINE, FEMININE |
| source | `varchar` + check constraint: CURATED, AI_GENERATED, AI_REFINED |
| status | `varchar` + check constraint: ACTIVE, FLAGGED, REJECTED (default ACTIVE) |
| provider | nullable, set only for AI_GENERATED / AI_REFINED |
| model | nullable |
| prompt_version | nullable |
| generation_log_id | nullable FK -> generation_log |
| created_at | |

**Unique constraint on `(normalized_name, race, gender)`.** This is the
actual correctness mechanism for deduplication -- two concurrent
generations both inserting "Aelric" for elf/feminine is a real race that
application-level set-difference checks cannot prevent on their own.

**Why not native Postgres enums:** Hibernate needs casting workarounds
for them, and adding a new race later means `ALTER TYPE ... ADD VALUE`,
which has transaction restrictions that conflict with Flyway's
transactional migrations. `varchar` + check constraint gives the same
safety with none of the friction -- adding a race is a one-line
migration.

**Index:** `(race, gender, status, source)` -- the unique index leads
with `normalized_name`, so it's useless for the actual serving query
(`WHERE race = ? AND gender = ? AND status = 'ACTIVE' AND source IN
(...)`), which runs on every page load.

**On `provider`/`model`/`prompt_version` vs. `generation_log_id`:**
these are deliberately stored twice -- once denormalized on `names` for
query convenience, and once properly via the FK to `generation_log`,
which also gives you the full `raw_response` a given name came out of
when debugging a weird batch. This duplication is intentional, not an
oversight.

**Serving a random subset:** `ORDER BY random() LIMIT n` against the
filtered set. Fine at hundreds of rows per combo -- it's a full scan by
design, revisit only if a combo's pool grows very large.

### `favorites`
| column | notes |
|---|---|
| id | |
| name_id | FK -> names |
| session_id | nullable |
| owner_id | nullable; unpopulated until Phase 2 auth exists |
| created_at | |

Unique constraint on `(session_id, name_id)`. Check constraint: at
least one of `session_id` / `owner_id` must be non-null. `owner_id` is
included now, unused, specifically so the Phase 2 migration is a data
update rather than a schema rewrite -- see "Phase 2 favorites migration"
below.

### `generation_log`
Audit table -- records every LLM generation attempt, regardless of
outcome. This is the observability mechanism for the pool: it's how you
answer "why does this pool look weird" or "how often does parsing fail"
after the fact, and it's where REFINEMENT-mode (memory feature)
generations live by default, since those must never write to `names`
unless a user favorites a result (see below).

| column | notes |
|---|---|
| id, timestamp | |
| race, gender | |
| mode | STANDARD, REFINEMENT |
| provider, model, prompt_version | |
| raw_response | text |
| names_requested, names_accepted | |
| names_rejected_duplicate, names_rejected_quality | |
| parse_success | boolean |
| error_message | nullable |

### `name_reports`
| column | notes |
|---|---|
| id | |
| name_id | FK -> names |
| session_id | |
| reason | nullable |
| created_at | |

**Unique constraint on `(session_id, name_id)`** -- without it, one user
clicking report five times looks like five reports, which corrupts any
future threshold-triggered auto-flagging.

A report is a raw signal, not an automatic status change -- flagging to
`FLAGGED` is a separate action (manually reviewed initially).

## Refinement and favorites

REFINEMENT-mode generations (from the deferred memory feature) are
logged to `generation_log` but do **not** write to `names` by default --
they deliberately diverge from the base curated style and would
pollute the shared pool if merged in undifferentiated.

This creates a gap: if a user favorites a refined name, there's nothing
for `favorites.name_id` to point at. Resolution: **lazy insert-on-favorite**.
When a refinement result is favorited, `FavoriteService` inserts a row
into `names` at that moment, with `source = AI_REFINED`
(excluded from pool-serving queries, but a real row) and
`generation_log_id` pointing back to the refinement call it came from.
The insert uses the same `ON CONFLICT (normalized_name, race, gender)`
handling as pool inserts -- if the refined name happens to collide with
an existing row (e.g. a name already in the curated set or AI pool),
the favorite links to the existing row instead of creating a duplicate,
which is the correct behavior.

The pool stays pure (only `AI_GENERATED` rows are served as suggestions),
and only names a human actually endorsed get persisted from refinement.

## Request flow -- standard name request

1. Browser (htmx) sends `GET /names?race=ELF&gender=FEMININE&source=BOTH`
   with session cookie attached.
2. `SessionIdFilter` mints a cookie if none exists.
3. `NameController` -> `NameService.getNames(...)`.
4. `NameService` queries `NameRepository` for `ACTIVE` rows matching
   race/gender/source filter (CURATED / AI_GENERATED / BOTH).
5. **The request always serves from what's already in the database.**
   It never blocks on an LLM call, and never talks to a provider
   directly -- there is no per-request provider error to handle here.
6. If the pool for that race+gender combo is below a configured
   threshold, `PoolReplenishmentService.replenish(...)` is triggered
   asynchronously (`@Async`) -- this request does not wait on it, and
   does not need to know whether it succeeds.
7. A random subset (config-driven batch size) of available names is
   returned as an htmx fragment. If the AI portion of the pool is
   currently empty for this combo, the response can include a small
   "AI names running low, check back shortly" notice -- this is driven
   by pool state at read time, not by catching a provider error.

Rate limiting is **not** applied on this path by default -- it's a cheap
DB read with no LLM call attached, so a blanket per-session/IP limit
here doesn't protect anything meaningful. See "Rate limiting" below.

## Replenishment flow (async, off the request path)

`PoolReplenishmentService.replenish(race, gender)`:

1. **Stampede guard first.** Before doing anything else, attempt to
   claim an in-flight lock for this combo (a
   `ConcurrentHashMap<ComboKey, AtomicBoolean>` with compare-and-set is
   enough for a single instance; use a Postgres advisory lock
   (`pg_try_advisory_lock`) if this ever runs on more than one
   instance). If the lock isn't acquired, another replenishment for
   this combo is already in flight -- return immediately. Without this,
   N concurrent requests below threshold trigger N LLM calls for one
   batch of names.
2. **Global LLM budget check**, here -- not in `RateLimitFilter`, since
   this is the only place the LLM is actually called for name
   generation. If the daily/global budget is exhausted, log a
   `generation_log` row noting the skip and return.
3. **Per-combo pool cap check.** If this combo is already at its
   configured cap, skip generation entirely -- this is the second cost
   circuit breaker.
4. Build a few-shot prompt from the `.st` template, populated with
   example names queried from `names` where `source = CURATED` **only**.
   Never source examples from `AI_GENERATED` or `AI_REFINED` rows -- this
   prevents the model drifting by imitating its own prior output.
5. Call `ChatClient` with generation-specific `ChatOptions` (higher
   temperature for variety), requesting structured output mapped to
   `List<NameSuggestion>`.
6. Bounded retry (e.g. 3 attempts) if structured output fails to parse
   -- provider behavior differs here (native JSON-schema enforcement vs.
   prompt-based coercion), which is itself a provider-switching lesson.
7. For each candidate: normalize -> `QualityGateService` (length bounds,
   character whitelist, blocklist check for famous/real names) ->
   `DeduplicationService` pre-filter against existing DB rows for that
   race+gender.
8. **Insert survivors via a native query or `JdbcTemplate`, not
   `JpaRepository.saveAll()`.** JPA has no clean mapping for
   `ON CONFLICT DO NOTHING`, and in Postgres a constraint violation
   marks the whole transaction rollback-only -- "catch and continue"
   inside one JPA transaction does not work. `NameInsertDao` owns this
   one native `INSERT ... ON CONFLICT (normalized_name, race, gender)
   DO NOTHING` path; everything else in the codebase can stay JPA.
9. If under-yield after quality/dedup filtering, bounded retry the
   whole generation step (still respecting the pool cap).
10. **Always log the outcome**, success or failure, inside a
    `try/catch` with the `generation_log` write in a `finally` block --
    `@Async` methods fail silently by default unless you've configured
    an `AsyncUncaughtExceptionHandler`, and the contract here ("log
    every attempt") depends on this being explicit rather than assumed.
11. Release the in-flight lock from step 1.

**Executor:** define an explicit `@Async` executor (thread pool size,
queue capacity) in `AsyncConfig` rather than relying on Spring's
`SimpleAsyncTaskExecutor` default. Also note: `@Async` only takes effect
through the Spring proxy -- calling `replenish()` from another method on
the *same* bean bypasses it silently. `NameService` calling into
`PoolReplenishmentService` (a different bean) avoids this trap; don't
refactor these together later.

## Rate limiting

Scoped deliberately, not blanket:
- **Global LLM budget** -- enforced inside `PoolReplenishmentService`,
  around the `ChatClient` call. This is the actual bill-protection
  mechanism, and it has to live where the LLM is called, since standard
  name requests never reach the HTTP-level filter for LLM purposes.
- **`RateLimitFilter` (per-session, per-IP)** -- applies to endpoints
  that make a *synchronous* LLM call on the request path. In Phase 1
  that's effectively none (name-serving is DB-only); it becomes
  meaningful in Phase 3 for the backstory endpoint. Applying it to
  name-serving today would penalize a cheap DB read for no reason.
- Per-session Bucket4j buckets are backed by Caffeine with a TTL, not
  an unbounded map -- sessions are just cookies, so nothing naturally
  evicts them otherwise.

## Prompt versioning
Version is encoded in the template itself -- e.g.
`name-generation-v1.st` -- with the version referenced from a constant
next to `PromptTemplateConfig`, rather than a separately-maintained
value. This guarantees `generation_log.prompt_version` always matches
the prompt that was actually sent, instead of drifting from it.

## Memory / conversational refinement (deferred, Phase 1 optional)
When built: Spring AI's `ChatMemory` + `MessageChatMemoryAdvisor`, keyed
on the existing session ID as conversation ID. Refinement generations
are logged to `generation_log` with `mode = REFINEMENT`. They are not
written to `names` at generation time -- only if and when a user
favorites a specific result (see "Refinement and favorites" above).

## Observability
- Spring AI's Micrometer instrumentation via Actuator -- token usage and
  latency per provider, making provider-switching experiments
  measurable rather than anecdotal.
- `SimpleLoggerAdvisor` on the `ChatClient` for prompt/response logging.

## Testing strategy
- Unit/service tests mock `ChatModel`/`ChatClient` -- no live API calls
  in the standard test suite.
- Testcontainers for Postgres-backed repository/integration tests,
  including a test that exercises the `ON CONFLICT DO NOTHING` native
  insert path under concurrent writers.
- A small eval-style test that calls the real provider and asserts:
  output parses, correct count returned, no duplicates against curated
  names. Run separately from the main suite (not on every build) given
  it requires a live key.

## Phase boundaries
- **Phase 1**: everything above.
- **Phase 2** (deferred, separate skill track from Spring AI): real
  username/password auth -- password hashing, login/register endpoints,
  session or JWT handling, route-level security. Migrate `favorites`
  (and memory, if built) from session-keyed to `owner_id`-keyed.
  **Migration note:** backfilling `owner_id` onto existing session-keyed
  favorites can produce a genuine duplicate if one person favorited the
  same name under two different browser sessions before logging in --
  `(session_id, name_id)` uniqueness doesn't protect `(owner_id,
  name_id)` uniqueness after backfill. Run the backfill `UPDATE` with
  `ON CONFLICT DO NOTHING` against a new `(owner_id, name_id)` unique
  constraint added at that point.
- **Phase 3** (deferred): backstory generation per name, using SSE
  streaming. Deferred together because streaming has no other use case
  in this project -- 5 short names return too fast for streaming to be
  visible, so it doesn't belong in Phase 1 without a real reason to use
  it. This is also where `RateLimitFilter` becomes meaningful on a
  request path, since backstory is a synchronous LLM call. Persistence
  model (shared/cached on the name vs. per-favorite/per-user) must be
  decided before implementation -- it changes the schema.

## Explicitly rejected approaches
- **Vector-store-based retrieval for style matching** -- rejected in
  favor of dynamic few-shot prompting. The curated data is small and
  already well-structured by race/gender; a vector store buys nothing
  here. (Note: the few-shot approach is sometimes loosely called "RAG"
  in conversation about this project -- it is not, in the strict sense,
  and should not be documented as such.)
- **Tool-calling for duplicate-name checking** -- rejected. This is a
  plain DB query; making the model call a tool for it adds latency and
  unreliability for no benefit. Tool calling should be adopted later
  only for a use case that actually benefits from the model deciding
  when to invoke it, not manufactured to fill a roadmap slot.
- **Token/API cost as the justification for the shared pool** -- cost
  for a handful of short names per call is negligible. The pool exists
  for latency (LLM calls are slow; DB reads are fast) and variety
  (avoiding repeat results), not cost savings.
- **LLM-instructed deduplication** ("don't repeat these names") --
  rejected as a correctness mechanism. Long negative/exclusion lists are
  unreliable for LLMs to honor, and the list is unbounded as the pool
  grows. The DB unique constraint is the real mechanism.
- **App-level set-difference as the sole dedup guarantee** -- rejected;
  it's a check-then-act race under concurrency. Kept as a pre-filter
  optimization, with the DB unique constraint as the actual guarantee.
- **Native Postgres enum types** for race/gender/source/status --
  rejected in favor of `varchar` + check constraints, to avoid Hibernate
  casting friction and `ALTER TYPE` transactional restrictions that
  conflict with Flyway.
- **HTTP-level rate limiting as the global LLM cost guard** -- rejected;
  standard name requests don't call the LLM synchronously, so a servlet
  filter never observes replenishment traffic. The global budget is
  enforced inside `PoolReplenishmentService` instead.
