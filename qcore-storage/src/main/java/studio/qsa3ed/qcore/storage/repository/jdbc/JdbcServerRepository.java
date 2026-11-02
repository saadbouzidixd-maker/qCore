package studio.qsa3ed.qcore.storage.repository.jdbc;

import studio.qsa3ed.qcore.common.json.Json;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.repository.ServerRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** JDBC implementation of {@link ServerRepository}. */
public final class JdbcServerRepository implements ServerRepository {

    private static final String UPSERT = """
            INSERT INTO qcore_servers (id, name, type, host, port, address, status, game_mode, weight,
                                       tags, accepting_players, registered_at, last_heartbeat_at, metrics_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                type = EXCLUDED.type,
                host = EXCLUDED.host,
                port = EXCLUDED.port,
                address = EXCLUDED.address,
                status = EXCLUDED.status,
                game_mode = EXCLUDED.game_mode,
                weight = EXCLUDED.weight,
                tags = EXCLUDED.tags,
                accepting_players = EXCLUDED.accepting_players,
                last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                metrics_json = EXCLUDED.metrics_json
            """;

    private static final String SELECT_ALL = """
            SELECT id, name, type, host, port, address, status, game_mode, weight, tags,
                   accepting_players, registered_at, last_heartbeat_at, metrics_json
            FROM qcore_servers
            """;

    private final Database database;

    public JdbcServerRepository(Database database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<Void> upsert(NetworkServer server) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, server.id());
                statement.setString(2, server.name());
                statement.setString(3, server.type().name());
                statement.setString(4, server.host());
                statement.setInt(5, server.port());
                statement.setString(6, server.address());
                statement.setString(7, server.status().name());
                statement.setString(8, server.gameMode());
                statement.setInt(9, server.weight());
                statement.setString(10, String.join(",", server.tags()));
                statement.setBoolean(11, server.acceptingPlayers());
                statement.setLong(12, server.registeredAt());
                statement.setLong(13, server.lastHeartbeatAt());
                statement.setString(14, writeMetrics(server.metrics()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<NetworkServer>> findById(String serverId) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL + " WHERE id = ?")) {
                statement.setString(1, serverId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(map(rows)) : Optional.<NetworkServer>empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<NetworkServer>> findAll() {
        return database.withConnection(connection -> {
            List<NetworkServer> servers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ALL + " ORDER BY name");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    servers.add(map(rows));
                }
            }
            return servers;
        });
    }

    @Override
    public CompletableFuture<Integer> markHeartbeatExpired(long threshold, NodeStatus status) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE qcore_servers SET status = ? WHERE last_heartbeat_at < ? AND status <> ?")) {
                statement.setString(1, status.name());
                statement.setLong(2, threshold);
                statement.setString(3, status.name());
                return statement.executeUpdate();
            }
        });
    }

    private static String writeMetrics(ServerMetrics metrics) throws SQLException {
        try {
            return Json.mapper().writeValueAsString(metrics);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Unable to serialize server metrics", failure);
        }
    }

    private static ServerMetrics readMetrics(String json) {
        if (json == null || json.isBlank()) {
            return ServerMetrics.unknown();
        }
        try {
            return Json.mapper().readValue(json, ServerMetrics.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            // A malformed sample must not break the registry; report it as unknown.
            return ServerMetrics.unknown();
        }
    }

    private static NetworkServer map(ResultSet rows) throws SQLException {
        Set<String> tags = new LinkedHashSet<>();
        String rawTags = rows.getString("tags");
        if (rawTags != null && !rawTags.isBlank()) {
            tags.addAll(Arrays.asList(rawTags.split(",")));
        }
        return new NetworkServer(
                rows.getString("id"),
                rows.getString("name"),
                NodeType.valueOf(rows.getString("type")),
                rows.getString("host"),
                rows.getInt("port"),
                rows.getString("address"),
                NodeStatus.valueOf(rows.getString("status")),
                readMetrics(rows.getString("metrics_json")),
                rows.getLong("registered_at"),
                rows.getLong("last_heartbeat_at"),
                rows.getString("game_mode"),
                rows.getInt("weight"),
                tags,
                rows.getBoolean("accepting_players"));
    }
}
