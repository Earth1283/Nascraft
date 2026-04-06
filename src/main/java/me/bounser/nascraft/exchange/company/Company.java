package me.bounser.nascraft.exchange.company;

import org.bukkit.Location;

import java.math.BigDecimal;
import java.util.UUID;

public class Company {
    private final UUID id;
    private String name;
    private final String ticker;
    private final UUID founderUuid;
    private final long foundedAt;
    private long ipoCompletedAt;
    private CompanyStatus status;

    private double authorizedShares;
    private double outstandingShares;
    private double floatShares;
    private double founderLockupShares;
    private long lockupExpiresAt;

    private double sharePrice;
    private Location vaultLocation;
    private double currentVaultValue;
    private double lastVaultSnapshot;
    private String prospectusText;
    private String regulatoryNote;
    private String haltReason;

    /** Primary constructor used when creating a new company (e.g., from IPO). */
    public Company(UUID id, String name, String ticker, double sharePrice, BigDecimal totalShares) {
        this.id               = id;
        this.name             = name;
        this.ticker           = ticker;
        this.founderUuid      = null;
        this.foundedAt        = System.currentTimeMillis();
        this.status           = CompanyStatus.PRIVATE;
        this.authorizedShares = totalShares.doubleValue();
        this.outstandingShares = 0;
        this.floatShares      = 0;
        this.sharePrice       = sharePrice;
        this.currentVaultValue = 0;
        this.lastVaultSnapshot = 0;
    }

    /** Full constructor used when loading from DB. */
    public Company(UUID id, String name, String ticker, UUID founderUuid, long foundedAt,
                   long ipoCompletedAt, CompanyStatus status,
                   double authorizedShares, double outstandingShares, double floatShares,
                   double founderLockupShares, long lockupExpiresAt, double sharePrice,
                   double lastVaultSnapshot, double currentVaultValue,
                   String prospectusText, String regulatoryNote) {
        this.id                 = id;
        this.name               = name;
        this.ticker             = ticker;
        this.founderUuid        = founderUuid;
        this.foundedAt          = foundedAt;
        this.ipoCompletedAt     = ipoCompletedAt;
        this.status             = status;
        this.authorizedShares   = authorizedShares;
        this.outstandingShares  = outstandingShares;
        this.floatShares        = floatShares;
        this.founderLockupShares = founderLockupShares;
        this.lockupExpiresAt    = lockupExpiresAt;
        this.sharePrice         = sharePrice;
        this.lastVaultSnapshot  = lastVaultSnapshot;
        this.currentVaultValue  = currentVaultValue;
        this.prospectusText     = prospectusText;
        this.regulatoryNote     = regulatoryNote;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getId()                  { return id; }
    public String getName()              { return name; }
    public String getTicker()            { return ticker; }
    public UUID getFounderUuid()         { return founderUuid; }
    public long getFoundedAt()           { return foundedAt; }
    public long getIpoCompletedAt()      { return ipoCompletedAt; }
    public CompanyStatus getStatus()     { return status; }
    public double getAuthorizedShares()  { return authorizedShares; }
    public double getOutstandingShares() { return outstandingShares; }
    public double getFloatShares()       { return floatShares; }
    public double getFounderLockupShares() { return founderLockupShares; }
    public long getLockupExpiresAt()     { return lockupExpiresAt; }
    public double getSharePrice()        { return sharePrice; }
    public Location getVaultLocation()   { return vaultLocation; }
    public double getCurrentVaultValue() { return currentVaultValue; }
    public double getVaultValue()        { return currentVaultValue; } // alias
    public double getLastVaultSnapshot() { return lastVaultSnapshot; }
    public String getProspectusText()    { return prospectusText; }
    public String getRegulatoryNote()    { return regulatoryNote; }
    public String getHaltReason()        { return haltReason; }
    public BigDecimal getTotalShares()   { return BigDecimal.valueOf(outstandingShares); }

    public double getMarketCap() { return sharePrice * outstandingShares; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setName(String name)                     { this.name = name; }
    public void setStatus(CompanyStatus status)          { this.status = status; }
    public void setIpoCompletedAt(long t)                { this.ipoCompletedAt = t; }
    public void setAuthorizedShares(double v)            { this.authorizedShares = v; }
    public void setOutstandingShares(double v)           { this.outstandingShares = v; }
    public void setFloatShares(double v)                 { this.floatShares = v; }
    public void setFounderLockupShares(double v)         { this.founderLockupShares = v; }
    public void setLockupExpiresAt(long t)               { this.lockupExpiresAt = t; }
    public void setSharePrice(double v)                  { this.sharePrice = v; }
    public void setVaultLocation(Location loc)           { this.vaultLocation = loc; }
    public void setCurrentVaultValue(double v)           { this.currentVaultValue = v; }
    public void setVaultValue(double v)                  { this.currentVaultValue = v; } // alias
    public void setLastVaultSnapshot(double v)           { this.lastVaultSnapshot = v; }
    public void setProspectusText(String t)              { this.prospectusText = t; }
    public void setRegulatoryNote(String n)              { this.regulatoryNote = n; }
    public void setHaltReason(String r)                  { this.haltReason = r; }
    public void setTotalShares(BigDecimal v)             { this.outstandingShares = v.doubleValue(); }
}
