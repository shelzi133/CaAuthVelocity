package z.caAuthVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import org.slf4j.Logger;
import z.caAuthVelocity.api.BridgeClient;
import z.caAuthVelocity.api.BridgeServer;
import z.caAuthVelocity.command.CaavCommand;
import z.caAuthVelocity.config.VelocityConfig;
import z.caAuthVelocity.flow.VerificationManager;
import z.caAuthVelocity.listener.AuthGateListener;
import z.caAuthVelocity.tunnel.SocksServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

@Plugin(
        id = "caauthvelocity",
        name = "CaAuthVelocity",
        version = BuildConstants.VERSION,
        dependencies = {@Dependency(id = "tiauth")}
)
public class CaAuthVelocity {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer server;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private volatile VelocityConfig config;
    private VerificationManager manager;
    private AuthGateListener listener;
    private SocksServer tunnel;
    private BridgeServer inbound;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("caav").plugin(this).build();
        commandManager.register(meta, new CaavCommand(this));
        reload();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        stopTunnel();
        stopInbound();
    }

    public VelocityConfig getConfig() {
        return config;
    }

    public synchronized boolean reload() {
        try {
            if (config == null) {
                config = createConfig();
            } else {
                config.load(true);
            }
        } catch (Exception e) {
            logger.error("Не удалось загрузить config.yml", e);
            return false;
        }

        restartTunnel();
        restartInbound();

        if (config.api.secret == null || config.api.secret.isBlank()) {
            logger.error("В config.yml не задан api.secret, скопируйте module.auth.velocity.secret из конфига основного плагина. Проверка входа отключена");
            if (listener != null) {
                server.getEventManager().unregisterListener(this, listener);
                listener = null;
            }
            return false;
        }

        BridgeClient client = new BridgeClient(config.api);
        if (manager == null) {
            manager = new VerificationManager(this, server, logger, config, client);
        } else {
            manager.setClient(client);
        }
        if (listener == null) {
            listener = new AuthGateListener(server, manager);
            server.getEventManager().register(this, listener);
        }

        logger.info("CaAuthVelocity включен, API основного плагина: {}:{}", config.api.host, config.api.port);
        return true;
    }

    private void stopInbound() {
        if (inbound != null) {
            inbound.stop();
            inbound = null;
        }
    }

    private void restartInbound() {
        stopInbound();
        VelocityConfig.Inbound settings = config.inbound;
        if (!settings.enabled || config.api.secret == null || config.api.secret.isBlank()) {
            return;
        }
        BridgeServer bridge = new BridgeServer(server, logger, settings, config.api.secret);
        try {
            bridge.start();
            inbound = bridge;
            logger.info("Входящий API запущен на {}:{}", settings.bindHost, settings.port);
        } catch (Exception e) {
            bridge.stop();
            logger.error("Не удалось запустить входящий API: {}", e.getMessage(), e);
        }
    }

    private void stopTunnel() {
        if (tunnel != null) {
            tunnel.stop();
            tunnel = null;
        }
    }

    private void restartTunnel() {
        stopTunnel();
        VelocityConfig.Tunnel settings = config.tunnel;
        if (!settings.enabled) {
            return;
        }
        if (settings.password == null || settings.password.isBlank()) {
            byte[] bytes = new byte[18];
            new SecureRandom().nextBytes(bytes);
            settings.password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            config.save();
            logger.info("[Tunnel] Сгенерирован пароль SOCKS5, он записан в config.yml: {}", settings.password);
        }
        SocksServer server = new SocksServer(settings, logger);
        try {
            server.start();
            tunnel = server;
            logger.info("[Tunnel] SOCKS5-сервер запущен на {}:{}", settings.bindHost, settings.port);
            if (settings.allowedHosts == null || settings.allowedHosts.isEmpty()) {
                logger.warn("[Tunnel] tunnel.allowed-hosts пуст, разрешены соединения к любым хостам");
            }
        } catch (Exception e) {
            server.stop();
            logger.error("[Tunnel] Не удалось запустить SOCKS5-сервер: {}", e.getMessage(), e);
        }
    }

    private VelocityConfig createConfig() throws Exception {
        Files.createDirectories(dataDirectory);
        return ConfigManager.create(VelocityConfig.class, it -> {
            it.withConfigurer(new YamlSnakeYamlConfigurer());
            it.withBindFile(dataDirectory.resolve("config.yml").toFile());
            it.saveDefaults();
            it.load(true);
        });
    }
}
