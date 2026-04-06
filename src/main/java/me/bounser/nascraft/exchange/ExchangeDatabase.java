package me.bounser.nascraft.exchange;

import me.bounser.nascraft.Nascraft;
import me.bounser.nascraft.exchange.company.Company;
import me.bounser.nascraft.exchange.company.CompanyStatus;
import me.bounser.nascraft.exchange.shares.ShareRegistry;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Exchange-specific database operations.
 * Uses its own MySQL connection (same credentials as the main DB).
 *
 * Tables managed here:
 *  - companies
 *  - share_positions      (player → company → quantity — cache, rebuildable from journal)
 *  - exchange_orders      (persisted order book state)
 *  - dividend_payments    (audit trail for dividend distributions)
 *  - insider_trade_flags  (for regulatory detection)
 */
public class ExchangeDatabase {

    private static ExchangeDatabase instance;
    private final Connection connection;

    private ExchangeDatabase(Connection connection) {
        this.connection = connection;
        createTables();
    }

    public static void init(Connection connection) {
        instance = new ExchangeDatabase(connection);
    }

    public static ExchangeDatabase getInstance() {
        if (instance == null) throw new IllegalStateException("ExchangeDatabase not initialised");
        return instance;
    }

    // ── Schema ─────────────────────────────────────────────────────────────

