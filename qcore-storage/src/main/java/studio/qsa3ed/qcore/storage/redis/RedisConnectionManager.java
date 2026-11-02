package studio.qsa3ed.qcore.storage.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.common.exception.StorageException;
import studio.qsa3ed.qcore.storage.config.RedisSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the Redis client and its long-lived connections.
 *
 * <p>Redis is optional: when it is disabled or unreachable the manager starts in
 * degraded mode, {@link #isAvailable()} returns {@code false} and central keeps
 * serving HTTP and database traffic. Lettuce handles reconnects for established
 * connections; {@link #ping()} lets health checks observe the current state.</p>
 */
public final class RedisConnectionManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConnectionManager.class);

    private final RedisSettings settings;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final List<AutoCloseable> dedicated = new ArrayList<>();
    private final String degradedReason;
    private volatile boolean closed;

    private RedisConnectionManager(RedisSettings settings, RedisClient client,
                                   StatefulRedisConnection<String, String> connection, String degradedReason) {
        this.settings = settings;
        this.client = client;
        this.connection = connection;
        this.commands = connection == null ? null : connection.sync();
        this.degradedReason = degradedReason;
    }

    /** Opens the manager, degrading instead of failing when Redis is unreachable. */
    public static RedisConnectionManager open(RedisSettings settings) {
        if (!settings.enabled()) {
            LOG.warn("Redis transport is disabled; nodes will not receive push messages");
            return new RedisConnectionManager(settings, null, null, "disabled by configuration");
        }
        RedisClient client = null;
        try {
            RedisURI.Builder uriBuilder = RedisURI.builder(RedisURI.create(settings.uri()))
                    .withTimeout(Duration.ofMillis(settings.timeoutMs()));
            if (settings.password() != null && !settings.password().isBlank()) {
                uriBuilder.withPassword(settings.password());
            }
            RedisURI uri = uriBuilder.build();
            client = RedisClient.create(uri);
            client.setOptions(ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build());
            StatefulRedisConnection<String, String> connection = client.connect();
            connection.setTimeout(Duration.ofMillis(settings.timeoutMs()));
            LOG.info("Redis transport ready: {}", settings.safeDescription());
            return new RedisConnectionManager(settings, client, connection, null);
        } catch (RuntimeException failure) {
            if (client != null) {
                client.shutdown();
            }
            LOG.error("Redis is unreachable at {}; continuing in degraded mode", settings.safeDescription(), failure);
            return new RedisConnectionManager(settings, null, null,
                    "unreachable: " + failure.getMessage());
        }
    }

    /** @return {@code true} when a live Redis connection exists. */
    public boolean isAvailable() {
        return connection != null && !closed;
    }

    /** @return why Redis is unavailable, or {@code null} when it is available. */
    public String degradedReason() {
        return isAvailable() ? null : degradedReason;
    }

    /** @return the shared command interface. */
    public RedisCommands<String, String> commands() {
        if (!isAvailable()) {
            throw new StorageException("Redis is not available: " + degradedReason);
        }
        return commands;
    }

    /** @return the settings in use. */
    public RedisSettings settings() {
        return settings;
    }

    /** @return the key prefix configured for this deployment. */
    public String key(String suffix) {
        return settings.keyPrefix() + suffix;
    }

    /** Opens a pub/sub connection owned by this manager. */
    public StatefulRedisPubSubConnection<String, String> openPubSubConnection() {
        if (!isAvailable()) {
            throw new StorageException("Redis is not available: " + degradedReason);
        }
        StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();
        register(pubSub);
        return pubSub;
    }

    /** Opens an additional dedicated connection, used for blocking stream reads. */
    public StatefulRedisConnection<String, String> openDedicatedConnection() {
        if (!isAvailable()) {
            throw new StorageException("Redis is not available: " + degradedReason);
        }
        StatefulRedisConnection<String, String> dedicatedConnection = client.connect();
        dedicatedConnection.setTimeout(Duration.ofMillis(settings.timeoutMs()));
        register(dedicatedConnection);
        return dedicatedConnection;
    }

    private void register(AutoCloseable resource) {
        synchronized (dedicated) {
            if (closed) {
                closeQuietly(resource);
                throw new StorageException("Redis connection manager is closed");
            }
            dedicated.add(resource);
        }
    }

    /** @return {@code true} when Redis answers a PING. */
    public boolean ping() {
        if (!isAvailable()) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(commands.ping());
        } catch (RuntimeException failure) {
            LOG.debug("Redis ping failed", failure);
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (dedicated) {
            dedicated.forEach(RedisConnectionManager::closeQuietly);
            dedicated.clear();
        }
        closeQuietly(connection);
        if (client != null) {
            client.shutdown();
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception failure) {
            LOG.debug("Ignoring error while closing a Redis resource", failure);
        }
    }
}
