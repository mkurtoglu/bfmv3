package io.pgvip.core.event;

/**
 * Groups of events that share one mail setting (on/off and throttle window) on the notifications screen.
 */
public enum MailRule {
    FAILOVER("Automatic failover started / finished", Severity.CRITICAL, 0, true),
    PRIMARY_UNREACHABLE("Primary unreachable by watcher quorum", Severity.CRITICAL, 0, true),
    AGENT_FENCED("Agent self-fenced (lease lost)", Severity.CRITICAL, 0, true),
    REPLICA_LAG("Replica lag above threshold", Severity.WARNING, 15 * 60_000L, true),
    INACTIVE_SLOT("Inactive slot holding WAL above threshold", Severity.WARNING, 60 * 60_000L, true),
    WATCHER("Watcher lost or rejoined", Severity.WARNING, 15 * 60_000L, true),
    NODE("Node down or back", Severity.WARNING, 15 * 60_000L, true),
    SWITCHOVER("Switchover finished", Severity.INFO, 0, true),
    REINIT_REJOIN("Reinit or rejoin finished", Severity.INFO, 0, false);

    private final String label;
    private final Severity severity;
    private final long defaultThrottleMs;
    private final boolean defaultEnabled;

    MailRule(String label, Severity severity, long defaultThrottleMs, boolean defaultEnabled) {
        this.label = label;
        this.severity = severity;
        this.defaultThrottleMs = defaultThrottleMs;
        this.defaultEnabled = defaultEnabled;
    }

    public String label() {
        return label;
    }

    public Severity severity() {
        return severity;
    }

    public long defaultThrottleMs() {
        return defaultThrottleMs;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }
}
