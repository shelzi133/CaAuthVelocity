package z.caAuthVelocity.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ru.matveylegenda.tiauth.cache.AuthCache;
import ru.matveylegenda.tiauth.config.MainConfig;
import ru.matveylegenda.tiauth.velocity.api.TiAuthAPI;
import z.caAuthVelocity.flow.VerificationManager;

import java.util.Optional;

public class AuthGateListener {

    private final ProxyServer server;
    private final VerificationManager manager;

    public AuthGateListener(ProxyServer server, VerificationManager manager) {
        this.server = server;
        this.manager = manager;
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();

        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return null;
        }
        if (target.get().getServerInfo().getName().equals(MainConfig.IMP.servers.auth)) {
            return null;
        }
        if (!AuthCache.isAuthenticated(player.getUsername())) {
            return null;
        }
        if (manager.isVerified(player.getUniqueId())) {
            return null;
        }

        boolean initialConnection = player.getCurrentServer().isEmpty();

        return EventTask.resumeWhenComplete(manager.evaluate(player, !initialConnection).thenAccept(allowed -> {
            if (allowed) {
                return;
            }
            if (initialConnection) {
                server.getServer(MainConfig.IMP.servers.auth).ifPresentOrElse(
                        limbo -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(limbo)),
                        () -> event.setResult(ServerPreConnectEvent.ServerResult.denied())
                );
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        }));
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();

        String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        if (!currentServer.equals(MainConfig.IMP.servers.auth)) {
            return;
        }
        if (!AuthCache.isAuthenticated(player.getUsername())) {
            return;
        }
        if (manager.isVerified(player.getUniqueId()) || manager.isInProgress(player.getUniqueId())) {
            return;
        }

        manager.evaluate(player, true).thenAccept(allowed -> {
            if (allowed) {
                TiAuthAPI.getInstance().getAuthManager().loginPlayer(player, true);
            }
        });
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        manager.handleTwinkCode(event.getPlayer(), event.getMessage());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.cleanup(event.getPlayer().getUniqueId());
    }
}