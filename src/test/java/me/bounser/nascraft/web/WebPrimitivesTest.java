package me.bounser.nascraft.web;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class WebPrimitivesTest {
    @Test
    @DisplayName("Large text payloads get a valid gzip copy; small ones don't")
    void payloadGzip() throws Exception {
        String big = "{\"x\":\"" + "abc".repeat(1000) + "\"}";
        Payload p = Payload.json(big.getBytes(StandardCharsets.UTF_8));
        assertNotNull(p.gzip());
        assertTrue(p.gzip().length < p.raw().length);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(p.gzip()))) {
            assertEquals(big, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(Payload.json("{}".getBytes()).gzip());
        assertNull(Payload.of("image/png", new byte[4096]).gzip());
        assertEquals(p.etag(), Payload.json(big.getBytes(StandardCharsets.UTF_8)).etag());
    }

    @Test
    @DisplayName("Cache rebuilds once per TTL even under concurrent requests")
    void cacheSingleFlight() throws Exception {
        TtlCache cache = new TtlCache(10);
        AtomicInteger builds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 64; i++) pool.submit(() -> {
            go.await();
            return cache.get("k", 60_000, () -> {
                builds.incrementAndGet();
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                return Payload.json("1".getBytes());
            });
        });
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, builds.get());

        assertEquals("1", new String(cache.get("k", 0, () -> Payload.json("2".getBytes())).raw()));

        assertEquals("z1", new String(cache.get("z", 0, () -> Payload.json("z1".getBytes())).raw()));
        assertEquals("z2", new String(cache.get("z", 0, () -> Payload.json("z2".getBytes())).raw()));

        cache.invalidate("");
        assertEquals("3", new String(cache.get("k", 60_000, () -> Payload.json("3".getBytes())).raw()));
    }

    @Test
    void rateLimiterBurstThenRefuse() {
        RateLimiter r = new RateLimiter(10);
        for (int i = 0; i < 10; i++) assertTrue(r.tryAcquire("ip"));
        assertFalse(r.tryAcquire("ip"));
        assertTrue(r.tryAcquire("other"));
    }

    @Test
    void jsonNumbersAndNulls() {
        String s = new String(JsonOut.build(j -> j.obj()
                .f("int", 3.0).f("nan", Double.NaN).f("inf", Double.POSITIVE_INFINITY)
                .f("small", 0.000123456789).f("big", 12345678.12345).f("str", "a\"b").end()), StandardCharsets.UTF_8);
        assertEquals("{\"int\":3,\"nan\":null,\"inf\":null,\"small\":1.23457E-4,\"big\":1.234567812E7,\"str\":\"a\\\"b\"}", s);
    }

    @Test
    void queryParsing() {
        var q = Http.parseQuery("a=1&b=hello%20world&c&a=2");
        assertEquals("1", q.get("a"));
        assertEquals("hello world", q.get("b"));
        assertEquals("", q.get("c"));
        assertTrue(Http.parseQuery(null).isEmpty());
    }

    @Test
    @DisplayName("Settings clamp and validate user input")
    void settingsValidation() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString("""
                port: 99999
                accent: "red"
                default-mode: PRO
                default-theme: purple
                default-language: "../x"
                languages: [es, klingon, ru, pl, "../etc"]
                public-url: "https://market.example.net///"
                login-code:
                  length: 2
                """);
        WebSettings s = WebSettings.from(c);
        assertEquals(65535, s.port);
        assertEquals("#6E56CF", s.accent);
        assertEquals("pro", s.defaultMode);
        assertEquals("system", s.defaultTheme);
        assertEquals("en", s.defaultLanguage);
        assertEquals(java.util.List.of("es", "ru", "pl"), s.languages);
        assertEquals("https://market.example.net", s.publicUrl);
        assertEquals(6, s.codeLength);
        assertFalse(s.enabled);
        assertEquals(WebSettings.BUNDLED_LANGUAGES, WebSettings.from(null).languages);
    }

    @Test
    void contentTypes() {
        assertEquals("text/javascript; charset=utf-8", StaticAssets.contentType("/js/app.js"));
        assertEquals("font/woff2", StaticAssets.contentType("/fonts/x.woff2"));
        assertEquals("&lt;b&gt; &amp; &quot;", StaticAssets.escapeHtml("<b> & \""));
    }

    @Test
    void sseFrameFormat() {
        assertEquals("event: prices\ndata: {\"a\":1}\n\n",
                new String(LiveHub.frame("prices", "{\"a\":1}".getBytes()), StandardCharsets.UTF_8));
    }
}
