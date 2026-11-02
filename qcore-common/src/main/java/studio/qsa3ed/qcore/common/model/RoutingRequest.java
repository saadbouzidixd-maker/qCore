package studio.qsa3ed.qcore.common.model;

import java.util.Set;
import java.util.UUID;

/**
 * A request to place a player on a server.
 *
 * @param playerId        player to route
 * @param group           requested server group or game mode, may be empty for any
 * @param requiredTags    tags the destination must carry
 * @param partySize       number of players that must fit together, at least 1
 * @param currentServerId server the player is on now, excluded from selection
 * @param allowFallback   whether a degraded server may be used when nothing healthy fits
 */
public record RoutingRequest(
        UUID playerId,
        String group,
        Set<String> requiredTags,
        int partySize,
        String currentServerId,
        boolean allowFallback) {

    public RoutingRequest {
        group = group == null ? "" : group.trim();
        requiredTags = requiredTags == null || requiredTags.isEmpty() ? Set.of() : Set.copyOf(requiredTags);
        if (partySize < 0) {
            throw new IllegalArgumentException("partySize must not be negative");
        }
        currentServerId = currentServerId == null ? "" : currentServerId;
    }

    /** @return the party size actually required, never below one. */
    public int effectivePartySize() {
        return Math.max(1, partySize);
    }
}
