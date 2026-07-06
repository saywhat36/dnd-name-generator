# Decisions

Lightweight running log of notable decisions and the tradeoffs
considered. Add a new dated entry whenever a non-trivial choice is made
— especially anything involving a new Spring AI feature, a rejected
alternative, or a correction to an earlier assumption.

## 2026-07-05: Java 21 over 17
Spring Boot 3.x only requires 17, but 21 is current LTS and enables
virtual threads (`spring.threads.virtual.enabled=true`), which suit this
project's workload (slow, blocking LLM calls) without needing WebFlux.

## 2026-07-05: Single `names` table with a `source` column
Considered separate tables for curated vs. AI-generated names. Rejected
in favor of one table with `source` (CURATED / AI_GENERATED) — querying
"all active names for elf/feminine" is one filtered query instead of a
UNION, and favoriting/reporting can reference "a name" generically
without caring where it came from.

## 2026-07-05: Few-shot prompting instead of a vector store
The curated name list is small and already well-structured by
race/gender. A vector store buys nothing here since there's no need for
semantic search — filtering by race+gender already narrows the example
set precisely. This is dynamic few-shot prompting, not RAG in the strict
sense, even though it was initially (incorrectly) described as RAG
during planning.

## 2026-07-05: DB unique constraint, not app-level dedup, as the
correctness mechanism
Initial plan was an app-level set-difference check against existing
names before insert. Caught in review: this is a check-then-act race —
two concurrent requests generating for the same empty race/gender pool
can both pass the check and insert overlapping names. The unique
constraint on `(normalized_name, race, gender)` with
`ON CONFLICT DO NOTHING` is the actual guarantee. App-level filtering
remains, but only as an optimization to avoid wasted generation.

## 2026-07-05: Pool justified by latency and variety, not token cost
Initial framing treated caching/pooling as a cost-saving measure.
Corrected in review: generating five short names costs fractions of a
cent regardless of caching. The pool exists because LLM calls are slow
(seconds) versus a DB read (instant), and to avoid repeat results for
users. Token cost was the wrong variable to optimize for and should not
be cited as the reason for this design going forward.

## 2026-07-05: Async pool replenishment, never synchronous-on-drain
If replenishment happened synchronously when the pool ran low, the
unlucky user who drains it would eat full LLM latency — defeating the
purpose of pooling. Replenishment is threshold-triggered and
asynchronous; requests always serve from whatever is currently in the
database.

## 2026-07-05: Quality gate required before AI names enter the shared
pool
Initial plan had no validation step — anything the model returned would
become permanent, visible-to-everyone data. Risk: real-world names,
names lifted from famous fiction, malformed strings. Added: length
bounds, character whitelist, blocklist check, and a `status` column
(ACTIVE/FLAGGED/REJECTED) so bad entries can be soft-removed without
breaking existing favorites that reference them.

## 2026-07-05: Rejected tool-calling for duplicate-name checking
Initially suggested as a way to practice Spring AI's tool-calling
feature. Rejected on review — a plain DB query is faster and more
reliable than having the model call a tool for this. Tool calling will
be adopted later only for a use case that genuinely benefits from the
model deciding when to invoke it.

## 2026-07-05: Backstories + SSE streaming moved to Phase 3
Originally planned for Phase 1 (paired together since streaming has no
other use case in this project — five short names return too fast for
streaming to matter). Deferred as a pair to keep Phase 1 focused on
core generation/pooling mechanics.

## 2026-07-05: Memory (conversational refinement) excluded from the
shared pool
Refinement requests ("5 more, but darker") deliberately diverge from
base curated style. If merged into the shared pool undifferentiated,
they'd pollute it the same way self-imitation from AI-generated few-shot
examples would. Decision: REFINEMENT-mode generations are recorded in
`generation_log` for observability but never written to `names`.

