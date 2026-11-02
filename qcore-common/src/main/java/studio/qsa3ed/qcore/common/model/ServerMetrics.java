package studio.qsa3ed.qcore.common.model;

import java.util.Objects;

/**
 * A point-in-time telemetry sample reported by a gameplay server.
 *
 * @param tps          ticks per second, {@code 20.0} is ideal; {@code -1} means unknown
 * @param mspt         average milliseconds per tick, {@code -1} means unknown
 * @param onlinePlayers current player count
 * @param maxPlayers    configured player capacity
 * @param heapUsedMb    used JVM heap in megabytes
 * @param heapMaxMb     maximum JVM heap in megabytes
 * @param software      server software description, for example {@code Paper 1.21.11}
 */
public record ServerMetrics(
        double tps,
        double mspt,
        int onlinePlayers,
        int maxPlayers,
        long heapUsedMb,
        long heapMaxMb,
        String software) {

    public ServerMetrics {
        if (!Double.isFinite(tps) || tps < -1.0 || tps > 10_000.0) {
            throw new IllegalArgumentException("tps out of range: " + tps);
        }
        if (!Double.isFinite(mspt) || mspt < -1.0) {
            throw new IllegalArgumentException("mspt out of range: " + mspt);
        }
        if (onlinePlayers < 0 || maxPlayers < 0) {
            throw new IllegalArgumentException("player counts must not be negative");
        }
        if (heapUsedMb < 0 || heapMaxMb < 0) {
            throw new IllegalArgumentException("heap values must not be negative");
        }
        software = software == null || software.isBlank() ? "unknown" : software;
    }

    /** @return a fully placeholder sample used before the first real heartbeat. */
    public static ServerMetrics unknown() {
        return new ServerMetrics(-1.0, -1.0, 0, 0, 0, 0, "unknown");
    }

    /** @return {@code true} when TPS telemetry was reported. */
    public boolean hasTps() {
        return tps >= 0.0;
    }

    /** @return remaining player slots, or {@link Integer#MAX_VALUE} when capacity is unknown. */
    public int freeSlots() {
        if (maxPlayers <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxPlayers - onlinePlayers);
    }

    /** @return utilisation in {@code [0, 1]}, based on player capacity. */
    public double capacityUtilisation() {
        if (maxPlayers <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) onlinePlayers / (double) maxPlayers);
    }

    /** @return the heap utilisation in {@code [0, 1]}, or {@code 0} when unknown. */
    public double heapUtilisation() {
        if (heapMaxMb <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) heapUsedMb / (double) heapMaxMb);
    }

    @Override
    public String toString() {
        return "ServerMetrics[tps=" + String.format(java.util.Locale.ROOT, "%.2f", tps)
                + ", mspt=" + String.format(java.util.Locale.ROOT, "%.2f", mspt)
                + ", players=" + onlinePlayers + "/" + maxPlayers
                + ", heap=" + heapUsedMb + "/" + heapMaxMb + "MB"
                + ", software=" + Objects.toString(software) + "]";
    }
}
