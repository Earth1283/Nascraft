package me.bounser.nascraft.web;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.api.events.BuyItemEvent;
import me.bounser.nascraft.api.events.SellItemEvent;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.Price;
import me.bounser.nascraft.portfolio.Portfolio;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class WebTrading {
    public record Result(boolean ok, String error, double worth, double balance, int holding) {
        static Result fail(String error) { return new Result(false, error, 0, 0, 0); }
    }

    private WebTrading() {}

    public static Result execute(Nascraft plugin, WebSettings s, UUID uuid, String itemId, boolean buy, int amount) throws Exception {
        if (!s.tradingEnabled) return Result.fail("trading_disabled");
        if (amount <= 0 || amount > s.maxTradeAmount) return Result.fail("bad_amount");
        return WebApi.onMain(plugin, () -> run(s, uuid, itemId, buy, amount));
    }

    private static Result run(WebSettings s, UUID uuid, String itemId, boolean buy, int amount) {
        MarketManager market = MarketManager.getInstance();
        if (!market.getActive()) return Result.fail("market_closed");
        Item item = market.getItem(itemId);
        if (item == null) return Result.fail("unknown_item");

        Player online = Bukkit.getPlayer(uuid);
        if (s.tradeRequiresOnline && online == null) return Result.fail("must_be_online");
        OfflinePlayer player = online != null ? online : Bukkit.getOfflinePlayer(uuid);

        Price price = item.getPrice();
        Portfolio portfolio = PortfoliosManager.getInstance().getPortfolio(uuid);
        MoneyManager money = MoneyManager.getInstance();
        boolean limitReached = !price.canStockChange(amount, buy);
        if (limitReached && item.isPriceRestricted()) return Result.fail(buy ? "top_limit" : "bottom_limit");

        double worth;
        if (buy) {
            BuyItemEvent event = new BuyItemEvent(online, item, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return Result.fail("cancelled");

            worth = item.buyPrice(amount);
            if (!money.hasEnoughMoney(player, item.getCurrency(), worth)) return Result.fail("insufficient_funds");
            if (!portfolio.hasSpace(item, amount)) return Result.fail("portfolio_full");

            money.withdraw(player, item.getCurrency(), worth, 1 - price.getBuyTaxMultiplier());
            item.applyExternalTrade(amount, true, worth, uuid, limitReached);
            portfolio.addItem(item, amount);
        } else {
            if (!portfolio.hasItem(item, amount)) return Result.fail("not_enough_items");

            if (DebtManager.getInstance().getDebtOfPlayer(uuid) > 0) return Result.fail("debt_locked");

            SellItemEvent event = new SellItemEvent(online, item, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return Result.fail("cancelled");

            worth = item.sellPrice(amount);
            money.deposit(player, item.getCurrency(), worth, price.getSellTaxMultiplier());
            item.applyExternalTrade(amount, false, worth, uuid, limitReached);
            portfolio.removeItem(item, amount);
        }

        Integer held = portfolio.getContent().get(item);
        return new Result(true, null, worth, money.getBalance(player, item.getCurrency()), held == null ? 0 : held);
    }
}
