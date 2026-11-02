package studio.qsa3ed.qcore.common.model;

import java.util.List;
import java.util.Objects;

/**
 * A consistent point-in-time view of the whole network.
 *
 * @param generatedAt      epoch millis when the snapshot was produced
 * @param totalPlayers     sum of online players across all servers
 * @param totalCapacity    sum of configured capacity across all servers
 * @param servers          every registered node
 * @param onlineServers    number of nodes that are online, degraded or overloaded
 * @param degradedServers  number of nodes reporting a degraded status
 */
public record NetworkSnapshot(
        long generatedAt,
        int totalPlayers,
        int totalCapacity,
        List<NetworkServer> servers,
        int onlineServers,
        int degradedServers) {

    public NetworkSnapshot {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /** Builds a snapshot from a server list. */
    public static NetworkSnapshot of(List<NetworkServer> servers, long now) {
        Objects.requireNonNull(servers, "servers");
        int players = 0;
        int capacity = 0;
        int online = 0;
        int degraded = 0;
        for (NetworkServer server : servers) {
            players += server.metrics().onlinePlayers();
            capacity += Math.max(0, server.metrics().maxPlayers());
            if (server.isOnline()) {
                online++;
            }
            if (server.status().isDegraded()) {
                degraded++;
            }
        }
        return new NetworkSnapshot(now, players, capacity, servers, online, degraded);
    }

    /** @return {@code true} when at least one routable server exists. */
    public boolean hasRoutableServer() {
        return servers.stream().anyMatch(NetworkServer::hasFreeCapacity);
    }

    /** @return the network-wide capacity utilisation in {@code [0, 1]}. */
    public double utilisation() {
        return totalCapacity <= 0 ? 0.0 : Math.min(1.0, (double) totalPlayers / (double) totalCapacity);
    }
}
