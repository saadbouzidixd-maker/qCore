package studio.qsa3ed.qcore.common.messaging;

import studio.qsa3ed.qcore.common.util.Ids;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The envelope carried by every qCore transport.
 *
 * <p>Redis Pub/Sub may drop messages, therefore each envelope is
 * self-describing and idempotent: {@code messageId} uniquely identifies the
 * event, {@code expiresAt} bounds how long a consumer may act on it, and
 * {@code attempt} supports retry accounting on the durable stream.</p>
 *
 * @param messageId     unique id, the idempotency key
 * @param type          message type
 * @param sourceNode    node that produced the message
 * @param targetNode    intended node, {@code "*"} for broadcast
 * @param timestamp     epoch millis when produced
 * @param expiresAt     epoch millis after which the message must be discarded, {@code 0} for no expiry
 * @param correlationId id of the request this message answers, may be empty
 * @param attempt       delivery attempt, starting at 1
 * @param payload       message body, values must be JSON-serializable
 */
public record NetworkMessage(
        String messageId,
        MessageType type,
        String sourceNode,
        String targetNode,
        long timestamp,
        long expiresAt,
        String correlationId,
        int attempt,
        Map<String, Object> payload) {

    /** Target value meaning "every node". */
    public static final String BROADCAST = "*";

    public NetworkMessage {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (messageId == null || messageId.isBlank()) {
            messageId = Ids.newId();
        }
        sourceNode = sourceNode == null || sourceNode.isBlank() ? "unknown" : sourceNode;
        targetNode = targetNode == null || targetNode.isBlank() ? BROADCAST : targetNode;
        timestamp = timestamp <= 0 ? Ids.now() : timestamp;
        correlationId = correlationId == null ? "" : correlationId;
        attempt = Math.max(1, attempt);
        payload = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    /** Builds a new broadcast message with a default 60 second lifetime. */
    public static NetworkMessage broadcast(MessageType type, String sourceNode, Map<String, Object> payload) {
        long now = Ids.now();
        return new NetworkMessage(Ids.newId(), type, sourceNode, BROADCAST, now, now + 60_000L, "", 1, payload);
    }

    /** Builds a new message aimed at a single node. */
    public static NetworkMessage direct(MessageType type, String sourceNode, String targetNode,
                                        Map<String, Object> payload) {
        long now = Ids.now();
        return new NetworkMessage(Ids.newId(), type, sourceNode, targetNode, now, now + 60_000L, "", 1, payload);
    }

    /** @return {@code true} when the message's lifetime has elapsed. */
    public boolean isExpired(long now) {
        return expiresAt > 0 && now > expiresAt;
    }

    /** @return {@code true} when this message is addressed to every node. */
    public boolean isBroadcast() {
        return BROADCAST.equals(targetNode);
    }

    /** @return {@code true} when this message targets the given node. */
    public boolean isFor(String nodeId) {
        return isBroadcast() || targetNode.equals(nodeId);
    }

    /** @return a copy with an incremented attempt counter. */
    public NetworkMessage nextAttempt() {
        return new NetworkMessage(messageId, type, sourceNode, targetNode, timestamp, expiresAt,
                correlationId, attempt + 1, payload);
    }

    /** @return a copy addressed to {@code targetNode}. */
    public NetworkMessage to(String targetNode) {
        return new NetworkMessage(messageId, type, sourceNode, targetNode, timestamp, expiresAt,
                correlationId, attempt, payload);
    }
}
