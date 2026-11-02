# qCore — Development Status

Version `0.1.0-SNAPSHOT` · By qSa3ed · qSa3ed Studio

This file is the single source of truth for what is implemented, what is
partially implemented, and what is deferred. Nothing is listed as working unless
the corresponding code exists and was compiled; verification levels are stated
explicitly.

Legend:

- **Implemented** — feature code exists and is exercised by unit tests or by a
  real local run.
- **Compiled** — code exists and compiles against the real platform API; no
  runtime server test was performed.
- **Partial** — a working subset exists, with the missing part named.
- **Deferred** — not implemented in this MVP.

---

## 1. Build and structure

| Item | Status | Notes |
|---|---|---|
| Maven parent + 7 modules | Implemented | `mvn clean verify` drives the whole reactor. |
| Java 21 target | Implemented | Compiled with `--release 21` on a JDK 25 toolchain. |
| Central executable jar (shaded) | Implemented | `qcore-central/target/qcore-central-0.1.0-SNAPSHOT.jar`. |
| Server plugin jar (shaded) | Implemented | Paper API is `provided`, never bundled. |
| Velocity plugin jar (shaded) | Implemented | Velocity API is `provided`, never bundled. |
| Package root `studio.qsa3ed.qcore` | Implemented | Every module follows it. |

## 2. `qcore-api` and `qcore-common`

| Item | Status | Notes |
|---|---|---|
| Public API interfaces | Implemented | `QCoreAPI`, `ServerRegistry`, `PlayerIdentityService`, `RoutingService`, `EventService`, `MetricsService`, `NetworkMessagingService`, `ConfigurationService`. |
| Network, server, player, routing models | Implemented | Immutable records, validated in compact constructors. |
| Message protocol (id, type, source, target, timestamp, expiry, correlation, attempt) | Implemented | Unit tested: round trip, expiry, retry, idempotent encoding. |
| Message validation (fields, node pattern, 256 KiB body bound) | Implemented | Unit tested. |
| HMAC-SHA256 signing and constant-time verification | Implemented | Unit tested, including tampering and wrong-secret cases. |
| YAML configuration reader with typed accessors and duration parsing | Implemented | Unit tested. |
| Signed asynchronous central HTTP client | Implemented | Used by both plugins; every call returns a future, none block a game thread. |

## 3. `qcore-storage`

| Item | Status | Notes |
|---|---|---|
| HikariCP PostgreSQL pool with bounded work queue | Implemented | Saturation fails fast instead of blocking the caller. |
| Ordered SQL migrations with checksum history | Implemented | Classpath discovery works from `target/classes` and from the jar. |
| Server / player / event repositories | Implemented | Prepared statements only; player writes are version-aware. |
| Routing and automation audit tables | Implemented | Written by routing and the intelligence engine. |
| Redis connection management, degraded mode, ping | Implemented | Unreachable Redis degrades messaging instead of aborting central. |
| Redis Pub/Sub bus | Implemented | Publishing reports failure; no delivery guarantee is assumed. |
| Redis Streams queue with consumer group and acknowledgements | Implemented | Approximate trimming and pending-count inspection. |
| Live PostgreSQL / Redis integration tests | Present but **not executed** here | Tagged `integration`, skipped without `QCORE_TEST_DB_URL` / `QCORE_TEST_REDIS_URI`. No database or Redis was available in this environment. |

## 4. `qcore-central`

