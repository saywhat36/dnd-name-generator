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

Race/gender are passed to the template as `race.name()` / `gender.name()`
explicitly, not the enum objects themselves -- caught in review: `Map.of()`
would otherwise rely on `Race`/`Gender` having no custom `toString()`, which
is true today but would silently change what "v1" renders if either enum
ever gained one, without the filename-encoded version actually changing to
match. Also caught in review, deliberately not fixed here: if a race/gender
combo has zero CURATED rows, `examples` renders as an empty string and the
template still gets sent to the model with a hollow "match these examples"
section and nothing to match. No guard is added yet since Week 2 has no
per-combo skip/threshold logic until `PoolReplenishmentService` (Week 3)
owns pool-cap and generation-trigger decisions -- tracked here rather than
worked around locally so it isn't forgotten.

## 2026-07-06: `QualityGateService` -- length, charset, blocklist
Third Week 2 slice. Added `QualityGateService.passesQualityGate(String)`,
checking (in order) length bounds, an allowed-character whitelist
(Unicode letters plus a single apostrophe/hyphen/space separator, never
leading/trailing or doubled), and a case-insensitive blocklist -- matching
the three checks named in `docs/ROADMAP.md` and the rationale already
recorded above ("Quality gate required before AI names enter the shared
pool"). Config (`app.quality-gate.min-length`, `max-length`, `blocklist`)
follows the existing `app.session-cookie.secure` convention: plain
`@Value` injection in the constructor, no `@ConfigurationProperties`
class, consistent with `SessionIdFilter`.

The blocklist is seeded with a small illustrative starter list (a few
well-known real/fictional names) in `application.yml`, not an exhaustive
list -- extending it is a config change, not a code change. Considered
loading it from a classpath resource file instead of a property; kept it
as a property for now since the list is short and the pattern already
exists for other `app.*` config in this codebase, revisit if it grows
large enough to need its own file.

Blocklist matching is exact-equality on the trimmed, lowercased candidate
-- not substring matching. Caught in review: this means "Frodo" (first
name only) or "Aragorn the Bold" would not match a blocklist entry of
"Frodo Baggins" or "Aragorn". Accepted as-is for this slice -- the
starter list is illustrative and short, and substring matching risks
false positives (e.g. a legitimately generated "Frodolyn" containing
"Frodo"). Revisit if real generated output shows partial-name evasion is
a real problem in practice, rather than guessing at a fuzzier match now.

This service is a pre-insert filter only, not a correctness mechanism --
per `docs/ARCHITECTURE.md`, wiring it into the actual generate -> filter ->
insert pipeline happens in `PoolReplenishmentService` (Week 3), alongside
`DeduplicationService` and `NameInsertDao` (both still separate,
not-yet-landed Week 2 items).

## 2026-07-06: `DeduplicationService` -- pre-filter, DB constraint remains
the correctness guarantee
Fourth Week 2 slice. Added `DeduplicationService.filterDuplicates(Race,
Gender, List<String>)`, which drops a candidate if its normalized form
already exists among stored rows for that race/gender (via a new
`NameRepository.findNormalizedNameByRaceAndGender(...)` derived
projection query), or if it collides with an earlier candidate already
kept in the same batch. Per the rationale already recorded above ("DB
unique constraint, not app-level dedup, as the correctness mechanism"),
this is explicitly an optimization to avoid wasted generation under
normal conditions -- two concurrent generations for the same
still-empty race/gender pool can both pass this check before either
inserts, so `NameInsertDao`'s `ON CONFLICT DO NOTHING` (not yet landed)
remains the actual guarantee.

The existing-row lookup queries **all** rows for the race/gender,
regardless of `status` or `source` -- the
`(normalized_name, race, gender)` unique constraint applies to every row
in the table, not just `ACTIVE`/`CURATED` ones, so a pre-filter that only
checked active curated names would miss real collisions against
`FLAGGED`/`REJECTED`/`AI_GENERATED` rows and let doomed inserts through
to the DB constraint unnecessarily.

`DeduplicationService.normalize(String)` is exposed as a public static
method implementing the canonical form from `docs/ARCHITECTURE.md` (trim,
lowercase, Unicode NFC) -- the same normalization
[dnd-name-generator#20](https://github.com/saywhat36/dnd-name-generator/issues/20)
flagged as missing from `QualityGateService`. Kept in one place here
rather than duplicated, so whichever component computes `normalized_name`
for storage (`NameInsertDao`, next Week 2 item) can reuse it instead of
re-implementing the same trim/lowercase/NFC logic.

## 2026-07-06: `NameInsertDao` -- native `ON CONFLICT DO NOTHING` insert path
Fifth Week 2 slice. Added `NameInsertDao.insertGenerated(Race, Gender,
List<String> displayNames, String provider, String model, String
promptVersion, Long generationLogId)` in `name/`, using `JdbcTemplate`
with one `INSERT ... ON CONFLICT (normalized_name, race, gender) DO
NOTHING` statement per candidate rather than a single batched statement.
One statement per row was chosen deliberately over
`JdbcTemplate.batchUpdate` so `jdbcTemplate.update(...)`'s per-statement
return value (0 or 1 rows affected) can be summed into an accurate
inserted-count -- the Week 3 replenishment path needs to know how many
candidates actually landed (vs. were silently dropped by the conflict
clause) to decide whether to retry for under-yield, and a single batched
statement's aggregate return value would not distinguish "3 of 5
inserted" from "5 of 5 inserted, 3 rows affected for some other reason."
Reuses `DeduplicationService.normalize(String)` for the `normalized_name`
value rather than re-implementing trim/lowercase/NFC here, per the
rationale already recorded above. Source/status are hardcoded to
`AI_GENERATED`/`ACTIVE` -- this DAO is scoped to the pool-write path only;
the lazy insert-on-favorite path for `AI_REFINED` rows (Week 5) is
expected to need its own insert call with a different source value, not
a parameter added here speculatively.

Tested against a real Postgres via Testcontainers
(`NameInsertDaoIT`, matching `MigrationIT`'s existing pattern) rather
than a mocked `JdbcTemplate` -- the entire point of this class is that
`ON CONFLICT DO NOTHING` actually skips a colliding row without poisoning
the transaction, which a mock verifying "was `update()` called with these
arguments" cannot demonstrate. Attempted a manual run
(`./mvnw test -Dtest=NameInsertDaoIT`) and hit the same pre-existing local
Docker/Testcontainers environment issue already affecting `MigrationIT`
on this machine (`ryuk` image pull failing on Docker API version
mismatch) -- an environment quirk, not evidence against the approach;
noted here rather than worked around.

## 2026-07-06: Bounded retry for parse failures and under-yield lives in
`NameGenerationService`, not a new class
Sixth Week 2 slice. Added `NameGenerationService.generateValidatedNames(Race,
Gender, int count)`, which bounded-retries (default 3 attempts, configurable
via `app.generation.max-attempts`, following the existing `app.*` `@Value`
convention) the generate -> quality-gate -> dedup step as a unit -- both when
structured output fails to parse (a `RuntimeException` from the
`ChatClient.entity()` call is caught and the attempt is retried) and when a
successful attempt under-yields (too many candidates rejected by
`QualityGateService` or as duplicates against existing rows/each other). Each
retry only asks the model for the remaining shortfall
(`count - survivors.size()`), and accumulated survivors are re-run through
`DeduplicationService.filterDuplicates` alongside each new attempt's
quality-passed candidates, so a name repeated across two separate attempts is
still caught even though nothing has been inserted yet to catch it via the DB
constraint. Result is capped to `count` if a single attempt yields more than
requested (a model ignoring the count instruction).

Considered a new dedicated class (matching the one-class-per-slice pattern of
`QualityGateService`/`DeduplicationService`/`NameInsertDao`), but
`docs/ARCHITECTURE.md`'s package layout doesn't list one, and this method's
only real responsibility is retrying the `ChatClient` call it already owns --
splitting it out would just relocate a loop around a method already on this
class. `PoolReplenishmentService` (Week 3) will call
`generateValidatedNames` directly rather than reimplementing retry logic, and
will own the remaining replenishment-flow responsibilities this method
deliberately does not: `NameInsertDao` writes, `generation_log` writes for
every attempt (including failures), the stampede guard, and the pool-cap /
global-budget checks. A parse failure that exhausts all retries is not
rethrown -- this method's contract is "best-effort, bounded," and surfacing
per-attempt failures to `generation_log` is explicitly `PoolReplenishmentService`'s
job, not this method's, per `docs/ARCHITECTURE.md`'s replenishment-flow
step 10.

## 2026-07-06: Deployment track -- same-origin on Render + Neon, kept
separate from the Phase 1 learning roadmap
Started a parallel effort to get the project to a small deployed product,
without disturbing the Week 3-6 Spring AI learning items. All deployment
design and runbook detail lives in a new `docs/DEPLOYMENT.md`; `ROADMAP.md`
is deliberately untouched. Key choices:

- **Frontend hosting: served from Render, same origin as the API**, not a
  static site on GitHub Pages. GitHub Pages only serves static files, so
  adopting it would have meant replacing the server-rendered htmx + Thymeleaf
  frontend (committed to in `ARCHITECTURE.md`, and the basis for the Phase 3
  SSE plan) with a fetch/JS SPA -- i.e. quietly rewriting a recorded decision.
  Same-origin also avoids CORS and, more importantly, avoids cross-origin
  session cookies, which would directly complicate the session-keyed Week 5
  favorites/reports. GitHub Pages rejected on that basis.
- **Database: Neon Postgres** (serverless, stock Postgres 16, genuine free
  tier). Flyway runs against it unchanged. Two connection-string constraints
  recorded in `DEPLOYMENT.md`: `sslmode=require`, and use the direct
  (non-pooled) endpoint because Flyway's session-level advisory lock isn't
  supported by Neon's PgBouncer transaction-mode pooler.
- **Additive `prod` profile, not a replacement.** `application-prod.yml`
  carries only deltas (Neon datasource from env, `spring.docker.compose.enabled=false`,
  `server.port=${PORT}`); it activates as `prod,gemini` so the existing
  `application-gemini.yml` provider config is reused, not duplicated. Local
  dev (`./mvnw spring-boot:run`, no profile, compose.yaml auto-start) is
  unchanged.
- **Dockerfile over buildpack** for reproducible pinned builds; tests skipped
  in the image build since `*IT` tests need Docker/Testcontainers unavailable
  in a build container. `render.yaml` committed for reproducibility with all
  secrets `sync: false` (dashboard-only, never in the repo).
- **Free-tier cold start (~50s after ~15m idle) accepted** for a
  demo/portfolio deployment rather than paying for an always-on instance.

## 2026-07-06: `V3` full curated seed -- real 5e dataset, gendered names only
Loaded the real curated dataset via a new versioned Flyway migration
`V3__seed_curated_names_full.sql`, consistent with the existing `V2` seed
pattern (native `INSERT`, now with `ON CONFLICT (normalized_name, race,
gender) DO NOTHING` added for idempotency). Key choices, all deliberate:

- **Gendered names only (594 rows across 6 races: Dwarf, Elf, Gnome,
  Halfling, Half-Orc, Tiefling).** The source data also has non-gender
  categories -- Elf `Child`, Tiefling `Virtue`, and Dwarf/Gnome `Clan` /
  Elf/Halfling `Family` surnames. Deferred, not loaded. `Child`/`Virtue` are
  standalone given names that just aren't masculine/feminine; the `Clan`/
  `Family` lists are surname *components* meant to combine with a first name
  (like the existing "Dorlin Ironfoot" seed), which the current
  single-`display_name` serving model has no concept of. Modeling either would
  mean a schema change (a `name_type` column and/or a new gender value),
  overriding the recorded "no third gender value" decision -- out of scope for
  a seed migration and left for a future roadmap item.
- **Added `HALF_ORC` to the race CHECK on both `names` and `generation_log`.**
  The source has Half-Orc names; the constraint only had `ORC`. Half-Orc is a
  distinct race, so `HALF_ORC` was added (and mirrored to the `Race` Java enum)
  rather than conflating it with `ORC`. Because V1's CHECKs are inline/unnamed,
  V3 drops and re-adds them by their Postgres-generated names
  (`names_race_check`, `generation_log_race_check`) -- verified empirically, not
  assumed (see below).
- **Full replace, dropping Human.** V3 does `DELETE FROM names WHERE
  source = 'CURATED'` then inserts the new set. The new data has no Human
  names, so Human curated coverage is intentionally dropped -- Human joins
  the constraint-but-no-curated-data races (like Dragonborn) until AI
  generation or a later seed fills it. Safe DELETE: no `favorites`/
  `name_reports` rows reference curated names yet (Week 5 unbuilt).
- **Data cleaning applied:** the raw Dwarf Female list was contaminated (its
  back half was the Dwarf Male list pasted in reverse) -- truncated to the 32
  genuine female names. Gnome Female "Alboe/Albaraite" (a slash-separated
  alternate spelling) reduced to "Alboe". Source typos (e.g. "Zannna") left
  as-is -- not this migration's job to correct spellings.
- **Verified** by applying V1 -> V2 -> V3 against a throwaway
  `postgres:16-alpine` container: migrations apply cleanly (confirming the
  inline-CHECK constraint names), 594 curated rows land with the expected
  per-race/gender counts, `HALF_ORC` inserts succeed, and a bogus race value is
  still rejected. This sidesteps the local Testcontainers/`ryuk` Docker issue
  noted in earlier decisions -- a plain container + `psql` needs no Testcontainers.

## 2026-07-06: Minimal browse-only slice of the Week 6 frontend, built ahead
of Weeks 3-5
Wanted a UI for the now-deployed product, but the full Week 6 item (race/
gender picker, source toggle, favorite/report actions) depends on Weeks 3-5
(`PoolReplenishmentService`, provider switching, favoriting/reporting), none
of which are built yet. Rather than reordering `ROADMAP.md` or waiting,
built a deliberately partial slice: `NameBrowserController` (new `web/`
package, matching `ARCHITECTURE.md`'s package layout) with `GET /` (full
page) and `GET /browse` (htmx fragment, returns the `index :: list`
fragment), backed directly by the existing `NameService.getNames(Race,
Gender)` -- which already hardcodes `CURATED`/`ACTIVE` today, so this slice
needed no service changes. No source toggle, no favorite/report buttons, no
pool-low notice -- those still land with Week 6 proper once Weeks 3-5 exist.
`ROADMAP.md` itself is left unchanged; this isn't a reordering, just an
early, intentionally incomplete slice of one of its items.

Added `spring-boot-starter-thymeleaf` (previously absent). htmx is pulled
from a CDN `<script>` tag rather than a webjar dependency -- simplest option
for one page; revisit if the real Week 6 build wants a pinned/offline copy.
The results fragment is defined inline in `index.html` via
`th:fragment="list"` rather than a separate `templates/fragments/` file,
since there's only one fragment so far; splitting it out is deferred until
a second fragment actually exists.

Verified by running the app locally against real Postgres
(`./mvnw spring-boot:run`, `SESSION_COOKIE_SECURE=false`) and curling `/`
and `/browse` directly -- confirmed the default page renders real V3 curated
names with the correct dropdown selection state, `/browse` returns a bare
`<ul>` fragment suitable for an htmx swap, and the empty-race case (e.g.
Human, dropped in V3) renders the "no curated names yet" message. Also
confirmed the explicit `@GetMapping("/")` takes priority over Spring Boot's
`WelcomePageHandlerMapping` auto-detection of `templates/index.html` (it
logs "Adding welcome page template: index" regardless, but the explicit
controller mapping wins).

Could not execute `NameBrowserControllerTest` (or the pre-existing
`NameControllerTest`) via `./mvnw test` on this machine -- only JDK 26 is
installed locally, and Mockito's Byte Buddy inline mock maker doesn't yet
support it (`Java 26 (70) is not supported ... officially supports Java
23`). Confirmed this is a pre-existing local-environment gap, not caused by
this change, by reproducing the identical failure against the already-merged
`NameControllerTest`. Test code compiles cleanly (`./mvnw test-compile`);
runtime verification for this slice relied on the manual curl checks above
instead. Revisit once a Java 21/23 JDK is available locally or Mockito/Byte
Buddy ships Java 26 support.

## 2026-07-06: `AsyncConfig` -- explicit executor for pool replenishment
First Week 3 slice. Added `AsyncConfig` (`config/`) implementing
`AsyncConfigurer`, with a `ThreadPoolTaskExecutor` bean named
`poolReplenishmentExecutor` (core/max pool size and queue capacity
configurable via `app.async.pool-replenishment.*`, following the existing
`app.*` `@Value`-injection convention rather than a `@ConfigurationProperties`
class). Landed ahead of `PoolReplenishmentService` itself (next Week 3 slice)
since the roadmap calls it out as its own item, and it has no dependency on
the service that will use it.

Also registered a logging `AsyncUncaughtExceptionHandler` as a
defense-in-depth backstop, not the primary failure-logging mechanism --
`@Async` only routes an uncaught exception to this handler for `void`
methods (never surfaces it to the caller), but the actual "log every
replenishment attempt to `generation_log`, success or failure" contract
described in `docs/ARCHITECTURE.md` belongs in `PoolReplenishmentService`'s
own explicit `try/catch` with the log write in `finally`, landing in the
next slice. This handler exists so that any exception escaping *before*
that try/catch is entered (e.g. a bean-wiring or proxy-level failure) still
gets logged somewhere instead of vanishing silently.

No dedicated test added -- matches the existing pattern for `ChatClientConfig`
and `PromptTemplateConfig` (plain `@Bean` wiring with no branching logic of
its own); behavior is exercised indirectly once `PoolReplenishmentService`
uses this executor.

Caught in review of #29: `queue-capacity` was initially set to 50 against a
`max-pool-size` of 4 -- since `ThreadPoolExecutor` only grows past
`core-pool-size` once its queue is full, a queue that deep would make
`max-pool-size` effectively dead configuration under normal load. Reduced
`queue-capacity` to 8 so the pool can actually reach 4 threads under
moderate contention. Also caught: the class javadoc overclaimed that
`@Async` failures "never surface to the caller" -- true for exceptions
thrown inside the async method body, but queue/pool saturation triggers a
synchronous `TaskRejectedException` at the submission call site instead,
which bypasses `getAsyncUncaughtExceptionHandler()` entirely. Javadoc
reworded to call this out explicitly. Two follow-ups filed rather than
fixed here, since neither has a caller yet to fix against:
[#30](https://github.com/saywhat36/dnd-name-generator/issues/30) (no bounds
validation on the core/max/queue config values) and
[#31](https://github.com/saywhat36/dnd-name-generator/issues/31)
(`PoolReplenishmentService`'s eventual caller must catch
`TaskRejectedException` rather than let it fail a user-facing request).

## 2026-07-06: `PoolReplenishmentService` -- stampede guard, budget/cap
checks, generate-and-insert wiring
Second Week 3 slice. Added `PoolReplenishmentService` (`generation/`), the
`@Async("poolReplenishmentExecutor")` method described in
`docs/ARCHITECTURE.md`'s "Replenishment flow" section. It does not own
generation itself -- that stays in `NameGenerationService.generateValidatedNames`
(Week 2), which already owns its own bounded retry, quality-gate/dedup
filtering, and per-attempt `generation_log` writes. This class owns the outer
cycle: stampede guard, budget/cap/examples gating, the native insert
(`NameInsertDao`), and one guaranteed `generation_log` row per replenishment
cycle (as distinct from `NameGenerationService`'s per-model-call rows).

**Split from the roadmap's single "PoolReplenishmentService,
`@Async`, threshold-triggered" item.** The service and its internal
gating land here; deciding *when* to call `replenish(...)` (the
"threshold-triggered" half) belongs to `NameService`, which doesn't have a
source toggle yet -- that's the next slice. `ROADMAP.md` reflects this split
rather than leaving the whole line unchecked.

**Stampede guard**: `ConcurrentHashMap<ComboKey, Boolean>` with
`putIfAbsent`, per the CAS approach `docs/ARCHITECTURE.md` calls out as
sufficient for a single instance. `ComboKey` is a small `Race`/`Gender`
record rather than a string-concatenation key, to rule out accidental
collisions.

**Budget check reordered relative to `docs/ARCHITECTURE.md`'s literal step
list.** The architecture doc lists the global budget check (step 2) before
the per-combo pool cap check (step 3). Implemented in the opposite order
instead: pool cap and curated-examples checks run first, and the budget
counter is only consumed immediately before the real `generateValidatedNames`
call. Consuming budget earlier would count combos that were never going to
generate anyway (already at cap, or with no curated style anchor) against
the shared daily limit, starving combos that actually need it for no
reason. The day-rollover + counter is a plain `synchronized` method backed
by an `AtomicInteger` and a `LocalDate` field -- single-instance in-memory,
same caveat as the stampede guard above (a shared store would be needed
across multiple instances).

**Resolves [#18](https://github.com/saywhat36/dnd-name-generator/issues/18)**
(hollow generation prompt for a combo with zero `CURATED` examples) by
skipping generation entirely when `NameRepository.countByRaceAndGenderAndStatusAndSource`
returns 0 for `ACTIVE`/`CURATED`, logging the skip rather than calling
`NameGenerationService` at all -- the fix option that issue's "suggested
fix" named as belonging to this class.

**generation_log lineage for inserted rows**: `NameInsertDao.insertGenerated`
needs a single `generationLogId` to attach to every row in one insert batch.
Rather than trying to attribute inserted rows back to whichever of
`NameGenerationService`'s (possibly several, per-retry) attempt-level log
rows produced them, this class saves one additional summary
`generation_log` row per replenishment cycle (`requested` = the batch size
asked for, `accepted` = survivor count after `NameGenerationService`'s own
filtering) and uses *that* row's id as the FK. This keeps the two logging
granularities cleanly separated: attempt-level (model-call diagnostics,
owned by `NameGenerationService`) versus cycle-level (what actually got
persisted, owned by this class).

**Provider/model values**: `NameInsertDao.insertGenerated` takes `provider`/
`model` strings for the inserted `Name` rows, but no bean currently exposes
"the active provider's name" (only one provider exists until Week 4).
Added `app.generation.provider` (set per-provider-profile, e.g.
`application-gemini.yml`, so Week 4's second provider gets its own value
rather than a shared property) and reused the existing
`spring.ai.google.genai.chat.options.model` property directly via `@Value`
for the model, rather than introducing a new property that would just
duplicate it.

**No further retry after `NameInsertDao.insertGenerated` under-yields.**
`NameInsertDao`'s own javadoc notes its `ON CONFLICT DO NOTHING` return
count is "how callers... detect under-yield and decide whether to retry."
Not implemented here: the stampede guard already prevents more than one
in-flight replenishment per combo on a single instance, so an insert-time
conflict for the *same* combo can only happen in a narrow window this
project doesn't need to close yet. Recorded here rather than silently
skipped, since it's flagged as expected future work in `NameInsertDao`
itself, not a newly discovered gap.

**Testing**: `PoolReplenishmentServiceTest` mocks all four collaborators
(`NameGenerationService`, `NameRepository`, `NameInsertDao`,
`GenerationLogRepository`), matching the existing plain-mock convention
(no Spring context) used by `NameGenerationServiceTest`. The stampede-guard
test spawns a real second `Thread` calling `replenish(...)` while the first
call is deliberately blocked (via a `CountDownLatch` inside the mocked
`generateValidatedNames`) to exercise the actual `ConcurrentHashMap`
check -- calling the method directly (not through a `@Async` proxy) means
both calls run on their own threads but the in-flight-map logic itself is
exercised exactly as it would be under the real proxy. Could not execute
`./mvnw test` locally -- same pre-existing JDK 26/Mockito inline-mock-maker
gap already documented above (`Mockito cannot mock this class`, reproduced
here against `NameGenerationService`); confirmed via `./mvnw test-compile`
that the test compiles cleanly, and reasoned through each assertion
manually given the local run is unavailable.

Caught in review of #32, fixed in this PR:

- **Double `generation_log` write on insert failure.** The original
  `logged` boolean + `finally` backstop didn't actually prevent a second
  row: if `NameInsertDao.insertGenerated` threw *after* the success row was
  already saved (to get the FK id insert needs), the outer
  `catch (RuntimeException)` unconditionally called `saveSkip(...)` again,
  producing two rows -- one success, one failure -- for a single cycle.
  Fixed by nesting a nested `try/catch` around only the insert call: a
  failure there is logged via `slf4j` only, not a second `generation_log`
  row, since the row already saved accurately describes what
  `NameGenerationService` produced (the part that actually succeeded). This
  also let the `logged` flag and `finally` backstop be removed entirely --
  every branch now writes its own single row at its own single exit point,
  so there's no shared state to keep in sync across future branches.
  Regression test added:
  `replenish_should_LogExactlyOnce_When_InsertFailsAfterSuccessfulGeneration`.
- **Hardcoded `spring.ai.google.genai.chat.options.model` property path.**
  Reading a Gemini-specific Spring AI property directly (rather than an
  `app.*`-namespaced one) would silently resolve to the `:unknown` default
  the moment Week 4 activates a different provider profile, since that
  property path doesn't exist under e.g. an OpenAI/Anthropic profile --
  contradicts `docs/ARCHITECTURE.md`'s explicit warning that provider
  option names aren't identical across providers. Switched to
  `app.generation.model`, set in `application-gemini.yml` via a YAML alias
  (`&gemini-model` / `*gemini-model`) pointing at the same
  `spring.ai.google.genai.chat.options.model` value, so the two can't drift
  without also being a duplicated literal.

Two more findings recorded rather than fixed here, since both need a
`generation_log` schema/entity decision bigger than this PR's scope:
[#33](https://github.com/saywhat36/dnd-name-generator/issues/33)
(`NameInsertDao`'s under-yield/conflict-drop signal is only logged via
`slf4j`, never persisted) and
[#34](https://github.com/saywhat36/dnd-name-generator/issues/34)
(routine skip reasons -- pool-at-cap, budget-exhausted, no-examples --
reuse `GenerationLog.parseFailure(...)`, polluting `parse_success` for
anyone querying "how often does parsing fail" as `docs/ARCHITECTURE.md`
describes).

## 2026-07-06: Source toggle + threshold-triggered replenishment wired into
`NameService`
Third Week 3 slice, completing the split recorded in the `PoolReplenishmentService`
entry above -- that entry landed the service and its internal gating; this one
adds the CURATED/AI_GENERATED/BOTH toggle and decides *when* `replenish(...)`
gets called.

**New `NameSourceFilter` enum, not a `BOTH` value on `NameSource`.** `NameSource`
is the persisted `names.source` column (`CURATED`/`AI_GENERATED`/`AI_REFINED`) --
`BOTH` is never a real row value, only a request-time filter, so adding it there
would let an invalid state leak into a column that's supposed to describe where a
specific row came from. `NameSourceFilter` lives in `name/` alongside `Race`/
`Gender` and is mapped to a `List<NameSource>` inside `NameService` (`CURATED` ->
`[CURATED]`, `AI_GENERATED` -> `[AI_GENERATED]`, `BOTH` -> `[CURATED,
AI_GENERATED]`).

**New `NameRepository.findByRaceAndGenderAndStatusAndSourceIn(...)`,
existing single-source method left alone.** `BOTH` needs an `IN (...)` query;
rather than replacing the existing `findByRaceAndGenderAndStatusAndSource`
(single `NameSource`) with this, both now coexist --
`NameGenerationService.generateNameSuggestions` still calls the single-source
method to pull CURATED-only few-shot examples (a hard rule, not something this
PR's scope touches), so replacing it would have meant re-verifying an unrelated
code path for no benefit.

**Threshold check only runs when the requested source includes
`AI_GENERATED`.** A CURATED-only request has no reason to look at the AI pool at
all or to trigger `replenish(...)` -- doing so would trigger background
generation work for combos the user never asked to see AI names for. The check
(`ACTIVE`/`AI_GENERATED` count < `app.pool-replenishment.replenish-threshold`,
default 5, same `@Value` convention as the other `pool-replenishment.*`
settings) runs for `AI_GENERATED` and `BOTH` alike. Caught in review: an
earlier version of this check called
`NameRepository.countByRaceAndGenderAndStatusAndSource` again to size the AI
pool, adding a second DB round trip to a path `docs/ARCHITECTURE.md`
explicitly calls "a cheap DB read with no LLM call attached" -- the list
`findByRaceAndGenderAndStatusAndSourceIn` already returned above contains
every ACTIVE/AI_GENERATED row for this combo whenever this branch runs, so
the count is now derived from that list in-memory (filtering on
`Name.getSource()`) instead of a second query.

**Threshold check is separate from `PoolReplenishmentService`'s own pool-cap
check, by design, not duplicated logic.** The two serve different questions
answered at different layers: `NameService`'s threshold decides *whether this
request's read is worth triggering a background refill for* (a low-but-nonzero
signal, checked on the synchronous read path); `PoolReplenishmentService`'s cap
check decides *whether that triggered cycle should actually generate anything*
(an upper bound, checked inside the async cycle, using its own count query
since the pool may have changed between the two checks under concurrent
requests). Collapsing them into one check would require `NameService` to know
about cap/budget concerns that `docs/ARCHITECTURE.md` deliberately scopes to
`PoolReplenishmentService` alone.

**Test for "never blocks on a live LLM call" simulates the `@Async` proxy's
behavior inside the mock, rather than booting a Spring context.** The rest of
this repo's unit tests avoid a Spring context entirely (plain mocks, no
`@SpringBootTest`), and the actual non-blocking guarantee here comes from
Spring's `@Async` proxy on `PoolReplenishmentService.replenish`, which a plain
mock of that class cannot itself exercise -- calling a real, unproxied instance
synchronously in a unit test would trivially block, proving nothing about
`NameService`. Instead, `NameServiceTest`'s
`getNames_should_ReturnWithoutBlocking_When_ReplenishSimulatesASlowProvider`
stubs `replenish(...)` with a Mockito `Answer` that mimics exactly what the real
proxy does at runtime -- hand off to a background thread and return
immediately -- with a deliberately slow (300ms) simulated LLM call on that
thread, then asserts `getNames` returns in under 100ms and that the slow work
still completes shortly after. This is a regression test against `NameService`
itself synchronously waiting on replenishment (e.g. via `.get()` on a returned
future, or a same-bean call bypassing the `@Async` proxy) -- it does not
re-verify Spring's own `@Async` machinery, which is out of scope for a unit
test and already relied on elsewhere in this codebase.

**`NameBrowserController`'s existing curated-only browse slice updated to pass
`NameSourceFilter.CURATED` explicitly**, rather than left broken by the
`NameService.getNames` signature change -- this keeps its documented "no source
toggle yet" behavior identical to before this PR, since Week 6 (not this PR) is
where that controller gets a real toggle.

## 2026-07-07: `favorite/` package -- add/remove/list, first Week 5 slice
First Week 5 slice, scoped to add/remove/list only per `docs/ROADMAP.md`.
Lazy insert-on-favorite for `AI_REFINED` names, `report/`, and the manual
FLAGGED-review flow are separate, later slices -- the first is blocked on
the not-yet-built refinement/memory feature.

**`Favorite` is the first entity this codebase JPA-`save()`s directly**,
unlike `Name` (only ever read via JPA; writes go through `NameInsertDao`'s
native path) or the batch-insert rows `NameInsertDao` itself writes. Gave it
a real public constructor (`sessionId`, `nameId`) that sets
`createdAt = Instant.now()` explicitly, since relying on the column's
`DEFAULT now()` would mean Hibernate sends `NULL` for a `NOT NULL` column on
insert. `nameId` is a plain `Long` FK column, matching the existing
no-`@ManyToOne`-anywhere convention (e.g. `Name.generationLogId`).

**`addFavorite` idempotency**: check `findBySessionIdAndNameId` first, return
the existing row if present. A duplicate-insert race (two concurrent
requests favoriting the same name for the same session) just throws a
catchable `DataIntegrityViolationException` off the `(session_id, name_id)`
unique constraint on a single-row `save()` -- caught, and the row the other
request just inserted is re-read and returned. This is not the
`NameInsertDao` batch-poisoning scenario recorded above ("Native insert path
for pool writes, not JPA `saveAll`") -- that problem is specific to a batch
of `ON CONFLICT DO NOTHING` inserts inside one transaction; a single JPA
`save()` failing and being caught outside that transaction has no such
issue. `removeFavorite` is idempotent for free -- a derived `deleteBy...`
query naturally no-ops when nothing matches.

**`listFavorites` re-orders `findAllById`'s result to match favorite order**
(most recently favorited first) -- Spring Data's `findAllById` does not
guarantee it preserves input order, so returning its result as-is would
silently reorder a user's favorites list on unrelated JPA/driver behavior.

**404 check lives in the controller, not the service.** `FavoriteController`
calls `nameRepository.existsById(nameId)` directly and throws
`ResponseStatusException(NOT_FOUND)` before calling `FavoriteService`,
rather than having the service throw an HTTP-aware exception. No existing
service-to-HTTP exception-translation convention exists in this codebase
yet, so keeping this one check in the controller avoided introducing one for
a single call site.

**Session id read from `SessionIdFilter.REQUEST_ATTRIBUTE`** -- this is the
first code that actually reads the attribute the filter has minted on every
request since Foundations.

Tests: plain-mock `FavoriteServiceTest` (no Spring context, matching
`NameServiceTest`) and `@WebMvcTest` `FavoriteControllerTest` (matching
`NameControllerTest`), including a regression test for the concurrent-insert
race and for `findAllById`'s out-of-order return. Could not run `./mvnw
test` locally -- same pre-existing JDK 26/Mockito inline-mock-maker gap
documented above; confirmed via `./mvnw compile test-compile` that
everything compiles cleanly.

Caught in review of #37, fixed in this PR:

- **`FavoriteControllerTest` was asserting against a session id it didn't
  actually control.** `@WebMvcTest` auto-registers `Filter` beans (documented
  Spring Boot slice behavior), so the real `SessionIdFilter` runs inside the
  test's MockMvc chain. With no cookie on the built request, the filter mints
  its own random UUID and overwrites the `requestAttr` the test had set,
  before `FavoriteController` ever reads it -- every `verify(favoriteService)`
  call in that test class was checking against a session id the test never
  actually produced. Fixed by attaching a real `SessionIdFilter.COOKIE_NAME`
  cookie (a valid UUID, since the filter validates the cookie value as one
  before trusting it) instead of setting the request attribute directly --
  the filter now recognizes and passes the test's session id through instead
  of minting its own.
- **`listFavorites` could put a `null` into the returned list.** If a
  favorited `nameId` had no matching row in `nameRepository.findAllById`,
  `namesById::get` returned `null` for that entry with no filter --
  `FavoriteController` maps this list straight into `NameResponse::from`,
  which would NPE the whole `GET /favorites` response for that session.
  There's no delete path for `Name` today, so this can't happen yet, but
  nothing rules one out later. Filtered with `Objects::nonNull` after the
  `map` rather than leaving it to whichever future path deletes a `Name` row
  to notice.
- **`findBySessionIdOrderByCreatedAtDesc` had no tiebreaker.** `createdAt` is
  set client-side (`Instant.now()` in `Favorite`'s constructor, not the
  column's `DEFAULT now()`), so two favorites added in quick succession for
  the same session can land on the same `Instant` -- `ORDER BY created_at
  DESC` alone gives no stable relative order between them. Added `id DESC` as
  a secondary sort key (`findBySessionIdOrderByCreatedAtDescIdDesc`).

Two more findings considered and deliberately not changed:

- **`FavoriteController.addFavorite` checks `nameRepository.existsById`
  before calling the service**, which is a redundant round trip given
  `favorites.name_id`'s `NOT NULL REFERENCES names(id)` constraint would
  reject an invalid id anyway. Kept as-is: the task requires a specific `404`
  response for this case, and reliably distinguishing an FK violation from
  the `(session_id, name_id)` unique-constraint violation already being
  caught one layer down (`FavoriteService.saveNew`) inside one
  `DataIntegrityViolationException` type is more fragile across JDBC drivers
  than one extra `SELECT` on a write path that isn't hot.
- **`listFavorites` issues two DB round trips** (`findBySessionIdOrder...`
  then `findAllById`) plus an in-app re-order, rather than a single joined
  query. `NameRepository`'s own doc comment on `findByRaceAndGenderAndStatusAndSourceIn`
  cites the same "avoid two queries merged in application code" reasoning for
  the CURATED/AI_GENERATED/BOTH toggle, so this isn't a clean precedent
  match. Not changed in this PR -- a per-session favorites list is bounded
  small and not a hot path, and a joined query would be a bigger change for
  a first slice. Revisit if this list ever needs to scale past a handful of
  rows per session.

## 2026-07-06: Week 6 htmx frontend brought forward; race/gender/source picker as
button groups, not dropdowns
Reordered ahead of Week 4/5 -- both are largely independent of the frontend
(provider switching and favoriting/reporting are backend concerns), and the
source toggle backend already landed in the previous PR, so extending the
existing browse-only frontend slice with it was unblocked. Only the
race/gender/source picker part of the Week 6 frontend item is done here --
favorite/report action buttons are deferred until Week 5's backend exists to
call, and `docs/ROADMAP.md`'s single Week 6 frontend line is split in two to
reflect that, matching the precedent set by the `PoolReplenishmentService`
entry's roadmap-line split.

**Buttons instead of `<select>` dropdowns**, per explicit request: each race/
gender/source option renders as its own `<button>` with an `hx-get` baked via
Thymeleaf's `@{...}` link expression (carrying the *other* two current
selections plus its own value), so a single click both selects and
immediately re-queries -- no separate "submit" step, and no JS beyond htmx
itself.

**`/browse` now returns the whole picker+results fragment (`index :: browser`),
not just the results list (`index :: list`) as before.** Buttons need to
visually highlight whichever option is currently selected (a `selected` CSS
class computed server-side from `selectedRace`/`selectedGender`/
`selectedSource`), and that highlight can only stay correct after a click if
the buttons themselves are part of what gets swapped -- swapping only the
results `<ul>` (the prior behavior) would leave stale buttons highlighted
after every click. `NameBrowserController.browse` and `.index` now share a
`populateBrowser(...)` helper so both endpoints populate the same
race/gender/source options plus the current selection identically, rather
than duplicating that model-building logic across the two methods.

**Empty-state message generalized** from "No curated names yet for this
race/gender." to "No names yet for this race/gender/source." -- the old
wording predated the source toggle and would be actively misleading for an
empty AI_GENERATED or BOTH result (e.g. a combo whose AI pool hasn't been
replenished yet has nothing to do with "curated").

Verified manually: started the app locally (`docker compose up -d`,
`SESSION_COOKIE_SECURE=false ./mvnw spring-boot:run
-Dspring-boot.run.profiles=local`) and curled `/` and `/browse` with several
race/gender/source combinations -- confirmed the default page loads with
ELF/FEMININE/CURATED highlighted and real curated names, clicking a different
combination (HALF_ORC/MASCULINE/BOTH) re-renders with exactly that
combination's buttons highlighted and the right names, and the empty-state
message renders correctly for a combo with no data (DRAGONBORN/CURATED).

## 2026-07-07: `report/` package -- report-name endpoint, second Week 5 slice
Second Week 5 slice, scoped to the report action only per `docs/ROADMAP.md`.
The manual review flow that flips `names.status` to `FLAGGED` stays a
separate, not-yet-built slice -- a report is a raw signal, not an automatic
status change, per `docs/ARCHITECTURE.md`'s "`name_reports`" section.

**Mirrors `favorite/`'s shape and idempotency pattern deliberately**, since
`name_reports` has the same problem `favorites` does: a single-row JPA
`save()`, guarded by a `(session_id, name_id)` unique constraint, with a
duplicate-report race handled by catching `DataIntegrityViolationException`
and re-reading the winner's row. `NameReportService.reportName` returns the
existing row (and ignores the new `reason`) if this session already reported
this name -- per `docs/ARCHITECTURE.md`: "without it, one user clicking
report five times looks like five reports, which corrupts any future
threshold-triggered auto-flagging."

**Differences from `favorite/`, driven by the schema, not by choice:**
`name_reports.session_id` is `NOT NULL` (unlike `favorites.session_id`,
which is nullable pending `owner_id`), so `NameReport` has no `ownerId`
field. `reason` is a nullable, freeform `varchar(256)` passed straight
through from the request -- no validation added, since the roadmap item is
just "writes to `name_reports`," not a moderation feature.

**No list/remove endpoints.** Unlike favorites, there's no product need to
let a session see or retract its own reports -- the roadmap only calls for
the report action itself, and the future FLAGGED-review flow reads reports
in aggregate (across sessions), not per-session.

**`NameReportControllerTest` sets the session cookie in `SessionIdFilter`'s
own format from the start** (a valid UUID, passed as a real
`SessionIdFilter.COOKIE_NAME` cookie), rather than setting the request
attribute directly -- the latter was found in review of #37 to be silently
overwritten by the real `SessionIdFilter`, which `@WebMvcTest` auto-registers
as a `Filter` bean.

Tests: plain-mock `NameReportServiceTest` (no Spring context, matching
`FavoriteServiceTest`) and `@WebMvcTest` `NameReportControllerTest` (matching
`FavoriteControllerTest`), including a regression test for the
concurrent-report race. Could not run `./mvnw test` locally -- same
pre-existing JDK 26/Mockito inline-mock-maker gap documented above;
confirmed via `./mvnw compile test-compile` that everything compiles
cleanly.

Caught in review of #38, fixed in this PR:

- **An over-length `reason` would have surfaced as an unmapped 500, not a
  400.** `name_reports.reason` is `VARCHAR(256)`; a longer value passed
  through unvalidated would throw a DB-level "string data right truncation"
  error on `save()`, which Spring's SQL exception translation maps to
  `DataIntegrityViolationException` -- the same exception type
  `NameReportService.saveNew`'s catch block interprets as a concurrent-report
  race. Its re-query (`findBySessionIdAndNameId`) would then find nothing
  (the insert never landed) and rethrow the *original* length-violation
  exception unmapped. Fixed by validating length in
  `NameReportController.normalizeReason` before calling the service, throwing
  a clean `ResponseStatusException(BAD_REQUEST)` instead. Regression test:
  `reportName_should_ReturnBadRequest_When_ReasonExceedsMaxLength`.
- **Blank (`""` or whitespace-only) `reason` now normalizes to `null`**,
  same method -- otherwise "no reason given" would have two different
  representations in the same nullable column, which the not-yet-built
  FLAGGED-review UI would eventually have to special-case. Regression test:
  `reportName_should_TreatReasonAsNull_When_ReasonIsBlank`.

One finding considered and deliberately not changed: `NameReportService.saveNew`
and `FavoriteService.saveNew` (and `NameReportController`/`FavoriteController`'s
`requireNameExists`/`sessionId` helpers) are near-identical. Not extracted into
a shared helper -- only two occurrences exist, and this codebase's own
convention favors "three similar lines... over a premature abstraction." Revisit
if a third favorite/report-shaped feature package needs the same pattern.
