package me.bounser.nascraft;

import me.bounser.nascraft.advancedgui.LayoutModifier;
import me.bounser.nascraft.api.NascraftAPI;
import me.bounser.nascraft.chart.price.ItemChartReduced;
import me.bounser.nascraft.commands.admin.nascraft.NascraftCommand;
import me.bounser.nascraft.commands.admin.nascraft.NascraftLogListener;
import me.bounser.nascraft.commands.admin.marketeditor.edit.item.EditItemMenuListener;
import me.bounser.nascraft.commands.admin.marketeditor.edit.category.CategoryEditorListener;
import me.bounser.nascraft.commands.admin.marketeditor.overview.MarketEditorInvListener;
import me.bounser.nascraft.commands.alert.AlertsCommand;
import me.bounser.nascraft.commands.alert.SetAlertCommand;
import me.bounser.nascraft.commands.credentials.WebCommand;
import me.bounser.nascraft.commands.discord.DiscordCommand;
import me.bounser.nascraft.commands.exchange.ExchangeCommand;
import me.bounser.nascraft.commands.portfolio.PortfolioCommand;
import me.bounser.nascraft.inventorygui.Portfolio.PortfolioInventory;
import me.bounser.nascraft.commands.market.MarketCommand;
import me.bounser.nascraft.commands.sell.SellHandCommand;
import me.bounser.nascraft.commands.sell.SellAllCommand;
import me.bounser.nascraft.commands.sell.sellinv.SellInvListener;
import me.bounser.nascraft.commands.sell.sellinv.SellInvCommand;
import me.bounser.nascraft.commands.sellwand.GiveSellWandCommand;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.discord.DiscordBot;
import me.bounser.nascraft.commands.discord.LinkCommand;
import me.bounser.nascraft.discord.linking.LinkManager;
import me.bounser.nascraft.discord.linking.LinkingMethod;
import me.bounser.nascraft.inventorygui.InventoryListener;
import me.bounser.nascraft.managers.DebtManager;
import me.bounser.nascraft.managers.EventsManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.placeholderapi.PAPIExpansion;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.sellwand.WandListener;
import me.bounser.nascraft.sellwand.WandsManager;
import me.bounser.nascraft.updatechecker.UpdateChecker;
import me.bounser.nascraft.exchange.ExchangeManager;
import me.bounser.nascraft.web.WebServerManager;
import me.leoko.advancedgui.AdvancedGUI;
import me.leoko.advancedgui.manager.GuiItemManager;
import me.leoko.advancedgui.manager.GuiWallManager;
import me.leoko.advancedgui.manager.LayoutManager;
import me.leoko.advancedgui.utils.VersionMediator;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.milkbowl.vault.permission.Permission;
import org.apache.commons.io.FileUtils;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;


public final class Nascraft extends JavaPlugin {

    private static Nascraft main;
    private static NascraftAPI apiInstance;
    private static Economy economy = null;
    private static Permission perms = null;

    private static final String AGUI_VERSION = "2.2.8";

    private BukkitAudiences adventure;

    private WebServerManager webServerManager;

    public static Nascraft getInstance() { return main; }

    public static NascraftAPI getAPI() { return apiInstance == null ? apiInstance = new NascraftAPI() : apiInstance; }

    // ANSI colour constants used for console output
    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_BOLD   = "\u001B[1m";
    private static final String ANSI_CYAN   = "\u001B[36m";
    private static final String ANSI_GREEN  = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GOLD   = "\u001B[38;5;214m";
    private static final String ANSI_GRAY   = "\u001B[90m";
    private static final String ANSI_RED    = "\u001B[31m";

    /** Log a green success line. */
    private void logOk(String msg) {
        getLogger().info(ANSI_GREEN + "  \u2714 " + ANSI_RESET + msg);
    }

    /** Log a yellow warning/skipped line. */
    private void logSkip(String msg) {
        getLogger().info(ANSI_YELLOW + "  \u26A0 " + ANSI_RESET + msg);
    }

    /** Log a cyan informational line. */
    private void logInfo(String msg) {
        getLogger().info(ANSI_CYAN + "  \u2139 " + ANSI_RESET + msg);
    }

    /** Log a red error line. */
    private void logErr(String msg) {
        getLogger().info(ANSI_RED + "  \u2718 " + ANSI_RESET + msg);
    }

    private void printBanner() {
        String v = getDescription().getVersion();
        System.out.println(ANSI_CYAN + "  \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  \u2502  " + ANSI_BOLD + ANSI_GOLD  + "  N A S C R A F T  " + ANSI_RESET + ANSI_CYAN + "\u2736  " + ANSI_RESET + "Economy Market Engine        " + ANSI_CYAN + "\u2502" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  \u2502  " + ANSI_GRAY  + "  version " + ANSI_GREEN + v + ANSI_GRAY + "  \u00B7  by Bounser" + ANSI_RESET + "                     " + ANSI_CYAN + "\u2502" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518" + ANSI_RESET);
        System.out.println();
    }