| Item | Status | Notes |
|---|---|---|
| Standalone application + graceful shutdown | Implemented | No Bukkit, Paper or Velocity dependency. |
| Server registry with PostgreSQL mirror and memory cache | Implemented | Persistence failure is logged and counted; the node keeps serving. |
| Heartbeat expiry sweeper | Implemented | Unit tested with a fixed clock; idempotent per sweep. |
| Network snapshots and operational counters | Implemented | Served entirely from memory. |
| Rule engine (conditions, actions, cooldowns, priorities, hourly limits) | Implemented | Unit tested for matching, cooldown, limit and priority. |
| Automation audit log | Implemented | Persisted asynchronously; unsupported actions are rejected and audited. |
| Smart routing with per-candidate reasons and fallback | Implemented | Unit tested: best server, TPS floor, party capacity, group/tag filters, current-server exclusion, degraded fallback. |
| Player identity and session coordination | Implemented | Unit tested with a repository double. |
| Event service with in-memory ring when PostgreSQL is down | Implemented | Publication is best effort; persistence is the guarantee. |
| Authentication (node HMAC + API key roles) | Implemented | Node identity is enforced server-side. |
| REST API with validation, rate limiting, structured errors | Implemented | Exercised by a manual runtime smoke test (see §8); still no automated HTTP-level test. |
| Configuration loading with env substitution | Implemented | Example secrets are rejected at startup. |

## 5. `qcore-server` (Paper / Purpur / Folia)

| Item | Status | Notes |
|---|---|---|
| Plugin bootstrap, config validation, reload | Compiled | Compiles against `paper-api 1.21.11`; not loaded on a live server here. |
| Folia-native scheduling abstraction | Compiled | Uses `getGlobalRegionScheduler`, `getRegionScheduler`, `EntityScheduler`, `getAsyncScheduler`. Region scheduler API present since Paper 1.20; **no Folia runtime test was performed**. |
| Registration + heartbeat with retry | Compiled | Retries on every interval, re-registers after central restarts. |
| TPS/MSPT/player/heap telemetry | Compiled | `Bukkit.getTPS()` and `getAverageTickTime()`. |
| Player session reporting | Compiled | Identity is read on the game thread; the network call is async. |
| `/qcore status|info|network|reload` | Compiled | Network output returns to the global region thread. |
| Paper compatibility test | API surface only | `PlatformApiSurfaceTest` verifies the required methods exist in the target API; it does not start a server. |

## 6. `qcore-velocity`

| Item | Status | Notes |
|---|---|---|
| Plugin bootstrap and descriptor | Compiled | `velocity-plugin.json` points at `QCoreVelocityPlugin`. |
| Proxy registration + heartbeat | Compiled | Reports proxy player count; TPS reported as unknown by design. |
| Session tracking | Compiled | Tracks the player's current server; reads from Velocity when unknown. |
| Smart routing execution with real transfers | Compiled | Central decides, the proxy transfers, and an unknown/unregistered destination is never used. |
| Fallback handling | Compiled | Falls back to the configured server when routing fails, or reports the failure if none is configured. |
| Party-aware routing interface | Partial | `ProxySessionTracker.PartyProvider` is the extension point; the default provider returns a party of one. No party plugin integration. |
| `/qcore status|servers|route|reload` | Compiled | Async results are delivered on the proxy scheduler. |
| Velocity compatibility test | API surface only | Verifies the required Velocity API methods exist; no live proxy run here. |

## 7. Not implemented (deferred)

- Web dashboard and its static assets.
- Gameplay systems: cross-server chat, friends, parties, economy bridges.
- Distributed multi-central clustering (the registry is single-writer).
- Redis Stream consumer inside the plugins (plugins use signed HTTP; central
  owns stream consumption).
- Token-based session authentication for browser clients (API keys today).
- Metrics export (Prometheus/OpenTelemetry) — counters are available over REST.
- Encryption at rest or TLS termination inside qCore (deploy behind a
  TLS-terminating reverse proxy).
- Graceful `SIGTERM` draining of in-flight REST requests (shutdown is immediate).

## 8. Verification performed in this environment

### 8.1 Build

