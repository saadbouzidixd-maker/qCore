package studio.qsa3ed.qcore.storage.repository.jdbc;

import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.json.Json;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.repository.EventRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** JDBC implementation of {@link EventRepository}. */
public final class JdbcEventRepository implements EventRepository {

    private final Database database;

    public JdbcEventRepository(Database database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> append(NetworkEvent event) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO qcore_events (event_id, type, source_node, occurred_at, data_json)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING""")) {
                statement.setString(1, event.eventId());
                statement.setString(2, event.type().name());
                statement.setString(3, event.sourceNode());
                statement.setLong(4, event.occurredAt());
                statement.setString(5, writeData(event.data()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<List<NetworkEvent>> recent(int limit) {
        int bounded = Math.max(1, Math.min(1_000, limit));
        return database.withConnection(connection -> {
            List<NetworkEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT event_id, type, source_node, occurred_at, data_json
                    FROM qcore_events ORDER BY occurred_at DESC LIMIT ?""")) {
                statement.setInt(1, bounded);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        events.add(new NetworkEvent(
                                rows.getString("event_id"),
                                NetworkEventType.valueOf(rows.getString("type")),
                                rows.getString("source_node"),
                                rows.getLong("occurred_at"),
                                readData(rows.getString("data_json"))));
                    }
                }
            }
            return events;
        });
    }

    @Override
    public CompletableFuture<Void> appendAutomationAudit(String ruleId, String nodeId, String action,
                                                         String details, long occurredAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO qcore_automation_audit (rule_id, node_id, action, details, occurred_at)
                    VALUES (?, ?, ?, ?, ?)""")) {
                statement.setString(1, ruleId);
                statement.setString(2, nodeId);
                statement.setString(3, action);
                statement.setString(4, details == null ? "" : details);
                statement.setLong(5, occurredAt);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> appendRoutingAudit(UUID playerId, String target, boolean routed,
                                                      String reason, String detailsJson, long occurredAt) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO qcore_routing_audit (player_id, target, routed, reason, details_json, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                statement.setObject(1, playerId);
                statement.setString(2, target);
                statement.setBoolean(3, routed);
                statement.setString(4, reason);
                statement.setString(5, detailsJson == null ? "{}" : detailsJson);
                statement.setLong(6, occurredAt);
                statement.executeUpdate();
            }
        });
    }

    private static String writeData(Map<String, Object> data) throws SQLException {
        try {
            return Json.mapper().writeValueAsString(data == null ? Map.of() : data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Unable to serialize event data", failure);
        }
    }

    private static Map<String, Object> readData(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> raw = Json.mapper().readValue(json, LinkedHashMap.class);
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return Map.of();
        }
    }
}
