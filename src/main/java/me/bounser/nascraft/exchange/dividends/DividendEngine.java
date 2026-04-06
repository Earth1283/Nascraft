package me.bounser.nascraft.exchange.dividends;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.config.lang.Lang;
import me.bounser.nascraft.config.lang.Message;
import me.bounser.nascraft.discord.DiscordBot;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyManager;
import me.bounser.nascraft.exchange.company.CompanyStatus;
import me.bounser.nascraft.exchange.shares.ShareRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DividendEngine {

    private static DividendEngine instance;

    private DividendEngine() {}

    public static DividendEngine getInstance() {
        if (instance == null) {
            instance = new DividendEngine();
        }
        return instance;
    }

    public void runWeeklyDividends() {
        for (Company company : CompanyManager.getInstance().getAllActiveCompanies()) {
            if (company.getStatus() != CompanyStatus.ACTIVE) continue;

            double profit = company.getCurrentVaultValue() - company.getLastVaultSnapshot();
            if (profit <= 0) {
                company.setLastVaultSnapshot(company.getCurrentVaultValue());
                continue;
            }

            double totalDividend = profit * Config.getInstance().getExchangeDividendPayoutPercentage();
            DividendResult result = calculateDividend(company.getId());

            result.perPlayerAmounts.forEach((playerUuid, amount) -> {
                distributeToShareholder(playerUuid, company.getId(), amount);
            });

            company.setLastVaultSnapshot(company.getCurrentVaultValue());

            // Discord notification
            if (me.bounser.nascraft.config.Config.getInstance().getDiscordEnabled() && DiscordBot.getInstance() != null) {
                DiscordBot.getInstance().getJDA().getTextChannelsByName("market-news", true).forEach(channel -> {
                    channel.sendMessage("📢 **Dividend Distribution**\n" +
                            "Company: " + company.getName() + " (" + company.getTicker() + ")\n" +
                            "Total Profit: " + String.format("%.2f", profit) + "\n" +
                            "Total Dividend Paid: " + String.format("%.2f", totalDividend)).queue();
                });
            }
        }
    }

    public DividendResult calculateDividend(UUID companyId) {
        Company company = CompanyManager.getInstance().getCompany(companyId).orElseThrow();
        double profit = company.getCurrentVaultValue() - company.getLastVaultSnapshot();
        double totalDividend = Math.max(0, profit * Config.getInstance().getExchangeDividendPayoutPercentage());

        Map<UUID, BigDecimal> shareholders = ShareRegistry.getInstance().getShareholders(companyId);
        BigDecimal totalShares = ShareRegistry.getInstance().getTotalOutstanding(companyId);

        Map<UUID, Double> perPlayerAmounts = new HashMap<>();
        if (totalShares.compareTo(BigDecimal.ZERO) > 0) {
            shareholders.forEach((playerUuid, amount) -> {
                double shareRatio = amount.divide(totalShares, 10, RoundingMode.HALF_UP).doubleValue();
                perPlayerAmounts.put(playerUuid, totalDividend * shareRatio);
            });
        }

        return new DividendResult(companyId, profit, totalDividend, perPlayerAmounts);
    }

    public void distributeToShareholder(UUID playerUuid, UUID companyId, double amount) {
        Nascraft.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(playerUuid), amount);

        CompanyManager.getInstance().getCompany(companyId).ifPresent(company -> {
            Player online = Bukkit.getPlayer(playerUuid);
            if (online != null) {
                String msg = Lang.get().message(Message.EXCHANGE_DIVIDEND_RECEIVED)
                        .replace("[AMOUNT]", String.format("%.2f", amount))
                        .replace("[COMPANY]", company.getName())
                        .replace("[TICKER]", company.getTicker());
                Lang.get().message(online, msg);
            }
        });
    }

    public static class DividendResult {
        public UUID companyId;
        public double profit;
        public double totalDividend;
        public Map<UUID, Double> perPlayerAmounts;

        public DividendResult(UUID companyId, double profit, double totalDividend, Map<UUID, Double> perPlayerAmounts) {
            this.companyId = companyId;
            this.profit = profit;
            this.totalDividend = totalDividend;
            this.perPlayerAmounts = perPlayerAmounts;
        }
    }
}
