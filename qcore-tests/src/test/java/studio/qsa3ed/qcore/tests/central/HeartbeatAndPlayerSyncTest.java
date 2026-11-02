package studio.qsa3ed.qcore.tests.central;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.central.network.HeartbeatMonitor;
import studio.qsa3ed.qcore.central.players.PlayerDataCoordinator;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.PlayerProfile;
import studio.qsa3ed.qcore.common.model.PlayerSession;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.storage.repository.PlayerRepository;
import studio.qsa3ed.qcore.tests.support.InMemoryServerRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatAndPlayerSyncTest {

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

    private static NetworkServer node(String id, long lastHeartbeat) {
        return new NetworkServer(id, id, NodeType.SERVER, "127.0.0.1", 25565, id + ":25565",
                NodeStatus.ONLINE, ServerMetrics.unknown(), 1_000L, lastHeartbeat, "lobby", 1,
                java.util.Set.of(), true);
    }

    @Test
    @DisplayName("silent nodes expire, fresh nodes stay online")
    void heartbeatExpiry() {
        long now = 10_000_000L;
        InMemoryServerRegistry registry = new InMemoryServerRegistry()
                .add(node("stale", now - 60_000L))
                .add(node("fresh", now - 1_000L));
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry, Duration.ofSeconds(30),
                Clock.fixed(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC));

        List<String> expired = monitor.sweep();

        assertEquals(List.of("stale"), expired);
        assertEquals(NodeStatus.OFFLINE, registry.cached("stale").orElseThrow().status());
        assertEquals(NodeStatus.ONLINE, registry.cached("fresh").orElseThrow().status());
    }

    @Test
    @DisplayName("a sweep is idempotent for nodes that are already offline")
    void sweepIsIdempotent() {
        long now = 10_000_000L;
        InMemoryServerRegistry registry = new InMemoryServerRegistry().add(node("stale", now - 60_000L));
        HeartbeatMonitor monitor = new HeartbeatMonitor(registry, Duration.ofSeconds(30),
                Clock.fixed(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC));

        assertEquals(List.of("stale"), monitor.sweep());
        assertTrue(monitor.sweep().isEmpty());
    }

    @Test
    @DisplayName("a heartbeat revives an offline node")
    void heartbeatRevivesNode() {
        InMemoryServerRegistry registry = new InMemoryServerRegistry()
                .add(node("lobby", 1_000L).withStatus(NodeStatus.OFFLINE, 1_000L));

        registry.recordHeartbeat("lobby", new ServerMetrics(20.0, 1.0, 1, 100, 10, 100, "Paper")).join();

        assertEquals(1, registry.cached("lobby").orElseThrow().metrics().onlinePlayers());
    }

    @Test
    @DisplayName("player profiles are created once and sessions bump the data version")
    void playerSync() {
        InMemoryPlayerRepository repository = new InMemoryPlayerRepository();
        PlayerDataCoordinator coordinator = new PlayerDataCoordinator(repository, NO_EVENTS, new Counters(),
                Clock.systemUTC());
        UUID uuid = UUID.randomUUID();

        PlayerProfile created = coordinator.ensureProfile(uuid, "Steve").join();
        assertEquals(1L, created.dataVersion());

        PlayerProfile again = coordinator.ensureProfile(uuid, "Steve").join();
        assertEquals(1L, again.dataVersion(), "an unchanged profile must not bump the version");

        coordinator.updateSession(PlayerSession.open(uuid, "Steve", "lobby-1", System.currentTimeMillis())).join();

        PlayerProfile synced = coordinator.profile(uuid).join().orElseThrow();
        assertEquals("lobby-1", synced.lastServerId());
        assertEquals(2L, synced.dataVersion());
        assertEquals(1, repository.sessions.size());
    }

    @Test
    @DisplayName("disconnecting closes the session without losing the profile")
    void disconnectKeepsProfile() {
        InMemoryPlayerRepository repository = new InMemoryPlayerRepository();
        PlayerDataCoordinator coordinator = new PlayerDataCoordinator(repository, NO_EVENTS, new Counters(),
                Clock.systemUTC());
        UUID uuid = UUID.randomUUID();
        coordinator.ensureProfile(uuid, "Alex").join();
        coordinator.updateSession(PlayerSession.open(uuid, "Alex", "survival-1", 5_000L)).join();

        coordinator.markDisconnected(uuid, "survival-1", 6_000L).join();

        assertFalse(repository.sessions.get(uuid).connected());
        assertTrue(coordinator.profile(uuid).join().isPresent());
    }

    /** A repository double that mirrors the version-aware write behaviour. */
    private static final class InMemoryPlayerRepository implements PlayerRepository {
        private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
        private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<PlayerProfile> upsert(PlayerProfile profile) {
            profiles.merge(profile.uuid(), profile,
                    (existing, incoming) -> incoming.dataVersion() >= existing.dataVersion() ? incoming : existing);
            return CompletableFuture.completedFuture(profiles.get(profile.uuid()));
        }

        @Override
        public CompletableFuture<Optional<PlayerProfile>> findByUuid(UUID uuid) {
            return CompletableFuture.completedFuture(Optional.ofNullable(profiles.get(uuid)));
        }

        @Override
        public CompletableFuture<Void> saveSession(PlayerSession session) {
            sessions.put(session.uuid(), session);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<PlayerProfile>> findByServer(String serverId) {
            return CompletableFuture.completedFuture(profiles.values().stream()
                    .filter(profile -> serverId.equals(profile.lastServerId()))
                    .toList());
        }
    }
}
