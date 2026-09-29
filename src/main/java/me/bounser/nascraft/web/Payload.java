package me.bounser.nascraft.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

public record Payload(String contentType, byte[] raw, byte[] gzip, String etag) {
    private static final int GZIP_MIN = 512;

    public static Payload of(String contentType, byte[] raw) {
        byte[] gz = null;
        if (raw.length >= GZIP_MIN && compressible(contentType)) {
            byte[] c = gzip(raw);
            if (c.length < raw.length * 0.9) gz = c;
        }
        return new Payload(contentType, raw, gz, "\"" + hash(raw) + "\"");
    }

    public static Payload json(byte[] raw) { return of("application/json; charset=utf-8", raw); }

    public static Payload text(String contentType, String body) {
        return of(contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean compressible(String type) {
        return type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg");
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    static String hash(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
