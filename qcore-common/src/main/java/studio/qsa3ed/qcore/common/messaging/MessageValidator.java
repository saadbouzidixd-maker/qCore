package studio.qsa3ed.qcore.common.messaging;

import studio.qsa3ed.qcore.common.exception.ValidationException;
import studio.qsa3ed.qcore.common.util.Validation;

import java.util.regex.Pattern;

/**
 * Validates messages before they enter the transport.
 *
 * <p>Validation happens on both sides of Redis: producers reject malformed
 * envelopes locally, consumers reject anything that arrives broken, and every
 * rejected message is reported to the caller instead of being silently
 * dropped.</p>
 */
public final class MessageValidator {

    /** Maximum serialized payload size accepted by the transport, in bytes. */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private static final int MAX_NODE_LENGTH = 128;
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.*\\-]{1,128}");

    /** Validates the envelope. Throws {@link ValidationException} on the first violation. */
    public void validate(NetworkMessage message) {
        if (message == null) {
            throw new ValidationException("message", "must not be null");
        }
        if (message.messageId() == null || message.messageId().isBlank()) {
            throw new ValidationException("messageId", "must not be blank");
        }
        Validation.maxLength(message.messageId(), 128, "messageId");
        if (message.type() == null) {
            throw new ValidationException("type", "must not be null");
        }
        validateNode(message.sourceNode(), "sourceNode");
        validateNode(message.targetNode(), "targetNode");
        if (message.timestamp() <= 0) {
            throw new ValidationException("timestamp", "must be a positive epoch millisecond value");
        }
        if (message.expiresAt() > 0 && message.expiresAt() < message.timestamp()) {
            throw new ValidationException("expiresAt", "must not be before timestamp");
        }
        if (message.attempt() < 1) {
            throw new ValidationException("attempt", "must be at least 1");
        }
        Validation.maxLength(message.correlationId(), 128, "correlationId");
        long size = MessageSerializer.estimateSize(message);
        if (size > MAX_PAYLOAD_BYTES) {
            throw new ValidationException("payload", "serialized message exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
    }

    private void validateNode(String node, String field) {
        if (node == null || node.isBlank()) {
            throw new ValidationException(field, "must not be blank");
        }
        if (node.length() > MAX_NODE_LENGTH || !NODE.matcher(node).matches()) {
            throw new ValidationException(field, "must match " + NODE.pattern());
        }
    }
}
