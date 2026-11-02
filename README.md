# qCore — Network Intelligence Engine

**One Network. One Intelligence. One Infrastructure.**

qCore connects Minecraft servers into one coordinated infrastructure: a central
coordinator that owns network state, gameplay servers that report their health,
and a Velocity proxy that places players using that data.

By **qSa3ed** | **qSa3ed Studio** | Version `0.1.0` | MIT licensed

| | |
|---|---|
| Language | Java 21 |
| Build | Maven multi-module |
| Minecraft target | Paper / Purpur / Folia `1.21.11`, Velocity `3.x` |
| Persistence | PostgreSQL (source of truth) |
| Transport | HTTP + Redis (Pub/Sub and Streams) |

---

## What works today

- **Central coordinator** — standalone JVM process, no Bukkit or Velocity dependency.
- **Server registration and heartbeats** with configurable expiry and offline detection.
- **Network intelligence** — a bounded, audited rule engine (conditions, actions,
  cooldowns, priorities, hourly limits, dry run).
- **Smart routing** — scored placement using TPS, capacity, health, group, tags and
  party size, with an explicit reason for every candidate.
- **Player identity** — UUID-keyed profiles, sessions, last known server, versioned writes.
- **REST API** — authenticated, rate limited, validated, versioned, structured errors.
- **Paper/Purpur/Folia plugin** — Folia-native scheduling, telemetry, session reporting,
  `/qcore` administration.
- **Velocity plugin** — proxy registration, session tracking, availability checks, real
  player transfers with fallback.
- **Persistence** — HikariCP pool, ordered SQL migrations with checksums, async repositories.
- **Messaging** — Redis Pub/Sub for transient notifications, Redis Streams for durable work,
  degraded mode when Redis is unreachable.