## 2026-07-05: Shared pool, not per-user
Considered scoping the AI-generated pool per user/session instead of
globally shared. Decided shared is fine for this use case (fantasy names
have no privacy sensitivity) and is simpler. Noted explicitly as a
deliberate choice rather than an unexamined default, since it does
change the product feel from "generated for you" to "a communal bucket."

## 2026-07-05: No gender-neutral name category
Curated source data only contains masculine and feminine names. Not
modeled as a third enum value; can be added later if source data grows
to support it.

## 2026-07-05: Authentication deferred to Phase 2
Simple username/password auth (hashing, login/register, session/JWT,
route security) is valuable practice but is a parallel skill track that
doesn't deepen Spring AI knowledge. Kept out of Phase 1 so it doesn't
entangle with the core AI feature work. `favorites.owner_id` is included
in the Phase 1 schema, unused, specifically to make the eventual
migration a data update rather than a schema rewrite.

## 2026-07-05: Lazy insert-on-favorite for refinement results
Keeping REFINEMENT-mode generations out of `names` (to protect the
shared pool from style drift) created a gap: `favorites.name_id` is an
FK, so there was nothing to point at if a user favorited a refined
result. Considered three options: (a) insert refined names into `names`
immediately with a distinct source, excluded from pool queries; (b)
insert only when a user favorites a result; (c) refined names simply
can't be favorited. Chose (b) -- a new `AI_REFINED` source value is
written to `names` only at the moment of favoriting, with
`ON CONFLICT` handling to link to an existing row if the refined name
collides with something already in the pool. Keeps the pool pure and
only persists names a human actually endorsed.

## 2026-07-05: Global LLM budget moved out of the HTTP rate-limit filter
Originally planned as part of `RateLimitFilter`. Caught in review:
standard name requests never call the LLM synchronously in this
architecture (replenishment is async and off the request path), so a
servlet filter never observes that traffic -- a burst of replenishment
across many race/gender combos could blow through budget while the
filter reports everyone as under limit. The global budget check now
lives inside `PoolReplenishmentService`, around the actual `ChatClient`
call, which is the only place the LLM is invoked for name generation.

