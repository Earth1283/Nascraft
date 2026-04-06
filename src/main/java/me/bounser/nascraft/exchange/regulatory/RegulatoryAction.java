package me.bounser.nascraft.exchange.regulatory;

import java.util.UUID;

public class RegulatoryAction {

    private final long id;
    private final UUID targetUuid;
    private final EnforcementLevel level;
    private final String reason;
    private final long timestamp;
    private final UUID issuedBy;      // null = system

    public RegulatoryAction(long id, UUID targetUuid, EnforcementLevel level,
                            String reason, long timestamp, UUID issuedBy) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.level = level;
        this.reason = reason;
        this.timestamp = timestamp;
        this.issuedBy = issuedBy;
    }

    public long getId()              { return id; }
    public UUID getTargetUuid()      { return targetUuid; }
    public EnforcementLevel getLevel() { return level; }
    public String getReason()        { return reason; }
    public long getTimestamp()       { return timestamp; }
    public UUID getIssuedBy()        { return issuedBy; }

    @Override
    public String toString() {
        return String.format("RegulatoryAction{id=%d, target=%s, level=%s, reason='%s'}",
                id, targetUuid, level, reason);
    }
}
