package studio.qsa3ed.qcore.storage.repository;

import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Persistence contract for registered nodes. */
public interface ServerRepository {

    /** Inserts or updates a node's description and state. */
    CompletableFuture<Void> upsert(NetworkServer server);

    /** Finds one node. */
    CompletableFuture<Optional<NetworkServer>> findById(String serverId);

    /** @return every persisted node. */
    CompletableFuture<List<NetworkServer>> findAll();

    /** Marks every node whose last heartbeat is older than {@code threshold} as offline. */
    CompletableFuture<Integer> markHeartbeatExpired(long threshold, NodeStatus status);
}
