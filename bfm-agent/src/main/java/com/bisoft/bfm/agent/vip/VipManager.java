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

    /** Adds the VIP and announces it. Throws when the address could not be brought up here. */
    public String acquire() {
        if (!cfg.vipEnabled) {
            return "VIP management disabled";
        }
        if (isHeld()) {
            String garp = announce();
            return "VIP " + cfg.vipAddress + " already up on " + cfg.vipInterface + garp;
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
                "iface", cfg.vipInterface == null ? "" : cfg.vipInterface));
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
