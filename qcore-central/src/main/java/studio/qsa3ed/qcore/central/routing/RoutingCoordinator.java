package studio.qsa3ed.qcore.central.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.MetricsService;
import studio.qsa3ed.qcore.api.RoutingService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.CandidateEvaluation;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Scores and selects a destination server.
 *
 * <p>Signals, in order: online status, refusing transfers, health rules,
 * remaining capacity for the whole party, TPS floor, utilisation ceiling,
 * requested group, required tags and configured weight. Every candidate gets a
 * documented reason so an operator can explain any routing outcome.</p>
 *
 * <p>No transfer is ever attempted here; this service only decides. Execution
 * belongs to the proxy.</p>
 */
public final class RoutingCoordinator implements RoutingService {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingCoordinator.class);

    private final ServerRegistry registry;
    private final MetricsService metrics;
    private final CentralConfiguration.Routing settings;
    private final EventService events;
    private final Clock clock;

    public RoutingCoordinator(ServerRegistry registry, MetricsService metrics,
                              CentralConfiguration.Routing settings, EventService events, Clock clock) {
        this.registry = registry;
        this.metrics = metrics;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<RoutingDecision> evaluate(RoutingRequest request) {
        NetworkSnapshot snapshot = metrics.currentSnapshot();
        List<CandidateEvaluation> evaluations = new ArrayList<>();
        List<NetworkServer> candidates = registry.cachedByType(NodeType.SERVER).stream()
                .limit(settings.maxCandidates())
                .toList();

        List<ScoredServer> eligible = new ArrayList<>();
        for (NetworkServer server : candidates) {
            CandidateEvaluation evaluation = evaluateCandidate(server, request, snapshot, false);
            evaluations.add(evaluation);
            if (evaluation.eligible()) {
                eligible.add(new ScoredServer(server, evaluation.score()));
            }
        }

        if (eligible.isEmpty() && request.allowFallback() && settings.allowDegradedFallback()) {
            for (NetworkServer server : candidates) {
                if (server.status().isDegraded() && !server.id().equals(request.currentServerId())) {
                    CandidateEvaluation fallback = evaluateCandidate(server, request, snapshot, true);
                    if (fallback.eligible()) {
                        evaluations.add(fallback);
                        eligible.add(new ScoredServer(server, fallback.score() - 1_000.0));
                    }
                }
            }
        }

        long now = clock.millis();
        RoutingDecision decision;
        if (eligible.isEmpty()) {
            decision = RoutingDecision.rejected("no eligible server", evaluations, now);
        } else {
            Optional<ScoredServer> best = eligible.stream().max(Comparator.comparingDouble(ScoredServer::score));
            NetworkServer target = best.orElseThrow().server();
            decision = RoutingDecision.routed(target.id(),
                    "selected " + target.name() + " (score "
                            + String.format(Locale.ROOT, "%.2f", best.orElseThrow().score()) + ")",
                    evaluations, now);
        }

        metrics.increment(decision.routed() ? "routing.routed" : "routing.rejected");
        if (!decision.routed()) {
            LOG.debug("Routing rejected for {}: {}", request.playerId(), decision.reason());
        }
        events.publish(decision.routed() ? NetworkEventType.ROUTING_DECISION : NetworkEventType.ROUTING_FAILED,
                "central", Map.of(
                        "playerId", request.playerId() == null ? "" : request.playerId().toString(),
                        "group", request.group(),
                        "target", decision.targetServerId() == null ? "" : decision.targetServerId(),
                        "reason", decision.reason()));
        return CompletableFuture.completedFuture(decision);
    }

    /** Evaluates one server and always returns a reason. */
    public CandidateEvaluation evaluateCandidate(NetworkServer server, RoutingRequest request,
                                                 NetworkSnapshot snapshot, boolean fallback) {
        if (server.status() == studio.qsa3ed.qcore.common.model.NodeStatus.OFFLINE) {
            return CandidateEvaluation.rejected(server.id(), "offline");
        }
        if (!fallback && server.status() == studio.qsa3ed.qcore.common.model.NodeStatus.OVERLOADED) {
            return CandidateEvaluation.rejected(server.id(), "overloaded by automation");
        }
        if (!server.acceptingPlayers()) {
            return CandidateEvaluation.rejected(server.id(), "not accepting transfers");
        }
        if (server.id().equals(request.currentServerId())) {
            return CandidateEvaluation.rejected(server.id(), "player is already connected here");
        }
        if (settings.excludedTags().stream().anyMatch(server.tags()::contains)) {
            return CandidateEvaluation.rejected(server.id(), "server carries an excluded tag");
        }
        if (!request.requiredTags().isEmpty() && !server.tags().containsAll(request.requiredTags())) {
            return CandidateEvaluation.rejected(server.id(), "missing required tags " + request.requiredTags());
        }
        if (!request.group().isBlank() && !matchesGroup(server, request.group())) {
            return CandidateEvaluation.rejected(server.id(), "group mismatch (wanted " + request.group() + ")");
        }
        if (server.metrics().maxPlayers() > 0 && server.metrics().freeSlots() < request.effectivePartySize()) {
            return CandidateEvaluation.rejected(server.id(), "not enough free slots for "
                    + request.effectivePartySize() + " player(s)");
        }
        if (server.metrics().hasTps() && server.metrics().tps() < settings.minimumTps()) {
            return CandidateEvaluation.rejected(server.id(), "tps " + server.metrics().tps()
                    + " below floor " + settings.minimumTps());
        }
        if (server.metrics().capacityUtilisation() > settings.maxCapacityUtilisation()) {
            return CandidateEvaluation.rejected(server.id(), "capacity utilisation above "
                    + settings.maxCapacityUtilisation());
        }
        double score = score(server, snapshot);
        String reason = fallback ? "eligible fallback destination" : "eligible";
        return CandidateEvaluation.accepted(server.id(), score, reason);
    }

    /**
     * Computes the ranking score.
     *
     * <p>Higher is better: configured weight dominates, then free capacity, then
     * TPS headroom; utilisation and network-wide fullness subtract.</p>
     */
    public double score(NetworkServer server, NetworkSnapshot snapshot) {
        double weightComponent = server.weight() * 10.0;
        double capacityComponent = Math.min(50.0, server.metrics().freeSlots() == Integer.MAX_VALUE
                ? 50.0 : server.metrics().freeSlots() * 0.5);
        double tpsComponent = server.metrics().hasTps() ? Math.max(0.0, server.metrics().tps() - 10.0) * 3.0 : 5.0;
        double utilisationPenalty = server.metrics().capacityUtilisation() * 20.0;
        double networkPenalty = snapshot.utilisation() * 5.0;
        return weightComponent + capacityComponent + tpsComponent - utilisationPenalty - networkPenalty;
    }

    private static boolean matchesGroup(NetworkServer server, String group) {
        String normalized = group.toLowerCase(Locale.ROOT);
        return server.gameMode().toLowerCase(Locale.ROOT).equals(normalized)
                || server.tags().contains(normalized);
    }

    private record ScoredServer(NetworkServer server, double score) {
    }
}
