package studio.qsa3ed.qcore.velocity.registration;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import studio.qsa3ed.qcore.common.client.CentralClient;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.velocity.config.ProxyConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registers the proxy with central and keeps its heartbeat alive.
 *
 * <p>The proxy reports its own player count so operators can see proxy load next
 * to server load. TPS is reported as unknown because a proxy has no game loop.</p>
 */
public final class ProxyRegistrar implements AutoCloseable {

    private final ProxyServer proxy;
    private final CentralClient client;
    private final ProxyConfig config;
    private final Logger logger;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qcore-proxy-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong heartbeats = new AtomicLong();

    private volatile boolean registered;
    private volatile long lastSuccessAt;
    private volatile String lastError = "";

    public ProxyRegistrar(ProxyServer proxy, CentralClient client, ProxyConfig config, Logger logger) {
        this.proxy = proxy;
        this.client = client;
        this.config = config;
        this.logger = logger;
    }

    /** Starts registration and periodic heartbeats. */
    public void start() {
        long period = Math.max(2, config.heartbeatInterval().toSeconds());
        scheduler.scheduleAtFixedRate(this::beat, 0, period, TimeUnit.SECONDS);
    }

    private void beat() {
        try {
            if (!registered) {
                client.register(describe()).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        registered = true;
                        lastSuccessAt = System.currentTimeMillis();
                        logger.info("Proxy registered with qCore central as {}", config.proxyId());
                    } else {
                        fail("registration", failure);
                    }
                });
                return;
            }
            client.heartbeat(config.proxyId(), metrics()).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    heartbeats.incrementAndGet();
                    lastSuccessAt = System.currentTimeMillis();
                } else {
                    registered = false;
                    fail("heartbeat", failure);
                }
            });
        } catch (RuntimeException failure) {
            fail("heartbeat scheduling", failure);
        }
    }

    private void fail(String operation, Throwable failure) {
        long count = failures.incrementAndGet();
        lastError = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        if (count == 1 || count % 10 == 0) {
            logger.warn("qCore proxy {} failed ({} time(s)): {}", operation, count, lastError);
        }
    }

    /** @return the node description published to central. */
    public NetworkServer describe() {
        long now = System.currentTimeMillis();
        return new NetworkServer(
                config.proxyId(),
                config.proxyName(),
                NodeType.PROXY,
                config.host(),
                config.port(),
                null,
                NodeStatus.ONLINE,
                metrics(),
                now,
                now,
                "proxy",
                config.weight(),
                java.util.Set.of("proxy"),
                true);
    }

    private ServerMetrics metrics() {
        int maxPlayers = proxy.getConfiguration().getShowMaxPlayers();
        return new ServerMetrics(-1.0, -1.0, proxy.getPlayerCount(), maxPlayers, 0, 0,
                "Velocity " + proxy.getVersion().getVersion());
    }

    public boolean isRegistered() {
        return registered;
    }

    public long heartbeats() {
        return heartbeats.get();
    }

    public long failures() {
        return failures.get();
    }

    public long lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        registered = false;
    }
}
