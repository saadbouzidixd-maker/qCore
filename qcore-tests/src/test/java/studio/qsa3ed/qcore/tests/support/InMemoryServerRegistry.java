package studio.qsa3ed.qcore.tests.support;

import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** A registry double that mirrors production caching semantics without I/O. */
public final class InMemoryServerRegistry implements ServerRegistry {

    private final Map<String, NetworkServer> servers = new ConcurrentHashMap<>();

    public InMemoryServerRegistry add(NetworkServer server) {
        servers.put(server.id(), server);
        return this;
    }

    @Override
    public CompletableFuture<Void> register(NetworkServer server) {
        servers.put(server.id(), server);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> recordHeartbeat(String serverId, ServerMetrics metrics) {
        NetworkServer existing = servers.get(serverId);
        if (existing == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + serverId));
        }
        servers.put(serverId, existing.withMetrics(metrics, existing.status(), System.currentTimeMillis()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> markOffline(String serverId, String reason) {
        NetworkServer existing = servers.get(serverId);
        if (existing == null) {
            return CompletableFuture.completedFuture(false);
        }
        servers.put(serverId, existing.withStatus(NodeStatus.OFFLINE, existing.lastHeartbeatAt()));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> updateStatus(String serverId, NodeStatus status) {
        NetworkServer existing = servers.get(serverId);
        if (existing == null) {
            return CompletableFuture.completedFuture(false);
        }
        servers.put(serverId, existing.withStatus(status, existing.lastHeartbeatAt()));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Optional<NetworkServer>> find(String serverId) {
        return CompletableFuture.completedFuture(Optional.ofNullable(servers.get(serverId)));
    }

    @Override
    public CompletableFuture<List<NetworkServer>> all() {
        return CompletableFuture.completedFuture(cachedAll());
    }

    @Override
    public Optional<NetworkServer> cached(String serverId) {
        return Optional.ofNullable(servers.get(serverId));
    }

    @Override
    public List<NetworkServer> cachedAll() {
        return List.copyOf(servers.values());
    }

    @Override
    public List<NetworkServer> cachedByType(NodeType type) {
        return servers.values().stream().filter(server -> server.type() == type).toList();
    }
}
