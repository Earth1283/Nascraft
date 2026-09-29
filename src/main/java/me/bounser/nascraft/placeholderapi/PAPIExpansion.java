package me.bounser.nascraft.placeholderapi;

import me.bounser.nascraft.commands.economy.EconomyCommand;
import me.bounser.nascraft.economy.EconomyEngine;
import me.bounser.nascraft.economy.MacroSnapshot;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.chart.cpi.CPIInstant;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.linking.LinkManager;
import me.bounser.nascraft.formatter.Formatter;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.formatter.RoundUtils;
import me.bounser.nascraft.portfolio.Portfolio;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import me.bounser.nascraft.scheduler.FoliaScheduler;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

public class PAPIExpansion extends PlaceholderExpansion {

    private String cpi;
    private String cpiMonth;
    private String cpiWeek;

    @Override
    public String getAuthor() { return "Bounser"; }

    @Override
    public String getIdentifier() { return "nascraft"; }

    @Override
    public String getVersion() { return Nascraft.getInstance().getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String identifier) {

        String params = PlaceholderAPI.setBracketPlaceholders(player, identifier);

        String[] dividedParams = params.split("_", 2);

        if (dividedParams.length == 0) return "Invalid format.";

        Item item;

        switch (dividedParams[0].toLowerCase()) {

            case "cpi":

                if (cpi == null) {

                    FoliaScheduler.runAsyncLater(Nascraft.getInstance(), () -> cpi = null, 200);
                    return cpi = String.valueOf(Math.round((MarketManager.getInstance().getConsumerPriceIndex()-100)*100.0)/100.0);

                } else return cpi;

            case "cpimonth":

                if (cpiMonth == null) {

                    FoliaScheduler.runAsyncLater(Nascraft.getInstance(), () -> cpiMonth = null, 200);

                    List<CPIInstant> cpiHistory = DatabaseManager.get().getDatabase().getCPIHistory();

                    int index = cpiHistory.size()-30;

                    if (index < 0) index = cpiHistory.size() - 1;

                    float initialCPI = cpiHistory.get(index).getIndexValue();

                    return cpiMonth = String.valueOf(Math.round((MarketManager.getInstance().getConsumerPriceIndex()-initialCPI)*100.0)/initialCPI);

                } else return cpiMonth;

            case "cpiweek":

                if (cpiWeek == null) {

                    FoliaScheduler.runAsyncLater(Nascraft.getInstance(), () -> cpiWeek = null, 200);

                    List<CPIInstant> cpiHistory = DatabaseManager.get().getDatabase().getCPIHistory();

                    int index = cpiHistory.size()-7;

                    if (index < 0) index = cpiHistory.size() - 1;

                    float initialCPI = cpiHistory.get(index).getIndexValue();

                    return cpiWeek = String.valueOf(Math.round((MarketManager.getInstance().getConsumerPriceIndex()-initialCPI)*100.0)/initialCPI);

                } else return cpiWeek;

            case "eco":
                return economyPlaceholder(dividedParams.length == 2 ? dividedParams[1].toLowerCase() : "");

            case "linked":
                return String.valueOf(LinkManager.getInstance().getUserDiscordID(player.getUniqueId()) != null);

            case "portfoliovalue":

                Portfolio portfolio = PortfoliosManager.getInstance().getPortfolio(player.getUniqueId());
                if (portfolio == null) return "0";
                Double value = PortfoliosManager.getInstance().getPortfolio(player.getUniqueId()).getInventoryValuePerCurrency().get(CurrenciesManager.getInstance().getDefaultCurrency());
                if (value == null) return "0";
                return String.valueOf(Formatter.roundToDecimals(value, CurrenciesManager.getInstance().getDefaultCurrency().getDecimalPrecission()));

            case "debt":
                double debt = DebtManager.getInstance().getDebtOfPlayer(player.getUniqueId());
                return String.valueOf(Formatter.roundToDecimals(debt, CurrenciesManager.getInstance().getDefaultCurrency().getDecimalPrecission()));

            case "interest":
                double interest = DebtManager.getInstance().getDebtOfPlayer(player.getUniqueId()) * EconomyEngine.loanDailyRate();
                return String.valueOf(Formatter.roundToDecimals(interest, CurrenciesManager.getInstance().getDefaultCurrency().getDecimalPrecission()));

            case "discordid":
                String id = LinkManager.getInstance().getUserDiscordID(player.getUniqueId());
                if (id == null) return "Not linked";
                return LinkManager.getInstance().getUserDiscordID(player.getUniqueId());

            case "price":

                if (dividedParams.length != 2) return "Invalid format";

                item = getItemFromString(dividedParams[1], player.getPlayer());

                if (item == null) return "0";

                return String.valueOf(RoundUtils.roundTo(item.getPrice().getValue(), item.getCurrency().getDecimalPrecission()));

            case "stock":

                if (dividedParams.length != 2) return "Invalid format";

                item = getItemFromString(dividedParams[1], player.getPlayer());

                if (item == null) return "0";

                return String.valueOf(RoundUtils.roundTo(item.getPrice().getStock(), 0));

            case "change":

                if (dividedParams.length != 2) return "Invalid format";

                item = getItemFromString(dividedParams[1], player.getPlayer());

                if (item == null) return "Invalid item";

                return String.valueOf(RoundUtils.roundToOne(-100 + item.getPrice().getValue() *100/item.getPrice().getValueAnHourAgo()));
        }

        String[] threeDividedParams = params.split("_", 3);

        if (threeDividedParams.length < 2) { return "Invalid format."; }

        int quantity;

        if (threeDividedParams[1].equalsIgnoreCase("mainhand")) {
            item = MarketManager.getInstance().getItem(player.getPlayer().getInventory().getItemInMainHand());
            quantity = player.getPlayer().getInventory().getItemInMainHand().getAmount();
        } else {
            if (threeDividedParams.length != 3) { return "Invalid format."; }
            item = MarketManager.getInstance().getItem(threeDividedParams[2]);
            try {
                quantity = Integer.parseInt(threeDividedParams[1]);
            } catch (NumberFormatException e) {
                return "Invalid quantity.";
            }
        }

        if (item == null) return "0";

        switch (threeDividedParams[0]) {
            case "buyprice": return String.valueOf(RoundUtils.roundTo(item.getPrice().getProjectedCost(-quantity, item.getPrice().getBuyTaxMultiplier()), item.getCurrency().getDecimalPrecission()));
            case "sellprice": return String.valueOf(RoundUtils.roundTo(item.getPrice().getProjectedCost(quantity, item.getPrice().getSellTaxMultiplier()), item.getCurrency().getDecimalPrecission()));
        }

        return "0";
    }