Known gaps, partially implemented areas and deferred features are listed explicitly in
[DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

---

## Architecture

```
                    ┌──────────────────────────────┐
                    │        qCore Central         │
                    │  registry · intelligence ·    │
                    │  routing · REST · audits      │
                    └───────┬──────────────┬───────┘
             signed HTTP    │              │   Redis Pub/Sub + Streams
        ┌───────────────────┘              └────────────────────┐
        │                                                       │
┌───────┴────────┐   ┌────────────────┐   ┌────────────────┐   ┌┴──────────────┐
│ lobby-1 (Paper)│   │ survival (Folia)│   │  velocity-1    │   │ other central │
│ heartbeat      │   │ heartbeat       │   │ routing +      │   │ instances /    │
│ sessions       │   │ sessions        │   │ transfers      │   │ consumers      │
└────────────────┘   └────────────────┘   └────────────────┘   └───────────────┘
                    PostgreSQL = source of truth
```

### Modules

| Module | Responsibility |
|---|---|
| `qcore-api` | Public developer interfaces. Never exposes implementation types. |
| `qcore-common` | Models, message protocol, serialization, validation, HMAC, HTTP client. |
| `qcore-storage` | HikariCP/PostgreSQL repositories, migrations, Redis transport. |
| `qcore-central` | Standalone coordinator: registry, intelligence, routing, REST. |
| `qcore-server` | Paper/Purpur/Folia plugin. |
| `qcore-velocity` | Velocity plugin. |
| `qcore-tests` | Unit, integration and platform API surface tests. |

---

## Quick start

### 1. Build

```bash
mvn clean verify
```

Artifacts:

| Artifact | Path |
|---|---|
| Central | `qcore-central/target/qcore-central-0.1.0.jar` |
| Server plugin | `qcore-server/target/qCore-0.1.0.jar` |
| Velocity plugin | `qcore-velocity/target/qCore-Velocity-0.1.0.jar` |

### 2. PostgreSQL and Redis

```sql
CREATE DATABASE qcore;
CREATE USER qcore WITH ENCRYPTED PASSWORD 'change-this';
GRANT ALL PRIVILEGES ON DATABASE qcore TO qcore;
```

Schema migrations are applied automatically at central startup.

### 3. Central

```bash
mkdir config
cp qcore-central/src/main/resources/{central,database,redis,security,rules}.yml config/
export QCORE_DB_PASSWORD=change-this
java -jar qcore-central/target/qcore-central-0.1.0.jar --config-dir config
```

Verify:

```bash
curl -s http://127.0.0.1:8080/api/v1/health | jq
```

### 4. Gameplay server

Drop the plugin jar into `plugins/`, start Paper/Purpur/Folia, then edit
`plugins/qCore/server.yml` (node id, port, group) and `plugins/qCore/config.yml`
(central URL, shared secret). The secret must match `security.secrets.<node id>`
in central's `security.yml`. Restart or run `/qcore reload`.

### 5. Proxy

Drop the Velocity jar into `plugins/`, start Velocity, then edit
`plugins/qcore/config.yml` and `plugins/qcore/proxy.yml`. Register the proxy id
and secret in central's `security.yml`.

Full instructions, including reverse-proxy and TLS notes, are in
[docs/INSTALLATION.md](docs/INSTALLATION.md).

---

## Commands

### Gameplay server (`/qcore`)

| Command | Purpose | Permission |
|---|---|---|
| `/qcore status` | Registration state, heartbeat counters, local TPS/MSPT | `qcore.command` |
| `/qcore info` | Node identity, group, tags, software, Folia detection | `qcore.command` |
| `/qcore network` | Network nodes fetched from central | `qcore.command.network` |
| `/qcore reload` | Reload configuration and restart reporting | `qcore.command.reload` |

### Proxy (`/qcore`)

| Command | Purpose | Permission |
|---|---|---|
| `/qcore status` | Proxy registration, sessions, fallback server | `qcore.command` |
| `/qcore servers` | Server list with status and player counts | `qcore.command` |
| `/qcore route <player> [group]` | Evaluate and perform a transfer | `qcore.command` |
| `/qcore reload` | Reload configuration and restart reporting | `qcore.command` |

---

## REST API

Base path `/api/v1`. Every response carries `Cache-Control: no-store`.
Nodes authenticate with HMAC-SHA256 headers (`X-QCore-Node`,
`X-QCore-Timestamp`, `X-QCore-Signature`); tooling uses `X-QCore-Api-Key`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Storage, Redis, messaging and node status |
| GET | `/network` | Network snapshot |
| GET | `/servers` | All registered nodes |
| GET | `/servers/{id}` | One node |
| GET | `/players/{uuid}` | Player profile |
| GET | `/metrics` | Snapshot plus operational counters |
| GET | `/events?limit=50` | Recent network events |
| GET | `/rules` | Configured automation rules (admin) |
| POST | `/servers/register` | Register or describe a node |
| POST | `/servers/{id}/heartbeat` | Report telemetry |
| POST | `/players/session` | Report a session change |
| POST | `/routing/evaluate` | Evaluate placement |
| POST | `/events` | Publish an event |
| POST | `/intelligence/evaluate` | Run one automation tick (admin) |

A node may only report for itself; API keys may act for any node.
See [docs/API.md](docs/API.md) for request/response examples.

---

## Configuration

| File | Component | Contents |
|---|---|---|
| `central.yml` | central | network name, REST bind, heartbeat window, routing thresholds, intelligence switch |
| `database.yml` | central | JDBC url, pool size, queue bound |
| `redis.yml` | central | Redis URI, key prefix, stream trimming |
| `security.yml` | central | node secrets, API keys and roles |
| `rules.yml` | central | automation rules |
| `config.yml` | server / proxy | central URL, shared secret, intervals |
| `server.yml` | server | node id, name, address, group, weight, tags |
| `proxy.yml` | proxy | proxy id, routing group, fallback server, transfer switch |

All secret values support `${ENV_VAR}` and `${ENV_VAR:default}` substitution, so
credentials do not need to live in the files. Example values are rejected at
startup (`change-me…`) rather than silently connecting.

---

## Security model

- Per-node HMAC-SHA256 signatures with a configurable clock-skew window.
- API keys mapped to `read`, `write`, `admin` roles.
- A node cannot report for another node; enforced server-side.
- Request bodies bounded, per-caller rate limiting, structured error bodies.
- No credential is ever logged or returned by the API.
- Database calls never run on a game thread.

---

## Development

```bash
mvn clean verify                        # build + unit tests
mvn -pl qcore-tests test -Dgroups=integration -DexcludedGroups=   # needs real PostgreSQL and Redis
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for conventions and
[DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) for honest feature status.
