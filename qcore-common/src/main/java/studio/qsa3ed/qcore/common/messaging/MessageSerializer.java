package studio.qsa3ed.qcore.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import studio.qsa3ed.qcore.common.exception.SerializationException;
import studio.qsa3ed.qcore.common.json.Json;

/** Serializes network messages to and from JSON using the shared mapper. */
public final class MessageSerializer {

    private final MessageValidator validator = new MessageValidator();

    /** Serializes a message, validating it first. */
    public String serialize(NetworkMessage message) {
        validator.validate(message);
        try {
            return Json.mapper().writeValueAsString(message);
        } catch (JsonProcessingException failure) {
            throw new SerializationException("Unable to serialize message " + message.messageId(), failure);
        }
    }

    /** Deserializes and validates a message. */
    public NetworkMessage deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new SerializationException("Message body must not be empty");
        }
        NetworkMessage message;
        try {
            message = Json.mapper().readValue(json, NetworkMessage.class);
        } catch (JsonProcessingException failure) {
            throw new SerializationException("Unable to deserialize network message", failure);
        }
        validator.validate(message);
        return message;
    }

    /** Converts the payload map into a typed value. */
    public <T> T payloadAs(NetworkMessage message, Class<T> type) {
        try {
            return Json.mapper().convertValue(message.payload(), type);
        } catch (IllegalArgumentException failure) {
            throw new SerializationException(
                    "Unable to convert payload of " + message.type() + " to " + type.getSimpleName(), failure);
        }
    }

    /** @return the serialized size in bytes, used to enforce transport limits. */
    static long estimateSize(NetworkMessage message) {
        try {
            return Json.mapper().writeValueAsBytes(message).length;
        } catch (JsonProcessingException failure) {
            // Unencodable payload: report the maximum so validation rejects it with a clear message.
            return Long.MAX_VALUE;
        }
    }
}
