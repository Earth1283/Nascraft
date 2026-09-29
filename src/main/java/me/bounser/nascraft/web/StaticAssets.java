package me.bounser.nascraft.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class StaticAssets {
    private static final String ROOT = "web";

    private final Map<String, Payload> files = new HashMap<>();

    public StaticAssets(Logger log, File overrideDir, Map<String, String> templateVars) {
        loadBundled(log);
        if (overrideDir != null && overrideDir.isDirectory()) loadDirectory(overrideDir.toPath());

        Payload index = files.get("/index.html");
        if (index != null) {
            String html = new String(index.raw(), StandardCharsets.UTF_8);
            for (var e : templateVars.entrySet()) html = html.replace("{{" + e.getKey() + "}}", escapeHtml(e.getValue()));
            files.put("/index.html", Payload.text("text/html; charset=utf-8", html));
        }
        log.info("Web: loaded " + files.size() + " static assets.");
    }

    public Payload get(String path) {
        if (path.equals("/")) return files.get("/index.html");
        Payload p = files.get(path);
        if (p != null) return p;
        if (path.lastIndexOf('.') > path.lastIndexOf('/')) return null;
        return files.get("/index.html");
    }

    public static boolean isShell(String path, Payload p) {
        return path.equals("/") || path.equals("/index.html") || p.contentType().startsWith("text/html");
    }

    private void loadBundled(Logger log) {
        URL marker = StaticAssets.class.getClassLoader().getResource(ROOT + "/index.html");
        if (marker == null) {
            log.warning("Web: bundled assets not found in the plugin jar.");
            return;
        }
        try {
            URI uri = marker.toURI();
            if ("jar".equals(uri.getScheme())) {
                FileSystem fs;
                boolean owned = true;
                try {
                    fs = FileSystems.newFileSystem(uri, Map.of());
                } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                    fs = FileSystems.getFileSystem(uri);
                    owned = false;
                }
                try {
                    loadDirectory(fs.getPath("/" + ROOT));
                } finally {
                    if (owned) fs.close();
                }
            } else {
                loadDirectory(Path.of(uri).getParent());
            }
        } catch (Exception e) {
            log.warning("Web: failed to load bundled assets: " + e.getMessage());
        }
    }

    private void loadDirectory(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = "/" + root.relativize(f).toString().replace('\\', '/');
                try (InputStream in = Files.newInputStream(f)) {
                    files.put(rel, Payload.of(contentType(rel), in.readAllBytes()));
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
