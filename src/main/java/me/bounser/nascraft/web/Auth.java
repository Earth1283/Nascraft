package me.bounser.nascraft.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Auth {
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    public record Code(UUID uuid, String name, long expires) {}

    public static final class Session {
        public final String tokenHash;
        public final UUID uuid;
        public final String name;
        public final long created;
        public final long expires;
        public final String device;
        public final String ip;
        volatile long lastSeen;

        Session(String tokenHash, UUID uuid, String name, long created, long expires, String device, String ip) {
            this.tokenHash = tokenHash;
            this.uuid = uuid;
            this.name = name;
            this.created = created;
            this.expires = expires;
            this.device = device;
            this.ip = ip;
            this.lastSeen = created;
        }
    }

    public interface Store {
        void saved(Session s);
        void removed(String tokenHash);
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<UUID, String> codeByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile WebSettings settings;
    private final Store store;

    public Auth(WebSettings settings, Store store) {
        this.settings = settings;
        this.store = store;
    }

    public void setSettings(WebSettings settings) { this.settings = settings; }

    public String issueCode(UUID uuid, String name, long now) {
        WebSettings s = settings;
        StringBuilder sb = new StringBuilder(s.codeLength);
        for (int i = 0; i < s.codeLength; i++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        String code = sb.toString();
        String old = codeByPlayer.put(uuid, code);
        if (old != null) codes.remove(old);
        codes.put(code, new Code(uuid, name, now + s.codeTtlMinutes * 60_000L));
        return code;
    }

    public String redeem(String rawCode, String device, String ip, long now) {
        if (rawCode == null) return null;
        String code = rawCode.trim().toUpperCase().replace("-", "").replace(" ", "");
        Code c = codes.remove(code);
        if (c == null) return null;
        codeByPlayer.remove(c.uuid(), code);
        if (c.expires() < now) return null;

        WebSettings s = settings;
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Session session = new Session(sha256(token), c.uuid(), c.name(), now,
                now + s.sessionLifetimeDays * 86_400_000L, device, ip);
        sessions.put(session.tokenHash, session);
        store.saved(session);
        enforceLimit(c.uuid());
        return token;
    }

    public Session resolve(String token, String device, String ip, long now) {
        if (token == null || token.isEmpty()) return null;
        String hash = sha256(token);
        Session s = sessions.get(hash);
        if (s == null) return null;
        WebSettings cfg = settings;
        boolean idle = cfg.idleTimeoutMinutes > 0 && now - s.lastSeen > cfg.idleTimeoutMinutes * 60_000L;
        boolean wrongDevice = cfg.bindDevice && s.device != null && !s.device.equals(device);
        boolean wrongIp = cfg.bindIp && s.ip != null && !s.ip.equals(ip);
        if (s.expires < now || idle) {
            revoke(hash);
            return null;
        }
        if (wrongDevice || wrongIp) return null;
        s.lastSeen = now;
        return s;
    }

    public void logout(String token) {
        if (token != null) revoke(sha256(token));
    }

    public void load(Session s) { sessions.put(s.tokenHash, s); }

    public static Session restored(String tokenHash, UUID uuid, String name, long created, long expires, String device, String ip) {
        return new Session(tokenHash, uuid, name, created, expires, device, ip);
    }

    public void sweep(long now) {
        codes.entrySet().removeIf(e -> {
            if (e.getValue().expires() >= now) return false;
            codeByPlayer.remove(e.getValue().uuid(), e.getKey());
            return true;
        });
        for (Session s : List.copyOf(sessions.values())) if (s.expires < now) revoke(s.tokenHash);
    }

    public int activeSessions() { return sessions.size(); }

    private void revoke(String hash) {
        if (sessions.remove(hash) != null) store.removed(hash);
    }

    private void enforceLimit(UUID uuid) {
        List<Session> mine = new ArrayList<>();
        for (Session s : sessions.values()) if (s.uuid.equals(uuid)) mine.add(s);
        int excess = mine.size() - settings.maxSessionsPerPlayer;
        if (excess <= 0) return;
        mine.sort(Comparator.comparingLong(s -> s.created));
        for (int i = 0; i < excess; i++) revoke(mine.get(i).tokenHash);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String newDeviceId() {
        byte[] b = new byte[18];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
