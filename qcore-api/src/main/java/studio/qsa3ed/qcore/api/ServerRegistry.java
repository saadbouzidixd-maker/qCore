package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.ServerMetrics;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The authoritative registry of network nodes.
 *
 * <p>Writes are persisted to PostgreSQL and mirrored into an in-memory cache
 * so that latency-sensitive readers (routing) never touch the database.</p>
 */
public interface ServerRegistry {

    /** Registers a node or refreshes its static description. */
    CompletableFuture<Void> register(NetworkServer server);

    /** Records telemetry for a node and refreshes its heartbeat. */
    CompletableFuture<Void> recordHeartbeat(String serverId, ServerMetrics metrics);

    /** Marks a node offline, for example because its heartbeat expired. */
    CompletableFuture<Boolean> markOffline(String serverId, String reason);

    /** Applies a health status to a node, for example from an automation rule. */
    CompletableFuture<Boolean> updateStatus(String serverId, studio.qsa3ed.qcore.common.model.NodeStatus status);

    /** Looks a node up in the authoritative registry. */
    CompletableFuture<Optional<NetworkServer>> find(String serverId);

    /** @return every registered node. */
    CompletableFuture<List<NetworkServer>> all();

    /** @return the cached view of a node, without any I/O. */
    Optional<NetworkServer> cached(String serverId);

    /** @return the cached view of every node, without any I/O. */
    List<NetworkServer> cachedAll();

    /** @return cached nodes of one type. */
    List<NetworkServer> cachedByType(studio.qsa3ed.qcore.common.model.NodeType type);
}
