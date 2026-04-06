package me.bounser.nascraft.database.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.chart.cpi.CPIInstant;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.commands.*;
import me.bounser.nascraft.database.commands.resources.DayInfo;
import me.bounser.nascraft.database.commands.resources.NormalisedDate;
import me.bounser.nascraft.database.commands.resources.Trade;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.market.unit.Item;
import me.bounser.nascraft.market.unit.stats.Instant;
import me.bounser.nascraft.portfolio.Portfolio;
import me.bounser.nascraft.web.dto.PlayerStatsDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

public class MySQL implements Database {

    private final String HOST;
    private final int PORT;
    private final String DATABASE;
    private final String USERNAME;
    private final String PASSWORD;

    private HikariDataSource dataSource;

    public MySQL(String host, int port, String database, String username, String password) {
        this.HOST = host;
        this.PORT = port;
        this.DATABASE = database;
        this.USERNAME = username;
        this.PASSWORD = password;
    }

    @Override
    public void connect() {
        HikariConfig config = new HikariConfig();
        
        config.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(5000);

        dataSource = new HikariDataSource(config);

        createTables();
    }

    @Override
    public void disconnect() {
        saveEverything();
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    private void createTable(String tableName, String columns) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (" + columns + ");");
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("Error creating table " + tableName + ": " + e.getMessage());
        }
    }

    @Override
    public void createTables() {

        createTable("items",
                "identifier VARCHAR(255) PRIMARY KEY, " +
                        "lastprice DOUBLE, " +
                        "lowest DOUBLE, " +
                        "highest DOUBLE, " +
                        "stock DOUBLE DEFAULT 0, " +
                        "taxes DOUBLE");

        createTable("prices_day",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "day INT, " +
                        "date VARCHAR(255)," +
                        "identifier VARCHAR(255)," +
                        "price DOUBLE," +
                        "volume INT");

        createTable("prices_month",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "day INT NOT NULL, " +
                        "date VARCHAR(255) NOT NULL," +
                        "identifier VARCHAR(255) NOT NULL," +
                        "price DOUBLE NOT NULL," +
                        "volume INT NOT NULL");

        createTable("prices_history",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "day INT," +
                        "date VARCHAR(255) NOT NULL," +
                        "identifier VARCHAR(255)," +
                        "price DOUBLE," +
                        "volume INT");

        createTable("portfolios",
                "uuid VARCHAR(36) NOT NULL," +
                        "identifier VARCHAR(255)," +
                        "amount INT");

        createTable("portfolios_log",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "day INT," +
                        "identifier VARCHAR(255)," +
                        "amount INT," +
                        "contribution DOUBLE");

        createTable("portfolios_worth",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "day INT," +
                        "worth DOUBLE");

        createTable("capacities",
                "uuid VARCHAR(36) PRIMARY KEY," +
                        "capacity INT");

        createTable("discord_links",
                "userid VARCHAR(18) NOT NULL," +
                        "uuid VARCHAR(36) NOT NULL," +
                        "nickname VARCHAR(255) NOT NULL");

        createTable("trade_log",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "day INT NOT NULL," +
                        "date VARCHAR(255) NOT NULL," +
                        "identifier VARCHAR(255) NOT NULL," +
                        "amount INT NOT NULL," +
                        "value TEXT NOT NULL," +
                        "buy INT NOT NULL, " +
                        "discord INT NOT NULL");

        createTable("cpi",
                        "day INT NOT NULL," +
                        "date VARCHAR(255) NOT NULL," +
                        "value DOUBLE NOT NULL");

        createTable("alerts",
                "day INT NOT NULL," +
                        "userid VARCHAR(255) NOT NULL," +
                        "identifier VARCHAR(255) NOT NULL," +
                        "price DOUBLE NOT NULL");

        createTable("flows",
                "day INT PRIMARY KEY," +
                        "flow DOUBLE NOT NULL," +
                        "taxes DOUBLE NOT NULL," +
                        "operations INT NOT NULL");

        createTable("limit_orders",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "expiration VARCHAR(255) NOT NULL," +
                        "uuid VARCHAR(36) NOT NULL," +
                        "identifier VARCHAR(255) NOT NULL," +
                        "type INT NOT NULL," +
                        "price DOUBLE NOT NULL," +
                        "to_complete INT NOT NULL," +
                        "completed INT NOT NULL," +
                        "cost INT NOT NULL");

        createTable("loans",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "debt DOUBLE NOT NULL");

        createTable("interests",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "paid DOUBLE NOT NULL");

        createTable("user_names",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "name VARCHAR(255) NOT NULL");

        createTable("balances",
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "uuid VARCHAR(36) NOT NULL," +
                        "balance DOUBLE NOT NULL");

        createTable("money_supply",
                "day INT PRIMARY KEY, " +
                        "supply DOUBLE NOT NULL");

        createTable("web_credentials",
                "name VARCHAR(255) NOT NULL, " +
                        "pass TEXT NOT NULL");

        createTable("player_stats",
                "day INT NOT NULL, " +
                        "uuid VARCHAR(36) NOT NULL, " +
                        "balance DOUBLE NOT NULL," +
                        "portfolio DOUBLE NOT NULL," +
                        "debt DOUBLE NOT NULL");

        createTable("discord",
                "userid VARCHAR(18) NOT NULL," +
                        "uuid VARCHAR(36) NOT NULL," +
                        "nickname VARCHAR(255) NOT NULL");
    }

    @Override
    public void saveEverything() {
        if (!isConnected()) return;
        for (Item item : MarketManager.getInstance().getAllParentItems()) {
            try (Connection connection = dataSource.getConnection()) {
                ItemProperties.saveItem(connection, item);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger().warning(e.getMessage());
            }
        }
    }

    // Link:

    @Override
    public void saveLink(String userId, UUID uuid, String nickname) {
        try (Connection connection = dataSource.getConnection()) {
            DiscordLink.saveLink(connection, userId, uuid, nickname);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeLink(String userId) {
        try (Connection connection = dataSource.getConnection()) {
            DiscordLink.removeLink(connection, userId);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public UUID getUUID(String userId) {
        try (Connection connection = dataSource.getConnection()) {
            return DiscordLink.getUUID(connection, userId);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public String getNickname(String userId) {
        try (Connection connection = dataSource.getConnection()) {
            return DiscordLink.getNickname(connection, userId);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public String getUserId(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return DiscordLink.getUserId(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    // Prices:

    @Override
    public void saveDayPrice(Item item, Instant instant) {
        try (Connection connection = dataSource.getConnection()) {
            HistorialData.saveDayPrice(connection, item, instant);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void saveMonthPrice(Item item, Instant instant) {
        try (Connection connection = dataSource.getConnection()) {
            HistorialData.saveMonthPrice(connection, item, instant);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void saveHistoryPrices(Item item, Instant instant) {
        try (Connection connection = dataSource.getConnection()) {
            HistorialData.saveHistoryPrices(connection, item, instant);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public List<Instant> getDayPrices(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return HistorialData.getDayPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getMonthPrices(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return HistorialData.getMonthPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getYearPrices(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return HistorialData.getYearPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getAllPrices(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return HistorialData.getAllPrices(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public Double getPriceOfDay(String identifier, int day) {
        try (Connection connection = dataSource.getConnection()) {
            return HistorialData.getPriceOfDay(connection, identifier, day);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return 0.0;
        }
    }

    // Items:

    @Override
    public void saveItem(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            ItemProperties.saveItem(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void retrieveItem(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            ItemProperties.retrieveItem(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void retrieveItems() {
        try (Connection connection = dataSource.getConnection()) {
            ItemProperties.retrieveItems(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public float retrieveLastPrice(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return ItemProperties.retrieveLastPrice(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return 0;
        }
    }

    // Trades:

    @Override
    public void saveTrade(Trade trade) {
        try (Connection connection = dataSource.getConnection()) {
            TradesLog.saveTrade(connection, trade);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public List<Trade> retrieveTrades(UUID uuid, int offset, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return TradesLog.retrieveTrades(connection, uuid, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Trade> retrieveTrades(UUID uuid, Item item, int offset, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return TradesLog.retrieveTrades(connection, uuid, item, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Trade> retrieveTrades(Item item, int offset, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return TradesLog.retrieveTrades(connection, item, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Trade> retrieveTrades(int offset, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            return TradesLog.retrieveLastTrades(connection, offset, limit);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void purgeHistory() {
        try (Connection connection = dataSource.getConnection()) {
            TradesLog.purgeHistory(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    // Portfolios:

    @Override
    public void updateItemPortfolio(UUID uuid, Item item, int quantity) {
        try (Connection connection = dataSource.getConnection()) {
            Portfolios.updateItemPortfolio(connection, uuid, item, quantity);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeItemPortfolio(UUID uuid, Item item) {
        try (Connection connection = dataSource.getConnection()) {
            Portfolios.removeItemPortfolio(connection, uuid, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void clearPortfolio(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            Portfolios.clearPortfolio(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void updateCapacity(UUID uuid, int capacity) {
        try (Connection connection = dataSource.getConnection()) {
            Portfolios.updateCapacity(connection, uuid, capacity);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public LinkedHashMap<Item, Integer> retrievePortfolio(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return Portfolios.retrievePortfolio(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public int retrieveCapacity(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return Portfolios.retrieveCapacity(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return 0;
        }
    }

    // Debt:

    @Override
    public void increaseDebt(UUID uuid, Double debt) {
        try (Connection connection = dataSource.getConnection()) {
            Debt.increaseDebt(connection, uuid, debt);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void decreaseDebt(UUID uuid, Double debt) {
        try (Connection connection = dataSource.getConnection()) {
            Debt.decreaseDebt(connection, uuid, debt);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public double getDebt(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getDebt(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    @Override
    public HashMap<UUID, Double> getUUIDAndDebt() {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getUUIDAndDebt(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public void addInterestPaid(UUID uuid, Double interest) {
        try (Connection connection = dataSource.getConnection()) {
            Debt.addInterestPaid(connection, uuid, interest);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public HashMap<UUID, Double> getUUIDAndInterestsPaid() {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getUUIDAndInterestsPaid(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public double getInterestsPaid(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getInterestsPaid(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    @Override
    public double getAllOutstandingDebt() {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getAllOutstandingDebt(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    @Override
    public double getAllInterestsPaid() {
        try (Connection connection = dataSource.getConnection()) {
            return Debt.getAllInterestsPaid(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    // Worth:

    @Override
    public void saveOrUpdateWorth(UUID uuid, int day, double worth) {
        try (Connection connection = dataSource.getConnection()) {
            PortfoliosWorth.saveOrUpdateWorth(connection, uuid, day, worth);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void saveOrUpdateWorthToday(UUID uuid, double worth) {
        try (Connection connection = dataSource.getConnection()) {
            PortfoliosWorth.saveOrUpdateWorthToday(connection, uuid, worth);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public HashMap<UUID, Portfolio> getTopWorth(int n) {
        try (Connection connection = dataSource.getConnection()) {
            return PortfoliosWorth.getTopWorth(connection, n);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public double getLatestWorth(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return PortfoliosWorth.getLatestWorth(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    // Stats/Logs:

    @Override
    public void logContribution(UUID uuid, Item item, int amount) {
        try (Connection connection = dataSource.getConnection()) {
            PortfoliosLog.logContribution(connection, uuid, item, amount);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void logWithdraw(UUID uuid, Item item, int amount) {
        try (Connection connection = dataSource.getConnection()) {
            PortfoliosLog.logWithdraw(connection, uuid, item, amount);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public HashMap<Integer, Double> getContributionChangeEachDay(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return PortfoliosLog.getContributionChangeEachDay(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public HashMap<Integer, HashMap<String, Integer>> getCompositionEachDay(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return PortfoliosLog.getCompositionEachDay(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return null;
        }
    }

    @Override
    public int getFirstDay(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return PortfoliosLog.getFirstDay(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return NormalisedDate.getDays();
        }
    }

    // Statistics:

    @Override
    public void saveCPIValue(float indexValue) {
        try (Connection connection = dataSource.getConnection()) {
            Statistics.saveCPI(connection, indexValue);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public List<CPIInstant> getCPIHistory() {
        try (Connection connection = dataSource.getConnection()) {
            return Statistics.getAllCPI(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<Instant> getPriceAgainstCPI(Item item) {
        try (Connection connection = dataSource.getConnection()) {
            return Statistics.getPriceAgainstCPI(connection, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void addTransaction(double newFlow, double effectiveTaxes) {
        try (Connection connection = dataSource.getConnection()) {
            Statistics.addTransaction(connection, newFlow, effectiveTaxes);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("Error while trying to log a transaction: " + e.getMessage());
        }
    }

    @Override
    public List<DayInfo> getDayInfos() {
        try (Connection connection = dataSource.getConnection()) {
            return Statistics.getDayInfos(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public double getAllTaxesCollected() {
        try (Connection connection = dataSource.getConnection()) {
            return Statistics.getAllTaxesCollected(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return 0;
    }

    // Alerts:

    @Override
    public void addAlert(String userid, Item item, double price) {
        try (Connection connection = dataSource.getConnection()) {
            Alerts.addAlert(connection, userid, item, price);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeAlert(String userid, Item item) {
        try (Connection connection = dataSource.getConnection()) {
            Alerts.removeAlert(connection, userid, item);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void retrieveAlerts() {
        try (Connection connection = dataSource.getConnection()) {
            Alerts.retrieveAlerts(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeAllAlerts(String userid) {
        try (Connection connection = dataSource.getConnection()) {
            Alerts.removeAllAlerts(connection, userid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void purgeAlerts() {
        try (Connection connection = dataSource.getConnection()) {
            Alerts.purgeAlerts(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    // Limit Orders:

    @Override
    public void addLimitOrder(UUID uuid, LocalDateTime expiration, Item item, int type, double price, int amount) {
        try (Connection connection = dataSource.getConnection()) {
            LimitOrders.addLimitOrder(connection, uuid, expiration, item, type, price, amount);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void updateLimitOrder(UUID uuid, Item item, int completed, double cost) {
        try (Connection connection = dataSource.getConnection()) {
            LimitOrders.updateLimitOrder(connection, uuid, item, completed, cost);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeLimitOrder(String uuid, String identifier) {
        try (Connection connection = dataSource.getConnection()) {
            LimitOrders.removeLimitOrder(connection, uuid, identifier);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void retrieveLimitOrders() {
        try (Connection connection = dataSource.getConnection()) {
            LimitOrders.retrieveLimitOrders(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    // Names:

    @Override
    public String getNameByUUID(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return UserNames.getNameByUUID(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return " ";
    }

    @Override
    public String getUUIDbyName(String name) {
        try (Connection connection = dataSource.getConnection()) {
            return UserNames.getUUIDbyName(connection, name);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return " ";
    }

    @Override
    public void saveOrUpdateName(UUID uuid, String name) {
        try (Connection connection = dataSource.getConnection()) {
            UserNames.saveOrUpdateNick(connection, uuid, name);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    // Balances:

    @Override
    public void updateBalance(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            Balances.updateBalance(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public Map<Integer, Double> getMoneySupplyHistory() {
        try (Connection connection = dataSource.getConnection()) {
            return Balances.getMoneySupplyHistory(connection);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    // Credentials:

    @Override
    public void storeCredentials(String userName, String hash) {
        try (Connection connection = dataSource.getConnection()) {
            Credentials.saveCredentials(connection, userName, hash);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public String retrieveHash(String userName) {
        try (Connection connection = dataSource.getConnection()) {
            return Credentials.getHashFromUserName(connection, userName);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public void clearUserCredentials(String userName) {
        try (Connection connection = dataSource.getConnection()) {
            Credentials.clearUserCredentials(connection, userName);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    // Player Stats:

    @Override
    public void saveOrUpdatePlayerStats(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            PlayerStats.saveOrUpdatePlayerStats(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public List<PlayerStatsDTO> getAllPlayerStats(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return PlayerStats.getAllPlayerStats(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    // Discord:

    @Override
    public void saveDiscordLink(UUID uuid, String userid, String nickname) {
        try (Connection connection = dataSource.getConnection()) {
            Discord.saveDiscordLink(connection, uuid, userid, nickname);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public void removeDiscordLink(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            Discord.removeLink(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
    }

    @Override
    public String getDiscordUserId(UUID uuid) {
        try (Connection connection = dataSource.getConnection()) {
            return Discord.getDiscordUserId(connection, uuid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public UUID getUUIDFromUserid(String userid) {
        try (Connection connection = dataSource.getConnection()) {
            return Discord.getUUIDFromUserid(connection, userid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }

    @Override
    public String getNicknameFromUserId(String userid) {
        try (Connection connection = dataSource.getConnection()) {
            return Discord.getNicknameFromUserId(connection, userid);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning(e.getMessage());
        }
        return null;
    }
}
