package studio.qsa3ed.qcore.tests.integration;

import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.storage.config.DatabaseSettings;
import studio.qsa3ed.qcore.storage.config.RedisSettings;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.database.MigrationRunner;
import studio.qsa3ed.qcore.storage.redis.RedisConnectionManager;
import studio.qsa3ed.qcore.storage.redis.RedisMessageBus;
import studio.qsa3ed.qcore.storage.redis.RedisStreamQueue;
import studio.qsa3ed.qcore.storage.repository.jdbc.JdbcEventRepository;
import studio.qsa3ed.qcore.storage.repository.jdbc.JdbcServerRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against real PostgreSQL and Redis instances.
 *
 * <p>They are tagged {@code integration} and therefore excluded from the default
 * build. Run them against disposable infrastructure:
 *
 * <pre>
 *   QCORE_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/qcore_it \
 *   QCORE_TEST_DB_USER=qcore QCORE_TEST_DB_PASSWORD=secret \
 *   QCORE_TEST_REDIS_URI=redis://127.0.0.1:6379/0 \
 *   mvn -pl qcore-tests test -Dgroups=integration -DexcludedGroups=
 * </pre>
 *
 * <p>When the environment variables are absent the tests are skipped, never
 * reported as passing.</p>
 */
@Tag("integration")
class StorageIntegrationTest {

    private static final String DB_URL = System.getenv("QCORE_TEST_DB_URL");
    private static final String DB_USER = environment("QCORE_TEST_DB_USER", "qcore");
    private static final String DB_PASSWORD = environment("QCORE_TEST_DB_PASSWORD", "");
    private static final String REDIS_URI = System.getenv("QCORE_TEST_REDIS_URI");

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    @DisplayName("PostgreSQL: migrations, idempotent server upsert and idempotent events")
    void postgresRoundTrip() {
        Assumptions.assumeTrue(DB_URL != null && !DB_URL.isBlank(),
                "QCORE_TEST_DB_URL is not set; skipping PostgreSQL integration test");

        Database database = Database.open(new DatabaseSettings(true, DB_URL, DB_USER, DB_PASSWORD, 2, 5_000L, 32));
        try {
            new MigrationRunner(database).migrate();
            new MigrationRunner(database).migrate(); // must be a no-op, not an error

            JdbcServerRepository servers = new JdbcServerRepository(database);
            long now = System.currentTimeMillis();
            NetworkServer server = new NetworkServer("it-lobby", "it-lobby", NodeType.SERVER, "127.0.0.1",
                    25565, "it-lobby:25565", NodeStatus.ONLINE,
                    new ServerMetrics(19.9, 2.0, 7, 100, 256, 2048, "Paper 1.21.11"),
                    now, now, "lobby", 5, Set.of("lobby", "test"), true);

            servers.upsert(server).join();
            servers.upsert(server).join();

            Optional<NetworkServer> stored = servers.findById("it-lobby").join();
            assertTrue(stored.isPresent(), "the server must be readable after upsert");
            assertEquals(7, stored.orElseThrow().metrics().onlinePlayers());
            assertEquals(Set.of("lobby", "test"), stored.orElseThrow().tags(),
                    "tags must survive the comma-encoded round trip");
            assertEquals(1, servers.findAll().join().stream()
                    .filter(candidate -> candidate.id().equals("it-lobby")).count(),
                    "upsert must not duplicate rows");

            JdbcEventRepository events = new JdbcEventRepository(database);
            NetworkEvent event = NetworkEvent.create(NetworkEventType.SERVER_REGISTERED, "it", Map.of("k", "v"));
            events.append(event).join();
            events.append(event).join(); // duplicate event id must not create a second row
            List<NetworkEvent> recent = events.recent(200).join();
            assertEquals(1, recent.stream().filter(candidate -> candidate.eventId().equals(event.eventId()))
                    .count(), "event persistence must be idempotent");
        } finally {
            database.close();
        }
    }

    @Test
    @DisplayName("Redis: Pub/Sub delivery and a durable stream round trip")
    void redisTransport() {
        Assumptions.assumeTrue(REDIS_URI != null && !REDIS_URI.isBlank(),
                "QCORE_TEST_REDIS_URI is not set; skipping Redis integration test");

        RedisConnectionManager manager = RedisConnectionManager.open(
                new RedisSettings(true, REDIS_URI, "", "qcore-it:", 2_000L, 1_000L));
        if (!manager.isAvailable()) {
            manager.close();
            Assumptions.abort("Redis is not reachable at " + REDIS_URI);
        }
        try (RedisMessageBus bus = new RedisMessageBus(manager)) {
            CountDownLatch latch = new CountDownLatch(1);
            RedisMessageBus.Subscription subscription = bus.subscribe("it-channel", payload -> {
                if ("hello".equals(payload)) {
                    latch.countDown();
                }
            });
            try {
                bus.publish("it-channel", "hello").join();
                assertTrue(latch.await(10, TimeUnit.SECONDS), "the subscriber must receive the published message");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                subscription.close();
            }
        }

        String id = "qcore-it-" + System.currentTimeMillis();
        try (RedisStreamQueue queue = new RedisStreamQueue(manager, "it-stream:" + id,
                "it-group", "it-consumer", 3)) {
            queue.ensureGroup().join();
            String entryId = queue.append(Map.of("id", id, "body", "payload")).join();
            assertNotNull(entryId, "append must return a stream entry id");

            List<StreamMessage<String, String>> messages = List.of();
            for (int attempt = 0; attempt < 20 && messages.isEmpty(); attempt++) {
                messages = queue.read(10, Duration.ofMillis(100)).join();
                if (messages.isEmpty()) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            assertTrue(messages.stream().anyMatch(message -> id.equals(message.getBody().get("id"))),
                    "the appended message must be readable from the consumer group");
            queue.acknowledge(messages.stream().map(StreamMessage::getId).toArray(String[]::new)).join();
            assertEquals(0L, queue.pendingCount().join(), "acknowledged messages must leave the pending list");
        } finally {
            manager.close();
        }
    }
}
