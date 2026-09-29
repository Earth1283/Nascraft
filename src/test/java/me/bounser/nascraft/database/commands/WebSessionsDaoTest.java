package me.bounser.nascraft.database.commands;

import me.bounser.nascraft.support.DatabaseTest;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSessionsDaoTest extends DatabaseTest {

    @Test
    void lifecycle() throws SQLException {
        WebSessions.createTable(connection);
        WebSessions.createTable(connection);
        WebSessions.insert(connection, new WebSessions.Row("h1", "u1", "Alice", 1, 100, "d", "ip"));
        WebSessions.insert(connection, new WebSessions.Row("h2", "u2", "Bob", 1, 10, null, null));

        List<WebSessions.Row> active = WebSessions.loadActive(connection, 50);
        assertEquals(1, active.size());
        assertEquals("Alice", active.get(0).name());

        WebSessions.deleteExpired(connection, 50);
        WebSessions.delete(connection, "h1");
        assertTrue(WebSessions.loadActive(connection, 0).isEmpty());
    }
}
