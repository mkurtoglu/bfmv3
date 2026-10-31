package com.bisoft.bfm.core.event;

/** What happened. Each type has a fixed severity and, when it can be mailed, the mail rule it belongs to. */
public enum EventType {
    CLUSTER_INITIALISED(Severity.INFO, null, false),
    FAILOVER_STARTED(Severity.CRITICAL, MailRule.FAILOVER, true),
    FAILOVER_FINISHED(Severity.CRITICAL, MailRule.FAILOVER, true),
    FAILOVER_FAILED(Severity.CRITICAL, MailRule.FAILOVER, true),
    FAILOVER_BLOCKED(Severity.WARNING, MailRule.PRIMARY_UNREACHABLE, false),
    PRIMARY_UNREACHABLE(Severity.CRITICAL, MailRule.PRIMARY_UNREACHABLE, false),
    PRIMARY_BACK(Severity.INFO, MailRule.PRIMARY_UNREACHABLE, false),
    PRIMARY_RESTARTED(Severity.WARNING, MailRule.FAILOVER, true),
    AGENT_FENCED(Severity.CRITICAL, MailRule.AGENT_FENCED, false),
    ROGUE_PRIMARY_FENCED(Severity.CRITICAL, MailRule.FAILOVER, true),
    EPOCH_CONFLICT(Severity.CRITICAL, MailRule.PRIMARY_UNREACHABLE, false),
    /** A node answers with another cluster's identity or another database: it is left alone until fixed. */
    WRONG_IDENTITY(Severity.CRITICAL, MailRule.PRIMARY_UNREACHABLE, false),
    REPLICA_LAG(Severity.WARNING, MailRule.REPLICA_LAG, false),
    REPLICA_LAG_RESOLVED(Severity.INFO, null, false),
    INACTIVE_SLOT(Severity.WARNING, MailRule.INACTIVE_SLOT, false),
    INACTIVE_SLOT_RESOLVED(Severity.INFO, null, false),
    WATCHER_LOST(Severity.WARNING, MailRule.WATCHER, false),
    WATCHER_REJOINED(Severity.INFO, MailRule.WATCHER, false),
    LEADER_CHANGED(Severity.INFO, null, false),
    NODE_DOWN(Severity.WARNING, MailRule.NODE, false),
    NODE_BACK(Severity.INFO, MailRule.NODE, false),
    SWITCHOVER_STARTED(Severity.INFO, null, true),
    SWITCHOVER_FINISHED(Severity.INFO, MailRule.SWITCHOVER, true),
    SWITCHOVER_FAILED(Severity.CRITICAL, MailRule.SWITCHOVER, true),
    REINIT_FINISHED(Severity.INFO, MailRule.REINIT_REJOIN, false),
    REINIT_FAILED(Severity.WARNING, MailRule.REINIT_REJOIN, false),
    REJOIN_FINISHED(Severity.INFO, MailRule.REINIT_REJOIN, false),
    REJOIN_FAILED(Severity.WARNING, MailRule.REINIT_REJOIN, false),
    VIP(Severity.INFO, null, false),
    CONTROL_CHANGED(Severity.INFO, null, false),
    SLOT_DROPPED(Severity.INFO, null, false),
    OPERATION(Severity.INFO, null, false);

    private final Severity severity;
    private final MailRule rule;
    private final boolean roleChange;

    EventType(Severity severity, MailRule rule, boolean roleChange) {
        this.severity = severity;
        this.rule = rule;
        this.roleChange = roleChange;
    }

    public Severity severity() {
        return severity;
    }

    /** The mail rule this event is sent under, or null when it is never mailed. */
    public MailRule rule() {
        return rule;
    }

    /** Failover, switchover and fencing events: what "Role changes" recipients get. */
    public boolean roleChange() {
        return roleChange;
    }
}
