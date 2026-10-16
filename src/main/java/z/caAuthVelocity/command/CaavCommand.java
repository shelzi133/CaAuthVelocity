package z.caAuthVelocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import z.caAuthVelocity.CaAuthVelocity;
import z.caAuthVelocity.config.VelocityConfig;
import z.caAuthVelocity.util.Text;

import java.util.List;

public class CaavCommand implements SimpleCommand {

    private static final String PERMISSION = "caauthvelocity.reload";

    private final CaAuthVelocity plugin;

    public CaavCommand(CaAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(Text.color(messages().noPermission));
            return;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            source.sendMessage(Text.color(messages().usage));
            return;
        }

        boolean success = plugin.reload();
        VelocityConfig.Messages messages = messages();
        source.sendMessage(Text.color(success ? messages.reloaded : messages.reloadFailed));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(PERMISSION)) {
            return List.of();
        }
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase();
        return "reload".startsWith(prefix) ? List.of("reload") : List.of();
    }

    private VelocityConfig.Messages messages() {
        VelocityConfig config = plugin.getConfig();
        return config != null ? config.messages : new VelocityConfig.Messages();
    }
}