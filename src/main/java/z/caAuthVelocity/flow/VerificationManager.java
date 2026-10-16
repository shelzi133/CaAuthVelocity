package z.caAuthVelocity.flow;

import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.matveylegenda.tiauth.velocity.api.TiAuthAPI;
import z.caAuthVelocity.CaAuthVelocity;
import z.caAuthVelocity.api.BridgeClient;
import z.caAuthVelocity.config.VelocityConfig;
import z.caAuthVelocity.util.Text;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class VerificationManager {

    private static final Pattern TWINK_CODE = Pattern.compile("\\d{6}");

    private final CaAuthVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final VelocityConfig config;
    private volatile BridgeClient client;

    private final Set<UUID> verified = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Flow> flows = new ConcurrentHashMap<>();

    public VerificationManager(CaAuthVelocity plugin, ProxyServer server, Logger logger,
                               VelocityConfig config, BridgeClient client) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.client = client;
    }

    public void setClient(BridgeClient client) {
        this.client = client;
    }

    public boolean isVerified(UUID uuid) {
        return verified.contains(uuid);
    }

    public boolean isInProgress(UUID uuid) {
        return flows.containsKey(uuid);
    }

    public CompletableFuture<Boolean> evaluate(Player player, boolean startFlow) {
        UUID uuid = player.getUniqueId();
        if (verified.contains(uuid)) {
            return CompletableFuture.completedFuture(true);
        }
        if (flows.containsKey(uuid)) {
            return CompletableFuture.completedFuture(false);
        }

        return client.check(uuid, player.getUsername(), ip(player)).handle((response, error) -> {
            if (error != null) {
                return handleApiError(player, error);
            }

            String action = response.get("action").getAsString();
            switch (action) {
                case "ALLOW":
                    verified.add(uuid);
                    return true;
                case "BIND":
                    if (!config.verification.requireBinding) {
                        verified.add(uuid);
                        return true;
                    }
                    if (startFlow) {
                        startBind(player);
                    }
                    return false;
                case "CONFIRM":
                    if (startFlow) {
                        startConfirm(player, response);
                    }
                    return false;
                case "TWINK":
                    if (startFlow) {
                        startTwink(player);
                    }
                    return false;
                default:
                    return handleApiError(player, new IllegalStateException("Неизвестное действие: " + action));
            }
        });
    }

    public void cleanup(UUID uuid) {
        verified.remove(uuid);
        Flow flow = flows.remove(uuid);
        if (flow == null) {
            return;
        }
        flow.stop();
        if (flow.kind == Kind.CONFIRM) {
            client.cancel(uuid).exceptionally(error -> null);
        }
    }

    public void handleTwinkCode(Player player, String message) {
        UUID uuid = player.getUniqueId();
        Flow flow = flows.get(uuid);
        if (flow == null || flow.kind == Kind.CONFIRM) {
            return;
        }
        String code = message.trim();
        if (!TWINK_CODE.matcher(code).matches() || !flow.redeeming.compareAndSet(false, true)) {
            return;
        }

        client.twinkRedeem(uuid, player.getUsername(), ip(player), code).whenComplete((response, error) -> {
            flow.redeeming.set(false);
            if (flows.get(uuid) != flow) {
                return;
            }
            if (error != null) {
                failApi(player, flow, error);
                return;
            }
            switch (response.get("result").getAsString()) {
                case "BOUND":
                    complete(player, flow, config.messages.twinkBound);
                    break;
                case "INVALID":
                    player.sendMessage(Text.color(config.messages.twinkInvalid));
                    break;
                case "LOCKED":
                    player.sendMessage(format(config.messages.twinkLocked,
                            "minutes", String.valueOf(response.get("minutes").getAsLong())));
                    break;
                default:
                    player.sendMessage(Text.color(config.messages.twinkError));
            }
        });
    }

    private void startTwink(Player player) {
        UUID uuid = player.getUniqueId();
        Flow flow = new Flow(Kind.TWINK);
        if (flows.putIfAbsent(uuid, flow) != null) {
            return;
        }

        int timeout = Math.max(30, config.verification.twinkTimeoutSeconds);
        int reminder = Math.max(5, config.verification.reminderSeconds);
        AtomicInteger elapsed = new AtomicInteger();

        player.sendMessage(format(config.messages.twinkRequired, "seconds", String.valueOf(timeout)));
        showBar(player, flow, config.bossBar.twinkTitle, "", timeout);

        flow.task = server.getScheduler().buildTask(plugin, () -> {
            if (flows.get(uuid) != flow) {
                flow.stop();
                return;
            }
            if (!player.isActive()) {
                cleanup(uuid);
                return;
            }

            int tick = elapsed.incrementAndGet();
            if (tick >= timeout) {
                kick(player, flow, config.messages.twinkTimeoutKick);
                return;
            }
            updateBar(flow, config.bossBar.twinkTitle, "", timeout - tick, timeout);
            if (tick % reminder == 0) {
                player.sendMessage(format(config.messages.twinkRequired,
                        "seconds", String.valueOf(timeout - tick)));
            }
        }).delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
    }

    private void startBind(Player player) {
        UUID uuid = player.getUniqueId();
        Flow flow = new Flow(Kind.BIND);
        if (flows.putIfAbsent(uuid, flow) != null) {
            return;
        }

        client.bindCode(uuid, player.getUsername()).whenComplete((response, error) -> {
            if (flows.get(uuid) != flow) {
                return;
            }
            if (error != null) {
                failApi(player, flow, error);
                return;
            }
            if (response.get("bound").getAsBoolean()) {
                complete(player, flow, config.messages.bound);
                return;
            }

            String code = response.get("code").getAsString();
            int timeout = Math.max(30, config.verification.bindTimeoutSeconds);
            int poll = Math.max(1, config.verification.bindPollSeconds);
            int reminder = Math.max(5, config.verification.reminderSeconds);
            AtomicInteger elapsed = new AtomicInteger();
            AtomicBoolean polling = new AtomicBoolean();

            player.sendMessage(format(config.messages.bindRequired,
                    "code", code, "seconds", String.valueOf(timeout)));
            showBar(player, flow, config.bossBar.bindTitle, "", timeout);

            flow.task = server.getScheduler().buildTask(plugin, () -> {
                if (flows.get(uuid) != flow) {
                    flow.stop();
                    return;
                }
                if (!player.isActive()) {
                    cleanup(uuid);
                    return;
                }

                int tick = elapsed.incrementAndGet();
                if (tick >= timeout) {
                    kick(player, flow, config.messages.bindTimeoutKick);
                    return;
                }
                updateBar(flow, config.bossBar.bindTitle, "", timeout - tick, timeout);
                if (tick % reminder == 0) {
                    player.sendMessage(format(config.messages.bindRequired,
                            "code", code, "seconds", String.valueOf(timeout - tick)));
                }
                if (tick % poll == 0 && polling.compareAndSet(false, true)) {
                    client.bindStatus(uuid, player.getUsername(), ip(player)).whenComplete((status, statusError) -> {
                        polling.set(false);
                        if (flows.get(uuid) != flow) {
                            return;
                        }
                        if (statusError != null) {
                            failApi(player, flow, statusError);
                            return;
                        }
                        if (status.get("bound").getAsBoolean()) {
                            complete(player, flow, config.messages.bound);
                            return;
                        }
                        if (status.has("twink") && status.get("twink").getAsBoolean()
                                && flow.twinkNotified.compareAndSet(false, true)) {
                            player.sendMessage(format(config.messages.twinkRequired,
                                    "seconds", String.valueOf(timeout - tick)));
                        }
                    });
                }
            }).delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
        });
    }

    private void startConfirm(Player player, JsonObject check) {
        UUID uuid = player.getUniqueId();
        Flow flow = new Flow(Kind.CONFIRM);
        if (flows.putIfAbsent(uuid, flow) != null) {
            return;
        }

        int timeout = check.get("timeoutSeconds").getAsInt();
        String bot = botName(check);
        int reminder = Math.max(5, config.verification.reminderSeconds);
        AtomicInteger elapsed = new AtomicInteger();

        player.sendMessage(format(config.messages.confirmSent,
                "bot", bot, "seconds", String.valueOf(timeout)));
        showBar(player, flow, config.bossBar.confirmTitle, bot, timeout);

        flow.task = server.getScheduler().buildTask(plugin, () -> {
            if (flows.get(uuid) != flow) {
                flow.stop();
                return;
            }
            if (!player.isActive()) {
                cleanup(uuid);
                return;
            }

            int tick = elapsed.incrementAndGet();
            updateBar(flow, config.bossBar.confirmTitle, bot, Math.max(0, timeout - tick), timeout);
            if (tick % reminder == 0 && tick < timeout) {
                player.sendMessage(format(config.messages.confirmSent,
                        "bot", bot, "seconds", String.valueOf(timeout - tick)));
            }
        }).delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();

        flow.request = client.confirm(uuid, player.getUsername(), ip(player), Duration.ofSeconds(timeout + 15L));
        flow.request.whenComplete((response, error) -> {
            if (flows.get(uuid) != flow) {
                return;
            }
            if (error != null) {
                failApi(player, flow, error);
                return;
            }

            switch (response.get("result").getAsString()) {
                case "CONFIRMED":
                    complete(player, flow, config.messages.confirmed);
                    break;
                case "DENIED":
                    kick(player, flow, config.messages.deniedKick);
                    break;
                case "TIMEOUT":
                    kick(player, flow, config.messages.confirmTimeoutKick);
                    break;
                case "UNAVAILABLE":
                    kick(player, flow, config.messages.botUnavailableKick);
                    break;
                default:
                    if (flows.remove(uuid, flow)) {
                        flow.stop();
                    }
            }
        });
    }

    private void complete(Player player, Flow flow, String message) {
        UUID uuid = player.getUniqueId();
        if (!flows.remove(uuid, flow)) {
            return;
        }
        flow.stop();
        verified.add(uuid);

        if (message != null && !message.isBlank()) {
            player.sendMessage(Text.color(message));
        }
        TiAuthAPI.getInstance().getAuthManager().loginPlayer(player, true);
    }

    private void kick(Player player, Flow flow, String message) {
        if (!flows.remove(player.getUniqueId(), flow)) {
            return;
        }
        flow.stop();
        player.disconnect(Text.color(message));
    }

    private void failApi(Player player, Flow flow, Throwable error) {
        logger.warn("Не удалось связаться с API основного плагина для {}: {}",
                player.getUsername(), describe(error));
        if (config.api.failOpen) {
            complete(player, flow, null);
        } else {
            kick(player, flow, config.messages.apiErrorKick);
        }
    }

    private boolean handleApiError(Player player, Throwable error) {
        logger.warn("Не удалось связаться с API основного плагина для {}: {}",
                player.getUsername(), describe(error));
        if (config.api.failOpen) {
            verified.add(player.getUniqueId());
            return true;
        }
        player.disconnect(Text.color(config.messages.apiErrorKick));
        return false;
    }

    private String describe(Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.toString();
    }

    private String botName(JsonObject check) {
        String type = check.has("type") && !check.get("type").isJsonNull()
                ? check.get("type").getAsString()
                : "";
        return "telegram".equalsIgnoreCase(type) ? "Telegram" : "Discord";
    }

    private void showBar(Player player, Flow flow, String template, String bot, int total) {
        if (!config.bossBar.enabled) {
            return;
        }
        BossBar bar = BossBar.bossBar(barTitle(template, bot, total), 1f, barColor(), barOverlay());
        flow.player = player;
        flow.bar = bar;
        player.showBossBar(bar);
    }

    private void updateBar(Flow flow, String template, String bot, int remaining, int total) {
        BossBar bar = flow.bar;
        if (bar == null) {
            return;
        }
        int left = Math.max(0, remaining);
        bar.name(barTitle(template, bot, left));
        bar.progress(Math.max(0f, Math.min(1f, left / (float) Math.max(1, total))));
    }

    private Component barTitle(String template, String bot, int seconds) {
        return Text.color(template
                .replace("{seconds}", String.valueOf(seconds))
                .replace("{bot}", bot));
    }

    private BossBar.Color barColor() {
        try {
            return BossBar.Color.valueOf(config.bossBar.color.trim().toUpperCase());
        } catch (Exception e) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay barOverlay() {
        try {
            return BossBar.Overlay.valueOf(config.bossBar.overlay.trim().toUpperCase());
        } catch (Exception e) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    private Component format(String template, String... replacements) {
        String result = template;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return Text.color(result);
    }

    private String ip(Player player) {
        return player.getRemoteAddress().getAddress().getHostAddress();
    }

    private enum Kind { BIND, CONFIRM, TWINK }

    private static final class Flow {
        private final Kind kind;
        private final AtomicBoolean redeeming = new AtomicBoolean();
        private final AtomicBoolean twinkNotified = new AtomicBoolean();
        private volatile ScheduledTask task;
        private volatile CompletableFuture<JsonObject> request;
        private volatile Player player;
        private volatile BossBar bar;

        private Flow(Kind kind) {
            this.kind = kind;
        }

        private void stop() {
            BossBar currentBar = bar;
            Player currentPlayer = player;
            if (currentBar != null && currentPlayer != null) {
                currentPlayer.hideBossBar(currentBar);
            }
            ScheduledTask currentTask = task;
            if (currentTask != null) {
                currentTask.cancel();
            }
            CompletableFuture<JsonObject> pending = request;
            if (pending != null) {
                pending.cancel(true);
            }
        }
    }
}