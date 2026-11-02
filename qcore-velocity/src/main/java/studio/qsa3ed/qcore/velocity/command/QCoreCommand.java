package studio.qsa3ed.qcore.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import studio.qsa3ed.qcore.common.client.CentralClient;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.velocity.QCoreVelocityPlugin;
import studio.qsa3ed.qcore.velocity.config.ProxyConfig;
import studio.qsa3ed.qcore.velocity.registration.ProxyRegistrar;
import studio.qsa3ed.qcore.velocity.routing.ProxyRoutingService;
import studio.qsa3ed.qcore.velocity.session.ProxySessionTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code /qcore} administration for the proxy.
 *
 * <p>All network work runs asynchronously; results are delivered back on the
 * proxy's scheduler, never on the caller's thread.</p>
 */
public final class QCoreCommand implements SimpleCommand {

    private final QCoreVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final CentralClient client;
    private final ProxyConfig config;
    private final ProxyRegistrar registrar;
    private final ProxyRoutingService routing;
    private final ProxySessionTracker sessions;
    private final AtomicLong routingFailures = new AtomicLong();

    public QCoreCommand(QCoreVelocityPlugin plugin, ProxyServer proxy, CentralClient client, ProxyConfig config,
                        ProxyRegistrar registrar, ProxyRoutingService routing, ProxySessionTracker sessions) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.client = client;
        this.config = config;
        this.registrar = registrar;
        this.routing = routing;
        this.sessions = sessions;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(source);
            case "servers" -> servers(source);
            case "route" -> route(source, args);
            case "reload" -> reload(source);
            default -> source.sendMessage(Component.text(
                    "Usage: /qcore <status|servers|route <player>|reload>", NamedTextColor.YELLOW));
        }
    }

    private void status(CommandSource source) {
        source.sendMessage(Component.text("qCore proxy " + config.proxyId(), NamedTextColor.AQUA));
        source.sendMessage(line("Central", config.centralUrl()));
        source.sendMessage(line("Registered", registrar.isRegistered() ? "yes" : "no"));
        source.sendMessage(line("Heartbeats", Long.toString(registrar.heartbeats())));
        source.sendMessage(line("Failures", Long.toString(registrar.failures())));
        source.sendMessage(line("Players", Integer.toString(proxy.getPlayerCount())));
        source.sendMessage(line("Tracked sessions", Integer.toString(sessions.trackedPlayers())));
        source.sendMessage(line("Fallback server", config.fallbackServer().isEmpty()
                ? "(none)" : config.fallbackServer()));
        if (!registrar.lastError().isEmpty()) {
            source.sendMessage(Component.text("  Last error: " + registrar.lastError(), NamedTextColor.RED));
        }
    }

    private void servers(CommandSource source) {
        source.sendMessage(Component.text("Fetching servers…", NamedTextColor.GRAY));
        client.servers().whenComplete((servers, failure) -> runOnProxy(() -> {
            if (failure != null) {
                source.sendMessage(Component.text("Unable to reach qCore central: "
                        + ProxyRoutingService.unwrap(failure).getMessage(), NamedTextColor.RED));
                return;
            }
            source.sendMessage(Component.text("Network: " + servers.size() + " node(s)", NamedTextColor.AQUA));
            for (NetworkServer server : servers) {
                source.sendMessage(Component.text("  " + server.id() + " [" + server.status() + "] "
                                + server.metrics().onlinePlayers() + "/" + server.metrics().maxPlayers()
                                + " players, group '" + server.gameMode() + "'",
                        server.status().isDegraded() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
            }
        }));
    }

    private void route(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /qcore route <player>", NamedTextColor.YELLOW));
            return;
        }
        Optional<Player> player = proxy.getPlayer(args[1]);
        if (player.isEmpty()) {
            source.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return;
        }
        String group = args.length >= 3 ? args[2] : config.routingGroup();
        routing.route(player.get(), group).whenComplete((outcome, failure) -> runOnProxy(() -> {
            if (failure != null) {
                routingFailures.incrementAndGet();
                source.sendMessage(Component.text("Routing failed: "
                        + ProxyRoutingService.unwrap(failure).getMessage(), NamedTextColor.RED));
                return;
            }
            source.sendMessage(Component.text(outcome.message(),
                    outcome.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }));
    }

    private void reload(CommandSource source) {
        if (plugin.reloadProxy()) {
            source.sendMessage(Component.text("qCore proxy configuration reloaded.", NamedTextColor.GREEN));
        } else {
            source.sendMessage(Component.text("Reload failed; see the proxy log.", NamedTextColor.RED));
        }
    }

    private void runOnProxy(Runnable task) {
        proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    private static Component line(String key, String value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("qcore.command");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "servers", "route", "reload").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && "route".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            proxy.getAllPlayers().forEach(player -> names.add(player.getUsername()));
            return names;
        }
        return List.of();
    }

    /** @return how many routing attempts failed. */
    public long routingFailures() {
        return routingFailures.get();
    }
}
