package studio.qsa3ed.qcore.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import studio.qsa3ed.qcore.common.client.CentralClient;
import studio.qsa3ed.qcore.common.exception.ConfigurationException;
import studio.qsa3ed.qcore.velocity.command.QCoreCommand;
import studio.qsa3ed.qcore.velocity.config.ProxyConfig;
import studio.qsa3ed.qcore.velocity.registration.ProxyRegistrar;
import studio.qsa3ed.qcore.velocity.routing.ProxyRoutingService;
import studio.qsa3ed.qcore.velocity.session.ProxySessionTracker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * qCore proxy node.
 *
 * <p>Registers the proxy with the network, tracks sessions and server
 * availability, and executes smart routing decisions from central. The proxy
 * never decides placement itself: it asks central, then performs the transfer
 * after checking that the destination actually exists in {@code velocity.toml}.</p>
 */
public final class QCoreVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final AtomicReference<ProxyConfig> config = new AtomicReference<>();
    private CentralClient client;
    private ProxyRegistrar registrar;
    private ProxyRoutingService routing;
    private ProxySessionTracker sessions;
    private QCoreCommand command;

    @Inject
    public QCoreVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** Creates the runtime from configuration. */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        ensureDefault("config.yml");
        ensureDefault("proxy.yml");

        ProxyConfig loaded;
        try {
            loaded = ProxyConfig.load(dataDirectory);
        } catch (ConfigurationException failure) {
            logger.error("qCore cannot start: {}", failure.getMessage());
            return;
        }
        config.set(loaded);
        start(loaded);

        proxy.getCommandManager().register("qcore", command, "qc");
        logger.info("qCore proxy '{}' started, reporting to {}", loaded.proxyId(), loaded.centralUrl());
    }

    private void start(ProxyConfig loaded) {
        client = new CentralClient(new CentralClient.Settings(
                loaded.centralUrl(), loaded.proxyId(), loaded.sharedSecret(), loaded.timeout()));
        sessions = new ProxySessionTracker(proxy, player -> 1);
        routing = new ProxyRoutingService(proxy, client, loaded, sessions, logger);
        registrar = new ProxyRegistrar(proxy, client, loaded, logger);
        registrar.start();
        command = new QCoreCommand(this, proxy, client, loaded, registrar, routing, sessions);
    }

    /** Records a player's server so routing can exclude it. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (sessions != null) {
            sessions.connected(event.getPlayer(), event.getServer());
        }
    }

    /** Tracks logins even before the first server connection. */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        logger.debug("Player {} joined the network", event.getPlayer().getUsername());
    }

    /** Forgets players that leave the proxy. */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (sessions != null) {
            sessions.disconnected(event.getPlayer());
        }
    }

    /** Stops heartbeat work when the proxy shuts down. */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        close();
    }

    /**
     * Reloads configuration and restarts reporting.
     *
     * @return {@code true} when the new configuration was accepted
     */
    public boolean reloadProxy() {
        try {
            ProxyConfig reloaded = ProxyConfig.load(dataDirectory);
            close();
            config.set(reloaded);
            start(reloaded);
            logger.info("qCore proxy configuration reloaded for '{}'", reloaded.proxyId());
            return true;
        } catch (ConfigurationException failure) {
            logger.error("Refusing to reload qCore: {}", failure.getMessage());
            return false;
        }
    }

    private void ensureDefault(String fileName) {
        Path target = dataDirectory.resolve(fileName);
        if (Files.isRegularFile(target)) {
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/" + fileName)) {
            if (in == null) {
                logger.warn("Bundled default {} is missing from the jar", fileName);
                return;
            }
            Files.createDirectories(dataDirectory);
            Files.copy(in, target);
        } catch (IOException failure) {
            logger.error("Unable to write the default configuration {}", fileName, failure);
        }
    }

    private void close() {
        if (registrar != null) {
            registrar.close();
            registrar = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /** @return the active proxy configuration, {@code null} before start. */
    public ProxyConfig proxyConfig() {
        return config.get();
    }

    /** @return the routing service, {@code null} before start. */
    public ProxyRoutingService routing() {
        return routing;
    }

    /** @return the proxy this plugin belongs to. */
    public ProxyServer proxy() {
        return proxy;
    }
}
