package studio.qsa3ed.qcore.central.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NodeStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects nodes that stopped sending heartbeats.
 *
 * <p>Expiry is based on the last heartbeat timestamp recorded in the registry,
 * so a restart of central immediately marks every silent node offline. The
 * window is configurable and validated at startup.</p>
 */
public final class HeartbeatMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final ServerRegistry registry;
    private final Duration timeout;
    private final Clock clock;

    public HeartbeatMonitor(ServerRegistry registry, Duration timeout, Clock clock) {
        this.registry = registry;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(30) : timeout;
        this.clock = clock;
    }

    /** Runs one sweep. @return the ids of nodes newly marked offline. */
    public List<String> sweep() {
        long threshold = clock.millis() - timeout.toMillis();
        List<String> expired = new ArrayList<>();
        for (NetworkServer server : registry.cachedAll()) {
            if (server.status() == NodeStatus.OFFLINE) {
                continue;
            }
            if (server.lastHeartbeatAt() > 0 && server.lastHeartbeatAt() < threshold) {
                expired.add(server.id());
            }
        }
        for (String serverId : expired) {
            registry.markOffline(serverId, "no heartbeat within " + timeout.toSeconds() + "s").exceptionally(failure -> {
                LOG.warn("Unable to mark {} offline", serverId, failure);
                return false;
            });
        }
        if (!expired.isEmpty()) {
            LOG.warn("{} node(s) lost their heartbeat: {}", expired.size(), expired);
        }
        return expired;
    }

    /** @return the configured heartbeat timeout. */
    public Duration timeout() {
        return timeout;
    }
}
