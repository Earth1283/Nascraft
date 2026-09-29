package me.bounser.nascraft.commands.web;

import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.web.WebServer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class WebCodeCommand extends Command {
    public WebCodeCommand(String name) {
        super(name, new String[0], "Get a web dashboard login code", "nascraft.web");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return;
        WebServer web = WebServer.get();
        if (web == null) {
            Lang.get().message(player, Message.WEB_DISABLED);
            return;
        }
        String code = web.auth().issueCode(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        String url = web.publicUrl();
        String pretty = code.length() >= 8 ? code.substring(0, code.length() / 2) + "-" + code.substring(code.length() / 2) : code;
        Lang.get().message(player, Lang.get().message(Message.WEB_CODE)
                .replace("[CODE]", pretty)
                .replace("[MINUTES]", String.valueOf(web.settings().codeTtlMinutes))
                .replace("[URL]", url + "/login"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
