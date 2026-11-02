# qCore Developer API

By qSa3ed | qSa3ed Studio · Version 0.1.0

Two integration surfaces exist:

1. **REST API** — language agnostic, used by the plugins and external tooling.
2. **Java API** (`qcore-api`) — for code running inside a qCore-aware process.

---

## Authentication

### Node signature (plugins)

```
X-QCore-Node: lobby-1
X-QCore-Timestamp: 1730000000000
X-QCore-Signature: <hex HMAC-SHA256>
```

The signature is computed over:

```
nodeId + "\n" + timestamp + "\n" + rawBody
```

using the node's shared secret. The timestamp must be within
`security.signature-tolerance` (default 60s). A node may only submit requests for
its own id; reporting for another node returns `403`.

### API key (tooling)

```
X-QCore-Api-Key: <key>
```

Roles: `read` < `write` < `admin`. `GET` requires `read`, mutations require
`write`, `/rules` and `/intelligence/evaluate` require `admin`.

### Errors

Every failure returns:

```json
{
  "error": {
    "code": "bad_request",
    "message": "central.url must not be blank",
    "requestId": "f2b9…"
  },
  "errors": [ { "code": "bad_request", "message": "…", "requestId": "f2b9…" } ]
}
```

Codes: `bad_request` (400), `unauthorized` (401), `forbidden` (403),
`not_found` (404), `payload_too_large` (413), `rate_limited` (429),
`unavailable` (503), `internal_error` (500).

All responses are `Cache-Control: no-store`.

---

## Endpoints

### `GET /api/v1/health`

```json
{
  "network": "qCore Network",
  "version": "0.1.0",
  "uptimeMillis": 183421,
  "storageAvailable": true,
  "redisAvailable": true,
  "messagingAvailable": true,
  "registeredServers": 3,
  "onlineServers": 3,
  "onlinePlayers": 57,
  "counters": {
    "heartbeats.received": 1820,
    "routing.routed": 143,
    "routing.rejected": 2,
    "intelligence.actions": 4
  }
}
```

Requires `read`. Use it as a liveness and readiness probe.

### `GET /api/v1/network`

A `NetworkSnapshot`:

```json
{
  "generatedAt": 1730000000000,
  "totalPlayers": 57,
  "totalCapacity": 600,
  "onlineServers": 3,
  "degradedServers": 0,
  "servers": [ { "id": "lobby-1", "status": "ONLINE", "…": "…" } ]
}
```

### `GET /api/v1/servers` and `GET /api/v1/servers/{id}`

Returns an array of nodes, or one node. Unknown ids return `404`.

```json
{
  "id": "lobby-1",
  "name": "lobby-1",
  "type": "SERVER",
  "host": "10.0.0.11",
  "port": 25565,
  "address": "10.0.0.11:25565",
  "status": "ONLINE",
  "gameMode": "lobby",
  "weight": 10,
  "tags": ["lobby"],
  "acceptingPlayers": true,
  "registeredAt": 1730000000000,
  "lastHeartbeatAt": 1730000012000,
  "metrics": {
    "tps": 20.0,
    "mspt": 1.8,
    "onlinePlayers": 24,
    "maxPlayers": 200,
    "heapUsedMb": 812,
    "heapMaxMb": 4096,
    "software": "Paper 1.21.11"
  }
}
```

### `GET /api/v1/players/{uuid}`

```json
{
  "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "username": "Notch",
  "lastServerId": "lobby-1",
  "firstSeenAt": 1729000000000,
  "lastSeenAt": 1730000000000,
  "dataVersion": 12,
  "metadata": {}
}
```

`dataVersion` increases on every accepted change. Writes carrying an older
version are ignored, which makes retries and duplicate deliveries safe.

### `GET /api/v1/metrics`

```json
{ "snapshot": { "…": "…" }, "counters": { "…": "…" } }
```

### `GET /api/v1/events?limit=50`

Newest first, `limit` bounded to `1..500`.

```json
[
  {
    "eventId": "0e5d…",
    "type": "RULE_TRIGGERED",
    "sourceNode": "central",
    "occurredAt": 1730000000000,
    "data": { "ruleId": "overloaded-when-slow-and-busy", "nodeId": "lobby-2" }
  }
]
```

Types: `SERVER_REGISTERED`, `SERVER_HEARTBEAT_LOST`, `SERVER_STATUS_CHANGED`,
`SERVER_OVERLOADED`, `PLAYER_JOINED_NETWORK`, `PLAYER_LEFT_NETWORK`,
`PLAYER_SERVER_CHANGED`, `ROUTING_DECISION`, `ROUTING_FAILED`, `RULE_TRIGGERED`,
`NODE_REGISTERED`, `MESSAGING_FAILURE`, `STORAGE_UNAVAILABLE`.

### `POST /api/v1/servers/register`

