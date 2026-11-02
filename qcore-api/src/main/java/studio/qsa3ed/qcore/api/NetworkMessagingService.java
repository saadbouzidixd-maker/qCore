package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.messaging.NetworkMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Low-level node messaging.
 *
 * <p>Transient notifications are published on Redis Pub/Sub and may be lost;
 * anything that must not be lost is also written to a Redis Stream. Callers
 * that require delivery guarantees must inspect the returned future.</p>
 */
public interface NetworkMessagingService {

    /** Publishes a message to every node. */
    CompletableFuture<Void> broadcast(NetworkMessage message);

    /** Sends a message to one node. */
    CompletableFuture<Boolean> send(NetworkMessage message);

    /** Appends a message to the durable stream for acknowledged processing. */
    CompletableFuture<String> enqueue(NetworkMessage message);
}