    @Override
    public void onEnable() {

        main = this;

        printBanner();

        logInfo("Starting initialization sequence...");

        logInfo("Loading configuration...");
        Config config = Config.getInstance();
        logOk("Configuration loaded " + ANSI_GRAY + "(lang: " + config.getSelectedLanguage() + ", db: " + config.getDatabaseType() + ")" + ANSI_RESET);

        logInfo("Setting up metrics...");
        setupMetrics();
        logOk("bStats metrics initialized");

        new UpdateChecker(this, 108216).getVersion(version -> {
            if (!getDescription().getVersion().equals(version)) {
                logSkip("New version available " + ANSI_GRAY + "(" + version + ")" + ANSI_RESET + " \u2192 spigotmc.org/resources/108216/");
            } else {
                logOk("Plugin is up to date");
            }
        });

        logInfo("Initializing Adventure platform...");
        this.adventure = BukkitAudiences.create(this);
        logOk("Adventure platform ready");

        logInfo("Hooking into Vault economy...");
        if (!setupEconomy()) {
            logSkip("Vault not found \u2014 configure an alternative currency supplier");
        } else {
            logOk("Vault economy hooked");
        }

        logInfo("Setting up permissions...");
        if (setupPermissions()) {
            logOk("Permissions provider loaded");
        } else {
            logSkip("No permissions provider found \u2014 using default Bukkit permissions");
        }

        logInfo("Checking AdvancedGUI integration...");
        setupAdvancedGUI(config);

        logInfo("Checking PlaceholderAPI integration...");
        setupPlaceholderAPI();

        logInfo("Setting up Discord extension...");
        setupDiscordExtension(config);

        logInfo("Setting up sell-wands...");
        setupSellWands(config);

        logInfo("Setting up loan system...");
        setupLoans(config);

        logInfo("Preparing image storage...");
        createImagesFolder();
        logOk("Images folder ready");

        logInfo("Loading market data...");
        MarketManager.getInstance();
        logOk("Market manager initialized " + ANSI_GRAY + "(" + MarketManager.getInstance().getAllParentItems().size() + " items)" + ANSI_RESET);

        logInfo("Registering commands and listeners...");
        setupCommandsAndListeners(config);
        logOk("Commands and listeners registered");

        Bukkit.getPluginManager().registerEvents(new EventsManager(), this);
        ItemChartReduced.load();
        logOk("Events manager and chart data ready");

        logInfo("Starting web server...");
        setupWebServer(config);

        // Exchange subsystem (MySQL must be configured)
        if (config.getDatabaseType() == me.bounser.nascraft.database.DatabaseType.MYSQL) {
            logInfo("Initializing stock exchange subsystem " + ANSI_GRAY + "(async)" + ANSI_RESET + "...");
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                ExchangeManager.getInstance().init();
                logOk("Exchange subsystem ready");
            });
            logInfo("Registering exchange commands...");
            new ExchangeCommand();
            logOk("Exchange commands registered " + ANSI_GRAY + "(/exchange, /ex)" + ANSI_RESET);
        } else {
            logSkip("Stock exchange disabled \u2014 requires MySQL");
        }

        System.out.println();
        System.out.println(ANSI_GREEN + ANSI_BOLD + "  Nascraft v" + getDescription().getVersion() + " enabled successfully." + ANSI_RESET);
        System.out.println();
    }

    private void setupAdvancedGUI(Config config) {
        Plugin AGUI = Bukkit.getPluginManager().getPlugin("AdvancedGUI");

        if (AGUI == null || !AGUI.isEnabled()) {
            logSkip("AdvancedGUI not found \u2014 in-game charts will be unavailable");
            logSkip("  Install it at spigotmc.org/resources/83636/");
        } else {
            if (config.getCheckResources()) checkResources();
            LayoutModifier.getInstance();
            if (!AGUI.getDescription().getVersion().equals(AGUI_VERSION)) {
                logSkip("AdvancedGUI version mismatch " + ANSI_GRAY + "(expected " + AGUI_VERSION + ", found " + AGUI.getDescription().getVersion() + ")" + ANSI_RESET);
            } else {
                logOk("AdvancedGUI hooked " + ANSI_GRAY + "(v" + AGUI.getDescription().getVersion() + ")" + ANSI_RESET);
            }
        }
    }

    private void setupPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PAPIExpansion().register();
            logOk("PlaceholderAPI hooked \u2014 placeholders registered");
        } else {
            logSkip("PlaceholderAPI not found \u2014 placeholders unavailable");
        }
    }

    private void setupDiscordExtension(Config config) {
        if (config.getDiscordEnabled()) {
            logInfo("  Discord: registering commands...");

            if (Config.getInstance().getLinkingMethod().equals(LinkingMethod.NATIVE)
            && config.isCommandEnabled("link")) new LinkCommand();
            if (Config.getInstance().getOptionAlertEnabled()) {
                if (config.isCommandEnabled("alerts")) new AlertsCommand();
                if (config.isCommandEnabled("setalerts")) new SetAlertCommand();
            }
            if (config.isCommandEnabled("discord")) new DiscordCommand();

            new DiscordBot();
            logOk("Discord bot connected " + ANSI_GRAY + "(linking: " + Config.getInstance().getLinkingMethod() + ")" + ANSI_RESET);
        } else {
            logSkip("Discord integration disabled");
        }
    }

    private void setupSellWands(Config config) {
        if (config.getSellWandsEnabled()) {
            if (config.isCommandEnabled("givesellwand")) new GiveSellWandCommand();
            Bukkit.getPluginManager().registerEvents(new WandListener(), this);
            WandsManager.getInstance();
            logOk("Sell-wands enabled");
        } else {
            logSkip("Sell-wands disabled");
        }
    }

    private void setupLoans(Config config) {
        if (config.getLoansEnabled()) {
            DebtManager.getInstance();
            logOk("Loan system enabled");
        } else {
            logSkip("Loan system disabled");
        }
    }

    private void setupCommandsAndListeners(Config config) {
        if (config.isCommandEnabled("nascraft")) {
            new NascraftCommand();

            Bukkit.getPluginManager().registerEvents(new NascraftLogListener(), this);

            Bukkit.getPluginManager().registerEvents(new MarketEditorInvListener(), this);
            Bukkit.getPluginManager().registerEvents(new EditItemMenuListener(), this);
            Bukkit.getPluginManager().registerEvents(new CategoryEditorListener(), this);
        }

        if (config.isCommandEnabled("market")) {
            new MarketCommand();
            Bukkit.getPluginManager().registerEvents(new InventoryListener(), this);
        }

        if (config.isCommandEnabled("sellhand")) new SellHandCommand();

        if (config.isCommandEnabled("sell-menu")) {
            new SellInvCommand();
            Bukkit.getPluginManager().registerEvents(new SellInvListener(), this);
        }

        if (config.isCommandEnabled("sellall")) new SellAllCommand();

        if (config.isCommandEnabled("portfolio")) {
            new PortfolioCommand();
            Bukkit.getPluginManager().registerEvents(new PortfolioInventory(), this);
        }
    }

    private void setupWebServer(Config config) {
        if (config.getWebEnabled()) {

            if (config.isCommandEnabled("web")) {
                new WebCommand();
            }

            extractDefaultWebFiles();
            extractImage("images/logo.png");
            extractImage("images/logo-color.png");
            extractImage("images/fire.png");

            webServerManager = new WebServerManager(this, config.getWebPort());

            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                webServerManager.startServer();
                logOk("Web server started " + ANSI_GRAY + "(port " + config.getWebPort() + ")" + ANSI_RESET);
            });
        } else {
            logSkip("Web server disabled");
        }
    }

    @Override
    public void onDisable() {

        System.out.println();
        System.out.println(ANSI_YELLOW + ANSI_BOLD + "  Nascraft shutting down..." + ANSI_RESET);

        logInfo("Flushing market data to database...");
        DatabaseManager.get().getDatabase().disconnect();
        logOk("Database saved and connection closed");

        if (Config.getInstance().getDiscordEnabled() && DiscordBot.getInstance() != null) {
            logInfo("Shutting down Discord bot...");
            DiscordBot.getInstance().sendClosedMessage();
            DiscordBot.getInstance().getJDA().shutdown();
            logOk("Discord bot disconnected");
        }

        if (this.adventure != null) {
            this.adventure.close();
            this.adventure = null;
        }

        if (webServerManager != null && webServerManager.isRunning()) {
            logInfo("Stopping web server...");
            webServerManager.stopServer();
            logOk("Web server stopped");
        }

        ExchangeManager.getInstance().shutdown();

        System.out.println(ANSI_GRAY + "  Nascraft v" + getDescription().getVersion() + " disabled." + ANSI_RESET);
        System.out.println();
    }

    private void setupMetrics() {
        Metrics metrics = new Metrics(this, 18404);

        metrics.addCustomChart(new SimplePie("discord_bridge", () -> String.valueOf(Config.getInstance().getDiscordEnabled())));

        if (Config.getInstance().getDiscordEnabled())
            metrics.addCustomChart(new SimplePie("linking_method", () -> Config.getInstance().getLinkingMethod().toString()));

        metrics.addCustomChart(new SimplePie("used_with_advancedgui", () -> String.valueOf(Bukkit.getPluginManager().getPlugin("AdvancedGUI") != null)));
        metrics.addCustomChart(new SingleLineChart("operations_per_hour", () -> MarketManager.getInstance().getOperationsLastHour()));
        metrics.addCustomChart(new AdvancedPie("players_linked_with_discord", new Callable<Map<String, Integer>>() {
            @Override
            public Map<String, Integer> call() {
                Map<String, Integer> valueMap = new HashMap<>();

                if (!Config.getInstance().getDiscordEnabled()) return valueMap;

                int linkedPlayers = getLinkedPlayers();
                valueMap.put("Linked", linkedPlayers);
                valueMap.put("Not linked", Bukkit.getOnlinePlayers().size() - linkedPlayers);
                return valueMap;
            }

            private int getLinkedPlayers() {
                int counter = 0;
                for (Player player : Bukkit.getOnlinePlayers())
                    if (LinkManager.getInstance().getUserDiscordID(player.getUniqueId()) != null) counter++;

                return counter;
            }
        }));
    }

    public static Economy getEconomy() { return economy; }

    public static Permission getPermissions() { return perms; }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) { return false; }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) { return false; }

        economy = rsp.getProvider();
        return economy != null;
    }

    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = rsp.getProvider();
        return perms != null;
    }

    public @NonNull BukkitAudiences adventure() {
        if (this.adventure == null) {
            throw new IllegalStateException("Tried to access Adventure when the plugin was disabled!");
        }
        return this.adventure;
    }

    private void createImagesFolder() {

        File imagesFolder = new File(getDataFolder(), "images");

        if (!imagesFolder.exists()) {
            boolean success = imagesFolder.mkdirs();
            if (!success) getLogger().warning("Failed to create images folder.");
        }
    }

    private void checkResources() {

        getLogger().info("Checking required layouts... ");
        getLogger().info("If you want to disable this procedure, set auto_resources_injection to false in the config.yml file.");

        File fileToReplace = new File(getDataFolder().getParent() + "/AdvancedGUI/layout/Nascraft.json");

        if (!fileToReplace.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(getResource("Nascraft.json")));
                StringBuilder jsonContent = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonContent.append(line);
                }
                reader.close();

                FileUtils.writeStringToFile(fileToReplace, jsonContent.toString(), "UTF-8");
            } catch (IOException e) {
                e.printStackTrace();
            }
            getLogger().info("Layout Nascraft.json added.");

            LayoutManager.getInstance().shutdownSync();
            GuiWallManager.getInstance().shutdown();
            GuiItemManager.getInstance().shutdown();

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                AdvancedGUI.getInstance().readConfig();
                VersionMediator.reload();
                LayoutManager.getInstance().reload(layout -> getLogger().severe("§cFailed to load layout: " + layout + " §7(see console for details)"));
                Bukkit.getScheduler().runTask(AdvancedGUI.getInstance(), () -> {
                    GuiWallManager.getInstance().setup();
                    GuiItemManager.getInstance().setup();
                });
            });
        } else {
            getLogger().info("Layout (Nascraft.json) present!");
        }
    }

    private void extractDefaultWebFiles() {
        getLogger().info("Checking external web directory: " + new File(getDataFolder(), "web").getPath());

        String[] essentialFiles = {
                "web/index.html",
                "web/style.css",
                "web/script.js"
        };

        boolean copiedAny = false;
        for (String resourcePath : essentialFiles) {
            File targetFile = new File(getDataFolder(), resourcePath);
            try {
                if (!targetFile.exists()) {
                    saveResource(resourcePath, false);
                    getLogger().info("Copied default file: " + resourcePath);
                    copiedAny = true;
                }
            } catch (IllegalArgumentException e) {
                getLogger().log(Level.SEVERE, "Error extracting: " + resourcePath, e);
            }
        }

        if (!copiedAny) {
            getLogger().info("External web files are present.");
        } else {
            getLogger().info("Default web files copied to " + new File(getDataFolder(), "web").getPath());
        }

    }

    private void extractImage(String resourcePath) {
        File targetFile = new File(getDataFolder(), resourcePath);

        if (!targetFile.exists()) {
            try {
                saveResource(resourcePath, false);
            } catch (IllegalArgumentException e) {
                getLogger().log(Level.SEVERE, "Failed to extract image: Resource path '" + resourcePath + "' not found within the plugin JAR!", e);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "An unexpected error occurred while extracting image '" + resourcePath + "'", e);
            }
        }
    }


}
