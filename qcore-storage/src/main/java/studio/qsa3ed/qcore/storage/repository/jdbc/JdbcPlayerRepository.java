package studio.qsa3ed.qcore.storage.repository.jdbc;

import studio.qsa3ed.qcore.common.json.Json;
import studio.qsa3ed.qcore.common.model.PlayerProfile;
import studio.qsa3ed.qcore.common.model.PlayerSession;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.repository.PlayerRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * JDBC implementation of {@link PlayerRepository}.
 *
 * <p>Conflicts are resolved by data version: an update is applied only when the
 * incoming version is greater than the stored one, which makes retries and
 * duplicated deliveries harmless.</p>
 */
public final class JdbcPlayerRepository implements PlayerRepository {

    private static final String UPSERT_PROFILE = """
            INSERT INTO qcore_players (uuid, username, last_server_id, first_seen_at, last_seen_at,
                                       data_version, metadata_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET
                username = CASE WHEN EXCLUDED.data_version >= qcore_players.data_version
                                THEN EXCLUDED.username ELSE qcore_players.username END,
                last_server_id = CASE WHEN EXCLUDED.data_version >= qcore_players.data_version
                                      THEN EXCLUDED.last_server_id ELSE qcore_players.last_server_id END,
                last_seen_at = GREATEST(qcore_players.last_seen_at, EXCLUDED.last_seen_at),
                data_version = GREATEST(qcore_players.data_version, EXCLUDED.data_version),
                metadata_json = CASE WHEN EXCLUDED.data_version >= qcore_players.data_version
                                     THEN EXCLUDED.metadata_json ELSE qcore_players.metadata_json END
            """;

    private static final String UPSERT_SESSION = """
            INSERT INTO qcore_player_sessions (uuid, username, server_id, connected_at, updated_at, connected)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET
                username = EXCLUDED.username,
                server_id = EXCLUDED.server_id,
                connected_at = EXCLUDED.connected_at,
                updated_at = EXCLUDED.updated_at,
                connected = EXCLUDED.connected
            """;

    private final Database database;

    public JdbcPlayerRepository(Database database) {
        this.database = database;
    }

    @Override
    public CompletableFuture<PlayerProfile> upsert(PlayerProfile profile) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_PROFILE)) {
                statement.setObject(1, profile.uuid());
                statement.setString(2, profile.username());
                statement.setString(3, profile.lastServerId());
                statement.setLong(4, profile.firstSeenAt());
                statement.setLong(5, profile.lastSeenAt());
                statement.setLong(6, profile.dataVersion());
                statement.setString(7, writeMetadata(profile.metadata()));
                statement.executeUpdate();
            }
            return profile;
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT uuid, username, last_server_id, first_seen_at, last_seen_at, data_version, metadata_json
                    FROM qcore_players WHERE uuid = ?""")) {
                statement.setObject(1, uuid);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(mapProfile(rows)) : Optional.<PlayerProfile>empty();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveSession(PlayerSession session) {
        return database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT_SESSION)) {
                statement.setObject(1, session.uuid());
                statement.setString(2, session.username());
                statement.setString(3, session.serverId());
                statement.setLong(4, session.connectedAt());
                statement.setLong(5, session.updatedAt());
                statement.setBoolean(6, session.connected());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<List<PlayerProfile>> findByServer(String serverId) {
        return database.withConnection(connection -> {
            List<PlayerProfile> profiles = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.uuid, p.username, p.last_server_id, p.first_seen_at, p.last_seen_at,
                           p.data_version, p.metadata_json
                    FROM qcore_players p
                    JOIN qcore_player_sessions s ON s.uuid = p.uuid
                    WHERE s.server_id = ? AND s.connected = TRUE
                    ORDER BY p.username""")) {
                statement.setString(1, serverId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        profiles.add(mapProfile(rows));
                    }
                }
            }
            return profiles;
        });
    }

    private static String writeMetadata(Map<String, Object> metadata) throws SQLException {
        try {
            return Json.mapper().writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Unable to serialize player metadata", failure);
        }
    }

    private static Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> raw = Json.mapper().readValue(json, LinkedHashMap.class);
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            // Corrupt metadata is reported as empty rather than failing the lookup.
            return Map.of();
        }
    }

    private static PlayerProfile mapProfile(ResultSet rows) throws SQLException {
        return new PlayerProfile(
                rows.getObject("uuid", UUID.class),
                rows.getString("username"),
                rows.getString("last_server_id"),
                rows.getLong("first_seen_at"),
                rows.getLong("last_seen_at"),
                rows.getLong("data_version"),
                readMetadata(rows.getString("metadata_json")));
    }
}