## 2026-07-05: Reworded graceful degradation for the async design
The original description ("provider timeout -> serve curated-only for
that request") described the earlier synchronous design and no longer
applies -- the request path never talks to a provider, so there's no
per-request provider error to catch. Degradation for name-serving now
means: replenishment keeps failing, the AI portion of a pool runs dry,
and requests naturally serve whatever's in the DB (mostly curated),
surfaced via a UI notice driven by pool state at read time. Backstory
(Phase 3) is the one path with a real synchronous provider call and
therefore real per-request error handling.

## 2026-07-05: Added a stampede guard to pool replenishment
Without one, N concurrent requests hitting a below-threshold pool would
each trigger their own `@Async` replenishment call before the first one
lands -- the unique constraint keeps the resulting data correct, but
it means paying for N LLM calls to get one batch of names. Added an
in-flight guard per race+gender combo (in-memory compare-and-set for a
single instance; a Postgres advisory lock if this ever runs on more
than one instance) as the first step of `replenish()`.

## 2026-07-05: Native insert path for pool writes, not JPA `saveAll`
`ON CONFLICT DO NOTHING` has no clean JPA equivalent, and in Postgres a
constraint violation marks the enclosing transaction rollback-only, so
"catch and continue" inside a single JPA transaction silently doesn't
work as intended. `NameInsertDao` uses a native query /
`JdbcTemplate` for this one insert path; the rest of the codebase stays
on JPA as normal.

## 2026-07-05: Explicit handling for `@Async` failures
`@Async` methods swallow exceptions silently unless an
`AsyncUncaughtExceptionHandler` is configured. Since the contract for
replenishment is "log every attempt to `generation_log`, success or
failure," the log write is placed in a `finally` block inside an
explicit `try/catch` in `replenish()` itself, rather than relying on
framework-level handling. An explicit executor (thread pool size,
queue) is also configured rather than using Spring's default, and
`replenish()` is only ever called from a different bean than the one
that defines it, since `@Async` only applies through the Spring proxy
and is silently bypassed by same-bean calls.

## 2026-07-05: `varchar` + check constraints instead of native Postgres
enums
Considered `CREATE TYPE ... AS ENUM` for race/gender/source/status.
Rejected -- Hibernate requires casting workarounds for native enums,
and adding a new value later means `ALTER TYPE ... ADD VALUE`, which
carries transactional restrictions that conflict with Flyway's
transactional migrations. `varchar` with a check constraint gives
equivalent safety with a one-line migration to add a new value.

## 2026-07-05: Added `generation_log_id` lineage FK on `names`, kept
denormalized columns too
`provider`/`model`/`prompt_version` on `names` duplicate what
`generation_log` already records. Added a nullable
`generation_log_id` FK for full lineage (including the raw response a
name came from) rather than replacing the denormalized columns --
kept both deliberately, since the denormalized columns are convenient
for filtering/querying and the FK is what you actually want when
debugging a specific batch.

## 2026-07-05: Added missing constraints and index caught in review
- Index on `names(race, gender, status, source)` -- the unique index
  leads with `normalized_name` and is otherwise useless for the actual
  serving query, which runs on every page load.
- Unique constraint on `name_reports(session_id, name_id)` -- without
  it, repeated clicks from one session look like independent reports.
- Check constraint on `favorites`: at least one of `session_id` /
  `owner_id` must be non-null.

## 2026-07-05: Rate limiting scoped to where the LLM is actually called
Per-session/IP HTTP rate limiting only protects endpoints that make a
synchronous LLM call on the request path. In Phase 1, name-serving is
DB-only, so applying `RateLimitFilter` there penalizes a cheap read for
no reason -- it becomes meaningful in Phase 3 once backstory (a
synchronous call) exists. Per-session Bucket4j buckets are backed by
Caffeine with a TTL rather than an unbounded map, since sessions are
just cookies with nothing to naturally evict them.

## 2026-07-05: Prompt version encoded in template filename
Rather than maintaining `prompt_version` as a separate value that could
drift from the prompt actually sent, the version lives in the template
filename itself (e.g. `name-generation-v1.st`), referenced from a
constant next to `PromptTemplateConfig`. Guarantees `generation_log`
always reflects the prompt that was really used.

## 2026-07-05: Package name resolved to `com.dndnamegen.namegen`
`docs/ARCHITECTURE.md` used `com.yourname.namegen` as a placeholder.
Resolved to a generic, non-personal package name matching the repo name
rather than a real username, since this is a public learning repo.

## 2026-07-05: Initial race list for the `names.race` check constraint
Roadmap and architecture docs didn't enumerate specific races. Seeded
the `Foundations` migration with eight common D&D-style races (ELF,
DWARF, HUMAN, HALFLING, ORC, GNOME, DRAGONBORN, TIEFLING), with curated
seed names for the first four (ELF, DWARF, HUMAN, HALFLING) to keep the
initial PR reviewable. Adding a race or backfilling curated names for
the remaining four is a one-line migration, by design (see
`docs/ARCHITECTURE.md`, "Why not native Postgres enums").

## 2026-07-05: `generation_log.race` given the same CHECK constraint as `names.race`
Caught in review: `generation_log.race` had no CHECK constraint, so a
typo'd or stale race value could land in the audit log (written on
every generation attempt) without being rejected, even though the same
value would be rejected on `names`. Added the identical CHECK to
`generation_log.race`. The two lists aren't backed by a shared
Postgres domain/enum (see the enum rationale above), so a future
migration adding a race must update both constraints -- noted inline
in `V1__init.sql`.

## 2026-07-05: Added indexes on `favorites.name_id` and `name_reports.name_id`
Caught in review: both FK columns had no dedicated index -- the only
indexes touching them were the unique constraints led by `session_id`,
which can't serve a lookup/count by `name_id` alone (e.g. "how many
favorites does this name have"). Added `idx_favorites_name_id` and
`idx_name_reports_name_id`.

