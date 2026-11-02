package studio.qsa3ed.qcore.central.rest;

import java.util.Map;

/**
 * Observable state of the central node.
 *
 * @param network            configured network name
 * @param version            qCore version
 * @param uptimeMillis       time since start
 * @param storageAvailable   whether PostgreSQL answered its last probe
 * @param redisAvailable     whether Redis answered its last probe
 * @param messagingAvailable whether messages can currently be published
 * @param registeredServers  number of registered gameplay servers
 * @param onlineServers      number of gameplay servers that are online
 * @param onlinePlayers      players reported across the network
 * @param counters           operational counters
 */
public record HealthReport(
        String network,
        String version,
        long uptimeMillis,
        boolean storageAvailable,
        boolean redisAvailable,
        boolean messagingAvailable,
        int registeredServers,
        int onlineServers,
        int onlinePlayers,
        Map<String, Long> counters) {

    public HealthReport {
        counters = counters == null ? Map.of() : Map.copyOf(counters);
    }
}
