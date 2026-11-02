-- qCore initial schema.
-- Values are stored as epoch milliseconds; JSON columns keep flexible payloads
-- searchable without schema churn.

CREATE TABLE IF NOT EXISTS qcore_servers (
    id                 TEXT PRIMARY KEY,
    name               TEXT        NOT NULL,
    type               TEXT        NOT NULL,
    host               TEXT        NOT NULL DEFAULT '',
    port               INTEGER     NOT NULL DEFAULT 0,
    address            TEXT        NOT NULL DEFAULT '',
    status             TEXT        NOT NULL,
    game_mode          TEXT        NOT NULL DEFAULT '',
    weight             INTEGER     NOT NULL DEFAULT 0,
    tags               TEXT        NOT NULL DEFAULT '',
    accepting_players  BOOLEAN     NOT NULL DEFAULT TRUE,
    registered_at      BIGINT      NOT NULL,
    last_heartbeat_at  BIGINT      NOT NULL,
    metrics_json       TEXT        NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_qcore_servers_status ON qcore_servers (status);
CREATE INDEX IF NOT EXISTS idx_qcore_servers_heartbeat ON qcore_servers (last_heartbeat_at);

CREATE TABLE IF NOT EXISTS qcore_players (
    uuid            UUID PRIMARY KEY,
    username        TEXT   NOT NULL,
    last_server_id  TEXT   NOT NULL DEFAULT '',
    first_seen_at   BIGINT NOT NULL,
    last_seen_at    BIGINT NOT NULL,
    data_version    BIGINT NOT NULL DEFAULT 1,
    metadata_json   TEXT   NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS qcore_player_sessions (
    uuid          UUID PRIMARY KEY,
    username      TEXT    NOT NULL,
    server_id     TEXT    NOT NULL,
    connected_at  BIGINT  NOT NULL,
    updated_at    BIGINT  NOT NULL,
    connected     BOOLEAN NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_qcore_sessions_server ON qcore_player_sessions (server_id);

CREATE TABLE IF NOT EXISTS qcore_events (
    event_id     TEXT PRIMARY KEY,
    type         TEXT   NOT NULL,
    source_node  TEXT   NOT NULL,
    occurred_at  BIGINT NOT NULL,
    data_json    TEXT   NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_qcore_events_occurred ON qcore_events (occurred_at DESC);

CREATE TABLE IF NOT EXISTS qcore_routing_audit (
    id           BIGSERIAL PRIMARY KEY,
    player_id    UUID   NULL,
    target        TEXT  NULL,
    routed       BOOLEAN NOT NULL,
    reason       TEXT   NOT NULL,
    details_json TEXT   NOT NULL DEFAULT '{}',
    occurred_at  BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_qcore_routing_occurred ON qcore_routing_audit (occurred_at DESC);

CREATE TABLE IF NOT EXISTS qcore_automation_audit (
    id          BIGSERIAL PRIMARY KEY,
    rule_id     TEXT   NOT NULL,
    node_id     TEXT   NOT NULL,
    action      TEXT   NOT NULL,
    details     TEXT   NOT NULL DEFAULT '',
    occurred_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_qcore_automation_occurred ON qcore_automation_audit (occurred_at DESC);
