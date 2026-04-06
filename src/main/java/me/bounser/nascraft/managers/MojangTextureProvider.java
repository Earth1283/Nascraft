package me.bounser.nascraft.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.bounser.nascraft.Nascraft;
import org.bukkit.scheduler.BukkitRunnable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class MojangTextureProvider {

    private static MojangTextureProvider instance;

    private static final String TARGET_VERSION = "1.21.4";
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCE_CDN_BASE = "https://resources.download.minecraft.net/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final File textureCache;
    private volatile Map<String, String> assetIndex; // texture path -> hash
    private volatile boolean loading = false;

    private MojangTextureProvider() {
        textureCache = new File(Nascraft.getInstance().getDataFolder(), "texture-cache");
        textureCache.mkdirs();
        initAsync();
    }

    public static MojangTextureProvider getInstance() {
        return instance == null ? instance = new MojangTextureProvider() : instance;
    }

    private void initAsync() {
        if (loading) return;
        loading = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                loadAssetIndex();
            }
        }.runTaskAsynchronously(Nascraft.getInstance());
    }

    private void loadAssetIndex() {
        File indexCache = new File(textureCache, "asset-index.json");
        String indexJson = null;

        if (indexCache.exists()) {
            try {
                indexJson = new String(Files.readAllBytes(indexCache.toPath()));
            } catch (IOException ignored) {}
        }

        if (indexJson == null) {
            indexJson = fetchAssetIndex();
            if (indexJson != null) {
                try {
                    Files.write(indexCache.toPath(), indexJson.getBytes());
                } catch (IOException ignored) {}
            }
        }

        if (indexJson != null) {
            parseAssetIndex(indexJson);
            Nascraft.getInstance().getLogger().info("Loaded " + assetIndex.size() + " Mojang texture mappings.");
        } else {
            Nascraft.getInstance().getLogger().warning("Could not load Mojang asset index — texture fallback disabled.");
        }
    }

    private String fetchAssetIndex() {
        try {
            String manifestJson = httpGet(VERSION_MANIFEST_URL);
            if (manifestJson == null) return null;

            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            String versionUrl = null;
            for (JsonElement el : manifest.getAsJsonArray("versions")) {
                JsonObject v = el.getAsJsonObject();
                if (TARGET_VERSION.equals(v.get("id").getAsString())) {
                    versionUrl = v.get("url").getAsString();
                    break;
                }
            }
            if (versionUrl == null) {
                Nascraft.getInstance().getLogger().warning("Minecraft version " + TARGET_VERSION + " not found in manifest.");
                return null;
            }

            String versionJson = httpGet(versionUrl);
            if (versionJson == null) return null;

            JsonObject versionObj = JsonParser.parseString(versionJson).getAsJsonObject();
            String assetIndexUrl = versionObj.getAsJsonObject("assetIndex").get("url").getAsString();

            return httpGet(assetIndexUrl);
        } catch (Exception e) {
            Nascraft.getInstance().getLogger().warning("Failed to fetch Mojang asset index: " + e.getMessage());
            return null;
        }
    }

    private void parseAssetIndex(String json) {
        Map<String, String> index = new HashMap<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject objects = root.getAsJsonObject("objects");
            for (String key : objects.keySet()) {
                if (key.startsWith("minecraft/textures/item/") || key.startsWith("minecraft/textures/block/")) {
                    String hash = objects.getAsJsonObject(key).get("hash").getAsString();
                    // Strip leading "minecraft/textures/" so lookup key is e.g. "item/stone.png"
                    index.put(key.substring("minecraft/textures/".length()), hash);
                }
            }
        } catch (Exception e) {
            Nascraft.getInstance().getLogger().warning("Failed to parse Mojang asset index: " + e.getMessage());
        }
        this.assetIndex = index;
    }

    /**
     * Attempt to download a Minecraft item/block texture by material name (e.g. "stone", "diamond_sword").
     * Returns null if the texture cannot be resolved.
     */
    public BufferedImage getTexture(String materialName) {
        if (assetIndex == null) return null;

        String name = materialName.toLowerCase();

        // Try item texture first, then block texture
        String hash = assetIndex.get("item/" + name + ".png");
        if (hash == null) hash = assetIndex.get("block/" + name + ".png");
        if (hash == null) return null;

        return fetchTexture(hash, name);
    }

    private BufferedImage fetchTexture(String hash, String name) {
        File cached = new File(textureCache, name + ".png");
        if (cached.exists()) {
            try (InputStream is = Files.newInputStream(cached.toPath())) {
                return ImageIO.read(is);
            } catch (IOException ignored) {}
        }

        String url = RESOURCE_CDN_BASE + hash.substring(0, 2) + "/" + hash;
        try {
            byte[] bytes = httpGetBytes(url);
            if (bytes == null) return null;
            Files.write(cached.toPath(), bytes);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Nascraft.getInstance().getLogger().warning("Failed to download texture '" + name + "': " + e.getMessage());
            return null;
        }
    }

    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] httpGetBytes(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
