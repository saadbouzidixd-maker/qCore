package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;

import java.util.concurrent.CompletableFuture;

/** Evaluates where a player should be placed. */
public interface RoutingService {

    /**
     * Evaluates a routing request against recent server state.
     *
     * <p>The returned decision always carries an explanation and the
     * per-candidate evaluations, whether or not a destination was found.</p>
     *
     * @param request the routing request
     * @return a future completing with the decision, never {@code null}
     */
    CompletableFuture<RoutingDecision> evaluate(RoutingRequest request);
}
