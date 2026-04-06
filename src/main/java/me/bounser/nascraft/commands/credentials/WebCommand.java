package me.bounser.nascraft.commands.credentials;

import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.database.DatabaseManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.util.Collections;
import java.util.List;

public class WebCommand extends Command {

    public WebCommand() {
        super("web", new String[]{"nasweb"}, "Web integration command", "nascraft.web");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Lang.get().message(Message.NOT_A_PLAYER));
            return;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("§eWeb integration link: §f" + Config.getInstance().getWebAddress());
            return;
        }

        if (args[0].equalsIgnoreCase("setpassword")) {
            if (args.length < 2) {
                player.sendMessage("§cUsage: /web setpassword <password>");
                return;
            }

            String password = args[1];
            String hash = BCrypt.hashpw(password, BCrypt.gensalt());

            DatabaseManager.get().getDatabase().storeCredentials(player.getName(), hash);
            player.sendMessage("§aPassword set successfully!");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("setpassword");
        }
        return Collections.emptyList();
    }
}
