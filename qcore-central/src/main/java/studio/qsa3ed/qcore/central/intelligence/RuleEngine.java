package studio.qsa3ed.qcore.central.intelligence;

import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;
import studio.qsa3ed.qcore.common.model.NodeStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic, bounded rule evaluator.
 *
 * <p>The engine only decides <em>what</em> should happen; applying an action is
 * the caller's responsibility. Every rule is guarded by a cooldown and a hard
 * hourly execution limit per node, so a faulty threshold can never trigger an
 * unlimited automation loop. Evaluation is pure with respect to the clock:
 * time is injected, which keeps it unit testable.</p>
 */
public final class RuleEngine {

    private final List<CentralConfiguration.Rule> rules;
    private final Clock clock;
    private final Map<String, RuleState> states = new HashMap<>();

    public RuleEngine(List<CentralConfiguration.Rule> rules, Clock clock) {
        this.rules = rules.stream()
                .filter(CentralConfiguration.Rule::enabled)
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList();
        this.clock = clock;
    }

    /** @return {@code true} when no rules are configured. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** @return the configured rules, highest priority first. */
    public List<CentralConfiguration.Rule> rules() {
        return rules;
    }

    /**
     * Evaluates every rule against one node.
     *
     * @param server   node to inspect
     * @param snapshot network state used for network-wide conditions
     * @return triggered rules with the actions that are allowed to run
     */
    public List<TriggeredRule> evaluate(NetworkServer server, NetworkSnapshot snapshot) {
        Map<String, Double> metrics = metricsOf(server, snapshot);
        List<TriggeredRule> triggered = new ArrayList<>();
        for (CentralConfiguration.Rule rule : rules) {
            if (rule.conditions().isEmpty() || !matches(rule, metrics)) {
                continue;
            }
            String stateKey = rule.id() + "|" + server.id();
            RuleState state = states.computeIfAbsent(stateKey, key -> new RuleState());
            long now = clock.millis();
            if (!state.mayExecute(now, rule.cooldown().toMillis(), rule.maxExecutionsPerHour())) {
                continue;
            }
            state.record(now);
            triggered.add(new TriggeredRule(rule, server.id(), metrics));
        }
        return triggered;
    }

    /** @return the metric map used for condition evaluation, exposed for auditing. */
    public static Map<String, Double> metricsOf(NetworkServer server, NetworkSnapshot snapshot) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("tps", server.metrics().hasTps() ? server.metrics().tps() : -1.0);
        metrics.put("mspt", server.metrics().mspt());
        metrics.put("onlinePlayers", (double) server.metrics().onlinePlayers());
        metrics.put("maxPlayers", (double) server.metrics().maxPlayers());
        metrics.put("capacityUtilisation", server.metrics().capacityUtilisation());
        metrics.put("heapUtilisation", server.metrics().heapUtilisation());
        metrics.put("statusDegraded", server.status().isDegraded() ? 1.0 : 0.0);
        metrics.put("networkPlayers", (double) snapshot.totalPlayers());
        metrics.put("networkUtilisation", snapshot.utilisation());
        return metrics;
    }

    private boolean matches(CentralConfiguration.Rule rule, Map<String, Double> metrics) {
        for (CentralConfiguration.Condition condition : rule.conditions()) {
            Double actual = metrics.get(condition.metric());
            if (actual == null) {
                // An unknown metric can never satisfy a condition; skipping keeps rules safe.
                return false;
            }
            if (!compare(actual, condition.operator(), condition.value())) {
                return false;
            }
        }
        return true;
    }

    private static boolean compare(double actual, CentralConfiguration.Operator operator, double expected) {
        return switch (operator) {
            case GREATER_THAN -> actual > expected;
            case GREATER_OR_EQUAL -> actual >= expected;
            case LESS_THAN -> actual < expected;
            case LESS_OR_EQUAL -> actual <= expected;
            case EQUALS -> Double.compare(actual, expected) == 0;
            case NOT_EQUALS -> Double.compare(actual, expected) != 0;
        };
    }

    /** Resolves the status an action asks for, if any. */
    public static Optional<NodeStatus> targetStatus(CentralConfiguration.Action action) {
        if (!"MARK_STATUS".equalsIgnoreCase(action.type())) {
            return Optional.empty();
        }
        Object raw = action.parameters().get("status");
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(NodeStatus.valueOf(String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** A rule that fired and is allowed to act. */
    public record TriggeredRule(CentralConfiguration.Rule rule, String serverId, Map<String, Double> metrics) {
    }

    /** Sliding-window execution accounting for one (rule, node) pair. */
    private static final class RuleState {
        private long lastExecutedAt;
        private final List<Long> executions = new ArrayList<>();

        boolean mayExecute(long now, long cooldownMillis, int maxPerHour) {
            if (lastExecutedAt > 0 && now - lastExecutedAt < cooldownMillis) {
                return false;
            }
            executions.removeIf(timestamp -> now - timestamp > 3_600_000L);
            return executions.size() < maxPerHour;
        }

        void record(long now) {
            lastExecutedAt = now;
            executions.add(now);
        }
    }
}
