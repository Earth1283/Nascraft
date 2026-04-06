package me.bounser.nascraft.web;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.managers.MoneyManager;
import me.bounser.nascraft.market.MarketManager;
import me.bounser.nascraft.portfolio.PortfoliosManager;
import me.bounser.nascraft.web.controllers.AuthController;
import me.bounser.nascraft.web.controllers.MarketController;
import me.bounser.nascraft.web.controllers.PlayerController;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class WebServerManager {

    private final JavaPlugin plugin;
    private Javalin webServer;
    private final int port;
    private final String externalWebRootPath;
    private final Database database;
    private final MarketManager marketManager;
    private final PortfoliosManager portfoliosManager;
    private final MoneyManager moneyManager;
    private final HttpClient httpClient;
    private final Gson gson;
    private final CodesManager codesManager;

    public WebServerManager(JavaPlugin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
        this.externalWebRootPath = plugin.getDataFolder() + "/web";
        this.database = DatabaseManager.get().getDatabase();
        this.marketManager = MarketManager.getInstance();
        this.portfoliosManager = PortfoliosManager.getInstance();
        this.moneyManager = MoneyManager.getInstance();
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        this.gson = new Gson();
        this.codesManager = CodesManager.getInstance();
    }

    public void startServer() {
        if (webServer != null) {
            plugin.getLogger().warning("Web server is already running!");
            return;
        }

        File webDir = new File(externalWebRootPath);
        if (!webDir.exists() || !webDir.isDirectory()) {
            plugin.getLogger().severe("External web directory not found: " + externalWebRootPath);
            return;
        }

        try {
            webServer = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = externalWebRootPath;
                    staticFiles.location = Location.EXTERNAL;
                    staticFiles.precompress = false;
                });
            }).start(port);

            plugin.getLogger().info("Web server started successfully on port " + port);

            ConcurrentHashMap<String, Long> rateLimits = new ConcurrentHashMap<>();
            webServer.before(ctx -> {
                if (!ctx.path().startsWith("/api/")) return;
                String ip = ctx.req().getRemoteAddr();
                long now = System.currentTimeMillis();
                long lastReq = rateLimits.getOrDefault(ip, 0L);
                if (now - lastReq < 200) { // Max 5 requests per second
                    ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(new StatusResponse("Too many requests."));
                    ctx.skipRemainingHandlers();
                } else {
                    rateLimits.put(ip, now);
                }
            });

            MarketController.register(webServer, marketManager);
            AuthController.register(webServer, plugin, database, httpClient, gson, codesManager);
            PlayerController.register(webServer, plugin, marketManager, portfoliosManager, moneyManager);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start web server: " + e.getMessage(), e);
            webServer = null;
        }
    }

    public void stopServer() {
        if (webServer != null) {
            try {
                webServer.stop();
                plugin.getLogger().info("Web server stopped.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error stopping web server: " + e.getMessage(), e);
            } finally {
                webServer = null;
            }
        }
    }

    public boolean isRunning() {
        return webServer != null;
    }

    // --- Static Inner Classes for DTOs ---

    public static class StatusResponse {
        private final String message;
        public StatusResponse(String message) { this.message = message; }
        public String getMessage() { return message; }
    }

    public static class LoginRequest {
        private String username;
        private String password;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginSuccessResponse {
        private final boolean success;
        private final String username;
        public LoginSuccessResponse(boolean success, String username) {
            this.success = success;
            this.username = username;
        }
        public boolean isSuccess() { return success; }
        public String getUsername() { return username; }
    }

    public static class DiscordClientIdResponse {
        private final String clientId;
        public DiscordClientIdResponse(String clientId) {
            this.clientId = clientId;
        }
        public String getClientId() { return clientId; }
    }

    public static class AuthStatusResponse {
        private final String authStatus;
        private final String minecraftUsername;
        private final String discordUserId;
        private final String discordNickname;
        private final String discordLinkStatus;
        private final String minecraftLinkingCode;
        private final String message;

        public AuthStatusResponse(String authStatus, String mcUsername, String dUserId, String dNickname, String dLinkStatus, String mcLinkCode, String msg) {
            this.authStatus = authStatus; this.minecraftUsername = mcUsername; this.discordUserId = dUserId;
            this.discordNickname = dNickname; this.discordLinkStatus = dLinkStatus; this.minecraftLinkingCode = mcLinkCode; this.message = msg;
        }
        public String getAuthStatus() { return authStatus; }
        public String getMinecraftUsername() { return minecraftUsername; }
        public String getDiscordUserId() { return discordUserId; }
        public String getDiscordNickname() { return discordNickname; }
        public String getDiscordLinkStatus() { return discordLinkStatus; }
        public String getMinecraftLinkingCode() { return minecraftLinkingCode; }
        public String getMessage() { return message; }
    }

    public static class SimplifiedTradeRequest {
        private String identifier;
        private int quantity;
        private String type;
        public String getIdentifier() { return identifier; }
        public int getQuantity() { return quantity; }
        public String getType() { return type; }
    }

    public static class TradeResponse {
        private final boolean success;
        private final String message;
        private final Double value;

        public TradeResponse(boolean success, String message, Double value) {
            this.success = success; this.message = message; this.value = value;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Double getValue() { return value; }
    }

    public static class BuySlotResponse {
        private final boolean success;
        private final String message;
        private final Double value;

        public BuySlotResponse(boolean success, String message, Double value) {
            this.success = success; this.message = message; this.value = value;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Double getValue() { return value; }
    }
}