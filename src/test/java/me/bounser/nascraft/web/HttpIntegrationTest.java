package me.bounser.nascraft.web;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class HttpIntegrationTest {
    private HttpServer server;
    private HttpClient client;
    private String base;
    private final Payload doc = Payload.json(("{\"data\":\"" + "x".repeat(4000) + "\"}").getBytes());

    @BeforeEach
    void start() throws Exception {
        WebSettings settings = WebSettings.from(new YamlConfiguration());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Http h = new Http(ex, settings);
            if (h.method.equals("POST")) {
                if (!h.sameOriginWrite()) h.error(403, "forbidden");
                else h.json(200, ("{\"body\":" + h.body().length() + "}").getBytes());
            } else {
                h.send(200, doc, "no-cache");
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    @DisplayName("gzip when accepted, identity otherwise, 304 on matching ETag")
    void compressionAndEtag() throws Exception {
        HttpResponse<byte[]> gz = client.send(HttpRequest.newBuilder(URI.create(base + "/x"))
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("gzip", gz.headers().firstValue("Content-Encoding").orElse(null));
        assertArrayEquals(doc.gzip(), gz.body());

        HttpResponse<byte[]> plain = client.send(HttpRequest.newBuilder(URI.create(base + "/x")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(doc.raw(), plain.body());
        assertEquals("nosniff", plain.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertTrue(plain.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));

        HttpResponse<Void> cached = client.send(HttpRequest.newBuilder(URI.create(base + "/x"))
                .header("If-None-Match", doc.etag()).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(304, cached.statusCode());
    }

    @Test
    @DisplayName("Writes need the custom header and a same-origin Origin")
    void csrfGuard() throws Exception {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString("{}");
        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/w")).POST(body).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        assertEquals(403, client.send(HttpRequest.newBuilder(URI.create(base + "/w")).POST(body)
                .header("X-Requested-With", "nascraft").header("Origin", "https://evil.example").build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create(base + "/w")).POST(body)
                .header("X-Requested-With", "nascraft").header("Origin", base).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertEquals("{\"body\":2}", ok.body());
    }
}
