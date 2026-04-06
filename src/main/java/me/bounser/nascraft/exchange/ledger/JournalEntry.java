package me.bounser.nascraft.exchange.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single double-entry journal entry.
 * Every economic event produces exactly one debit and one credit.
 * Entries are hash-chained: each entry's hash covers the previous entry's hash.
 */
public class JournalEntry {

    private final long id;
    private final String entryType;       // e.g. "TRADE_BUY", "TRADE_SELL", "DIVIDEND", "IPO_FEE"
    private final String debitAccount;    // account identifier being debited
    private final String creditAccount;   // account identifier being credited
    private final BigDecimal amount;
    private final String reference;       // optional free-text reference (trade id, order id, etc.)
    private final UUID actorUuid;         // player who triggered this
    private final long timestamp;
    private final String previousHash;    // hash of the previous entry in chain
    private final String hash;            // SHA-256 of (id + entryType + debit + credit + amount + previousHash)

    public JournalEntry(long id, String entryType, String debitAccount, String creditAccount,
                        BigDecimal amount, String reference, UUID actorUuid,
                        long timestamp, String previousHash, String hash) {
        this.id = id;
        this.entryType = entryType;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amount = amount;
        this.reference = reference;
        this.actorUuid = actorUuid;
        this.timestamp = timestamp;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    public long getId()              { return id; }
    public String getEntryType()     { return entryType; }
    public String getDebitAccount()  { return debitAccount; }
    public String getCreditAccount() { return creditAccount; }
    public BigDecimal getAmount()    { return amount; }
    public String getReference()     { return reference; }
    public UUID getActorUuid()       { return actorUuid; }
    public long getTimestamp()       { return timestamp; }
    public String getPreviousHash()  { return previousHash; }
    public String getHash()          { return hash; }

    @Override
    public String toString() {
        return String.format("JournalEntry{id=%d, type=%s, debit=%s, credit=%s, amount=%s, hash=%.8s}",
                id, entryType, debitAccount, creditAccount, amount, hash);
    }
}
