# Roadmap

Status legend: not started / in progress / done

## Phase 1 -- Core Spring AI learning (target: ~6 weeks)

### Foundations
- [x] Project scaffold: Spring Boot 3.x, Java 21, Maven
- [x] Docker Compose for local Postgres
- [x] Flyway migration: `names`, `favorites`, `generation_log`,
  `name_reports` tables -- using `varchar` + check constraints for
  race/gender/source/status, NOT native Postgres enums
- [x] Index on `names(race, gender, status, source)`
- [x] Unique constraint on `names(normalized_name, race, gender)`
- [x] Unique constraint on `name_reports(session_id, name_id)`
- [x] Check constraint on `favorites`: at least one of
  `session_id`/`owner_id` non-null
- [x] Seed curated names into `names` (source = CURATED) -- ELF, DWARF,
  HUMAN, HALFLING seeded; ORC, GNOME, DRAGONBORN, TIEFLING have the
  `race` check constraint but no curated names yet
- [x] `SessionIdFilter` -- server-issued cookie

### Week 1 -- Basic AI endpoint
- [x] `NameController` + `NameService` returning curated names only
  (no AI yet), by race + gender
- [x] First `ChatClient` call wired up (Gemini), plain text response, to
  confirm the pipe works before adding structure -- verified via
  `NameGenerationServiceEvalIT`, run manually against a live
  `GEMINI_API_KEY`

### Week 2 -- Structured output
- [x] `NameSuggestion` record, structured output converter in
  `NameGenerationService`
- [x] Externalized, versioned prompt (`name-generation-v1.st`), few-shot
  examples pulled from CURATED rows only
- [x] `QualityGateService` (length, charset, blocklist)
- [x] `DeduplicationService` (pre-filter) + DB unique constraint as the
  actual correctness guarantee
- [x] `NameInsertDao` -- native `INSERT ... ON CONFLICT (normalized_name,
  race, gender) DO NOTHING` path (JPA `saveAll` does not support this
  cleanly and a constraint violation poisons the transaction)
- [x] Bounded retry for parse failures and under-yield after
  quality/dedup filtering
- [x] `generation_log` writes on every attempt, including failures

### Week 3 -- Pool + toggle
- [ ] `PoolReplenishmentService`, `@Async`, threshold-triggered
- [ ] Explicit executor config (`AsyncConfig`) -- do not rely on
  `SimpleAsyncTaskExecutor` defaults
- [ ] Explicit try/catch + `generation_log` write in `finally` --
  `@Async` failures are silent otherwise
- [ ] Stampede guard: in-flight lock per race+gender combo before
  triggering generation (in-memory CAS is fine for a single instance)
- [ ] Per-combo pool cap, checked before generating
- [ ] Global LLM budget check inside the replenishment path (not in a
  servlet filter -- standard requests never reach one)
- [ ] Three-way source toggle: CURATED / AI_GENERATED / BOTH
- [ ] Confirm requests never block on a live LLM call (verify via a
  slow/mocked provider in a test)
- [ ] Test: concurrent replenishment triggers for the same combo result
  in exactly one generation call (stampede guard verification)

### Week 4 -- Provider switching
- [ ] Second provider wired in (OpenAI or Anthropic) via Spring profile
- [ ] Compare output/behavior between providers, note differences in
  `DECISIONS.md` -- especially structured-output parse reliability
- [ ] Graceful degradation for name-serving: since requests never call
  the LLM synchronously, this just means confirming the UI surfaces an
  "AI pool low" notice based on pool state, not error handling

### Week 5 -- Favoriting + reporting
- [ ] `favorite/` package: add/remove/list, keyed on session_id,
  `owner_id` column present but unused
- [ ] Lazy insert-on-favorite for refinement-mode results:
  `AI_REFINED` source value, `generation_log_id` set, `ON CONFLICT`
  handling links to an existing row on collision
- [ ] `report/` package: report-name endpoint, writes to `name_reports`
- [ ] Manual review flow to flip `names.status` to FLAGGED

### Week 6 -- Observability + hardening
- [ ] Actuator + Micrometer wired up
- [ ] `SimpleLoggerAdvisor` on ChatClient
- [ ] Per-use-case `ChatOptions` (temperature tuning for generation)
- [ ] Testcontainers integration tests, including the native insert
  path under concurrent writers
- [ ] Mocked-`ChatModel` unit tests for service layer
- [ ] Eval-style test against a live provider (separate test run, not
  part of default build)
- [ ] `RateLimitFilter` scoped correctly: not applied to name-serving
  (DB-only), reserved for endpoints with a synchronous LLM call
  (none yet in Phase 1 -- this becomes active in Phase 3)
- [ ] Per-session Bucket4j buckets backed by Caffeine with a TTL, not
  an unbounded map
- [ ] htmx + Thymeleaf frontend: race buttons, gender selector, toggle,
  favorite/report actions

### Deferred within Phase 1 (optional, after core weeks)
- [ ] Memory/conversational refinement: `ChatMemory` +
  `MessageChatMemoryAdvisor`, keyed on session ID. REFINEMENT-mode
  generations logged to `generation_log` only, written to `names` (as
  `AI_REFINED`) only via the lazy insert-on-favorite path.

## Phase 2 -- Authentication (separate skill track, deferred)
- [ ] `users` table, password hashing
- [ ] Login/register endpoints
- [ ] Session or JWT-based auth
- [ ] Route-level security
- [ ] Add `(owner_id, name_id)` unique constraint on `favorites`
- [ ] Migrate `favorites.owner_id` (and memory conversation ownership,
  if built) from session-keyed to user-keyed, backfilling with
  `ON CONFLICT DO NOTHING` to handle the case where one person
  favorited the same name under two different sessions pre-login

## Phase 3 -- Backstories + streaming (deferred)
- [ ] Decide backstory persistence model (shared/cached on the name vs.
  per-favorite/per-user) before building -- this changes the schema
- [ ] Backstory generation endpoint per name
- [ ] SSE streaming response (htmx SSE extension client-side)
- [ ] `RateLimitFilter` becomes meaningful here -- this is the first
  synchronous LLM call on a request path in the whole project
