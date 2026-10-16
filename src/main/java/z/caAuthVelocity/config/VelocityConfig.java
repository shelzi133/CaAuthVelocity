package z.caAuthVelocity.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.NameModifier;
import eu.okaeri.configs.annotation.NameStrategy;
import eu.okaeri.configs.annotation.Names;

import java.util.ArrayList;
import java.util.List;

@Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
public class VelocityConfig extends OkaeriConfig {

    public Api api = new Api();
    public Inbound inbound = new Inbound();
    public Verification verification = new Verification();
    public BossBarSettings bossBar = new BossBarSettings();
    public Tunnel tunnel = new Tunnel();
    public Messages messages = new Messages();

    public static class Api extends OkaeriConfig {
        public String host = "127.0.0.1";
        public int port = 25599;
        public String secret = "";
        public int connectTimeoutSeconds = 5;
        public boolean failOpen = false;
    }

    public static class Inbound extends OkaeriConfig {
        public boolean enabled = true;
        public String bindHost = "127.0.0.1";
        public int port = 25600;
    }

    public static class Verification extends OkaeriConfig {
        public boolean requireBinding = true;
        public int bindTimeoutSeconds = 300;
        public int bindPollSeconds = 3;
        public int reminderSeconds = 15;
        public int twinkTimeoutSeconds = 300;
    }

    public static class BossBarSettings extends OkaeriConfig {
        public boolean enabled = true;
        public String color = "YELLOW";
        public String overlay = "PROGRESS";
        public String bindTitle = "&eПривяжите аккаунт, осталось &f{seconds} &eсек.";
        public String confirmTitle = "&eПодтвердите вход в {bot}, осталось &f{seconds} &eсек.";
        public String twinkTitle = "&eВведите код твинка из бота в чат, осталось &f{seconds} &eсек.";
    }

    public static class Tunnel extends OkaeriConfig {
        public boolean enabled = false;
        public String bindHost = "0.0.0.0";
        public int port = 1080;
        public String username = "calisto";
        public String password = "";
        public List<String> allowedHosts = new ArrayList<>(List.of("api.telegram.org", "discord.com", "discord.gg", "discordapp.com", "discordapp.net"));
    }

    public static class Messages extends OkaeriConfig {
        public String bindRequired = "&eДля входа на сервер привяжите аккаунт. Ваш код: &f{code}&e. Отправьте его боту в Discord или Telegram.";
        public String bound = "&aАккаунт привязан!";
        public String twinkRequired = "&eВаш ник добавлен как твинк. Отправьте в чат код, который вы получили в боте. Осталось &f{seconds} &eсек.";
        public String twinkBound = "&aТвинк привязан! Подтверждения входа будут приходить на основной аккаунт.";
        public String twinkInvalid = "&cНеверный или просроченный код. Проверьте код в боте и отправьте его в чат ещё раз.";
        public String twinkLocked = "&cСлишком много неверных кодов. Попробуйте снова через {minutes} мин.";
        public String twinkError = "&cНе удалось привязать твинка. Попробуйте позже или создайте новый код в боте.";
        public String twinkTimeoutKick = "&cВы не ввели код твинка за отведённое время. Зайдите снова и отправьте код в чат.";
        public String confirmSent = "&eПодтвердите вход в {bot}.";
        public String confirmed = "&aВход подтверждён, приятной игры!";
        public String bindTimeoutKick = "&cВы не привязали аккаунт за отведённое время. Зайдите снова и привяжите аккаунт.";
        public String confirmTimeoutKick = "&cВы не подтвердили вход через бота за отведённое время. Зайдите снова и подтвердите вход.";
        public String deniedKick = "&cВход был отклонён через бота. Если это были не вы - смените пароль от Discord/Telegram!";
        public String botUnavailableKick = "&cНе удалось отправить запрос подтверждения в бота. Попробуйте зайти позже.";
        public String apiErrorKick = "&cСервис подтверждения входа недоступен. Попробуйте зайти позже.";
        public String reloaded = "&aКонфиг CaAuthVelocity перезагружен.";
        public String reloadFailed = "&cНе удалось перезагрузить конфиг, подробности в консоли.";
        public String noPermission = "&cУ вас нет прав на эту команду.";
        public String usage = "&eИспользование: &f/caav reload";
    }
}