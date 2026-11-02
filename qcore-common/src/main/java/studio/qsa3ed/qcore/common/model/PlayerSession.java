package studio.qsa3ed.qcore.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A player's presence on a specific server.
 *
 * @param uuid        player UUID
 * @param username    player name at session start
 * @param serverId    node the player is connected to
 * @param connectedAt epoch millis of the join
 * @param updatedAt   epoch millis of the last session refresh
 * @param connected   {@code false} once the player disconnected
 */
public record PlayerSession(
        UUID uuid,
        String username,
        String serverId,
        long connectedAt,
        long updatedAt,
        boolean connected) {

    public PlayerSession {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(serverId, "serverId");
        username = username == null ? "" : username;
        if (serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
    }

    /** @return a new open session. */
    public static PlayerSession open(UUID uuid, String username, String serverId, long now) {
        return new PlayerSession(uuid, username, serverId, now, now, true);
    }

    /** @return a copy marked as disconnected. */
    public PlayerSession closed(long when) {
        return new PlayerSession(uuid, username, serverId, connectedAt, when, false);
    }
}
