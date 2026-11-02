package studio.qsa3ed.qcore.storage.repository;

import studio.qsa3ed.qcore.common.events.NetworkEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Persistence contract for network events and audit trails. */
public interface EventRepository {

    /** Appends an event; duplicate event ids are ignored, which makes publishing idempotent. */
    CompletableFuture<Void> append(NetworkEvent event);

    /** @return the most recent events, newest first. */
    CompletableFuture<List<NetworkEvent>> recent(int limit);

    /** Records an automation action for later audit. */
    CompletableFuture<Void> appendAutomationAudit(String ruleId, String nodeId, String action, String details, long occurredAt);

    /** Records a routing evaluation for later audit. */
    CompletableFuture<Void> appendRoutingAudit(java.util.UUID playerId, String target, boolean routed,
                                               String reason, String detailsJson, long occurredAt);
}
