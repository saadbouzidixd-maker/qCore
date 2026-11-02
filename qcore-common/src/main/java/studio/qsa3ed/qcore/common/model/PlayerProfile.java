package studio.qsa3ed.qcore.common.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The network-wide identity of one player, keyed by Minecraft UUID.
 *
 * <p>Only fields listed in the configuration's synchronisation policy are
 * mirrored between servers; {@code metadata} holds arbitrary developer
 * attributes that the owning plugin chose to publish.</p>
 *
 * @param uuid        the player's Mojang UUID
 * @param username    last known username
 * @param lastServerId id of the server the player was last seen on, may be empty
 * @param firstSeenAt epoch millis of first observation
 * @param lastSeenAt  epoch millis of the last observation
 * @param dataVersion monotonically increasing version used for conflict resolution
 * @param metadata    configurable, network-wide attributes
 */
public record PlayerProfile(
        UUID uuid,
        String username,
        String lastServerId,
        long firstSeenAt,
        long lastSeenAt,
        long dataVersion,
        Map<String, Object> metadata) {

    public PlayerProfile {
        Objects.requireNonNull(uuid, "uuid");
        username = username == null ? "" : username;
        lastServerId = lastServerId == null ? "" : lastServerId;
        metadata = metadata == null || metadata.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    /** @return a new profile, created at {@code now}. */
    public static PlayerProfile create(UUID uuid, String username, long now) {
        return new PlayerProfile(uuid, username, "", now, now, 1L, Map.of());
    }

    /** @return a copy moved to another server, version bumped. */
    public PlayerProfile withLastServer(String serverId, long when) {
        return new PlayerProfile(uuid, username, serverId, firstSeenAt, when, dataVersion + 1, metadata);
    }

    /** @return a copy with a refreshed username and version bump. */
    public PlayerProfile withUsername(String newUsername, long when) {
        return new PlayerProfile(uuid, newUsername, lastServerId, firstSeenAt, when, dataVersion + 1, metadata);
    }

    /** @return a copy with merged metadata, version bumped. */
    public PlayerProfile withMetadata(Map<String, Object> additional, long when) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(additional);
        return new PlayerProfile(uuid, username, lastServerId, firstSeenAt, when, dataVersion + 1, merged);
    }
}
