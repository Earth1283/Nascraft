package me.bounser.nascraft.commands.economy;

import me.bounser.nascraft.commands.Command;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.economy.EconomyEngine;
import me.bounser.nascraft.economy.EconomyMath;
import me.bounser.nascraft.economy.MacroSnapshot;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.formatter.Style;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.managers.currencies.Currency;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.resources.Category;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.scheduler.FoliaScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EconomyCommand extends Command {
    private static final String ADMIN = "nascraft.economy.admin";

    public EconomyCommand() {
        super(
                "economy",
                new String[]{Config.getInstance().getCommandAlias("economy")},
                "Server economy overview",
                "nascraft.economy"
        );
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        EconomyEngine engine = EconomyEngine.get();
        if (engine == null) { send(sender, Lang.get().message(Message.ECONOMY_DISABLED)); return; }

        if (args.length == 0) { send(sender, summary(engine)); return; }

        if (!sender.hasPermission(ADMIN)) { send(sender, summary(engine)); return; }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                engine.reload();
                send(sender, Lang.get().message(Message.ECONOMY_RELOADED));
            }
            case "treasury" -> {
                if (args.length < 3) { usage(sender); return; }
                double amount;
                try { amount = Math.abs(Double.parseDouble(args[2])); } catch (NumberFormatException e) { usage(sender); return; }
                double delta = args[1].equalsIgnoreCase("withdraw") ? -amount : amount;

                FoliaScheduler.runAsync(Nascraft.getInstance(), () -> {
                    boolean ok = engine.adjustTreasury(delta);
                    send(sender, ok
                            ? Lang.get().message(Message.ECONOMY_TREASURY_ADJUSTED).replace("[AMOUNT]", money(engine.treasury()))
                            : Lang.get().message(Message.ECONOMY_TREASURY_INSUFFICIENT));
                });
            }
            case "shock" -> {
                if (args.length < 4) { usage(sender); return; }
                String category = args[1].equalsIgnoreCase("all") ? null : args[1];
                if (category != null && MarketManager.getInstance().getCategoryFromIdentifier(category) == null) { usage(sender); return; }
                try {
                    double pct = Double.parseDouble(args[2]);
                    double hours = Double.parseDouble(args[3]);
                    if (pct <= -100 || hours <= 0) { usage(sender); return; }
                    engine.triggerShock(category, pct, hours);
                    send(sender, Lang.get().message(Message.ECONOMY_SHOCK_TRIGGERED));
                } catch (NumberFormatException e) { usage(sender); }
            }
            default -> usage(sender);
        }
    }

    private void usage(CommandSender sender) { send(sender, Lang.get().message(Message.ECONOMY_ADMIN_USAGE)); }

    private static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage.replace("§", "&")));
    }

    public static String phaseName(EconomyMath.Phase phase) {
        return Lang.get().message(switch (phase) {
            case EXPANSION -> Message.ECONOMY_PHASE_EXPANSION;
            case SLOWDOWN -> Message.ECONOMY_PHASE_SLOWDOWN;
            case CONTRACTION -> Message.ECONOMY_PHASE_CONTRACTION;
            case RECOVERY -> Message.ECONOMY_PHASE_RECOVERY;
        });
    }

    private static String money(double v) {
        Currency c = CurrenciesManager.getInstance().getDefaultCurrency();
        return Formatter.format(c, v, Style.ROUND_BASIC);
    }

    private static String pct(double fraction, int decimals) {
        return Formatter.roundToDecimals(fraction * 100, decimals) + "%";
    }

    private String summary(EconomyEngine engine) {
        MacroSnapshot s = engine.latest();
        return Lang.get().message(Message.ECONOMY_SUMMARY)
                .replace("[PHASE]", phaseName(s.phase()))
                .replace("[CPI]", String.valueOf(Formatter.roundToDecimals(s.cpi(), 2)))
                .replace("[INFLATION]", pct(s.inflation(), 3))
                .replace("[RATE]", pct(EconomyEngine.loanDailyRate(), 3))
                .replace("[LIQUIDITY]", String.valueOf(Formatter.roundToDecimals(s.liquidity(), 3)))
                .replace("[GDP]", money(s.gdp()))
                .replace("[GAP]", pct(s.outputGap(), 1))
                .replace("[MONEY]", money(s.moneySupply()))
                .replace("[VELOCITY]", String.valueOf(Formatter.roundToDecimals(s.velocity(), 3)))
                .replace("[PRICE-LEVEL]", String.valueOf(Formatter.roundToDecimals(s.priceLevel(), 3)))
                .replace("[TAX-SCALE]", String.valueOf(Formatter.roundToDecimals(s.taxScale(), 3)))
                .replace("[TREASURY]", money(engine.treasury()))
                .replace("[UBI]", money(s.ubiPerCapita()))
                .replace("[GINI]", String.valueOf(Formatter.roundToDecimals(s.gini(), 3)))
                .replace("[SHOCKS]", String.valueOf(engine.activeShocks().size()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) return List.of();
        if (args.length == 1) return filter(List.of("treasury", "shock", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("treasury")) return filter(List.of("deposit", "withdraw"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("shock")) {
            List<String> cats = new ArrayList<>();
            cats.add("all");
            for (Category c : MarketManager.getInstance().getCategories()) cats.add(c.getIdentifier());
            return filter(cats, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("shock")) return List.of("10", "-10", "25");
        if (args.length == 4 && args[0].equalsIgnoreCase("shock")) return List.of("2", "6", "12");
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        return out;
    }
}
