package com.bisoft.bfm.agent.vip;

import com.bisoft.bfm.agent.AgentConfig;
import com.bisoft.bfm.agent.AgentException;
import com.bisoft.bfm.agent.exec.CommandTemplate;
import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Brings the virtual IP up and down with {@code sudo ip addr add/del}.
 *
 * <p>{@link #acquire()} is all-or-nothing: it refuses when the address already answers from another host
 * (duplicate address detection with {@code arping -D}), and it only reports success once the address is
 * visible on the interface. The watcher promotes a replica only after this call has succeeded.
 */
public class VipManager {

    private final AgentConfig cfg;
    private final ProcessRunner runner;
    private volatile String lastDadError;
    /** Consecutive answered self-test runs needed. */
    static final int DAD_SELF_TEST_RUNS = 3;
    /** arping's own summary of a reply; exit code 1 alone also comes from sudo refusing the command. */
    private static final java.util.regex.Pattern ANSWERED = java.util.regex.Pattern.compile("Received [1-9]\\d* response");

    public VipManager(AgentConfig cfg, ProcessRunner runner) {
        this.cfg = cfg;
        this.runner = runner;
    }

    public boolean enabled() {
        return cfg.vipEnabled;
    }

    public String address() {
        return cfg.vipAddress;
    }

    /** Whether the VIP is configured on the local interface right now. */
    public boolean isHeld() {
        if (!cfg.vipEnabled) {
            return false;
        }
        ProcessResult r = run(cfg.vipShowCommand);
        if (!r.ok()) {
            throw new AgentException("cannot read addresses of " + cfg.vipInterface + ": " + r.summary());
        }
        return containsAddress(r.stdout(), cfg.vipAddress);
    }

    /**
     * Checks that no other host answers for the VIP.
     *
     * @return null when the address is free, otherwise the reason it is not
     */
    public String checkFree() {
        if (!cfg.vipEnabled || !cfg.vipDadEnabled) {
            return null;
        }
        ProcessResult r = run(cfg.vipDadCommand);
        if (r.ok()) {
            return null;
        }
        if (!r.timedOut() && r.exitCode() == 1) {
            return "VIP " + cfg.vipAddress + " answers from another host (arping -D)";
        }
        return "duplicate address check failed: " + r.summary();
    }

    /**
     * Checks that the duplicate check can see other hosts at all: {@code arping -D} against a live neighbour
     * ({@code vip.dad.probe}) must get an answer. On a network that does not answer ARP probes (cloud VPCs; cloud test
     * 2026-11-02: two servers held the VIP and every address read as free) the check passes for anything, so the agent
     * refuses to bring the VIP up rather than rely on it (user decision 2026-11-02).
     *
     * @return null when the check works or cannot be tested (no probe address), otherwise why it cannot be trusted
     */
    public String dadSelfTest() {
        if (!dadSelfTested()) {
            return null;
        }
        // three runs in a row must see the neighbour: on the cloud test's network 0 to 2 of 8 probes were answered, so
        // one lucky answer proves nothing. On a working network the neighbour answers at once and this costs little.
        String why = null;
        for (int i = 0; i < DAD_SELF_TEST_RUNS && why == null; i++) {
            ProcessResult r = run(cfg.vipDadProbeCommand);
            if (answered(r)) {
                continue; // the neighbour answered: duplicates are visible here
            }
            why = r.ok()
                    ? "the duplicate address check cannot see other hosts on " + cfg.vipInterface + ": arping -D reports "
                            + cfg.vipDadProbe + " (a live neighbour) as free; set vip.dad.enabled=false only if the VIP "
                            + "is protected otherwise"
                    : "duplicate address self-test failed: " + r.summary();
        }
        lastDadError = why;
        return why;
    }

    /**
     * Whether an {@code arping -D} run got a reply: exit 1 and arping's "Received N response(s)" line. A sudo that
     * refuses the command (no matching rule, a password asked) also exits 1, and must not count as an answer.
     */
    public static boolean answered(ProcessResult r) {
        return !r.timedOut() && r.exitCode() == 1 && ANSWERED.matcher(r.stdout() == null ? "" : r.stdout()).find();
    }

    /** The result of the last self-test (null = passed or not run). */
    public String lastDadError() {
        return lastDadError;
    }

    /** Whether a self-test runs at all (a probe address is configured). */
    public boolean dadSelfTested() {
        return cfg.vipEnabled && cfg.vipDadEnabled && cfg.vipDadProbe != null;
    }

    /** Adds the VIP and announces it. Throws when the address could not be brought up here. */
    public String acquire() {
        if (!cfg.vipEnabled) {
            return "VIP management disabled";
        }
        if (isHeld()) {
            String garp = announce();
            return "VIP " + cfg.vipAddress + " already up on " + cfg.vipInterface + garp;
        }
        String blind = dadSelfTest();
        if (blind != null) {
            throw AgentException.conflict(blind);
        }
        String busy = checkFree();
        if (busy != null) {
            throw AgentException.conflict(busy);
        }
        ProcessResult add = run(cfg.vipAddCommand);
        if (!add.ok()) {
            throw new AgentException("ip addr add failed: " + add.summary());
        }
        if (!isHeld()) {
            throw new AgentException("VIP " + cfg.vipAddress + " not visible on " + cfg.vipInterface + " after ip addr add");
        }
        return "VIP " + cfg.vipAddress + " up on " + cfg.vipInterface + announce();
    }

    /** Removes the VIP; succeeds when it is not configured here afterwards. */
    public String release() {
        if (!cfg.vipEnabled) {
            return "VIP management disabled";
        }
        if (!isHeld()) {
            return "VIP not held";
        }
        ProcessResult del = run(cfg.vipDelCommand);
        if (!del.ok() && isHeld()) {
            throw new AgentException("ip addr del failed: " + del.summary());
        }
        if (isHeld()) {
            throw new AgentException("VIP still configured on " + cfg.vipInterface + " after ip addr del");
        }
        return "VIP " + cfg.vipAddress + " removed from " + cfg.vipInterface;
    }

    private String announce() {
        ProcessResult garp = run(cfg.vipGarpCommand);
        return garp.ok() ? ", gratuitous ARP sent" : ", warning: gratuitous ARP failed (" + garp.summary() + ")";
    }

    private ProcessResult run(String template) {
        List<String> argv = CommandTemplate.expand(template, Map.of(
                "vip", cfg.vipAddress == null ? "" : cfg.vipAddress,
                "prefix", Integer.toString(cfg.vipPrefix),
                "iface", cfg.vipInterface == null ? "" : cfg.vipInterface,
                "probe", cfg.vipDadProbe == null ? "" : cfg.vipDadProbe));
        return runner.run(argv, Duration.ofSeconds(cfg.commandTimeoutSec));
    }

    /** Looks for "inet 192.168.0.50/24" in {@code ip -o addr show} output. */
    static boolean containsAddress(String ipOutput, String address) {
        String[] tokens = ipOutput.split("\\s+");
        for (int i = 0; i + 1 < tokens.length; i++) {
            if (("inet".equals(tokens[i]) || "inet6".equals(tokens[i]))
                    && (tokens[i + 1].equals(address) || tokens[i + 1].startsWith(address + "/"))) {
                return true;
            }
        }
        return false;
    }
}
