package studio.qsa3ed.qcore.tests.central;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.central.network.NetworkStateManager;
import studio.qsa3ed.qcore.central.routing.RoutingCoordinator;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.CandidateEvaluation;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.tests.support.InMemoryServerRegistry;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingCoordinatorTest {

    private InMemoryServerRegistry registry;
    private RoutingCoordinator routing;

    private static final EventService NO_EVENTS = new EventService() {
        @Override
        public CompletableFuture<NetworkEvent> publish(NetworkEventType type, String sourceNode,
                                                       Map<String, Object> data) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<NetworkEvent> publish(NetworkEvent event) {
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public CompletableFuture<List<NetworkEvent>> recent(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
    };

    @BeforeEach
    void setUp() {
        registry = new InMemoryServerRegistry();
        NetworkStateManager state = new NetworkStateManager(registry, new Counters(), Clock.systemUTC());
        routing = new RoutingCoordinator(registry, state,
                new CentralConfiguration.Routing(15.0, 0.95, false, Set.of("maintenance"), 100),
                NO_EVENTS, Clock.systemUTC());
    }

    private static NetworkServer server(String id, String group, int weight, double tps,
                                        int online, int max, NodeStatus status) {
        return new NetworkServer(id, id, NodeType.SERVER, "127.0.0.1", 25565, id + ":25565",
                status, new ServerMetrics(tps, 5.0, online, max, 512, 2048, "Paper 1.21.11"),
                System.currentTimeMillis(), System.currentTimeMillis(), group, weight, Set.of(group), true);
    }

    private static RoutingRequest request(String group, int partySize, String currentServer) {
        return new RoutingRequest(UUID.randomUUID(), group, Set.of(), partySize, currentServer, false);
    }

    @Test
    @DisplayName("the highest scoring healthy server is selected")
    void selectsBestServer() {
        registry.add(server("lobby-a", "lobby", 10, 20.0, 10, 100, NodeStatus.ONLINE))
                .add(server("lobby-b", "lobby", 1, 20.0, 10, 100, NodeStatus.ONLINE));

        RoutingDecision decision = routing.evaluate(request("lobby", 1, "")).join();

        assertTrue(decision.routed());
        assertEquals("lobby-a", decision.targetServerId());
        assertEquals(2, decision.candidates().size());
    }

    @Test
    @DisplayName("a server below the TPS floor is rejected with a reason")
    void rejectsLowTps() {
        registry.add(server("slow", "lobby", 100, 8.0, 10, 100, NodeStatus.ONLINE));

        RoutingDecision decision = routing.evaluate(request("lobby", 1, "")).join();

        assertFalse(decision.routed());
        CandidateEvaluation evaluation = decision.candidates().get(0);
        assertFalse(evaluation.eligible());
        assertTrue(evaluation.reason().contains("tps"));
    }

    @Test
    @DisplayName("a server without free slots for the whole party is rejected")
    void rejectsFullForParty() {
        registry.add(server("tight", "lobby", 10, 20.0, 99, 100, NodeStatus.ONLINE));

        RoutingDecision decision = routing.evaluate(request("lobby", 5, "")).join();

        assertFalse(decision.routed());
        assertTrue(decision.candidates().get(0).reason().contains("free slots"));
    }

    @Test
    @DisplayName("group mismatch and excluded tags disqualify candidates")
    void rejectsGroupAndTags() {
        registry.add(server("survival-1", "survival", 10, 20.0, 1, 100, NodeStatus.ONLINE));
        NetworkServer maintenance = new NetworkServer("lobby-x", "lobby-x", NodeType.SERVER, "127.0.0.1",
                25565, "lobby-x:25565", NodeStatus.ONLINE,
                new ServerMetrics(20.0, 1.0, 0, 100, 1, 1, "Paper"), System.currentTimeMillis(),
                System.currentTimeMillis(), "lobby", 1, Set.of("maintenance"), true);
        registry.add(maintenance);

        RoutingDecision decision = routing.evaluate(request("lobby", 1, "")).join();

        assertFalse(decision.routed());
        assertTrue(decision.candidates().stream().anyMatch(c -> c.reason().contains("excluded")));
        assertTrue(decision.candidates().stream().anyMatch(c -> c.reason().contains("group mismatch")));
    }

    @Test
    @DisplayName("the server the player is already on is never chosen")
    void neverRoutesToCurrentServer() {
        registry.add(server("lobby-a", "lobby", 10, 20.0, 5, 100, NodeStatus.ONLINE));

        RoutingDecision decision = routing.evaluate(request("lobby", 1, "lobby-a")).join();

        assertFalse(decision.routed());
        assertEquals("player is already connected here", decision.candidates().get(0).reason());
    }

    @Test
    @DisplayName("fallback to a degraded server requires both the request and the config to allow it")
    void degradedFallbackIsOptIn() {
        registry.add(server("degraded", "lobby", 10, 20.0, 5, 100, NodeStatus.DEGRADED));

        RoutingDecision withoutFallback = routing.evaluate(request("lobby", 1, "")).join();
        assertTrue(withoutFallback.routed(), "DEGRADED nodes remain routable for normal traffic");

        NetworkStateManager state = new NetworkStateManager(registry, new Counters(), Clock.systemUTC());
        RoutingCoordinator strict = new RoutingCoordinator(registry, state,
                new CentralConfiguration.Routing(15.0, 0.95, false, Set.of(), 100), NO_EVENTS,
                Clock.systemUTC());
        RoutingRequest overloaded = new RoutingRequest(UUID.randomUUID(), "lobby", Set.of(), 1, "", true);
        registry.add(server("degraded", "lobby", 10, 20.0, 5, 100, NodeStatus.OVERLOADED));

        RoutingDecision decision = strict.evaluate(overloaded).join();

        assertFalse(decision.routed());
        assertNotNull(decision.reason());
    }

    @Test
    @DisplayName("an empty network produces an explicit rejection")
    void emptyNetwork() {
        RoutingDecision decision = routing.evaluate(request("", 1, "")).join();

        assertFalse(decision.routed());
        assertEquals("no eligible server", decision.reason());
    }
}
