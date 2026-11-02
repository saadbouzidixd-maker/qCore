package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Publishes and queries network-wide events. */
public interface EventService {

    /** Persists and broadcasts an event. */
    CompletableFuture<NetworkEvent> publish(NetworkEventType type, String sourceNode, Map<String, Object> data);

    /** Persists and broadcasts a pre-built event. */
    CompletableFuture<NetworkEvent> publish(NetworkEvent event);

    /** @return the most recent events, newest first. */
    CompletableFuture<List<NetworkEvent>> recent(int limit);
}