    private String economyPlaceholder(String metric) {
        EconomyEngine engine = EconomyEngine.get();
        if (engine == null) return "";
        MacroSnapshot s = engine.latest();
        int dp = CurrenciesManager.getInstance().getDefaultCurrency().getDecimalPrecission();
        return switch (metric) {
            case "cpi" -> String.valueOf(Formatter.roundToDecimals(s.cpi(), 2));
            case "inflation" -> String.valueOf(Formatter.roundToDecimals(s.inflation() * 100, 3));
            case "rate" -> String.valueOf(Formatter.roundToDecimals(EconomyEngine.loanDailyRate() * 100, 3));
            case "gdp" -> String.valueOf(Formatter.roundToDecimals(s.gdp(), dp));
            case "realgdp" -> String.valueOf(Formatter.roundToDecimals(s.realGdp(), dp));
            case "moneysupply" -> String.valueOf(Formatter.roundToDecimals(s.moneySupply(), dp));
            case "velocity" -> String.valueOf(Formatter.roundToDecimals(s.velocity(), 3));
            case "gap" -> String.valueOf(Formatter.roundToDecimals(s.outputGap() * 100, 1));
            case "phase" -> EconomyCommand.phaseName(s.phase());
            case "recession" -> String.valueOf(s.recession());
            case "liquidity" -> String.valueOf(Formatter.roundToDecimals(s.liquidity(), 3));
            case "taxscale" -> String.valueOf(Formatter.roundToDecimals(s.taxScale(), 3));
            case "pricelevel" -> String.valueOf(Formatter.roundToDecimals(s.priceLevel(), 3));
            case "treasury" -> String.valueOf(Formatter.roundToDecimals(engine.treasury(), dp));
            case "ubi" -> String.valueOf(Formatter.roundToDecimals(s.ubiPerCapita(), dp));
            case "gini" -> String.valueOf(Formatter.roundToDecimals(s.gini(), 3));
            case "debt" -> String.valueOf(Formatter.roundToDecimals(s.outstandingDebt(), dp));
            case "taxes" -> String.valueOf(Formatter.roundToDecimals(s.taxes(), dp));
            case "trades" -> String.valueOf(s.trades());
            case "issuance" -> String.valueOf(Formatter.roundToDecimals(s.netIssuance(), dp));
            case "shocks" -> String.valueOf(engine.activeShocks().size());
            default -> "Invalid metric";
        };
    }

    public Item getItemFromString(String itemIdentifier, Player player) {

        Item item;

        if (itemIdentifier.equalsIgnoreCase("mainhand")) {
            item = MarketManager.getInstance().getItem(player.getInventory().getItemInMainHand());
        } else {
            item = MarketManager.getInstance().getItem(itemIdentifier);
        }
        return item;
    }
}
