package me.bounser.nascraft.web;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.regex.Pattern;

public final class WebSettings {
    public static final List<String> BUNDLED_LANGUAGES = List.of("en", "es", "zh_CN", "de", "fr", "pt_BR", "ru");
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public final boolean enabled;
    public final String bindAddress;
    public final int port;
    public final String publicUrl;
    public final boolean behindProxy;

    public final String title;
    public final String accent;
    public final String defaultMode;
    public final boolean lockMode;
    public final String defaultTheme;
    public final String defaultLanguage;
    public final List<String> languages;
    public final boolean customAssets;

    public final boolean pageMarket, pageEconomy, pageAnalytics, pageLeaderboard, tradesFeed;
    public final boolean showNames, showWealth;

    public final String loginCommand;
    public final int codeLength;
    public final int codeTtlMinutes;
    public final boolean bindDevice, bindIp;
    public final int sessionLifetimeDays;
    public final int idleTimeoutMinutes;
    public final int maxSessionsPerPlayer;
    public final boolean secureCookies;

    public final boolean tradingEnabled;
    public final int maxTradeAmount;
    public final boolean tradeRequiresOnline;

    public final int liveIntervalSeconds;
    public final int maxLiveClients;
    public final int marketCacheSeconds;
    public final int heavyCacheSeconds;
    public final int requestsPerMinute;
    public final int loginsPerMinute;

    private WebSettings(ConfigurationSection c) {
        enabled = c.getBoolean("enabled", false);
        bindAddress = c.getString("bind-address", "0.0.0.0");
        port = clamp(c.getInt("port", 8080), 1, 65535);
        publicUrl = stripSlash(c.getString("public-url", ""));
        behindProxy = c.getBoolean("behind-proxy", false);

        title = c.getString("title", "Nascraft Market");
        String a = c.getString("accent", "#6E56CF");
        accent = a != null && HEX.matcher(a).matches() ? a : "#6E56CF";
        defaultMode = oneOf(c.getString("default-mode", "regular"), "regular", "pro");
        lockMode = c.getBoolean("lock-mode", false);
        defaultTheme = oneOf(c.getString("default-theme", "system"), "system", "light", "dark");
        String dl = c.getString("default-language", "en");
        defaultLanguage = BUNDLED_LANGUAGES.contains(dl) ? dl : "en";
        List<String> langs = c.getStringList("languages").stream().filter(BUNDLED_LANGUAGES::contains).toList();
        languages = langs.isEmpty() ? BUNDLED_LANGUAGES : langs;
        customAssets = c.getBoolean("custom-assets", false);

        pageMarket = c.getBoolean("pages.market", true);
        pageEconomy = c.getBoolean("pages.economy", true);
        pageAnalytics = c.getBoolean("pages.analytics", true);
        pageLeaderboard = c.getBoolean("pages.leaderboard", true);
        tradesFeed = c.getBoolean("pages.trades-feed", true);
        showNames = c.getBoolean("privacy.show-player-names", true);
        showWealth = c.getBoolean("privacy.show-wealth-distribution", true);

        loginCommand = c.getString("login-command", "webcode");
        codeLength = clamp(c.getInt("login-code.length", 8), 6, 32);
        codeTtlMinutes = clamp(c.getInt("login-code.ttl-minutes", 5), 1, 60);
        bindDevice = c.getBoolean("session.bind-device", true);
        bindIp = c.getBoolean("session.bind-ip", false);
        sessionLifetimeDays = clamp(c.getInt("session.lifetime-days", 14), 1, 365);
        idleTimeoutMinutes = Math.max(0, c.getInt("session.idle-timeout-minutes", 0));
        maxSessionsPerPlayer = clamp(c.getInt("session.max-per-player", 5), 1, 100);
        secureCookies = c.getBoolean("session.secure-cookies", false);

        tradingEnabled = c.getBoolean("trading.enabled", true);
        maxTradeAmount = clamp(c.getInt("trading.max-amount", 2304), 1, 1_000_000);
        tradeRequiresOnline = c.getBoolean("trading.require-online", false);

        liveIntervalSeconds = clamp(c.getInt("performance.live-interval-seconds", 2), 1, 60);
        maxLiveClients = clamp(c.getInt("performance.max-live-clients", 200), 0, 100_000);
        marketCacheSeconds = clamp(c.getInt("performance.market-cache-seconds", 2), 0, 600);
        heavyCacheSeconds = clamp(c.getInt("performance.heavy-cache-seconds", 60), 0, 3600);
        requestsPerMinute = clamp(c.getInt("rate-limit.requests-per-minute", 600), 10, 1_000_000);
        loginsPerMinute = clamp(c.getInt("rate-limit.logins-per-minute", 10), 1, 1000);
    }

    public static WebSettings from(ConfigurationSection section) {
        return new WebSettings(section == null ? new YamlConfiguration() : section);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static String oneOf(String v, String... allowed) {
        if (v != null) for (String a : allowed) if (a.equalsIgnoreCase(v)) return a;
        return allowed[0];
    }

    private static String stripSlash(String url) {
        if (url == null) return "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
}
