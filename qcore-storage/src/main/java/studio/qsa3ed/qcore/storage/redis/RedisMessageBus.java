package studio.qsa3ed.qcore.storage.redis;

import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.common.exception.MessagingException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Redis Pub/Sub transport for transient notifications.
 *
 * <p>Pub/Sub provides no delivery guarantee, so publishing reports failure to
 * the caller instead of pretending the message was delivered. Durable work
 * belongs on {@link RedisStreamQueue}.</p>
 */
public final class RedisMessageBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisMessageBus.class);

    private final RedisConnectionManager manager;
    private final ExecutorService executor;
    private final List<Subscription> subscriptions = new ArrayList<>();

    public RedisMessageBus(RedisConnectionManager manager) {
        this.manager = manager;
        this.executor = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2_000),
                new ThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return {@code true} when messages can be published. */
    public boolean isAvailable() {
        return manager.isAvailable();
    }

    /** Publishes a message, completing exceptionally when Redis rejects it. */
    public CompletableFuture<Long> publish(String channel, String payload) {
        if (!manager.isAvailable()) {
            return CompletableFuture.failedFuture(
                    new MessagingException("Redis is not available: " + manager.degradedReason()));
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(manager.commands().publish(manager.key(channel), payload));
                } catch (RuntimeException failure) {
                    future.completeExceptionally(new MessagingException("Unable to publish to " + channel, failure));
                }
            });
        } catch (RejectedExecutionException saturated) {
            future.completeExceptionally(new MessagingException("Redis publish queue is saturated", saturated));
        }
        return future;
    }

    /**
     * Subscribes to a channel.
     *
     * @return a handle that unsubscribes when closed
     */
    public Subscription subscribe(String channel, Consumer<String> listener) {
        if (!manager.isAvailable()) {
            throw new MessagingException("Redis is not available: " + manager.degradedReason());
        }
        String key = manager.key(channel);
        StatefulRedisPubSubConnection<String, String> connection = manager.openPubSubConnection();
        connection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String subscribedChannel, String message) {
                try {
                    listener.accept(message);
                } catch (RuntimeException failure) {
                    // A misbehaving listener must not kill the shared subscriber connection.
                    LOG.warn("Subscriber for {} failed to handle a message", subscribedChannel, failure);
                }
            }
        });
        connection.async().subscribe(key);
        Subscription subscription = new Subscription(connection, key);
        synchronized (subscriptions) {
            subscriptions.add(subscription);
        }
        return subscription;
    }

    @Override
    public void close() {
        synchronized (subscriptions) {
            subscriptions.forEach(Subscription::close);
            subscriptions.clear();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** A live subscription that can be closed to stop delivery. */
    public final class Subscription implements AutoCloseable {
        private final StatefulRedisPubSubConnection<String, String> connection;
        private final String channel;

        private Subscription(StatefulRedisPubSubConnection<String, String> connection, String channel) {
            this.connection = connection;
            this.channel = channel;
        }

        @Override
        public void close() {
            try {
                connection.async().unsubscribe(channel);
            } finally {
                connection.close();
            }
        }
    }

    private static final class ThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "qcore-redis-pub-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
