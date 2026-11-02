# qCore Installation Guide

By qSa3ed | qSa3ed Studio · Version 0.1.0-SNAPSHOT

This guide installs a complete qCore network: PostgreSQL, Redis, one central
node, one gameplay server and one Velocity proxy.

---

## 1. Requirements

| Component | Requirement |
|---|---|
| Java | 21 or newer on every host |
| PostgreSQL | 13 or newer |
| Redis | 6 or newer |
| Minecraft | Paper, Purpur or Folia `1.21.11`; Velocity `3.x` |
| Network | central reachable from every server and proxy over HTTP; Redis reachable from central |

Central does not need to run on the same machine as the game servers, but it must
not be exposed to the public internet without a TLS-terminating reverse proxy.

---

## 2. Build the artifacts

```bash
mvn clean verify
```

| Artifact | Location |
|---|---|
| `qcore-central-0.1.0-SNAPSHOT.jar` | `qcore-central/target/` |
| `qCore-0.1.0-SNAPSHOT.jar` (server plugin) | `qcore-server/target/` |
| `qCore-Velocity-0.1.0-SNAPSHOT.jar` | `qcore-velocity/target/` |

Keep these jars separate from the source tree when deploying; `target/` is build
output and is not version controlled.

---

## 3. Prepare PostgreSQL

```sql
CREATE DATABASE qcore;
CREATE USER qcore WITH ENCRYPTED PASSWORD 'replace-with-a-strong-password';
GRANT ALL PRIVILEGES ON DATABASE qcore TO qcore;
\c qcore
GRANT ALL ON SCHEMA public TO qcore;
```

Central creates its schema on first start (tables prefixed `qcore_`). If you
prefer a dedicated schema user, grant `CREATE` on the target schema only.

Verify the JDBC URL works before starting central:

```bash
psql "postgresql://qcore:password@127.0.0.1:5432/qcore" -c "select 1;"
```

---

## 4. Prepare Redis

```bash
redis-cli ping            # expect PONG
redis-cli CONFIG SET requirepass "replace-with-a-strong-password"
```

qCore uses Redis for transient notifications and one durable stream. It is never
the source of truth: if Redis is down, central keeps serving REST and PostgreSQL,
and the health endpoint reports `redisAvailable: false`.

---

## 5. Configure the central node

```bash
mkdir -p /opt/qcore/config
cd /opt/qcore
cp <repo>/qcore-central/src/main/resources/central.yml   config/
cp <repo>/qcore-central/src/main/resources/database.yml  config/
cp <repo>/qcore-central/src/main/resources/redis.yml     config/
cp <repo>/qcore-central/src/main/resources/security.yml  config/
cp <repo>/qcore-central/src/main/resources/rules.yml     config/
```

Edit, at minimum:

**`database.yml`**

```yaml
database:
  enabled: true
  jdbc-url: "jdbc:postgresql://127.0.0.1:5432/qcore"
  username: "qcore"
  password: "${QCORE_DB_PASSWORD}"
```

**`redis.yml`**

```yaml
redis:
  enabled: true
  uri: "redis://127.0.0.1:6379/0"
  password: "${QCORE_REDIS_PASSWORD}"
```

**`security.yml`** — one secret per node id, plus API keys for tooling. Generate
secrets with `openssl rand -hex 32`. Never commit this file.

```yaml
security:
  nodes: [lobby-1, survival-1, proxy-1]
  secrets:
    lobby-1:    "${QCORE_SECRET_LOBBY_1}"
    survival-1: "${QCORE_SECRET_SURVIVAL_1}"
    proxy-1:    "${QCORE_SECRET_PROXY_1}"
  api-keys: [dashboard]
  keys:
    dashboard: "${QCORE_API_KEY_DASHBOARD}"
  roles:
    dashboard: read
```

**`central.yml`** — bind address and routing thresholds. Keep `bind-address:
127.0.0.1` and put nginx/Caddy in front if central must be reachable remotely.

Start central with the secrets exported:

```bash
export QCORE_DB_PASSWORD='...'
export QCORE_REDIS_PASSWORD='...'
export QCORE_SECRET_LOBBY_1='...'
export QCORE_SECRET_SURVIVAL_1='...'
export QCORE_SECRET_PROXY_1='...'
export QCORE_API_KEY_DASHBOARD='...'

java -jar qcore-central-0.1.0-SNAPSHOT.jar --config-dir /opt/qcore/config
```

Expected log lines:

