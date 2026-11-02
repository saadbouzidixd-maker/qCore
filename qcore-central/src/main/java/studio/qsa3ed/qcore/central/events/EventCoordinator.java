package studio.qsa3ed.qcore.central.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.NetworkMessagingService;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.messaging.MessageType;
import studio.qsa3ed.qcore.common.messaging.NetworkMessage;
import studio.qsa3ed.qcore.storage.repository.EventRepository;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Persists network events and broadcasts them to subscribed nodes.
 *
 * <p>Persistence is the guarantee; publication is best effort. When PostgreSQL
 * or Redis is unavailable the event still lands in a bounded in-memory ring so
 * operators can inspect recent activity, and the failure is counted.</p>
 */
public final class EventCoordinator implements EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventCoordinator.class);
    private static final int RING_CAPACITY = 500;

    private final EventRepository repository;
    private final NetworkMessagingService messaging;
    private final Counters metrics;
    private final Deque<NetworkEvent> recent = new ConcurrentLinkedDeque<>();
    private final String nodeId;

    public EventCoordinator(EventRepository repository, NetworkMessagingService messaging,
                            Counters metrics, String nodeId) {
        this.repository = repository;
        this.messaging = messaging;
        this.metrics = metrics;
        this.nodeId = nodeId;
    }

    @Override
    public CompletableFuture<NetworkEvent> publish(NetworkEventType type, String sourceNode,
                                                  Map<String, Object> data) {
        return publish(NetworkEvent.create(type, sourceNode == null ? nodeId : sourceNode, data));
    }

    @Override
    public CompletableFuture<NetworkEvent> publish(NetworkEvent event) {
        recent.addFirst(event);
        while (recent.size() > RING_CAPACITY) {
            recent.pollLast();
        }
        metrics.increment("events.published");
        repository.append(event).whenComplete((ignored, failure) -> {
            if (failure != null) {
                metrics.increment("events.persistence.failed");
                // Expected while PostgreSQL is degraded: warn once per event, keep the stack for DEBUG.
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                LOG.warn("Unable to persist event {} ({}); it remains in the in-memory ring",
                        event.eventId(), cause.getMessage());
                LOG.debug("Event persistence failure", cause);
            }
        });
        messaging.broadcast(NetworkMessage.broadcast(MessageType.NETWORK_EVENT, nodeId,
                Map.of("eventId", event.eventId(),
                        "type", event.type().name(),
                        "occurredAt", event.occurredAt(),
                        "data", event.data()))).exceptionally(failure -> {
            metrics.increment("events.broadcast.failed");
            LOG.debug("Unable to broadcast event {}", event.eventId(), failure);
            return null;
        });
        return CompletableFuture.completedFuture(event);
    }

    @Override
    public CompletableFuture<List<NetworkEvent>> recent(int limit) {
        int bounded = Math.max(1, Math.min(RING_CAPACITY, limit));
        return repository.recent(bounded).handle((events, failure) -> {
            if (failure != null) {
                LOG.warn("Unable to read events from PostgreSQL; serving the in-memory ring", failure);
                return recent.stream().limit(bounded).toList();
            }
            return events.isEmpty() ? new ArrayList<>(recent.stream().limit(bounded).toList()) : events;
        });
    }
}