## 2026-07-05: First Spring AI feature -- ChatClient via the OpenAI-compatible
starter, targeting Groq
No native Groq starter exists for Spring AI, so `spring-ai-starter-model-openai`
is used against Groq's OpenAI-compatible endpoint (`base-url` override,
per-provider `application-groq.yml` rather than merging into the shared
`application.yml` -- see the provider-switching rationale in
`docs/ARCHITECTURE.md`). `spring-ai-bom` is pinned to the GA release
(`1.1.2`), available on Maven Central -- no milestone repository needed.
Week 1 scope is deliberately minimal: one `ChatClient` bean
(`ChatClientConfig`), one plain-text method
(`NameGenerationService.testPrompt`) -- no prompt template, no structured
output, no `ChatOptions` tuning. Those land in Week 2+ once the pipe is
proven.

## 2026-07-05: Eval test excluded from `mvn test` via explicit Surefire
config, not `*IT` naming alone
Verified empirically that Surefire 3.2.5's default include patterns
(`**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`)
do **not** match `*IT`-suffixed classes -- `MigrationIT` was never picked
up by the default `mvn test` run in this project either, since no
`maven-failsafe-plugin` is configured to bind `*IT` classes to a lifecycle
phase. That means `*IT` naming alone was never sufficient to keep
`NameGenerationServiceEvalIT` out of CI (there was no CI inclusion of
`*IT` classes to opt out of), and it also means `MigrationIT` itself has
no automatic execution path today -- a gap this decision doesn't close.
Added an explicit `**/*EvalIT.java` exclude to the `maven-surefire-plugin`
config in `pom.xml`, scoped to the new `*EvalIT` suffix so future
non-`Eval` `*IT` classes aren't affected by this exclude either way. Run
manually: `GEMINI_API_KEY=... ./mvnw test -Dtest=NameGenerationServiceEvalIT`.
`@BeforeEach` also calls `assumeTrue` on `GEMINI_API_KEY` being set, as a
second layer of defense for that direct-run path.

## 2026-07-05: Placeholder `spring.ai.openai.api-key` in base `application.yml`
Caught during manual verification: `OpenAiChatAutoConfiguration` requires a
non-blank API key to construct its beans at startup, regardless of which
Spring profile is active -- it isn't gated on the `groq` profile just
because that's the only place `base-url` is overridden. Without a
placeholder, the app fails to start at all (`./mvnw spring-boot:run
-Dspring-boot.run.profiles=local`, no AI feature in use yet) unless
`GROQ_API_KEY` happens to be set. Added
`api-key: ${GROQ_API_KEY:not-configured}` to the base `application.yml`,
with a comment explaining why -- the real key and Groq `base-url` still
come from `application-groq.yml` when that profile is active.

## 2026-07-05: Switched default provider from Groq to Gemini -- Groq
dropped its free tier
The three entries above (Groq via the OpenAI-compatible starter,
`application-groq.yml`, `GROQ_API_KEY`) are superseded by this one --
Groq no longer has a free tier, which was the whole reason it was picked
as the Phase 1 default for a learning project. Switched to Google's
Gemini Developer API (AI Studio), which still has a genuine free tier
with no credit card required.

- Dependency: `spring-ai-starter-model-google-genai` (native starter,
  not an OpenAI-compatibility shim like Groq needed) -- confirmed on
  `spring-ai-bom:1.1.2` by unpacking the autoconfigure jar rather than
  guessing artifact/property names, since guessing wrong burned time on
  the Groq/milestone-BOM setup earlier.
- Config prefix is `spring.ai.google.genai.*` (`api-key`,
  `chat.options.model`), not `spring.ai.google.genai.vertex-ai` --
  leaving `vertex-ai` unset (defaults `false`) is what selects the free
  AI-Studio API-key auth path instead of Vertex AI's GCP-project/billing
  path. This distinction matters: Vertex AI Gemini is a *different*
  Spring AI starter (`spring-ai-starter-model-vertex-ai-gemini`) and is
  not free.
