package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.model.PlayerProfile;
import studio.qsa3ed.qcore.common.model.PlayerSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Unified, UUID-keyed player identity across the network.
 *
 * <p>Synchronisation is policy driven: only fields enabled in the
 * configuration are mirrored, and every write bumps the profile's data
 * version so conflicting updates can be resolved deterministically.</p>
 */
public interface PlayerIdentityService {

    /** Creates the profile when absent, otherwise refreshes its username. */
    CompletableFuture<PlayerProfile> ensureProfile(UUID uuid, String username);

    /** Looks up a profile. */
    CompletableFuture<Optional<PlayerProfile>> profile(UUID uuid);

    /** Records a player's presence on a server. */
    CompletableFuture<Void> updateSession(PlayerSession session);

    /** Marks a player session as closed. */
    CompletableFuture<Void> markDisconnected(UUID uuid, String serverId, long when);

    /** @return profiles that are currently connected to the given server. */
    CompletableFuture<List<PlayerProfile>> onlineOn(String serverId);
}
