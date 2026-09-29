package me.bounser.nascraft.portfolio;

import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.linking.LinkManager;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.currencies.CurrenciesManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class PortfoliosManager {

    private final ConcurrentHashMap<UUID, Portfolio> inventories = new ConcurrentHashMap<>();

    private static PortfoliosManager instance;

    public static PortfoliosManager getInstance() { return instance == null ? instance = new PortfoliosManager() : instance; }

    public Portfolio getPortfolio(UUID uuid) {
        return inventories.computeIfAbsent(uuid, Portfolio::new);
    }

    public Portfolio getPortfolio(String userid) {
        UUID uuid = LinkManager.getInstance().getUUID(userid);
        if (uuid == null) return null;
        return getPortfolio(uuid);
    }

    public void savePortfoliosWorthOfOnlinePlayers() {

        for (Player player : Bukkit.getOnlinePlayers()) {

            if (player == null) continue;

            double value = getPortfolio(player.getUniqueId()).getValueOfDefaultCurrency();
            double debt = DebtManager.getInstance().getDebtOfPlayer(player.getUniqueId());

            if (value == 0 && debt == 0) continue;

            DatabaseManager.get().getDatabase().saveOrUpdateWorthToday(player.getUniqueId(), value - debt);
        }
    }

    public void savePortfolioOfPlayer(Player player) {
        if (player == null) return;

        Portfolio portfolio = getPortfolio(player.getUniqueId());

        if (portfolio == null) return;

        double worth = portfolio.getValueOfDefaultCurrency();
        double debt = DebtManager.getInstance().getDebtOfPlayer(player.getUniqueId());

        DatabaseManager.get().getDatabase().saveOrUpdateWorthToday(player.getUniqueId(), worth - debt);
    }

}