```bash
curl -X POST http://127.0.0.1:8080/api/v1/servers/register \
  -H 'X-QCore-Api-Key: <key>' \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "lobby-3",
    "name": "lobby-3",
    "type": "SERVER",
    "host": "10.0.0.13",
    "port": 25565,
    "status": "ONLINE",
    "gameMode": "lobby",
    "weight": 10,
    "tags": ["lobby"],
    "acceptingPlayers": true
  }'
```

Response: `{"ok": true, "nodeId": "lobby-3"}`. Re-registering preserves the
original `registeredAt`, so it is safe to call on every reconnect.

### `POST /api/v1/servers/{id}/heartbeat`

```json
{
  "tps": 19.94,
  "mspt": 2.1,
  "onlinePlayers": 24,
  "maxPlayers": 200,
  "heapUsedMb": 812,
  "heapMaxMb": 4096,
  "software": "Paper 1.21.11"
}
```

Heartbeats are preserved for `heartbeat.timeout`; after that the node is marked
`OFFLINE` and a `SERVER_HEARTBEAT_LOST` event is published.

### `POST /api/v1/players/session`

```json
{
  "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "username": "Notch",
  "serverId": "lobby-1",
  "connectedAt": 1730000000000,
  "updatedAt": 1730000000000,
  "connected": true
}
```

### `POST /api/v1/routing/evaluate`

```json
{
  "playerId": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "group": "lobby",
  "requiredTags": [],
  "partySize": 1,
  "currentServerId": "lobby-1",
  "allowFallback": false
}
```

Response:

```json
{
  "routed": true,
  "targetServerId": "lobby-2",
  "reason": "selected lobby-2 (score 149.20)",
  "evaluatedAt": 1730000000000,
  "candidates": [
    { "serverId": "lobby-1", "eligible": false, "score": 0.0,
      "reason": "player is already connected here" },
    { "serverId": "lobby-2", "eligible": true, "score": 149.2, "reason": "eligible" },
    { "serverId": "lobby-3", "eligible": false, "score": 0.0,
      "reason": "tps 9.4 below floor 15.0" }
  ]
}
```

Scoring: configured weight dominates, then free capacity, then TPS headroom;
utilisation and network-wide fullness subtract. A destination must be accepting
transfers, not offline or overloaded, within the capacity and TPS thresholds,
match the requested group, carry the required tags and have room for the whole
party.

### `POST /api/v1/events`

Body is a `NetworkEvent`. Persistence is idempotent per `eventId`.

### `POST /api/v1/intelligence/evaluate`

Runs one automation tick immediately (admin). Response:
`{"appliedActions": 1}`. Rules remain bounded by cooldowns and hourly limits.

---

## Java API

Add the dependency:

```xml
<dependency>
  <groupId>studio.qsa3ed.qcore</groupId>
  <artifactId>qcore-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
QCoreAPI qcore = ...; // supplied by the platform integration

// Registry
qcore.servers().cachedAll().forEach(server ->
        System.out.println(server.id() + " " + server.status()));

// Routing
RoutingDecision decision = qcore.routing().evaluate(new RoutingRequest(
        playerUuid, "lobby", Set.of(), 1, currentServer, true)).join();
if (decision.routed()) {
    transfer(player, decision.targetServerId());
} else {
    log.info("no destination: {}", decision.reason());
}

// Identity
PlayerProfile profile = qcore.players().ensureProfile(uuid, name).join();
Long coins = (Long) profile.metadata().getOrDefault("coins", 0L);

// Events
qcore.events().publish(NetworkEventType.PLAYER_JOINED_NETWORK, "lobby-1",
        Map.of("uuid", uuid.toString()));

// Metrics
NetworkSnapshot snapshot = qcore.metrics().currentSnapshot();
```

Rules for integrations:

- Do not call blocking API methods on a game thread. All I/O methods return
  `CompletableFuture`.
- Treat `RoutingDecision.routed() == false` as a normal outcome, not an error.
- Never read a secret from configuration; qCore does not expose them through the
  API.

---

## Redis contract

| Key | Type | Purpose |
|---|---|---|
| `qcore:network` | Pub/Sub channel | Transient network events and notifications |
| `qcore:stream:network` | Stream | Durable messages for acknowledged processing |
| consumer group | `qcore-nodes` | Durable message processing |

Messages are JSON envelopes:

```json
{
  "messageId": "b2f1…",
  "type": "NETWORK_EVENT",
  "sourceNode": "central",
  "targetNode": "*",
  "timestamp": 1730000000000,
  "expiresAt": 1730000060000,
  "correlationId": "",
  "attempt": 1,
  "payload": { "…": "…" }
}
```

Consumers must: drop expired messages, deduplicate on `messageId`, and never
assume Pub/Sub delivery. Use the stream when a message must not be lost.
