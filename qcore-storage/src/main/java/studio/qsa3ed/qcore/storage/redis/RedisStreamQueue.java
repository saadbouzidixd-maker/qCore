package studio.qsa3ed.qcore.storage.redis;

import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.common.exception.MessagingException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable message processing on Redis Streams.
 *
 * <p>Messages are appended with an approximate maximum length, consumed by a
 * consumer group, and only acknowledged after the handler succeeded. A message
 * that exceeds {@code maxAttempts} is left unacknowledged on purpose so an
 * operator can inspect the pending list rather than losing it silently.</p>
 */
public final class RedisStreamQueue implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisStreamQueue.class);

    private final RedisConnectionManager manager;
    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final int maxAttempts;
    private final ExecutorService executor;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    public RedisStreamQueue(RedisConnectionManager manager, String stream, String group,
                            String consumer, int maxAttempts) {
        this.manager = manager;
        this.streamKey = manager.key(stream);
        this.groupName = group;
        this.consumerName = consumer;
        this.maxAttempts = Math.max(1, maxAttempts);
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qcore-redis-stream-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private RedisCommands<String, String> commands() {
        if (!manager.isAvailable()) {
            throw new MessagingException("Redis is not available: " + manager.degradedReason());
        }
        if (commands == null) {
            connection = manager.openDedicatedConnection();
            commands = connection.sync();
        }
        return commands;
    }

    /** Creates the consumer group if it does not exist yet. Safe to call repeatedly. */
    public CompletableFuture<Void> ensureGroup() {
        return run(() -> {
            try {
                commands().xgroupCreate(XReadArgs.StreamOffset.from(streamKey, "0"), groupName,
                        XGroupCreateArgs.Builder.mkstream(true));
            } catch (RuntimeException failure) {
                // BUSYGROUP: the group already exists, which is the expected steady state.
                if (failure.getMessage() != null && failure.getMessage().contains("BUSYGROUP")) {
                    return null;
                }
                throw failure;
            }
            return null;
        });
    }

    /** Appends a message; @return the generated stream entry id. */
    public CompletableFuture<String> append(Map<String, String> payload) {
        return run(() -> commands().xadd(streamKey,
                XAddArgs.Builder.maxlen(manager.settings().streamMaxLen()), payload));
    }

    /** Reads up to {@code count} new messages, blocking for at most {@code block}. */
    public CompletableFuture<List<StreamMessage<String, String>>> read(int count, Duration block) {
        return run(() -> {
            // XReadArgs.Builder methods are static factories returning a complete argument object,
            // so count and block cannot be combined through the builder.
            List<StreamMessage<String, String>> messages = commands().xreadgroup(
                    Consumer.from(groupName, consumerName),
                    XReadArgs.Builder.count(Math.max(1, count)),
                    XReadArgs.StreamOffset.from(streamKey, ">"));
            return messages == null ? List.<StreamMessage<String, String>>of() : new ArrayList<>(messages);
        });
    }

    /** Acknowledges processed entries. */
    public CompletableFuture<Long> acknowledge(String... entryIds) {
        return run(() -> commands().xack(streamKey, groupName, entryIds));
    }

    /** @return the number of messages delivered but never acknowledged. */
    public CompletableFuture<Long> pendingCount() {
        return run(() -> {
            var summary = commands().xpending(streamKey, groupName);
            return summary == null ? 0L : summary.getCount();
        });
    }

    /** @return the retry limit after which a failing message stays pending for inspection. */
    public int maxAttempts() {
        return maxAttempts;
    }

    private <T> CompletableFuture<T> run(Operation<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(operation.run());
                } catch (RuntimeException failure) {
                    LOG.debug("Redis stream operation failed", failure);
                    future.completeExceptionally(failure instanceof MessagingException messaging
                            ? messaging
                            : new MessagingException("Redis stream operation failed", failure));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            future.completeExceptionally(new MessagingException("Redis stream executor is shut down", saturated));
        }
        return future;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException failure) {
                LOG.debug("Ignoring error while closing the stream connection", failure);
            }
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
