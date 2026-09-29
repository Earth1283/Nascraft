package me.bounser.nascraft.managers;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.advancedgui.LayoutModifier;
import me.bounser.nascraft.crossserver.RedisManager;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.alerts.DiscordAlerts;
import me.bounser.nascraft.discord.DiscordBot;
import me.bounser.nascraft.discord.DiscordLog;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.unit.stats.Instant;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import me.bounser.nascraft.scheduler.FoliaScheduler;
import me.leoko.advancedgui.manager.GuiWallManager;
import me.leoko.advancedgui.utils.GuiWallInstance;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class TasksManager {

    public static TasksManager instance;

    private final int ticksPerSecond = 20;

    private final Plugin AGUI = Bukkit.getPluginManager().getPlugin("AdvancedGUI");

    public static TasksManager getInstance() { return instance == null ? instance = new TasksManager() : instance; }

    private TasksManager(){

        LocalTime timeNow = LocalTime.now();

        LocalTime nextMinute = timeNow.plusMinutes(1).withSecond(0);
        Duration timeRemaining = Duration.between(timeNow, nextMinute);

        // Registering tasks:
        saveDataTask();
        noiseTask((int) timeRemaining.getSeconds());
        discordTask((int) timeRemaining.getSeconds());
        shortTermPricesTask((int) timeRemaining.getSeconds());
        hourlyTask();
        saveInstants();

        DatabaseManager.get().getDatabase().purgeHistory();
    }

    private void shortTermPricesTask(int delay) {

        FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

            var parents = MarketManager.getInstance().getAllParentItems();
            boolean noise = Config.getInstance().getPriceNoise();

            float allChanges = 0;
            for (Item item : parents) {
                if (noise)
                    allChanges += item.getPrice().getChange();

                item.lowerOperations();

                item.getPrice().addValueToShortTermStorage();
            }

            MarketManager.getInstance().updateMarketChange1h(parents.isEmpty() ? 0 : allChanges/parents.size());

            if (AGUI != null &&
                AGUI.isEnabled() &&
                GuiWallManager.getInstance().getActiveInstances() != null)

                for (GuiWallInstance instance : GuiWallManager.getInstance().getActiveInstances()) {

                    if (instance.getLayout().getName().equals("Nascraft"))
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            var interaction = instance.getInteraction(player);
                            if (interaction != null)
                                LayoutModifier.getInstance().updateMainPage(interaction.getComponentTree(), true, player);
                        }

                }

        }, (long) delay * ticksPerSecond, 60L * ticksPerSecond);
    }

    private void discordTask(int delay) {

        if (Config.getInstance().getDiscordEnabled()) {

            FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

                if (Config.getInstance().getDiscordMenuEnabled()) {
                    DiscordBot.getInstance().update();
                    DiscordAlerts.getInstance().updateAlerts();
                }

                if (Config.getInstance().getLogChannelEnabled())
                    DiscordLog.getInstance().flushBuffer();

            }, (long) delay * ticksPerSecond, ((long) Config.getInstance().getUpdateTime() *  ticksPerSecond));
        }
    }

    private void noiseTask(int delay) {

        FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

            if (!Config.getInstance().isPrimaryNode()) return;

            RedisManager redis = Nascraft.getInstance().getRedisManager();

            if (!Config.getInstance().getPriceNoise()) return;

            var parents = MarketManager.getInstance().getAllParentItems();

            for (Item item : parents) item.getPrice().applyNoise();

            // Cross-server: stamp + broadcast the new stocks in one pipelined batch.
            if (redis != null && redis.isConnected()) redis.advanceAndPublish(parents);
        }, (long) delay * ticksPerSecond, (long) Config.getInstance().getNoiseTime() *  ticksPerSecond);
    }

    private void saveDataTask() {

        FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

            // Item state + CPI are authoritative/global — only the primary writes
            // them to the shared DB (followers would clobber). Per-player balance
            // and portfolio-worth saves run on every node, for that node's players.
            if (Config.getInstance().isPrimaryNode()) {
                DatabaseManager.get().getDatabase().saveEverything();
                DatabaseManager.get().getDatabase().saveCPIValue(MarketManager.getInstance().getConsumerPriceIndex());
            }

            PortfoliosManager.getInstance().savePortfoliosWorthOfOnlinePlayers();

            java.util.List<java.util.UUID> online = new java.util.ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) online.add(player.getUniqueId());
            DatabaseManager.get().getDatabase().updateBalances(online);

        }, 60L * 5 * ticksPerSecond, 60L * 5 * ticksPerSecond); // 5 min
    }

    private void saveInstants() {

        FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

            LocalDateTime now = LocalDateTime.now();

            for (Item item : MarketManager.getInstance().getAllParentItems()) {

                item.getItemStats().addInstant(new Instant(
                        now,
                        item.getPrice().getValue(),
                        item.getVolume()
                ));

                item.restartVolume();
            }

        }, 2400, 60L * ticksPerSecond);
    }

    private void hourlyTask() {
        LocalTime timeNow = LocalTime.now();

        LocalTime nextHour = timeNow.plusHours(1).withMinute(0).withSecond(0);
        Duration timeRemaining = Duration.between(timeNow, nextHour);

        FoliaScheduler.runAsyncTimer(Nascraft.getInstance(), () -> {

            for (Item item : MarketManager.getInstance().getAllItems()) {
                item.getPrice().restartHourLimits();
            }

            MarketManager.getInstance().setOperationsLastHour(0);

            if (Config.getInstance().getAlertsMenuEnabled()) DatabaseManager.get().getDatabase().purgeAlerts();

        }, timeRemaining.getSeconds()*ticksPerSecond, 60 * 60 * ticksPerSecond); // 1 hour
    }
}
