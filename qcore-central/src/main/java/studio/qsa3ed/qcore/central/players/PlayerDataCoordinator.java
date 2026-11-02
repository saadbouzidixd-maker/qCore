package studio.qsa3ed.qcore.central.players;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.PlayerIdentityService;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.PlayerProfile;
import studio.qsa3ed.qcore.common.model.PlayerSession;
import studio.qsa3ed.qcore.common.util.Validation;
import studio.qsa3ed.qcore.storage.repository.PlayerRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains the unified player identity.
 *
 * <p>Synchronisation is deliberately narrow: identity, last server and the
 * network metadata a developer explicitly publishes. Gameplay state such as
 * inventories is never copied blindly; those fields belong to the game server's
 * own synchronisation policy. Every write bumps the profile data version, and
 * the repository applies only newer versions, so duplicated or re-ordered
 * deliveries converge instead of overwriting newer data.</p>
 */
public final class PlayerDataCoordinator implements PlayerIdentityService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerDataCoordinator.class);

    private final PlayerRepository repository;
    private final EventService events;
    private final Counters metrics;
    private final Clock clock;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    public PlayerDataCoordinator(PlayerRepository repository, EventService events,
                                 Counters metrics, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<PlayerProfile> ensureProfile(UUID uuid, String username) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(
                    new studio.qsa3ed.qcore.common.exception.ValidationException("uuid", "must not be null"));
        }
        Validation.maxLength(username, 16, "username");
        PlayerProfile cached = cache.get(uuid);
        if (cached != null && cached.username().equals(username)) {
            return CompletableFuture.completedFuture(cached);
        }
        return repository.findByUuid(uuid).thenCompose(existing -> {
            long now = clock.millis();
            PlayerProfile profile = existing
                    .map(found -> found.username().equals(username) ? found : found.withUsername(username, now))
                    .orElseGet(() -> PlayerProfile.create(uuid, username, now));
            cache.put(uuid, profile);
            return repository.upsert(profile).thenApply(saved -> {
                metrics.increment("players.profile.resolved");
                return saved;
            });
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerProfile>> profile(UUID uuid) {
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return repository.findByUuid(uuid).thenApply(found -> {
            found.ifPresent(profile -> cache.put(uuid, profile));
            return found;
        });
    }

    @Override
    public CompletableFuture<Void> updateSession(PlayerSession session) {
        metrics.increment("players.session.updated");
        return repository.saveSession(session)
                .thenCompose(ignored -> ensureProfile(session.uuid(), session.username()))
                .thenCompose(profile -> repository.upsert(profile.withLastServer(session.serverId(), session.updatedAt())))
                .thenAccept(stored -> {
                    cache.put(stored.uuid(), stored);
                    events.publish(NetworkEventType.PLAYER_SERVER_CHANGED, "central", Map.of(
                            "uuid", stored.uuid().toString(),
                            "username", stored.username(),
                            "serverId", stored.lastServerId(),
                            "connected", session.connected()));
                });
    }

    @Override
    public CompletableFuture<Void> markDisconnected(UUID uuid, String serverId, long when) {
        if (uuid == null) {
            return CompletableFuture.failedFuture(
                    new studio.qsa3ed.qcore.common.exception.ValidationException("uuid", "must not be null"));
        }
        String resolvedServer = serverId == null || serverId.isBlank()
                ? profile(uuid).join().map(PlayerProfile::lastServerId).orElse("unknown")
                : serverId;
        PlayerSession session = new PlayerSession(uuid, cache.containsKey(uuid)
                ? cache.get(uuid).username() : "", resolvedServer, when, when, false);
        metrics.increment("players.session.closed");
        return repository.saveSession(session).thenAccept(ignored ->
                events.publish(NetworkEventType.PLAYER_LEFT_NETWORK, "central", Map.of(
                        "uuid", uuid.toString(),
                        "serverId", resolvedServer)));
    }

    @Override
    public CompletableFuture<List<PlayerProfile>> onlineOn(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new studio.qsa3ed.qcore.common.exception.ValidationException("serverId", "must not be blank"));
        }
        return repository.findByServer(serverId).exceptionally(failure -> {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warn("Unable to list players on {} ({}); returning the cached view", serverId, cause.getMessage());
            LOG.debug("Player listing failure", cause);
            return cache.values().stream()
                    .filter(profile -> serverId.equals(profile.lastServerId()))
                    .toList();
        });
    }
}
