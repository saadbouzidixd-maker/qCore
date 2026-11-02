package studio.qsa3ed.qcore.tests.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the platform APIs qCore compiles against.
 *
 * <p><strong>Scope:</strong> these are API surface checks against the
 * {@code paper-api} and {@code velocity-api} artifacts. They prove that every
 * method qCore depends on exists in the targeted API version; they are not a
 * runtime compatibility test on a live Paper, Purpur, Folia or Velocity
 * installation. See DEVELOPMENT_STATUS.md for the runtime status.</p>
 */
class PlatformApiSurfaceTest {

    @Test
    @DisplayName("Paper/Folia region scheduler API is available to the plugin")
    void paperRegionSchedulerApi() throws Exception {
        Class<?> bukkit = Class.forName("org.bukkit.Bukkit");

        Method global = bukkit.getMethod("getGlobalRegionScheduler");
        Method region = bukkit.getMethod("getRegionScheduler");
        Method async = bukkit.getMethod("getAsyncScheduler");
        Method tps = bukkit.getMethod("getTPS");
        Method mspt = bukkit.getMethod("getAverageTickTime");

        assertNotNull(global);
        assertNotNull(region);
        assertNotNull(async);
        assertNotNull(tps);
        assertNotNull(mspt);
        assertTrue(tps.getReturnType().isArray(), "getTPS must return an array of recent TPS values");

        Class<?> scheduledTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
        assertNotNull(scheduledTask.getMethod("cancel"));
        // Signature: run(Plugin, Consumer<ScheduledTask>, Runnable retiredCallback)
        Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                .getMethod("run", org.bukkit.plugin.Plugin.class,
                        java.util.function.Consumer.class, Runnable.class);
    }

    @Test
    @DisplayName("Velocity proxy API is available to the plugin")
    void velocityApi() throws Exception {
        Class<?> proxy = Class.forName("com.velocitypowered.api.proxy.ProxyServer");
        Class<?> registeredServer = Class.forName("com.velocitypowered.api.proxy.server.RegisteredServer");
        Class<?> player = Class.forName("com.velocitypowered.api.proxy.Player");

        assertNotNull(proxy.getMethod("getServer", String.class));
        assertNotNull(proxy.getMethod("getAllServers"));
        assertNotNull(proxy.getMethod("getAllPlayers"));
        assertNotNull(player.getMethod("createConnectionRequest", registeredServer));
        assertNotNull(Class.forName("com.velocitypowered.api.event.player.ServerConnectedEvent"));
        assertNotNull(Class.forName("com.velocitypowered.api.command.SimpleCommand"));
        assertNotNull(Class.forName("com.velocitypowered.api.plugin.annotation.DataDirectory"));
    }

    @Test
    @DisplayName("the plugin descriptors reference real main classes")
    void descriptorsPointAtExistingClasses() throws Exception {
        assertNotNull(Class.forName("studio.qsa3ed.qcore.server.QCoreServerPlugin"));
        assertNotNull(Class.forName("studio.qsa3ed.qcore.velocity.QCoreVelocityPlugin"));
        assertNotNull(Class.forName("studio.qsa3ed.qcore.central.CentralApplication"));
    }
}
