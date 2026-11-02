package studio.qsa3ed.qcore.central.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.NetworkMessagingService;
import studio.qsa3ed.qcore.common.exception.MessagingException;
import studio.qsa3ed.qcore.common.messaging.MessageSerializer;
import studio.qsa3ed.qcore.common.messaging.NetworkMessage;
import studio.qsa3ed.qcore.storage.redis.RedisMessageBus;
import studio.qsa3ed.qcore.storage.redis.RedisStreamQueue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes messages over Redis Pub/Sub and appends durable messages to a stream.
 *
 * <p>Pub/Sub delivery is never assumed: publishing completes exceptionally when
 * Redis is unavailable, and callers that need a guarantee use {@link #enqueue}
 * which writes to a Redis Stream instead.</p>
 */
public final class NodeMessagingService implements NetworkMessagingService {

    private static final Logger LOG = LoggerFactory.getLogger(NodeMessagingService.class);
    /** Pub/Sub channel used for transient notifications. */
    public static final String NETWORK_CHANNEL = "network";
    /** Redis Stream holding messages that must not be lost. */
    public static final String DURABLE_STREAM = "stream:network";
    /** Consumer group used for durable message processing. */
    public static final String GROUP = "qcore-nodes";

    private final RedisMessageBus bus;
    private final RedisStreamQueue queue;
    private final MessageSerializer serializer;

    public NodeMessagingService(RedisMessageBus bus, RedisStreamQueue queue, MessageSerializer serializer) {
        this.bus = bus;
        this.queue = queue;
        this.serializer = serializer;
    }

    @Override
    public CompletableFuture<Void> broadcast(NetworkMessage message) {
        return bus.publish(NETWORK_CHANNEL, serializer.serialize(message)).thenApply(delivered -> {
            if (delivered != null && delivered == 0L) {
                LOG.debug("No node was subscribed to the broadcast of {}", message.messageId());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> send(NetworkMessage message) {
        return bus.publish(NETWORK_CHANNEL, serializer.serialize(message))
                .thenApply(delivered -> delivered != null && delivered > 0L);
    }

    @Override
    public CompletableFuture<String> enqueue(NetworkMessage message) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("id", message.messageId());
        entry.put("type", message.type().name());
        entry.put("source", message.sourceNode());
        entry.put("target", message.targetNode());
        entry.put("timestamp", Long.toString(message.timestamp()));
        entry.put("expiresAt", Long.toString(message.expiresAt()));
        entry.put("attempt", Integer.toString(message.attempt()));
        entry.put("body", serializer.serialize(message));
        return queue.ensureGroup()
                .thenCompose(ignored -> queue.append(entry))
                .exceptionally(failure -> {
                    throw new MessagingException("Unable to enqueue message " + message.messageId(), failure);
                });
    }

    /** @return {@code true} when Pub/Sub is usable. */
    public boolean isAvailable() {
        return bus.isAvailable();
    }
}
