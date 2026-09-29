package me.bounser.nascraft.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class LiveHub {
    private static final byte[] HEARTBEAT = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

    private final Set<BlockingQueue<byte[]>> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private final Logger log;
    private volatile int maxClients;

    public LiveHub(Logger log, int intervalSeconds, int maxClients, Supplier<byte[]> prices, Supplier<String> economyVersion,
                   Supplier<byte[]> economy) {
        this.log = log;
        this.maxClients = maxClients;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Nascraft-Web-Live");
            t.setDaemon(true);
            return t;
        });
        String[] lastEconomy = { null };
        scheduler.scheduleAtFixedRate(() -> {
            if (clients.isEmpty()) return;
            try {
                publish(frame("prices", prices.get()));
                String v = economyVersion.get();
                if (v != null && !v.equals(lastEconomy[0])) {
                    lastEconomy[0] = v;
                    byte[] eco = economy.get();
                    if (eco != null) publish(frame("economy", eco));
                }
            } catch (RuntimeException e) {
                log.fine("Live frame failed: " + e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public int clients() { return clients.size(); }

    public void setMaxClients(int maxClients) { this.maxClients = maxClients; }

    static byte[] frame(String event, byte[] json) {
        byte[] head = ("event: " + event + "\ndata: ").getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + json.length + 2];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(json, 0, out, head.length, json.length);
        out[out.length - 2] = '\n';
        out[out.length - 1] = '\n';
        return out;
    }

    private void publish(byte[] frame) {
        for (BlockingQueue<byte[]> q : clients) {
            if (!q.offer(frame)) {
                q.clear();
                q.offer(new byte[0]);
            }
        }
    }

    public void serve(HttpExchange ex, byte[] initial) throws IOException {
        if (clients.size() >= maxClients) {
            ex.sendResponseHeaders(503, -1);
            ex.close();
            return;
        }
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
        clients.add(queue);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Accel-Buffering", "no");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            out.write("retry: 5000\n\n".getBytes(StandardCharsets.UTF_8));
            if (initial != null) out.write(frame("prices", initial));
            out.flush();
            while (!scheduler.isShutdown()) {
                byte[] next = queue.poll(20, TimeUnit.SECONDS);
                if (next == null) next = HEARTBEAT;
                else if (next.length == 0) break;
                out.write(next);
                out.flush();
            }
        } catch (IOException | InterruptedException ignored) {
        } finally {
            clients.remove(queue);
            ex.close();
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        for (BlockingQueue<byte[]> q : clients) { q.clear(); q.offer(new byte[0]); }
    }
}
