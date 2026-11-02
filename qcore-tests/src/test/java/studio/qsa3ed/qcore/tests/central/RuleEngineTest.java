package studio.qsa3ed.qcore.tests.central;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.central.intelligence.RuleEngine;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.common.model.NodeType;
import studio.qsa3ed.qcore.common.model.ServerMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static final NetworkServer SERVER = new NetworkServer("lobby-1", "lobby-1", NodeType.SERVER,
            "127.0.0.1", 25565, "lobby-1:25565", NodeStatus.ONLINE,
            new ServerMetrics(10.0, 40.0, 95, 100, 900, 1024, "Paper 1.21.11"),
            1_000L, 2_000L, "lobby", 10, java.util.Set.of("lobby"), true);

    private static CentralConfiguration.Rule overloadRule(Duration cooldown, int maxPerHour) {
        return new CentralConfiguration.Rule("overload", "mark overloaded", true, 100, cooldown, maxPerHour,
                List.of(new CentralConfiguration.Condition("tps", CentralConfiguration.Operator.LESS_THAN, 15.0),
                        new CentralConfiguration.Condition("capacityUtilisation",
                                CentralConfiguration.Operator.GREATER_OR_EQUAL, 0.85)),
                List.of(new CentralConfiguration.Action("MARK_STATUS", Map.of("status", "OVERLOADED"))));
    }

    private static RuleEngine engineWithFixedClock(CentralConfiguration.Rule rule) {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        return new RuleEngine(List.of(rule), clock);
    }

    @Test
    @DisplayName("a rule fires only when every condition matches")
    void allConditionsMustMatch() {
        RuleEngine engine = engineWithFixedClock(overloadRule(Duration.ZERO, 100));
        NetworkSnapshot snapshot = NetworkSnapshot.of(List.of(SERVER), 1_000_000L);

        assertEquals(1, engine.evaluate(SERVER, snapshot).size(),
                "tps 10 and 95% utilisation satisfy both conditions");

        NetworkServer healthy = SERVER.withMetrics(
                new ServerMetrics(20.0, 1.0, 95, 100, 100, 1024, "Paper"), NodeStatus.ONLINE, 2_000L);
        assertTrue(engine.evaluate(healthy, snapshot).isEmpty(), "healthy TPS must not fire the rule");
    }

    @Test
    @DisplayName("cooldown prevents an action storm")
    void cooldownLimitsExecution() {
        RuleEngine engine = engineWithFixedClock(overloadRule(Duration.ofSeconds(60), 100));
        NetworkSnapshot snapshot = NetworkSnapshot.of(List.of(SERVER), 1_000_000L);

        assertEquals(1, engine.evaluate(SERVER, snapshot).size());
        assertEquals(0, engine.evaluate(SERVER, snapshot).size(), "second evaluation inside the cooldown");
    }

    @Test
    @DisplayName("the hourly execution limit is enforced per rule and node")
    void hourlyLimit() {
        MutableClock clock = new MutableClock(1_000_000L);
        RuleEngine engine = new RuleEngine(List.of(overloadRule(Duration.ofSeconds(1), 2)), clock);
        NetworkSnapshot snapshot = NetworkSnapshot.of(List.of(SERVER), 1_000_000L);

        assertEquals(1, engine.evaluate(SERVER, snapshot).size());
        clock.advance(2_000L);
        assertEquals(1, engine.evaluate(SERVER, snapshot).size());
        clock.advance(2_000L);
        assertEquals(0, engine.evaluate(SERVER, snapshot).size(), "hourly limit of 2 reached");
    }

    @Test
    @DisplayName("mark status actions expose their target status")
    void exposesTargetStatus() {
        CentralConfiguration.Action action = overloadRule(Duration.ZERO, 10).actions().get(0);

        assertEquals(Optional.of(NodeStatus.OVERLOADED), RuleEngine.targetStatus(action));
        assertTrue(RuleEngine.targetStatus(new CentralConfiguration.Action("NOTIFY", Map.of())).isEmpty());
        assertTrue(RuleEngine.targetStatus(
                new CentralConfiguration.Action("MARK_STATUS", Map.of("status", "NOPE"))).isEmpty());
    }

    @Test
    @DisplayName("disabled rules never evaluate")
    void disabledRule() {
        CentralConfiguration.Rule disabled = new CentralConfiguration.Rule("disabled", "", false, 1,
                Duration.ZERO, 10, overloadRule(Duration.ZERO, 10).conditions(),
                overloadRule(Duration.ZERO, 10).actions());
        RuleEngine engine = engineWithFixedClock(disabled);

        assertTrue(engine.isEmpty());
        assertFalse(engine.evaluate(SERVER, NetworkSnapshot.of(List.of(SERVER), 1_000L)).iterator().hasNext());
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long delta) {
            millis += delta;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
