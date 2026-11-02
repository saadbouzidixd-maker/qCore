package studio.qsa3ed.qcore.central.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.exception.ValidationException;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.storage.repository.ServerRepository;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The authoritative in-memory registry, mirrored to PostgreSQL.
 *
 * <p>Reads for latency-sensitive paths (routing, snapshots) are served from the
 * cache and never touch the database. Writes update the cache first and persist
 * asynchronously; a persistence failure is logged and counted but does not take
 * the network down, and the state is re-persisted on the next heartbeat.</p>
 */
public final class ServerRegistryManager implements ServerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ServerRegistryManager.class);

    private final ConcurrentHashMap<String, NetworkServer> cache = new ConcurrentHashMap<>();
    private final ServerRepository repository;
    private final EventService events;
    private final Clock clock;
    private final AtomicLong persistenceFailures = new AtomicLong();

    public ServerRegistryManager(ServerRepository repository, EventService events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /** Loads persisted state into the cache at startup. */
    public CompletableFuture<Integer> warmUp() {
        return repository.findAll().handle((servers, failure) -> {
            if (failure != null) {
                // Degraded startup is expected when persistence is disabled; keep the console clean.
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                LOG.warn("Unable to restore the server registry from PostgreSQL ({}); starting with an empty cache",
                        cause.getMessage());
                LOG.debug("Registry warm-up failure", failure);
                return 0;
            }
            servers.forEach(server -> cache.put(server.id(), server));
            LOG.info("Restored {} server(s) from PostgreSQL", servers.size());
            return servers.size();
        });
    }

    @Override
    public CompletableFuture<Void> register(NetworkServer server) {
        if (server == null) {
            return CompletableFuture.failedFuture(new ValidationException("server", "must not be null"));
        }
        NetworkServer existing = cache.get(server.id());
        NetworkServer merged = existing == null ? server : new NetworkServer(
                server.id(), server.name(), server.type(), server.host(), server.port(), server.address(),
                server.status(), server.metrics(), existing.registeredAt(),
                Math.max(existing.lastHeartbeatAt(), server.lastHeartbeatAt()),
                server.gameMode(), server.weight(), server.tags(), server.acceptingPlayers());
        cache.put(merged.id(), merged);
        if (existing == null) {
            events.publish(merged.type() == NodeType.SERVER
                            ? NetworkEventType.SERVER_REGISTERED : NetworkEventType.NODE_REGISTERED,
                    "central", Map.of("nodeId", merged.id(), "name", merged.name(),
                            "type", merged.type().name()));
        }
        return persist(merged);
    }

    @Override
    public CompletableFuture<Void> recordHeartbeat(String serverId, ServerMetrics metrics) {
        if (serverId == null || serverId.isBlank()) {
            return CompletableFuture.failedFuture(new ValidationException("serverId", "must not be blank"));
        }
        if (metrics == null) {
            return CompletableFuture.failedFuture(new ValidationException("metrics", "must not be null"));
        }
        NetworkServer existing = cache.get(serverId);
        if (existing == null) {
            return CompletableFuture.failedFuture(new ValidationException("serverId",
                    "unknown node '" + serverId + "'; register before sending heartbeats"));
        }
        NodeStatus status = existing.status() == NodeStatus.OFFLINE ? NodeStatus.ONLINE : existing.status();
        NetworkServer updated = existing.withMetrics(metrics, status, clock.millis());
        cache.put(serverId, updated);
        return persist(updated);
    }

    @Override
    public CompletableFuture<Boolean> markOffline(String serverId, String reason) {
        NetworkServer existing = cache.get(serverId);
        if (existing == null || existing.status() == NodeStatus.OFFLINE) {
            return CompletableFuture.completedFuture(false);
        }
        NetworkServer offline = existing.withStatus(NodeStatus.OFFLINE, existing.lastHeartbeatAt());
        cache.put(serverId, offline);
        LOG.warn("Node {} ({}) marked offline: {}", offline.name(), serverId, reason);
        events.publish(NetworkEventType.SERVER_HEARTBEAT_LOST, "central",
                Map.of("nodeId", serverId, "reason", reason == null ? "" : reason));
        return persist(offline).thenApply(ignored -> true);
    }

    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, NodeStatus status) {
        NetworkServer existing = cache.get(serverId);
        if (existing == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (existing.status() == status) {
            return CompletableFuture.completedFuture(true);
        }
        NetworkServer updated = existing.withStatus(status, existing.lastHeartbeatAt());
        cache.put(serverId, updated);
        events.publish(NetworkEventType.SERVER_STATUS_CHANGED, "central", Map.of(
                "nodeId", serverId,
                "from", existing.status().name(),
                "to", status.name()));
        return persist(updated).thenApply(ignored -> true);
    }

    @Override
    public CompletableFuture<Optional<NetworkServer>> find(String serverId) {
        NetworkServer cached = cache.get(serverId);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return repository.findById(serverId).exceptionally(failure -> {
            LOG.warn("Server lookup for {} failed; reporting absent", serverId, failure);
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<NetworkServer>> all() {
        return repository.findAll().exceptionally(failure -> {
            LOG.warn("Unable to list servers from PostgreSQL; serving the cached view", failure);
            return cachedAll();
        });
    }

    @Override
    public Optional<NetworkServer> cached(String serverId) {
        return Optional.ofNullable(cache.get(serverId));
    }

    @Override
    public List<NetworkServer> cachedAll() {
        return cache.values().stream()
                .sorted(Comparator.comparing(NetworkServer::name))
                .toList();
    }

    @Override
    public List<NetworkServer> cachedByType(NodeType type) {
        return cachedAll().stream().filter(server -> server.type() == type).toList();
    }

    /** Persists a node, counting and logging failures instead of failing the caller. */
    private CompletableFuture<Void> persist(NetworkServer server) {
        return repository.upsert(server).whenComplete((ignored, failure) -> {
            if (failure != null) {
                persistenceFailures.incrementAndGet();
                LOG.warn("Unable to persist node {}; it stays available in memory", server.id(), failure);
            }
        }).exceptionally(failure -> null);
    }

    /** @return how many persistence attempts failed since start. */
    public long persistenceFailures() {
        return persistenceFailures.get();
    }
}
