package studio.qsa3ed.qcore.tests.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.common.exception.SerializationException;
import studio.qsa3ed.qcore.common.exception.ValidationException;
import studio.qsa3ed.qcore.common.messaging.MessageSerializer;
import studio.qsa3ed.qcore.common.messaging.MessageType;
import studio.qsa3ed.qcore.common.messaging.MessageValidator;
import studio.qsa3ed.qcore.common.messaging.NetworkMessage;
import studio.qsa3ed.qcore.common.model.ServerMetrics;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageProtocolTest {

    private final MessageSerializer serializer = new MessageSerializer();

    @Test
    @DisplayName("a message survives a JSON round trip with payload intact")
    void roundTrip() {
        NetworkMessage message = new NetworkMessage("msg-1", MessageType.SERVER_HEARTBEAT, "lobby-1", "central",
                1_700_000_000_000L, 1_700_000_060_000L, "corr-1", 1,
                Map.of("tps", 19.8, "players", 42));

        NetworkMessage decoded = serializer.deserialize(serializer.serialize(message));

        assertEquals(message.messageId(), decoded.messageId());
        assertEquals(MessageType.SERVER_HEARTBEAT, decoded.type());
        assertEquals("lobby-1", decoded.sourceNode());
        assertEquals("central", decoded.targetNode());
        assertEquals(message.timestamp(), decoded.timestamp());
        assertEquals(42, ((Number) decoded.payload().get("players")).intValue());
    }

    @Test
    @DisplayName("the same message id serializes identically, which makes redelivery idempotent")
    void idempotentEncoding() {
        NetworkMessage message = NetworkMessage.broadcast(MessageType.NETWORK_EVENT, "central", Map.of("k", "v"));

        String first = serializer.serialize(message);
        String second = serializer.serialize(message);

        assertEquals(first, second);
        assertEquals(message.messageId(), serializer.deserialize(first).messageId());
    }

    @Test
    @DisplayName("payload maps convert to typed models")
    void typedPayload() {
        NetworkMessage message = NetworkMessage.direct(MessageType.SERVER_HEARTBEAT, "lobby-1", "central",
                Map.of("tps", 20.0, "mspt", 1.5, "onlinePlayers", 3, "maxPlayers", 100,
                        "heapUsedMb", 512, "heapMaxMb", 2048, "software", "Paper 1.21.11"));

        ServerMetrics metrics = serializer.payloadAs(message, ServerMetrics.class);

        assertEquals(20.0, metrics.tps());
        assertEquals(97, metrics.freeSlots());
    }

    @Test
    @DisplayName("expired messages report expiry")
    void expiry() {
        long now = System.currentTimeMillis();
        NetworkMessage message = new NetworkMessage("m", MessageType.COMMAND, "central", "*",
                now, now + 1_000L, "", 1, Map.of());

        assertFalse(message.isExpired(now));
        assertTrue(message.isExpired(now + 2_000L));
    }

    @Test
    @DisplayName("retry accounting increments the attempt and keeps the id")
    void retryAccounting() {
        NetworkMessage message = NetworkMessage.broadcast(MessageType.NETWORK_EVENT, "central", Map.of());

        NetworkMessage retried = message.nextAttempt();

        assertEquals(message.messageId(), retried.messageId());
        assertEquals(2, retried.attempt());
    }

    @Test
    @DisplayName("validation rejects an expiry before the timestamp")
    void rejectsInvertedExpiry() {
        NetworkMessage message = new NetworkMessage("m", MessageType.COMMAND, "central", "*",
                5_000L, 1_000L, "", 1, Map.of());

        assertThrows(ValidationException.class, () -> new MessageValidator().validate(message));
    }

    @Test
    @DisplayName("validation rejects an invalid node identifier")
    void rejectsInvalidNode() {
        NetworkMessage message = new NetworkMessage("m", MessageType.COMMAND, "central node", "*",
                System.currentTimeMillis(), 0L, "", 1, Map.of());

        assertThrows(ValidationException.class, () -> new MessageValidator().validate(message));
    }

    @Test
    @DisplayName("validation rejects oversized messages")
    void rejectsOversizedPayload() {
        String huge = "x".repeat(MessageValidator.MAX_PAYLOAD_BYTES);
        NetworkMessage message = NetworkMessage.broadcast(MessageType.NETWORK_EVENT, "central", Map.of("data", huge));

        assertThrows(ValidationException.class, () -> new MessageValidator().validate(message));
    }

    @Test
    @DisplayName("an unparseable body is reported, never silently ignored")
    void rejectsMalformedJson() {
        assertThrows(SerializationException.class, () -> serializer.deserialize("{not json"));
        assertThrows(SerializationException.class, () -> serializer.deserialize(""));
    }
}
