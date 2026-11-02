package studio.qsa3ed.qcore.central.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.MetricsService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.common.events.NetworkEventType;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;
import studio.qsa3ed.qcore.common.model.NodeStatus;
import studio.qsa3ed.qcore.storage.repository.EventRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the rule engine and applies its decisions.
 *
 * <p>Automation is intentionally conservative: rules are evaluated on a timer,
 * every action is bounded by per-rule cooldowns and hourly limits inside
 * {@link RuleEngine}, a global dry-run switch exists, and every applied or
 * suppressed action is written to the automation audit table.</p>
 */
public final class IntelligenceEngine {

    private static final Logger LOG = LoggerFactory.getLogger(IntelligenceEngine.class);

    private final RuleEngine engine;
    private final ServerRegistry registry;
    private final EventService events;
    private final MetricsService metrics;
    private final EventRepository auditRepository;
    private final CentralConfiguration.Intelligence settings;
    private final Clock clock;

    public IntelligenceEngine(RuleEngine engine, ServerRegistry registry, EventService events,
                              MetricsService metrics, EventRepository auditRepository,
                              CentralConfiguration.Intelligence settings, Clock clock) {
        this.engine = engine;
        this.registry = registry;
        this.events = events;
        this.metrics = metrics;
        this.auditRepository = auditRepository;
        this.settings = settings;
        this.clock = clock;
    }

    /** Evaluates every server. @return the number of applied actions. */
    public int tick() {
        if (!settings.enabled() || engine.isEmpty()) {
            return 0;
        }
        NetworkSnapshot snapshot = metrics.currentSnapshot();
        int applied = 0;
        for (NetworkServer server : registry.cachedByType(studio.qsa3ed.qcore.common.model.NodeType.SERVER)) {
            for (RuleEngine.TriggeredRule triggered : engine.evaluate(server, snapshot)) {
                for (CentralConfiguration.Action action : triggered.rule().actions()) {
                    if (apply(triggered, action)) {
                        applied++;
                    }
                }
            }
        }
        if (applied > 0) {
            LOG.info("Network intelligence applied {} action(s)", applied);
        }
        metrics.increment("intelligence.ticks");
        return applied;
    }

    private boolean apply(RuleEngine.TriggeredRule triggered, CentralConfiguration.Action action) {
        String ruleId = triggered.rule().id();
        Optional<NodeStatus> targetStatus = RuleEngine.targetStatus(action);
        String details = triggered.metrics().toString();

        if (settings.dryRun()) {
            audit(ruleId, triggered.serverId(), "DRY_RUN:" + action.type(), details);
            metrics.increment("intelligence.dry_run");
            return false;
        }

        if (targetStatus.isPresent()) {
            registry.updateStatus(triggered.serverId(), targetStatus.get()).whenComplete((updated, failure) -> {
                if (failure != null) {
                    LOG.warn("Rule {} could not update {}: {}", ruleId, triggered.serverId(), failure.getMessage());
                }
            });
            audit(ruleId, triggered.serverId(), action.type() + ":" + targetStatus.get(), details);
            events.publish(NetworkEventType.RULE_TRIGGERED, "central", Map.of(
                    "ruleId", ruleId,
                    "nodeId", triggered.serverId(),
                    "action", action.type(),
                    "status", targetStatus.get().name(),
                    "metrics", triggered.metrics()));
            metrics.increment("intelligence.actions");
            return true;
        }

        switch (action.type().toUpperCase(java.util.Locale.ROOT)) {
            case "EMIT_EVENT" -> {
                events.publish(NetworkEventType.RULE_TRIGGERED, "central", Map.of(
                        "ruleId", ruleId,
                        "nodeId", triggered.serverId(),
                        "metrics", triggered.metrics()));
                audit(ruleId, triggered.serverId(), action.type(), details);
                metrics.increment("intelligence.actions");
                return true;
            }
            case "NOTIFY" -> {
                LOG.warn("Automation rule {} matched node {}: {}", ruleId, triggered.serverId(), details);
                audit(ruleId, triggered.serverId(), action.type(), details);
                metrics.increment("intelligence.actions");
                return true;
            }
            default -> {
                LOG.warn("Rule {} declares unsupported action '{}'; it was not applied", ruleId, action.type());
                audit(ruleId, triggered.serverId(), "UNSUPPORTED:" + action.type(), details);
                return false;
            }
        }
    }

    private void audit(String ruleId, String nodeId, String action, String details) {
        long now = clock.millis();
        auditRepository.appendAutomationAudit(ruleId, nodeId, action, details, now)
                .exceptionally(failure -> {
                    LOG.warn("Unable to persist the automation audit record for rule {}", ruleId, failure);
                    return null;
                });
        LOG.debug("Automation audit: rule={} node={} action={}", ruleId, nodeId, action);
    }

    /** @return the evaluated rules, exposed for administration endpoints. */
    public List<CentralConfiguration.Rule> rules() {
        return engine.rules();
    }
}
