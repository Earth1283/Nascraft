package me.bounser.nascraft.exchange.ledger;

import me.bounser.nascraft.Nascraft;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Double-entry ledger.  Every economic event must go through {@link #record}.
 *
 * Hash chain: entry N's hash = SHA-256( N.id | N.type | N.debit | N.credit | N.amount | hash[N-1] )
 * Integrity can be verified offline by replaying the chain.
 */
public class Ledger {

    private static Ledger instance;

    private final Connection connection;

    // Cache the latest chain head so we can chain without a DB round-trip each call.
    private String chainHead = "GENESIS";

    private Ledger(Connection connection) {
        this.connection = connection;
        ensureTable();
        loadChainHead();
    }

    public static Ledger getInstance() {
        if (instance == null) throw new IllegalStateException("Ledger not initialised");
        return instance;
    }

    public static void init(Connection connection) {
        instance = new Ledger(connection);
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    private void ensureTable() {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS journal_entries (
                    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                    entry_type   VARCHAR(64)    NOT NULL,
                    debit_acct   VARCHAR(128)   NOT NULL,
                    credit_acct  VARCHAR(128)   NOT NULL,
                    amount       DECIMAL(20,8)  NOT NULL,
                    reference    VARCHAR(255),
                    actor_uuid   VARCHAR(36),
                    ts           BIGINT         NOT NULL,
                    prev_hash    VARCHAR(64)    NOT NULL,
                    hash         VARCHAR(64)    NOT NULL
                ) ENGINE=InnoDB;
                """);
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().log(Level.SEVERE, "Failed to create journal_entries table", e);
        }
    }

    private void loadChainHead() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hash FROM journal_entries ORDER BY id DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) chainHead = rs.getString("hash");
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("Could not load chain head: " + e.getMessage());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Record a double-entry pair atomically.
     * @return the persisted JournalEntry
     */
    public synchronized JournalEntry record(String entryType,
                                             String debitAccount,
                                             String creditAccount,
                                             BigDecimal amount,
                                             String reference,
                                             UUID actorUuid) {
        long ts = System.currentTimeMillis();
        // Compute hash before insert (id will be assigned by DB; we use a placeholder then update)
        // Strategy: insert first with hash='PENDING', get id, compute hash, update row.
        try {
            connection.setAutoCommit(false);

            String actorStr = actorUuid != null ? actorUuid.toString() : null;

            PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO journal_entries (entry_type, debit_acct, credit_acct, amount, reference, actor_uuid, ts, prev_hash, hash) " +
                    "VALUES (?,?,?,?,?,?,?,?,'PENDING')",
                    Statement.RETURN_GENERATED_KEYS);
            ins.setString(1, entryType);
            ins.setString(2, debitAccount);
            ins.setString(3, creditAccount);
            ins.setBigDecimal(4, amount);
            ins.setString(5, reference);
            ins.setString(6, actorStr);
            ins.setLong(7, ts);
            ins.setString(8, chainHead);
            ins.executeUpdate();

            ResultSet keys = ins.getGeneratedKeys();
            if (!keys.next()) throw new SQLException("No generated key returned");
            long id = keys.getLong(1);

            String hash = computeHash(id, entryType, debitAccount, creditAccount, amount, chainHead);

            PreparedStatement upd = connection.prepareStatement(
                    "UPDATE journal_entries SET hash=? WHERE id=?");
            upd.setString(1, hash);
            upd.setLong(2, id);
            upd.executeUpdate();

            connection.commit();
            connection.setAutoCommit(true);

            chainHead = hash;

            return new JournalEntry(id, entryType, debitAccount, creditAccount, amount,
                    reference, actorUuid, ts, chainHead, hash);

        } catch (SQLException e) {
            try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
            Nascraft.getInstance().getLogger().log(Level.SEVERE,
                    "Failed to write journal entry: " + entryType, e);
            throw new RuntimeException("Ledger write failed", e);
        }
    }

    /**
     * Verify the entire hash chain. Returns true if intact.
     */
    public boolean verifyChain() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, entry_type, debit_acct, credit_acct, amount, prev_hash, hash " +
                "FROM journal_entries ORDER BY id ASC")) {
            ResultSet rs = ps.executeQuery();
            String prevHash = "GENESIS";
            while (rs.next()) {
                long id       = rs.getLong("id");
                String type   = rs.getString("entry_type");
                String debit  = rs.getString("debit_acct");
                String credit = rs.getString("credit_acct");
                BigDecimal amt = rs.getBigDecimal("amount");
                String storedPrev = rs.getString("prev_hash");
                String storedHash = rs.getString("hash");

                if (!storedPrev.equals(prevHash)) {
                    Nascraft.getInstance().getLogger()
                            .warning("Chain break at entry " + id + ": prev_hash mismatch");
                    return false;
                }

                String expected = computeHash(id, type, debit, credit, amt, prevHash);
                if (!expected.equals(storedHash)) {
                    Nascraft.getInstance().getLogger()
                            .warning("Chain break at entry " + id + ": hash mismatch");
                    return false;
                }
                prevHash = storedHash;
            }
            return true;
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().log(Level.WARNING, "Chain verify error", e);
            return false;
        }
    }

    /** Returns the current chain head hash (useful for external checkpoints). */
    public String getChainHead() { return chainHead; }

    /** Fetch recent entries for display. */
    public List<JournalEntry> getRecent(int limit) {
        List<JournalEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM journal_entries ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(fromResultSet(rs));
            }
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("getRecent ledger error: " + e.getMessage());
        }
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String computeHash(long id, String type, String debit, String credit,
                                      BigDecimal amount, String prevHash) {
        String raw = id + "|" + type + "|" + debit + "|" + credit + "|" + amount.toPlainString() + "|" + prevHash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static JournalEntry fromResultSet(ResultSet rs) throws SQLException {
        String actorStr = rs.getString("actor_uuid");
        UUID actor = actorStr != null ? UUID.fromString(actorStr) : null;
        return new JournalEntry(
                rs.getLong("id"),
                rs.getString("entry_type"),
                rs.getString("debit_acct"),
                rs.getString("credit_acct"),
                rs.getBigDecimal("amount"),
                rs.getString("reference"),
                actor,
                rs.getLong("ts"),
                rs.getString("prev_hash"),
                rs.getString("hash")
        );
    }
}
