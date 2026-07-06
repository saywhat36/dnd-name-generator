# Deployment

This document covers getting `dnd-name-generator` running as a small deployed
product. It is **separate from the Phase 1 Spring AI learning track** in
[`ROADMAP.md`](./ROADMAP.md) -- none of the Week 3-6 items change because of
anything here. Deployment work is purely additive: a new `prod` Spring profile,
a `Dockerfile`, and hosting config, none of which touch the local dev workflow.

## Target topology

```
  Browser
    |  HTTPS (same origin)
    v
  Render web service  ──►  Gemini Developer API (name generation)
  (Spring Boot app,        (async, off the request path)
   serves API + the
   Week 6 htmx/Thymeleaf
   frontend)
    |  JDBC + TLS
    v
  Neon Postgres (serverless)
```

**One service, one origin.** The Spring Boot app on Render serves both the API
and (once Week 6 lands) the server-rendered htmx + Thymeleaf frontend. This was
a deliberate choice over hosting a static frontend on GitHub Pages:

- It preserves the server-rendered htmx + Thymeleaf architecture already
  committed to in [`ARCHITECTURE.md`](./ARCHITECTURE.md) and keeps the Phase 3
  SSE streaming plan viable.
- Same origin means **no CORS** and **no cross-origin session cookies**. The
  session cookie (`SessionIdFilter`) that Week 5 favorites/reports depend on
  stays a simple first-party `Secure` cookie.
- GitHub Pages only serves static files; adopting it would have meant building a
  fetch/JS SPA instead of the htmx frontend -- i.e. quietly replacing a recorded
  architecture decision. Rejected for that reason.

## Local dev is unchanged

Everything below is additive. Local development still runs exactly as documented
in the README:

```bash
./mvnw spring-boot:run          # no profile; compose.yaml auto-starts local Postgres
```

`compose.yaml`, `application.yml`, and `application-gemini.yml` are untouched.
The `prod` profile only ever activates when `SPRING_PROFILES_ACTIVE` names it.

## Profiles

Production runs with **two** profiles active together:

```
SPRING_PROFILES_ACTIVE=prod,gemini
```

- `application-gemini.yml` (existing) supplies the Gemini provider config,
  reused as-is -- not duplicated into the prod profile, per the per-provider-file
  convention in `ARCHITECTURE.md`.
- `application-prod.yml` (new) supplies only the deployment deltas: Neon
  datasource from env vars, `spring.docker.compose.enabled=false` (so the
  container never tries to start Docker), and `server.port=${PORT}` (Render
  injects the port to bind on).

`ddl-auto: validate`, `open-in-view: false`, Flyway, and virtual threads are all
inherited from the base `application.yml` -- no need to restate them.

## Database: Neon Postgres

Neon is stock Postgres 16, so Flyway migrations run against it unchanged; the
schema is created on first boot exactly as it is locally.

Two things to get right in the connection string:

1. **`sslmode=require`** -- Neon only accepts TLS connections.
2. **Use the DIRECT (non-pooled) endpoint, not the `-pooler` one.** Flyway takes
   a session-level advisory lock while migrating, which Neon's PgBouncer
   (transaction-mode) pooler endpoint does not support. The pooled endpoint is
   fine for high-connection-count app traffic later, but migrations must run
   against the direct endpoint. For this project's traffic, just use the direct
   endpoint for everything.

JDBC form (note the `jdbc:` prefix Neon's dashboard string omits):

```
jdbc:postgresql://ep-xxxx.<region>.aws.neon.tech/namegen?sslmode=require
```

Username and password are supplied separately via `SPRING_DATASOURCE_USERNAME` /
`SPRING_DATASOURCE_PASSWORD` rather than embedded in the URL.

## API + frontend host: Render

- **Build:** a committed multi-stage [`Dockerfile`](../Dockerfile) (Maven build →
  JRE 21 runtime). Chosen over Render's auto-detected buildpack for reproducible,
  pinned builds. Tests are skipped in the image build (the `*IT` tests need
  Docker/Testcontainers, unavailable inside a build container) -- they run via
  `./mvnw test` locally and in CI instead.
- **Config:** [`render.yaml`](../render.yaml) declares the service as a free-tier
  Docker web service. Secrets are marked `sync: false` (set in the dashboard,
  never committed).
- **Cold start:** the free tier spins the service down after ~15 minutes idle;
  the next request pays a ~50s cold start (JVM boot + Flyway validation + first
  DB connection). Fine for a demo/portfolio deployment; worth knowing before
  showing it to someone live. Upgrading to a paid instance or an external pinger
  removes it.
- **Health check:** `render.yaml` currently points at `GET /names?...` since
  that's a real endpoint today. Once Actuator lands (Week 6), switch this to
  `/actuator/health`.

## Secrets / env vars

Set on the Render service (dashboard → Environment), never committed:

| Var | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod,gemini` (safe to commit in `render.yaml`) |
| `SESSION_COOKIE_SECURE` | `true` (safe to commit) |
| `SPRING_DATASOURCE_URL` | Neon direct JDBC URL with `?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | Neon role |
| `SPRING_DATASOURCE_PASSWORD` | Neon password |
| `GEMINI_API_KEY` | Google AI Studio key (same one used locally) |

## Curated seed data in production

The curated names load via Flyway, so they reach Neon automatically on first
boot -- there is no separate prod-only import step. See the `V3` seed migration
(`src/main/resources/db/migration/`) and the corresponding entry in
[`DECISIONS.md`](./DECISIONS.md) for how the real curated dataset is loaded and
how it relates to the illustrative `V2` seed.

## Provisioning checklist (human-in-the-loop steps)

These touch external accounts and secrets and are done by you, not automated:

1. **Neon:** create an account + a project/database named `namegen`; copy the
   direct connection string, convert to JDBC form (add `jdbc:`, `?sslmode=require`).
2. **Render:** create an account; New → Blueprint (point at this repo's
   `render.yaml`) or New → Web Service (Docker); paste the four secret env vars.
3. **First deploy:** Flyway creates the schema and runs the seed migration
   against Neon automatically on boot. Verify with
   `GET https://<service>.onrender.com/names?race=ELF&gender=FEMININE`.
