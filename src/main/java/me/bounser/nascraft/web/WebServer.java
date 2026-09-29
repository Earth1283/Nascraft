package me.bounser.nascraft.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.config.Config;
import me.bounser.nascraft.database.BaseDatabase;
import me.bounser.nascraft.database.Database;
import me.bounser.nascraft.database.DatabaseManager;
import me.bounser.nascraft.database.commands.WebSessions;
import me.bounser.nascraft.economy.EconomyEngine;
import me.bounser.nascraft.scheduler.FoliaScheduler;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The web dashboard: JDK HTTP server on virtual threads, a small router, and
 * the lifecycle glue (sessions, sweeps, live stream).
 */
public final class WebServer {

    private static final String SESSION_COOKIE = "nc_session";
    private static final String DEVICE_COOKIE = "nc_device";

    private static volatile WebServer instance;

    public static WebServer get() { return instance; }

    private final Nascraft plugin;
    private final Logger log;
    private volatile WebSettings settings;
    private final HttpServer server;
    private final ExecutorService executor;
    private final StaticAssets assets;
    private final WebApi api;
    private final Auth auth;
    private final LiveHub live;
    private final RateLimiter limiter;
    private final RateLimiter loginLimiter;

    private WebServer(Nascraft plugin, WebSettings settings) throws IOException {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.settings = settings;
        this.api = new WebApi(plugin, settings);
        this.auth = new Auth(settings, sessionStore());
        this.limiter = new RateLimiter(settings.requestsPerMinute);
        this.loginLimiter = new RateLimiter(settings.loginsPerMinute);

        File override = settings.customAssets ? new File(plugin.getDataFolder(), "web") : null;
        this.assets = new StaticAssets(log, override, Map.of(
                "TITLE", settings.title,
                "ACCENT", settings.accent,
                "THEME", settings.defaultTheme,
                "LANG", settings.defaultLanguage.replace('_', '-')));

        this.live = new LiveHub(log, settings.liveIntervalSeconds, settings.maxLiveClients,
                api::liveFrame, WebServer::economyVersion, this::economyBytes);

        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server = HttpServer.create(new InetSocketAddress(settings.bindAddress, settings.port), 256);
        this.server.setExecutor(executor);
        this.server.createContext("/", ex -> {
            Http http = new Http(ex, this.settings);
            try {
                route(http);
            } catch (Exception e) {
                log.log(Level.FINE, "Web request failed: " + http.path, e);
                if (!http.sent()) {
                    try { http.error(500, "internal_error"); } catch (IOException ignored) { }
                }
            } finally {
                if (!http.path.equals("/api/live")) ex.close();
            }
        });
    }

    public static void start(Nascraft plugin) {
        WebSettings s = WebSettings.from(Config.getInstance().getSection("website"));
        if (!s.enabled) return;
        try {
            WebServer w = new WebServer(plugin, s);
            w.restoreSessions();
            w.server.start();
            instance = w;
            FoliaScheduler.runAsyncTimer(plugin, w::sweep, 20L * 60, 20L * 60);
            plugin.getLogger().info("Web dashboard listening on " + s.bindAddress + ":" + s.port);
        } catch (IOException e) {
            plugin.getLogger().severe("Web dashboard failed to start on port " + s.port + ": " + e.getMessage());
        }
    }

    public void stop() {
        instance = null;
        live.shutdown();
        server.stop(1);
        executor.shutdownNow();
    }

    public WebSettings settings() { return settings; }

    public Auth auth() { return auth; }

    public String publicUrl() {
        if (!settings.publicUrl.isEmpty()) return settings.publicUrl;
        String ip = org.bukkit.Bukkit.getIp();
        if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) ip = "localhost";
        return "http://" + ip + ":" + settings.port;
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private void route(Http h) throws Exception {
        String path = h.path;
        if (!path.startsWith("/api/")) {
            if (!h.method.equals("GET") && !h.method.equals("HEAD")) { h.error(405, "method_not_allowed"); return; }
            Payload p = assets.get(path);
            if (p == null) { h.error(404, "not_found"); return; }
            h.send(200, p, StaticAssets.isShell(path, p) ? "no-cache" : "public, max-age=3600");
            return;
        }

        if (!limiter.tryAcquire(h.ip())) { h.error(429, "rate_limited"); return; }
        WebSettings s = settings;

        if (h.method.equals("POST")) {
            if (!h.sameOriginWrite()) { h.error(403, "forbidden"); return; }
            switch (path) {
                case "/api/auth/login" -> login(h);
                case "/api/auth/logout" -> {
                    auth.logout(h.cookie(SESSION_COOKIE));
                    h.setCookie(SESSION_COOKIE, "", 0);
                    h.noContent();
                }
                case "/api/trade" -> trade(h);
                default -> h.error(404, "not_found");
            }
            return;
        }
        if (!h.method.equals("GET") && !h.method.equals("HEAD")) { h.error(405, "method_not_allowed"); return; }

        if (path.equals("/api/live")) {
            live.serve(h.ex, s.pageMarket ? api.liveFrame() : null);
            return;
        }

        Payload p = switch (path) {
            case "/api/config" -> api.config();
            case "/api/market" -> s.pageMarket ? api.market() : null;
            case "/api/trades" -> s.pageMarket ? api.recentTrades(h.queryInt("limit", 30, 1, 100)) : null;
            case "/api/economy" -> s.pageEconomy ? api.economy() : null;
            case "/api/economy/history" -> s.pageEconomy ? api.economyHistory(h.query("range")) : null;
            case "/api/analytics" -> s.pageAnalytics ? api.analytics() : null;
            case "/api/leaderboard" -> s.pageLeaderboard ? api.leaderboard() : null;
            case "/api/me" -> { me(h); yield null; }
            default -> itemRoute(h, path);
        };
        if (h.sent()) return;
        if (p == null) { h.error(404, "not_found"); return; }
        h.send(200, p, path.endsWith("/icon") ? "public, max-age=86400" : "no-cache");
    }