    private void createTables() {
        String[] ddl = {
            """
            CREATE TABLE IF NOT EXISTS companies (
                id               VARCHAR(36)    PRIMARY KEY,
                name             VARCHAR(128)   NOT NULL,
                ticker           VARCHAR(10)    NOT NULL UNIQUE,
                founder_uuid     VARCHAR(36)    NOT NULL,
                founded_at       BIGINT         NOT NULL,
                ipo_completed_at BIGINT         DEFAULT 0,
                status           VARCHAR(20)    NOT NULL DEFAULT 'PRIVATE',
                authorized_shares DOUBLE        NOT NULL DEFAULT 1000000,
                outstanding_shares DOUBLE       NOT NULL DEFAULT 0,
                float_shares     DOUBLE         NOT NULL DEFAULT 0,
                lockup_shares    DOUBLE         NOT NULL DEFAULT 0,
                lockup_expires   BIGINT         NOT NULL DEFAULT 0,
                share_price      DOUBLE         NOT NULL DEFAULT 0,
                vault_world      VARCHAR(64),
                vault_x          INT,
                vault_y          INT,
                vault_z          INT,
                last_vault_snapshot DOUBLE      NOT NULL DEFAULT 0,
                current_vault_value DOUBLE      NOT NULL DEFAULT 0,
                prospectus       TEXT,
                regulatory_note  VARCHAR(512)
            ) ENGINE=InnoDB;
            """,
            """
            CREATE TABLE IF NOT EXISTS share_positions (
                player_uuid  VARCHAR(36)   NOT NULL,
                company_id   VARCHAR(36)   NOT NULL,
                quantity     DECIMAL(20,8) NOT NULL DEFAULT 0,
                updated_at   BIGINT        NOT NULL,
                PRIMARY KEY (player_uuid, company_id)
            ) ENGINE=InnoDB;
            """,
            """
            CREATE TABLE IF NOT EXISTS exchange_orders (
                id           VARCHAR(36)    PRIMARY KEY,
                owner_uuid   VARCHAR(36)    NOT NULL,
                ticker       VARCHAR(10)    NOT NULL,
                side         VARCHAR(4)     NOT NULL,
                order_type   VARCHAR(8)     NOT NULL,
                price        DECIMAL(20,8),
                quantity     DECIMAL(20,8)  NOT NULL,
                filled_qty   DECIMAL(20,8)  NOT NULL DEFAULT 0,
                status       VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
                created_at   BIGINT         NOT NULL,
                expires_at   BIGINT         NOT NULL DEFAULT 0,
                updated_at   BIGINT         NOT NULL,
                INDEX idx_ticker_status (ticker, status),
                INDEX idx_owner (owner_uuid)
            ) ENGINE=InnoDB;
            """,
            """
            CREATE TABLE IF NOT EXISTS dividend_payments (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                company_id   VARCHAR(36)    NOT NULL,
                player_uuid  VARCHAR(36)    NOT NULL,
                amount       DECIMAL(20,8)  NOT NULL,
                paid_at      BIGINT         NOT NULL,
                snapshot_value DOUBLE       NOT NULL
            ) ENGINE=InnoDB;
            """,
            """
            CREATE TABLE IF NOT EXISTS material_disclosures (
                id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                company_id   VARCHAR(36)    NOT NULL,
                filed_by     VARCHAR(36)    NOT NULL,
                event_type   VARCHAR(64)    NOT NULL,
                description  TEXT           NOT NULL,
                filed_at     BIGINT         NOT NULL
            ) ENGINE=InnoDB;
            """
        };

        for (String sql : ddl) {
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                Nascraft.getInstance().getLogger()
                        .log(Level.SEVERE, "Exchange DDL error: " + e.getMessage(), e);
            }
        }
    }

    // ── Companies ─────────────────────────────────────────────────────────────

    public void saveCompany(Company c) {
        String sql = """
            INSERT INTO companies (id,name,ticker,founder_uuid,founded_at,ipo_completed_at,
              status,authorized_shares,outstanding_shares,float_shares,lockup_shares,lockup_expires,
              share_price,vault_world,vault_x,vault_y,vault_z,last_vault_snapshot,
              current_vault_value,prospectus,regulatory_note)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              name=VALUES(name), ticker=VALUES(ticker), status=VALUES(status),
              authorized_shares=VALUES(authorized_shares),
              outstanding_shares=VALUES(outstanding_shares),
              float_shares=VALUES(float_shares), lockup_shares=VALUES(lockup_shares),
              lockup_expires=VALUES(lockup_expires), share_price=VALUES(share_price),
              vault_world=VALUES(vault_world), vault_x=VALUES(vault_x),
              vault_y=VALUES(vault_y), vault_z=VALUES(vault_z),
              last_vault_snapshot=VALUES(last_vault_snapshot),
              current_vault_value=VALUES(current_vault_value),
              prospectus=VALUES(prospectus), regulatory_note=VALUES(regulatory_note),
              ipo_completed_at=VALUES(ipo_completed_at)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, c.getId().toString());
            ps.setString(2, c.getName());
            ps.setString(3, c.getTicker());
            ps.setString(4, c.getFounderUuid().toString());
            ps.setLong(5, c.getFoundedAt());
            ps.setLong(6, c.getIpoCompletedAt());
            ps.setString(7, c.getStatus().name());
            ps.setDouble(8, c.getAuthorizedShares());
            ps.setDouble(9, c.getOutstandingShares());
            ps.setDouble(10, c.getFloatShares());
            ps.setDouble(11, c.getFounderLockupShares());
            ps.setLong(12, c.getLockupExpiresAt());
            ps.setDouble(13, c.getSharePrice());
            if (c.getVaultLocation() != null) {
                ps.setString(14, c.getVaultLocation().getWorld().getName());
                ps.setInt(15, c.getVaultLocation().getBlockX());
                ps.setInt(16, c.getVaultLocation().getBlockY());
                ps.setInt(17, c.getVaultLocation().getBlockZ());
            } else { ps.setNull(14, Types.VARCHAR); ps.setNull(15, Types.INTEGER); ps.setNull(16, Types.INTEGER); ps.setNull(17, Types.INTEGER); }
            ps.setDouble(18, c.getLastVaultSnapshot());
            ps.setDouble(19, c.getCurrentVaultValue());
            ps.setString(20, c.getProspectusText());
            ps.setString(21, c.getRegulatoryNote());
            ps.executeUpdate();
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("saveCompany error: " + e.getMessage());
        }
    }

    public List<Company> loadAllCompanies() {
        List<Company> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM companies")) {
            while (rs.next()) {
                out.add(companyFromResultSet(rs));
            }
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("loadAllCompanies error: " + e.getMessage());
        }
        return out;
    }

    // ── Share Positions ───────────────────────────────────────────────────────

    public void savePosition(UUID playerUuid, UUID companyId, java.math.BigDecimal qty) {
        String sql = """
            INSERT INTO share_positions (player_uuid, company_id, quantity, updated_at)
            VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE quantity=VALUES(quantity), updated_at=VALUES(updated_at)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, companyId.toString());
            ps.setBigDecimal(3, qty);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("savePosition error: " + e.getMessage());
        }
    }

    public Map<UUID, java.math.BigDecimal> loadPositionsForCompany(UUID companyId) {
        Map<UUID, java.math.BigDecimal> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, quantity FROM share_positions WHERE company_id=?")) {
            ps.setString(1, companyId.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.put(UUID.fromString(rs.getString("player_uuid")), rs.getBigDecimal("quantity"));
            }
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("loadPositions error: " + e.getMessage());
        }
        return out;
    }

    // ── Dividend Log ──────────────────────────────────────────────────────────

    public void logDividend(UUID companyId, UUID playerUuid, double amount, double snapshotValue) {
        String sql = "INSERT INTO dividend_payments (company_id, player_uuid, amount, paid_at, snapshot_value) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, companyId.toString());
            ps.setString(2, playerUuid.toString());
            ps.setDouble(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.setDouble(5, snapshotValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            Nascraft.getInstance().getLogger().warning("logDividend error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Company companyFromResultSet(ResultSet rs) throws SQLException {
        UUID id         = UUID.fromString(rs.getString("id"));
        UUID founder    = UUID.fromString(rs.getString("founder_uuid"));
        String name     = rs.getString("name");
        String ticker   = rs.getString("ticker");

        Company c = new Company(id, name, ticker, founder, rs.getLong("founded_at"),
                rs.getLong("ipo_completed_at"),
                CompanyStatus.valueOf(rs.getString("status")),
                rs.getDouble("authorized_shares"),
                rs.getDouble("outstanding_shares"),
                rs.getDouble("float_shares"),
                rs.getDouble("lockup_shares"),
                rs.getLong("lockup_expires"),
                rs.getDouble("share_price"),
                rs.getDouble("last_vault_snapshot"),
                rs.getDouble("current_vault_value"),
                rs.getString("prospectus"),
                rs.getString("regulatory_note"));
        return c;
    }
}