```
PostgreSQL pool ready: jdbc:postgresql://127.0.0.1:5432/qcore
Database schema is up to date (1 migration(s) applied)
Redis transport ready: redis://127.0.0.1:6379/0
qCore REST API listening on http://127.0.0.1:8080/api/v1
qCore 0.1.0-SNAPSHOT started — network 'qCore Network' — 0 server(s) registered
```

Check health:

```bash
curl -s http://127.0.0.1:8080/api/v1/health
```

`storageAvailable` and `redisAvailable` must both be `true` before adding nodes.

---

## 6. Install a gameplay server node

1. Copy `qCore-0.1.0-SNAPSHOT.jar` into the server's `plugins/` directory.
2. Start Paper/Purpur/Folia once; qCore writes `plugins/qCore/config.yml` and
   `plugins/qCore/server.yml`.
3. Edit `server.yml`:

```yaml
server:
  id: "lobby-1"
  name: "lobby-1"
  host: "10.0.0.11"
  port: 25565
  game-mode: "lobby"
  weight: 10
  tags: [lobby]
  accepting-players: true
```

4. Edit `config.yml`:

```yaml
central:
  url: "http://10.0.0.5:8080"
  shared-secret: "${QCORE_SECRET_LOBBY_1}"
```

Export the same secret in the service unit that starts the server, or paste the
value directly if the file is protected. Example systemd drop-in:

```ini
[Service]
Environment=QCORE_SECRET_LOBBY_1=...
```

5. Restart, then check in-game:

```
/qcore status
/qcore info
```

`Registered: yes` means central accepted the node. On central, the node appears
under `GET /api/v1/servers`.

---

## 7. Install the Velocity proxy

1. Copy `qCore-Velocity-0.1.0-SNAPSHOT.jar` into Velocity's `plugins/`.
2. Start Velocity once; `plugins/qcore/config.yml` and `proxy.yml` are created.
3. Edit `proxy.yml`:

```yaml
proxy:
  id: "proxy-1"
  name: "qCore Proxy"
  host: "10.0.0.20"
  port: 25577

routing:
  group: "lobby"
  fallback-server: "lobby"
  execute-transfers: true
  require-fresh-data: true
```

`fallback-server` must be a server name registered in `velocity.toml`.

4. Set the shared secret in `plugins/qcore/config.yml` and export
   `QCORE_SECRET_PROXY_1` for the proxy process.
5. Restart Velocity, then:

```
/qcore status
/qcore servers
/qcore route <player>
```

---

## 8. Reverse proxy and TLS (recommended)

Central speaks plain HTTP and should sit behind a TLS terminator on a private
interface. nginx example:

```nginx
server {
    listen 443 ssl;
    server_name qcore.internal.example.com;
    ssl_certificate     /etc/ssl/qcore.crt;
    ssl_certificate_key /etc/ssl/qcore.key;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        client_max_body_size 256k;
    }
}
```

Then point every node's `central.url` at `https://qcore.internal.example.com`.
Do not enable anonymous reads (`security.allow-unauthenticated-reads`).

---

## 9. Operations

| Task | Action |
|---|---|
| Rotate a node secret | Update `security.yml`, restart central, update the node's environment, `/qcore reload` |
| Add a server | Add its id + secret in `security.yml`, restart central, configure the node |
| Tune automation | Edit `rules.yml`, restart central. Use `intelligence.dry-run: true` while tuning |
| Inspect routing | `GET /api/v1/events`, `GET /api/v1/servers`, and the `qcore_routing_audit` table |
| Inspect automation | `qcore_automation_audit` table |
| Store | PostgreSQL `qcore_servers`, `qcore_players`, `qcore_player_sessions`, `qcore_events` |

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Node shows `OFFLINE` after 30s | Heartbeats not arriving | Check `central.url`, firewall, and the node secret |
| `401 invalid request signature` | Secret mismatch or host clock skew | Compare secrets; enable NTP |
| `403 a node may only report for itself` | `server.id` differs from the signed node id | Make them equal |
| `503` on REST | PostgreSQL unavailable | Check `storageAvailable` in `/health`, then the pool logs |
| Routing returns "no eligible server" | All candidates filtered | Read the candidate reasons in the response; `/qcore servers` shows the same state |
| Proxy says "does not know that server" | `server.name` not in `velocity.toml` | Align names, or disable `require-fresh-data` to allow fallback |

---

## 10. Upgrading

1. Stop central.
2. Replace the jar.
3. Start central; pending migrations run automatically and are checksummed.
4. Replace plugin jars and restart the servers and proxy.

Never edit an applied migration. Add a new file, for example
`V2__add_table.sql`; the runner rejects modified history.