    private Payload itemRoute(Http h, String path) {
        if (!settings.pageMarket || !path.startsWith("/api/item/")) return null;
        String rest = path.substring("/api/item/".length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String sub = slash < 0 ? "" : rest.substring(slash + 1);
        return switch (sub) {
            case "" -> api.item(id);
            case "history" -> api.history(id, h.query("span"));
            case "icon" -> api.icon(id);
            default -> null;
        };
    }

    private String deviceHash(Http h) {
        String d = h.cookie(DEVICE_COOKIE);
        return d == null ? null : Auth.sha256(d + "|" + String.valueOf(h.header("User-Agent")));
    }

    private Auth.Session session(Http h) {
        return auth.resolve(h.cookie(SESSION_COOKIE), deviceHash(h), h.ip(), System.currentTimeMillis());
    }

    private void login(Http h) throws IOException {
        if (!loginLimiter.tryAcquire(h.ip())) { h.error(429, "rate_limited"); return; }
        String code;
        try {
            JsonObject o = JsonParser.parseString(h.body()).getAsJsonObject();
            code = o.has("code") ? o.get("code").getAsString() : null;
        } catch (RuntimeException | IOException e) {
            h.error(400, "bad_request");
            return;
        }
        String device = h.cookie(DEVICE_COOKIE);
        if (device == null) {
            device = auth.newDeviceId();
            h.setCookie(DEVICE_COOKIE, device, 400L * 86_400);
        }
        String deviceHash = Auth.sha256(device + "|" + String.valueOf(h.header("User-Agent")));
        String token = auth.redeem(code, deviceHash, h.ip(), System.currentTimeMillis());
        if (token == null) { h.error(401, "invalid_code"); return; }
        h.setCookie(SESSION_COOKIE, token, settings.sessionLifetimeDays * 86_400L);
        h.json(200, JsonOut.build(j -> j.obj().f("ok", true).end()));
    }

    private void me(Http h) throws Exception {
        Auth.Session s = session(h);
        if (s == null) { h.error(401, "not_signed_in"); return; }
        h.json(200, api.me(s));
    }

    private void trade(Http h) throws Exception {
        Auth.Session s = session(h);
        if (s == null) { h.error(401, "not_signed_in"); return; }
        String item, side;
        int amount;
        try {
            JsonObject o = JsonParser.parseString(h.body()).getAsJsonObject();
            item = o.get("item").getAsString();
            side = o.get("side").getAsString();
            amount = o.get("amount").getAsInt();
        } catch (RuntimeException | IOException e) {
            h.error(400, "bad_request");
            return;
        }
        if (!side.equals("buy") && !side.equals("sell")) { h.error(400, "bad_request"); return; }

        WebTrading.Result r = WebTrading.execute(plugin, settings, s.uuid, item, side.equals("buy"), amount);
        if (!r.ok()) { h.error(409, r.error()); return; }
        h.json(200, JsonOut.build(j -> j.obj().f("ok", true).f("worth", r.worth()).f("balance", r.balance())
                .f("holding", r.holding()).end()));
    }

    // ------------------------------------------------------------------
    // Live stream sources
    // ------------------------------------------------------------------

    private static String economyVersion() {
        EconomyEngine e = EconomyEngine.get();
        return e == null ? null : Long.toString(e.latest().timestamp());
    }

    private byte[] economyBytes() {
        if (!settings.pageEconomy) return null;
        Payload p = api.economy();
        return p == null ? null : p.raw();
    }

    // ------------------------------------------------------------------
    // Sessions persistence + housekeeping
    // ------------------------------------------------------------------

    private BaseDatabase db() {
        Database d = DatabaseManager.get().getDatabase();
        return d instanceof BaseDatabase b ? b : null;
    }

    private Auth.Store sessionStore() {
        return new Auth.Store() {
            @Override public void saved(Auth.Session s) {
                BaseDatabase db = db();
                if (db == null) return;
                FoliaScheduler.runAsync(plugin, () -> db.withConnection(c -> WebSessions.insert(c,
                        new WebSessions.Row(s.tokenHash, s.uuid.toString(), s.name, s.created, s.expires, s.device, s.ip))));
            }
            @Override public void removed(String tokenHash) {
                BaseDatabase db = db();
                if (db == null) return;
                FoliaScheduler.runAsync(plugin, () -> db.withConnection(c -> WebSessions.delete(c, tokenHash)));
            }
        };
    }

    private void restoreSessions() {
        BaseDatabase db = db();
        if (db == null) return;
        db.withConnection(WebSessions::createTable);
        long now = System.currentTimeMillis();
        for (WebSessions.Row r : db.queryConnection(c -> WebSessions.loadActive(c, now))) {
            try {
                auth.load(Auth.restored(r.tokenHash(), UUID.fromString(r.uuid()), r.name(), r.created(), r.expires(), r.device(), r.ip()));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        auth.sweep(now);
        limiter.sweep();
        loginLimiter.sweep();
        BaseDatabase db = db();
        if (db != null) db.withConnection(c -> WebSessions.deleteExpired(c, now));
    }
}