| Check | Result |
|---|---|
| `mvn clean verify` (all 7 modules + parent) | **BUILD SUCCESS** |
| Unit tests (`MessageProtocolTest` 9, `ConfigurationAndSecurityTest` 6, `RoutingCoordinatorTest` 7, `RuleEngineTest` 5, `HeartbeatAndPlayerSyncTest` 5) | **32 passed, 0 failed** |
| `PlatformApiSurfaceTest` (Paper/Folia + Velocity API surface + descriptor targets) | **3 passed, 0 failed** |
| Total tests in the default build | **35 run, 0 failures, 0 errors, 0 skipped** |
| Artifacts produced | `qcore-central-0.1.0-SNAPSHOT.jar` (12.6 MB shaded), `qCore-0.1.0-SNAPSHOT.jar` (2.5 MB), `qCore-Velocity-0.1.0-SNAPSHOT.jar` (2.5 MB) |
| Packaging inspection | `plugin.yml`, `velocity-plugin.json`, qCore classes and Jackson are bundled; `org/bukkit` and `com.velocitypowered` are **not** bundled (still `provided`) |

### 8.2 Central runtime smoke test (degraded mode, no PostgreSQL or Redis present)

The built jar was started with `database.enabled: false` and `redis.enabled: false` and
probed over HTTP. This exercises the real configuration loader, REST stack,
authentication, registry cache, routing and event ring.

| Probe | Observed |
|---|---|
| `GET /api/v1/health` without credentials | `401 unauthorized` with a structured error body |
| `GET /api/v1/health` with an API key | `200`, `storageAvailable:false`, `redisAvailable:false`, `messagingAvailable:false` |
| `POST /servers/register` signed with a valid node HMAC | `200 {"ok":true,"nodeId":"smoke-node"}` |
| `POST /servers/{id}/heartbeat` signed | `200 {"ok":true}` |
| `GET /api/v1/servers` | Node returned with TPS 19.9 and 12/100 players |
| `POST /routing/evaluate` | `routed:true`, `targetServerId:smoke-node`, score 170.70, one eligible candidate |
| Forged signature | `401` |
| Stale timestamp (10 minutes old) | `401` |
| Node reporting for a different node id | `403 a node may only report for itself` |
| Unknown route | `404` |
| 135 requests against a 120/min budget | 120 × `200`, 15 × `429` |
| Startup with PostgreSQL unreachable (persistence enabled) | Exit 1 with `Unable to open the PostgreSQL connection pool at jdbc:postgresql://127.0.0.1:5432/qcore` — fail-fast, no hang |
| `java -jar … --version` / `--help` | `qCore 0.1.0-SNAPSHOT` and usage text |

### 8.3 Not verified (no infrastructure available)

| Check | Status |
|---|---|
| PostgreSQL integration test (`StorageIntegrationTest`) | **Selected and skipped** — requires `QCORE_TEST_DB_URL`; the documented opt-in command was verified to select both integration tests and report `Tests run: 2, Skipped: 2` rather than a silent pass |
| Redis integration test (`StorageIntegrationTest`) | **Selected and skipped** — requires `QCORE_TEST_REDIS_URI`; same verification as above |
| Central against a live PostgreSQL + Redis pair | **Not executed** |
| Live Paper/Purpur server: plugin load, registration, heartbeat expiry, session sync | **Not executed** — no server runtime available |
| Live Folia server | **Not executed** — Folia compatibility is verified at API and design level only |
| Live Velocity proxy: routing and a real player transfer | **Not executed** — no proxy runtime available |
| Migrations against a real database | **Not executed** — migration code compiles and is unit-reached only through the guarded integration test |

## 9. Next development tasks

1. Run the integration suite against disposable PostgreSQL and Redis, then wire
   the result into CI.
2. Boot a Paper 1.21.11 server plus Velocity and verify registration, heartbeat
   expiry, session sync and a real transfer end to end.
3. Execute the same test on Folia to promote scheduling from "compiled" to
   "verified".
4. Add an HTTP-level REST test suite (including 401/403/429 paths) against an
   embedded central instance.
5. Add graceful shutdown draining and connection draining for deploys.
6. Add the dashboard on top of the existing REST contract.
