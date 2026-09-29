package me.bounser.nascraft.web;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthTest {

    private static final long MIN = 60_000L;
    private final List<String> saved = new ArrayList<>();
    private final List<String> removed = new ArrayList<>();
    private final UUID alice = UUID.randomUUID();

    private Auth auth(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return new Auth(WebSettings.from(c), new Auth.Store() {
            @Override public void saved(Auth.Session s) { saved.add(s.tokenHash); }
            @Override public void removed(String h) { removed.add(h); }
        });
    }

    @BeforeEach
    void clear() { saved.clear(); removed.clear(); }

    @Test
    @DisplayName("A code is single use and yields a session bound to the device")
    void codeSingleUse() throws Exception {
        Auth a = auth("");
        String code = a.issueCode(alice, "Alice", 0);
        assertEquals(8, code.length());
        assertTrue(code.matches("[A-HJ-NP-Z2-9]+"), "ambiguous characters in " + code);

        String token = a.redeem(code.substring(0, 4).toLowerCase() + "-" + code.substring(4), "dev", "1.1.1.1", 1000);
        assertNotNull(token);
        assertNull(a.redeem(code, "dev", "1.1.1.1", 1000), "code reused");
        assertEquals(1, saved.size());

        Auth.Session s = a.resolve(token, "dev", "9.9.9.9", 2000);
        assertNotNull(s);
        assertEquals(alice, s.uuid);
        assertNull(a.resolve(token, "other-device", "1.1.1.1", 2000), "device binding ignored");
        assertNull(a.resolve(token + "x", "dev", "1.1.1.1", 2000));
    }

    @Test
    void expiredCodeRejected() throws Exception {
        Auth a = auth("login-code:\n  ttl-minutes: 5\n");
        String code = a.issueCode(alice, "Alice", 0);
        assertNull(a.redeem(code, "d", "ip", 5 * MIN + 1));
    }

    @Test
    void newCodeInvalidatesOld() throws Exception {
        Auth a = auth("");
        String first = a.issueCode(alice, "Alice", 0);
        String second = a.issueCode(alice, "Alice", 0);
        assertNull(a.redeem(first, "d", "ip", 1));
        assertNotNull(a.redeem(second, "d", "ip", 1));
    }

    @Test
    @DisplayName("IP binding, idle timeout and lifetime are enforced")
    void bindingsAndExpiry() throws Exception {
        Auth a = auth("session:\n  bind-ip: true\n  bind-device: false\n  idle-timeout-minutes: 10\n  lifetime-days: 1\n");
        String token = a.redeem(a.issueCode(alice, "Alice", 0), "d", "1.1.1.1", 0);
        assertNull(a.resolve(token, "d", "2.2.2.2", MIN));
        assertNotNull(a.resolve(token, "other", "1.1.1.1", MIN));
        assertNull(a.resolve(token, "d", "1.1.1.1", 12 * MIN), "idle session still valid");
        assertEquals(1, removed.size());

        String t2 = a.redeem(a.issueCode(alice, "Alice", 0), "d", "1.1.1.1", 0);
        for (long t = 5 * MIN; t < 86_400_000L; t += 5 * MIN) assertNotNull(a.resolve(t2, "d", "1.1.1.1", t));
        assertNull(a.resolve(t2, "d", "1.1.1.1", 86_400_000L + 1));
    }

    @Test
    void oldestSessionsDroppedOverLimit() throws Exception {
        Auth a = auth("session:\n  max-per-player: 2\n");
        String t1 = a.redeem(a.issueCode(alice, "A", 0), "d", "ip", 1);
        String t2 = a.redeem(a.issueCode(alice, "A", 0), "d", "ip", 2);
        String t3 = a.redeem(a.issueCode(alice, "A", 0), "d", "ip", 3);
        assertNull(a.resolve(t1, "d", "ip", 4));
        assertNotNull(a.resolve(t2, "d", "ip", 4));
        assertNotNull(a.resolve(t3, "d", "ip", 4));
        assertEquals(2, a.activeSessions());
    }

    @Test
    void logoutAndSweep() throws Exception {
        Auth a = auth("");
        String t = a.redeem(a.issueCode(alice, "A", 0), "d", "ip", 0);
        a.logout(t);
        assertNull(a.resolve(t, "d", "ip", 1));
        a.issueCode(alice, "A", 0);
        a.sweep(Long.MAX_VALUE / 2);
        assertEquals(0, a.activeSessions());
    }

    @Test
    void tokensAreNeverStoredRaw() throws Exception {
        Auth a = auth("");
        String t = a.redeem(a.issueCode(alice, "A", 0), "d", "ip", 0);
        assertFalse(saved.contains(t));
        assertEquals(Auth.sha256(t), saved.get(0));
    }
}
