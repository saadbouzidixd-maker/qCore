package studio.qsa3ed.qcore.storage.repository;

import studio.qsa3ed.qcore.common.model.PlayerProfile;
import studio.qsa3ed.qcore.common.model.PlayerSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Persistence contract for player identity and sessions. */
public interface PlayerRepository {

    /** Inserts a profile or updates it when the incoming version is newer. */
    CompletableFuture<PlayerProfile> upsert(PlayerProfile profile);

    /** Finds a profile. */
    CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID uuid);

    /** Stores the player's current session. */
    CompletableFuture<Void> saveSession(PlayerSession session);

    /** @return profiles whose session is open on the given server. */
    CompletableFuture<List<PlayerProfile>> findByServer(String serverId);
}
