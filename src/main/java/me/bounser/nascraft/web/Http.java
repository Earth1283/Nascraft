package me.bounser.nascraft.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** One HTTP request/response with the helpers every handler needs. */
public final class Http {

    public static final int MAX_BODY = 16 * 1024;

    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
            "script-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    public final HttpExchange ex;
    public final String method;
    public final String path;
    private final WebSettings settings;
    private Map<String, String> query;
    private Map<String, String> cookies;
    private boolean sent;

    Http(HttpExchange ex, WebSettings settings) {
        this.ex = ex;
        this.settings = settings;
        this.method = ex.getRequestMethod().toUpperCase();
        String p = ex.getRequestURI().getRawPath();
        this.path = p == null || p.isEmpty() ? "/" : URLDecoder.decode(p, StandardCharsets.UTF_8);
    }

    public String header(String name) { return ex.getRequestHeaders().getFirst(name); }

    /** Client IP, trusting X-Forwarded-For only when configured to sit behind a proxy. */
    public String ip() {
        if (settings.behindProxy) {
            String fwd = header("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
            String real = header("X-Real-IP");
            if (real != null && !real.isBlank()) return real.trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    public boolean secure() {
        if (settings.secureCookies) return true;
        return settings.behindProxy && "https".equalsIgnoreCase(header("X-Forwarded-Proto"));
    }

    public String query(String name) {
        if (query == null) query = parseQuery(ex.getRequestURI().getRawQuery());
        return query.get(name);
    }

    public int queryInt(String name, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(query(name)))); }
        catch (RuntimeException e) { return def; }
    }

    public String cookie(String name) {
        if (cookies == null) {
            cookies = new HashMap<>();
            for (String h : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
                for (String part : h.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0) cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                }
            }
        }
        return cookies.get(name);
    }

    public void setCookie(String name, String value, long maxAgeSeconds) {
        StringBuilder sb = new StringBuilder(name).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(maxAgeSeconds);
        if (secure()) sb.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    public String body() throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY) throw new IOException("body too large");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Mutating requests must carry our custom header (a cross-site form can't
     * add it) and, if the browser sends an Origin, it must be this host.
     */
    public boolean sameOriginWrite() {
        if (!"nascraft".equals(header("X-Requested-With"))) return false;
        String origin = header("Origin");
        if (origin == null) return true;
        String host = header("Host");
        if (host == null) return false;
        String o = origin.replaceFirst("^https?://", "");
        if (o.equalsIgnoreCase(host)) return true;
        String forwardedHost = settings.behindProxy ? header("X-Forwarded-Host") : null;
        return forwardedHost != null && o.equalsIgnoreCase(forwardedHost);
    }

    private void securityHeaders(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "same-origin");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", CSP);
    }

    /** Sends a prepared payload, honouring If-None-Match and Accept-Encoding. */
    public void send(int status, Payload p, String cacheControl) throws IOException {
        Headers h = ex.getResponseHeaders();
        securityHeaders(h);
        h.set("Content-Type", p.contentType());
        h.set("ETag", p.etag());
        h.set("Cache-Control", cacheControl);
        h.set("Vary", "Accept-Encoding");

        String inm = header("If-None-Match");
        if (status == 200 && inm != null && inm.contains(p.etag())) {
            sent = true;
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }

        byte[] body = p.raw();
        String ae = header("Accept-Encoding");
        if (p.gzip() != null && ae != null && ae.contains("gzip")) {
            h.set("Content-Encoding", "gzip");
            body = p.gzip();
        }
        write(status, body);
    }

    public void json(int status, byte[] json) throws IOException {
        send(status, Payload.json(json), "no-store");
    }

    public void error(int status, String code) throws IOException {
        json(status, JsonOut.build(j -> j.obj().f("error", code).end()));
    }

    public void noContent() throws IOException {
        securityHeaders(ex.getResponseHeaders());
        sent = true;
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private void write(int status, byte[] body) throws IOException {
        sent = true;
        if ("HEAD".equals(method)) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    public boolean sent() { return sent; }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            m.putIfAbsent(k, v);
        }
        return m;
    }
}
