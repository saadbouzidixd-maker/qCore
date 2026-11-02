package studio.qsa3ed.qcore.common.model;

import java.util.List;

/**
 * The outcome of a routing evaluation.
 *
 * <p>A decision is auditable: it carries the reason, every candidate that was
 * considered and when it was made.</p>
 *
 * @param routed         whether a destination was found
 * @param targetServerId selected server, {@code null} when {@code routed} is {@code false}
 * @param reason         explanation of the decision
 * @param candidates     per-server evaluations
 * @param evaluatedAt    epoch millis of the evaluation
 */
public record RoutingDecision(
        boolean routed,
        String targetServerId,
        String reason,
        List<CandidateEvaluation> candidates,
        long evaluatedAt) {

    public RoutingDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (routed && (targetServerId == null || targetServerId.isBlank())) {
            throw new IllegalArgumentException("a routed decision requires a target server");
        }
    }

    public static RoutingDecision routed(String serverId, String reason,
                                         List<CandidateEvaluation> candidates, long now) {
        return new RoutingDecision(true, serverId, reason, candidates, now);
    }

    public static RoutingDecision rejected(String reason, List<CandidateEvaluation> candidates, long now) {
        return new RoutingDecision(false, null, reason, candidates, now);
    }
}
