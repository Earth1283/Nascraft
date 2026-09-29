package me.bounser.nascraft.web;

import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Thin fluent wrapper over Gson's streaming writer. Non-finite numbers become null. */
public final class JsonOut {

    @FunctionalInterface
    public interface Body { void write(JsonOut j) throws IOException; }

    private final JsonWriter w;

    private JsonOut(JsonWriter w) { this.w = w; }

    public static byte[] build(Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.setSerializeNulls(true);
            body.write(new JsonOut(w));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public JsonOut obj() throws IOException { w.beginObject(); return this; }
    public JsonOut end() throws IOException { w.endObject(); return this; }
    public JsonOut arr() throws IOException { w.beginArray(); return this; }
    public JsonOut endArr() throws IOException { w.endArray(); return this; }
    public JsonOut name(String n) throws IOException { w.name(n); return this; }

    public JsonOut val(String v) throws IOException { w.value(v); return this; }
    public JsonOut val(boolean v) throws IOException { w.value(v); return this; }
    public JsonOut val(long v) throws IOException { w.value(v); return this; }

    /** Numbers are rounded to 6 significant decimals to keep payloads small. */
    public JsonOut val(double v) throws IOException {
        if (!Double.isFinite(v)) w.nullValue();
        else if (v == Math.rint(v) && Math.abs(v) < 1e15) w.value((long) v);
        else w.value(round(v));
        return this;
    }

    public JsonOut nul() throws IOException { w.nullValue(); return this; }

    public JsonOut f(String n, String v) throws IOException { return name(n).val(v); }
    public JsonOut f(String n, boolean v) throws IOException { return name(n).val(v); }
    public JsonOut f(String n, long v) throws IOException { return name(n).val(v); }
    public JsonOut f(String n, double v) throws IOException { return name(n).val(v); }

    static double round(double v) {
        double a = Math.abs(v);
        if (a >= 1e6) return Math.rint(v * 100) / 100;
        if (a >= 1) return Math.rint(v * 1e6) / 1e6;
        if (a == 0) return 0;
        // Keep ~6 significant digits for small magnitudes (rates, inflation).
        double scale = Math.pow(10, 6 - (int) Math.floor(Math.log10(a)) - 1);
        return Math.rint(v * scale) / scale;
    }
}