- Model: `gemini-2.5-flash` -- fast/cheap tier suited to short name-list
  generations, matches the free tier's rate limits better than the
  `-pro` variants.
- `application-groq.yml` replaced with `application-gemini.yml`; base
  `application.yml`'s placeholder key property moved from
  `spring.ai.openai.api-key` to `spring.ai.google.genai.api-key` for the
  same eager-autoconfiguration reason described above.
- `NameGenerationServiceEvalIT` now activates the `gemini` profile and
  checks `GEMINI_API_KEY` instead of `GROQ_API_KEY`.

## 2026-07-06: Session cookie's `Secure` flag made configurable, defaulting
to `true`
`SessionIdFilter` hardcoded `.secure(true)` on the minted cookie. Browsers
drop `Secure` cookies received over plain HTTP, and local dev runs on
plain `http://localhost` with no TLS anywhere in the stack -- so the
cookie was minted fresh on every single request in local dev, silently
breaking the sole identity mechanism for favorites/reports. Caught via
review of #1, but the fix never actually landed before merge.

Fixed by injecting the flag as `app.session-cookie.secure`
(`SESSION_COOKIE_SECURE` env var), defaulting to `true` so production
stays secure-by-default; local dev sets `SESSION_COOKIE_SECURE=false` to
persist the cookie over HTTP. Documented in `README.md`. Considered
switching the default to `false` instead and requiring an explicit opt-in
for production, but that inverts the safer default for a project that
will eventually run somewhere with real TLS.

## 2026-07-06: `NameSuggestion` record + structured output via `ChatClient.entity()`
First Week 2 slice: added `NameSuggestion(String name)` in `name/dto/` and
`NameGenerationService.generateNameSuggestions(String promptText)`, which
calls `ChatClient`'s structured-output support
(`entity(ParameterizedTypeReference<List<NameSuggestion>>)`) instead of
hand-parsing model text, per the project's structured-output convention.
Kept `testPrompt` in place rather than replacing it in this PR --
`NameGenerationServiceEvalIT` still exercises it, and Week 2 is being
landed as one small reviewable PR per roadmap item, so plain-text ->
structured-output cutover for the real generation flow is deferred to a
later slice (prompt template externalization, few-shot examples, quality
gate, dedup, and the native insert path) rather than bundled here. The
prompt passed to `generateNameSuggestions` is still a raw string in this
PR -- externalizing it to `name-generation-v1.st` is the next Week 2 item,
not this one.

## 2026-07-06: Externalized `name-generation-v1.st` prompt template with
CURATED-only few-shot examples
Second Week 2 slice. Added `src/main/resources/prompts/name-generation-v1.st`
(loaded as a classpath `Resource` in the new `PromptTemplateConfig`, which
also holds `NAME_GENERATION_PROMPT_VERSION = "v1"` as a constant kept in
sync with the filename per the prompt-versioning rationale in
`docs/ARCHITECTURE.md`) and a new
`NameGenerationService.generateNameSuggestions(Race, Gender, int)` overload
that renders the template with few-shot examples queried via the existing
`NameRepository.findByRaceAndGenderAndStatusAndSource(...)`, hardcoded to
`NameSource.CURATED` / `NameStatus.ACTIVE` -- never `AI_GENERATED` or
`AI_REFINED`, per the hard rule against self-imitation drift. The
raw-string `generateNameSuggestions(String)` overload from the previous PR
is kept and reused internally rather than duplicated, since the actual
`ChatClient` call/structured-output path doesn't change, only how the
prompt text is produced.

Quality gate, deduplication, the native insert path, retry, and
`generation_log` writes are still not wired in -- those are the next four
Week 2 items -- so this PR's new method returns raw, unfiltered
`NameSuggestion`s, same as before.
