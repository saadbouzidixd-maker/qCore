package studio.qsa3ed.qcore.velocity.routing;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import studio.qsa3ed.qcore.common.client.CentralClient;
import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;
import studio.qsa3ed.qcore.velocity.config.ProxyConfig;
import studio.qsa3ed.qcore.velocity.session.ProxySessionTracker;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Executes smart routing decisions on the proxy.
 *
 * <p>Central decides, the proxy transfers. A destination is only used when it is
 * registered in {@code velocity.toml} and central did not reject the request.
 * When routing fails the configured fallback server is used, so a player is
 * never left stranded and never sent to a server the proxy does not know.</p>
 */
public final class ProxyRoutingService {

    /** Result of a routing attempt, suitable for command feedback. */
    public record Outcome(boolean success, String server, String message) {
    }

    private final ProxyServer proxy;
    private final CentralClient client;
    private final ProxyConfig config;
    private final ProxySessionTracker sessions;
    private final Logger logger;

    public ProxyRoutingService(ProxyServer proxy, CentralClient client, ProxyConfig config,
                               ProxySessionTracker sessions, Logger logger) {
        this.proxy = proxy;
        this.client = client;
        this.config = config;
        this.sessions = sessions;
        this.logger = logger;
    }

    /** Asks central for a destination without connecting the player. */
    public CompletableFuture<RoutingDecision> evaluate(Player player, String group) {
        String requestedGroup = group == null || group.isBlank() ? config.routingGroup() : group;
        RoutingRequest request = new RoutingRequest(
                player.getUniqueId(),
                requestedGroup,
                Set.of(),
                sessions.partySize(player),
                sessions.serverOf(player.getUniqueId()).orElse(""),
                true);
        return client.evaluateRouting(request);
    }

    /** Routes the player and performs the transfer when configured to. */
    public CompletableFuture<Outcome> route(Player player, String group) {
        return evaluate(player, group).thenCompose(decision -> {
            if (!decision.routed()) {
                return CompletableFuture.completedFuture(fallback(player,
                        "central found no destination (" + decision.reason() + ")"));
            }
            Optional<RegisteredServer> target = findServer(decision.targetServerId());
            if (target.isEmpty()) {
                if (config.requireFreshData()) {
                    return CompletableFuture.completedFuture(fallback(player,
                            "central selected '" + decision.targetServerId()
                                    + "' but this proxy does not know that server"));
                }
                logger.warn("Central selected unknown server '{}'; using the fallback",
                        decision.targetServerId());
                return CompletableFuture.completedFuture(fallback(player, "unknown destination"));
            }
            if (!config.executeTransfers()) {
                return CompletableFuture.completedFuture(new Outcome(false, target.get().getServerInfo().getName(),
                        "transfer execution is disabled by configuration"));
            }
            return connect(player, target.get())
                    .thenApply(success -> success
                            ? new Outcome(true, target.get().getServerInfo().getName(),
                                    "routed to " + target.get().getServerInfo().getName())
                            : fallbackResult(player, "the transfer to " + target.get().getServerInfo().getName()
                                    + " was refused"));
        });
    }

    private CompletableFuture<Boolean> connect(Player player, RegisteredServer server) {
        return player.createConnectionRequest(server).connect().handle((result, failure) -> {
            if (failure != null) {
                logger.warn("Transfer of {} to {} failed", player.getUsername(), server.getServerInfo().getName(),
                        failure);
                return false;
            }
            if (!result.isSuccessful()) {
                logger.info("Transfer of {} to {} was refused: {}", player.getUsername(),
                        server.getServerInfo().getName(), result.getStatus());
            }
            return result.isSuccessful();
        });
    }

    private Outcome fallback(Player player, String reason) {
        logger.info("Routing {} failed: {}", player.getUsername(), reason);
        if (config.fallbackServer().isBlank()) {
            return new Outcome(false, "", reason + " and no fallback server is configured");
        }
        if (!config.executeTransfers()) {
            return new Outcome(false, config.fallbackServer(),
                    reason + "; would fall back to " + config.fallbackServer()
                            + " but transfers are disabled");
        }
        Optional<RegisteredServer> fallback = findServer(config.fallbackServer());
        if (fallback.isEmpty()) {
            return new Outcome(false, config.fallbackServer(),
                    reason + " and the fallback server '" + config.fallbackServer() + "' is not registered");
        }
        connect(player, fallback.get()).join();
        return new Outcome(true, fallback.get().getServerInfo().getName(),
                reason + "; fell back to " + fallback.get().getServerInfo().getName());
    }

    private Outcome fallbackResult(Player player, String reason) {
        return fallback(player, reason);
    }

    /** Finds a proxy-registered server by name, ignoring case. */
    public Optional<RegisteredServer> findServer(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<RegisteredServer> exact = proxy.getServer(name);
        if (exact.isPresent()) {
            return exact;
        }
        return proxy.getAllServers().stream()
                .filter(server -> server.getServerInfo().getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /** @return the configured fallback server name. */
    public String fallbackName() {
        return config.fallbackServer();
    }

    /** Hides checked-completion noise from callers that only need the outcome. */
    public static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }
}
