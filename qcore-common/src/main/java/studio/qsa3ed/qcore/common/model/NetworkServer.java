package studio.qsa3ed.qcore.common.model;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable view of a node registered with the network.
 *
 * <p>Model instances are immutable; state transitions return new instances
 * through the {@code with*} factory methods, which keeps the shared in-memory
 * registry and any reader of a snapshot consistent.</p>
 *
 * @param id              stable node id, unique across the network
 * @param name            human readable name, also used as the proxy server name
 * @param type            node role
 * @param host            reachable host or ip
 * @param port            port of the node's Minecraft listener or API
 * @param address         address players are transferred to, for example {@code lobby-1:25566}
 * @param status          current health status
 * @param metrics         most recent telemetry sample
 * @param registeredAt    epoch millis of the first registration
 * @param lastHeartbeatAt epoch millis of the most recent heartbeat
 * @param gameMode        configured game mode or group, may be empty
 * @param weight          routing weight, higher is preferred
 * @param tags            free-form tags used by routing constraints
 * @param acceptingPlayers whether the node currently accepts transfers
 */
public record NetworkServer(
        String id,
        String name,
        NodeType type,
        String host,
        int port,
        String address,
        NodeStatus status,
        ServerMetrics metrics,
        long registeredAt,
        long lastHeartbeatAt,
        String gameMode,
        int weight,
        Set<String> tags,
        boolean acceptingPlayers) {

    public NetworkServer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("invalid node id");
        }
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("invalid node name");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        status = status == null ? NodeStatus.UNKNOWN : status;
        metrics = metrics == null ? ServerMetrics.unknown() : metrics;
        host = host == null ? "" : host;
        gameMode = gameMode == null ? "" : gameMode;
        address = address == null || address.isBlank() ? host + ":" + port : address;
        weight = Math.max(0, weight);
        tags = tags == null || tags.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(tags));
        if (registeredAt <= 0) {
            registeredAt = System.currentTimeMillis();
        }
    }

    /** @return a copy with new telemetry and an explicit status. */
    public NetworkServer withMetrics(ServerMetrics newMetrics, NodeStatus newStatus, long heartbeatAt) {
        return new NetworkServer(id, name, type, host, port, address, newStatus, newMetrics,
                registeredAt, heartbeatAt, gameMode, weight, tags, acceptingPlayers);
    }

    /** @return a copy with a new status. */
    public NetworkServer withStatus(NodeStatus newStatus, long heartbeatAt) {
        return withMetrics(metrics, newStatus, heartbeatAt);
    }

    /** @return a copy with a new accept-transfers flag. */
    public NetworkServer withAcceptingPlayers(boolean accepting) {
        return new NetworkServer(id, name, type, host, port, address, status, metrics,
                registeredAt, lastHeartbeatAt, gameMode, weight, tags, accepting);
    }

    /** @return {@code true} when the node reports free capacity and accepts transfers. */
    public boolean hasFreeCapacity() {
        return acceptingPlayers && status.isRoutable() && metrics.freeSlots() > 0;
    }

    /** @return {@code true} when the node accepted at least one heartbeat. */
    public boolean isOnline() {
        return status == NodeStatus.ONLINE || status == NodeStatus.DEGRADED || status == NodeStatus.OVERLOADED;
    }

    @Override
    public String toString() {
        return "NetworkServer[" + id + " (" + name + ", " + type + ", " + status + ")]";
    }
}
