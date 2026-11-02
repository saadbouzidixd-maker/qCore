package studio.qsa3ed.qcore.common.events;

import studio.qsa3ed.qcore.common.util.Ids;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A durable, network-wide event.
 *
 * @param eventId    unique id, used for idempotent persistence
 * @param type       event type
 * @param sourceNode node that produced the event
 * @param occurredAt epoch millis
 * @param data       event attributes, values must be JSON-serializable
 */
public record NetworkEvent(
        String eventId,
        NetworkEventType type,
        String sourceNode,
        long occurredAt,
        Map<String, Object> data) {

    public NetworkEvent {
        Objects.requireNonNull(type, "type");
        data = data == null || data.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(data));
        if (eventId == null || eventId.isBlank()) {
            eventId = Ids.newId();
        }
        sourceNode = sourceNode == null ? "unknown" : sourceNode;
        if (occurredAt <= 0) {
            occurredAt = Ids.now();
        }
    }

    /** Creates a new event with a fresh id and the current timestamp. */
    public static NetworkEvent create(NetworkEventType type, String sourceNode, Map<String, Object> data) {
        return new NetworkEvent(Ids.newId(), type, sourceNode, Ids.now(), data);
    }

    /** Creates a new event with a single attribute. */
    public static NetworkEvent of(NetworkEventType type, String sourceNode, String key, Object value) {
        return create(type, sourceNode, Map.of(key, value));
    }
}
