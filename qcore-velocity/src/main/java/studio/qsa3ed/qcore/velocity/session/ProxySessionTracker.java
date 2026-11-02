package studio.qsa3ed.qcore.velocity.session;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks where each player currently is, and how many players must travel
 * together.
 *
 * <p>Party support is expressed through {@link PartyProvider}. The default
 * provider treats every player as a party of one, which is correct for a network
 * without a party plugin; integrating a party plugin means supplying another
 * provider without touching routing.</p>
 */
public final class ProxySessionTracker {

    /** Resolves the number of players that must be placed together. */
    @FunctionalInterface
    public interface PartyProvider {
        int partySize(Player player);
    }

    private final ProxyServer proxy;
    private final Map<UUID, String> currentServer = new ConcurrentHashMap<>();
    private final PartyProvider partyProvider;

    public ProxySessionTracker(ProxyServer proxy, PartyProvider partyProvider) {
        this.proxy = proxy;
        this.partyProvider = partyProvider == null ? player -> 1 : partyProvider;
    }

    /** Records that a player connected to a server. */
    public void connected(Player player, RegisteredServer server) {
        currentServer.put(player.getUniqueId(), server.getServerInfo().getName());
    }

    /** Forgets a player. */
    public void disconnected(Player player) {
        currentServer.remove(player.getUniqueId());
    }

    /** @return the name of the server the player is connected to, if any. */
    public Optional<String> serverOf(UUID uuid) {
        String tracked = currentServer.get(uuid);
        if (tracked != null) {
            return Optional.of(tracked);
        }
        return proxy.getPlayer(uuid)
                .flatMap(Player::getCurrentServer)
                .map(connection -> connection.getServerInfo().getName());
    }

    /** @return the party size that must fit on the destination. */
    public int partySize(Player player) {
        return Math.max(1, partyProvider.partySize(player));
    }

    /** @return how many players this tracker currently knows about. */
    public int trackedPlayers() {
        return currentServer.size();
    }
}
