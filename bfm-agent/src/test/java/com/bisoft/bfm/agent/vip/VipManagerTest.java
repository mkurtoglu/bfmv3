package com.bisoft.bfm.agent.vip;

import com.bisoft.bfm.agent.AgentException;
import com.bisoft.bfm.agent.FakeRunner;
import com.bisoft.bfm.agent.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VipManagerTest {

    @TempDir
    Path dir;

    private static final String WITHOUT = "2: eth0    inet 192.168.0.27/24 brd 192.168.0.255 scope global eth0\\";
    private static final String WITH = WITHOUT + "\n2: eth0    inet 192.168.0.50/24 scope global secondary eth0\\";

    /** A tiny model of one host: whether the VIP is on eth0 and whether someone else answers for it. */
    private FakeRunner host(AtomicBoolean held, boolean answeredElsewhere, boolean addFails) {
        return new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("addr show")) {
                return FakeRunner.ok(argv, held.get() ? WITH : WITHOUT);
            }
            if (cmd.contains("arping -D")) {
                return FakeRunner.exit(argv, answeredElsewhere ? 1 : 0, "");
            }
            if (cmd.contains("addr add")) {
                if (addFails) {
                    return FakeRunner.exit(argv, 1, "sudo: a password is required");
                }
                held.set(true);
                return FakeRunner.ok(argv, "");
            }
            if (cmd.contains("addr del")) {
                held.set(false);
                return FakeRunner.ok(argv, "");
            }
            if (cmd.contains("arping -U")) {
                return FakeRunner.ok(argv, "");
            }
            return FakeRunner.exit(argv, 127, "unexpected " + cmd);
        });
    }

    @Test
    void acquireAddsVerifiesAndAnnounces() {
        AtomicBoolean held = new AtomicBoolean(false);
        FakeRunner runner = host(held, false, false);
        VipManager vip = new VipManager(TestConfigs.config(dir), runner);

        String msg = vip.acquire();

        assertTrue(held.get());
        assertTrue(msg.contains("up on eth0"), msg);
        assertEquals(List.of("sudo -n /usr/sbin/ip addr add 192.168.0.50/24 dev eth0"), runner.matching("addr add"));
        assertEquals(1, runner.count("arping -U"));
    }

    @Test
    void acquireRefusesWhenAddressAnswersElsewhere() {
        AtomicBoolean held = new AtomicBoolean(false);
        FakeRunner runner = host(held, true, false);
        VipManager vip = new VipManager(TestConfigs.config(dir), runner);

        AgentException e = assertThrows(AgentException.class, vip::acquire);

        assertEquals(409, e.httpStatus());
        assertTrue(e.getMessage().contains("answers from another host"));
        assertEquals(0, runner.count("addr add"));
        assertFalse(held.get());
    }

    @Test
    void acquireFailsWhenIpAddFails() {
        AtomicBoolean held = new AtomicBoolean(false);
        VipManager vip = new VipManager(TestConfigs.config(dir), host(held, false, true));

        AgentException e = assertThrows(AgentException.class, vip::acquire);

        assertTrue(e.getMessage().contains("ip addr add failed"), e.getMessage());
        assertFalse(held.get());
    }

    @Test
    void acquireIsIdempotent() {
        AtomicBoolean held = new AtomicBoolean(true);
        FakeRunner runner = host(held, true, false);
        VipManager vip = new VipManager(TestConfigs.config(dir), runner);

        assertTrue(vip.acquire().contains("already up"));
        assertEquals(0, runner.count("arping -D"));
    }

    @Test
    void releaseRemovesAddress() {
        AtomicBoolean held = new AtomicBoolean(true);
        VipManager vip = new VipManager(TestConfigs.config(dir), host(held, false, false));

        vip.release();

        assertFalse(held.get());
        assertEquals("VIP not held", vip.release());
    }

    @Test
    void checkFreeReportsDuplicate() {
        VipManager free = new VipManager(TestConfigs.config(dir), host(new AtomicBoolean(), false, false));
        VipManager taken = new VipManager(TestConfigs.config(dir), host(new AtomicBoolean(), true, false));
        assertNull(free.checkFree());
        assertTrue(taken.checkFree().contains("another host"));
    }

    @Test
    void parsesIpOutput() {
        assertTrue(VipManager.containsAddress(WITH, "192.168.0.50"));
        assertFalse(VipManager.containsAddress(WITHOUT, "192.168.0.50"));
        assertFalse(VipManager.containsAddress("inet 192.168.0.500/24", "192.168.0.50"));
    }

    /** A host whose network answers ARP probes for the neighbour 192.168.0.1 only when {@code dadSees} is set. */
    private FakeRunner probing(AtomicBoolean held, boolean dadSees) {
        FakeRunner base = host(held, false, false);
        return new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("arping -D") && cmd.endsWith("192.168.0.1")) {
                return dadSees ? reply(argv) : FakeRunner.exit(argv, 0, "");
            }
            return base.run(argv, java.time.Duration.ofSeconds(1), java.util.Map.of());
        });
    }

    /** What iputils arping -D prints and returns when the neighbour answers. */
    private static com.bisoft.bfm.agent.exec.ProcessResult reply(List<String> argv) {
        return new com.bisoft.bfm.agent.exec.ProcessResult(argv, 1,
                "Unicast reply from 192.168.0.1 [52:54:00:12:34:56]  0.611ms\nSent 1 probes (1 broadcast(s))\n"
                        + "Received 1 response(s)\n", "", false);
    }

    @Test
    void blindDuplicateCheckRefusesTheVip() {
        // cloud test 2026-11-02: arping -D answered "free" for everything, so two servers held the VIP
        var p = TestConfigs.base(dir);
        p.setProperty("vip.dad.probe", "192.168.0.1");
        AtomicBoolean held = new AtomicBoolean();
        VipManager blind = new VipManager(com.bisoft.bfm.agent.AgentConfig.of(p), probing(held, false));
        AgentException e = assertThrows(AgentException.class, blind::acquire);
        assertEquals(409, e.httpStatus());
        assertTrue(e.getMessage().contains("cannot see other hosts"), e.getMessage());
        assertFalse(held.get(), "the VIP was not added");
        assertTrue(blind.lastDadError().contains("192.168.0.1"));

        VipManager seeing = new VipManager(com.bisoft.bfm.agent.AgentConfig.of(p), probing(held, true));
        assertTrue(seeing.acquire().contains("up on eth0"));
        assertNull(seeing.lastDadError());

        // a network that answers now and then (the cloud test saw 0 to 2 of 8 probes answered) is blind too
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        FakeRunner seeing2 = probing(new AtomicBoolean(), true);
        VipManager flaky = new VipManager(com.bisoft.bfm.agent.AgentConfig.of(p), new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("arping -D") && cmd.endsWith("192.168.0.1")) {
                return n.incrementAndGet() == 2 ? FakeRunner.exit(argv, 0, "") : reply(argv);
            }
            return seeing2.run(argv, java.time.Duration.ofSeconds(1), java.util.Map.of());
        }));
        assertTrue(flaky.dadSelfTest() != null, "one unanswered run of three is enough to distrust the check");

        // sudo refusing the probe also exits 1: that is no answer (a missing or stale sudo line must not open the VIP)
        VipManager refused = new VipManager(com.bisoft.bfm.agent.AgentConfig.of(p), new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("arping -D") && cmd.endsWith("192.168.0.1")) {
                return FakeRunner.exit(argv, 1, "sudo: a password is required");
            }
            return seeing2.run(argv, java.time.Duration.ofSeconds(1), java.util.Map.of());
        }));
        assertThrows(AgentException.class, refused::acquire);
        assertTrue(refused.lastDadError().contains("self-test failed"), refused.lastDadError());

        // without a probe address nothing is self-tested (and nothing refused)
        assertFalse(new VipManager(TestConfigs.config(dir), host(new AtomicBoolean(), false, false)).dadSelfTested());
        p.setProperty("vip.dad.enabled", "false");
        assertNull(new VipManager(com.bisoft.bfm.agent.AgentConfig.of(p), probing(new AtomicBoolean(), false))
                .dadSelfTest());
    }
}
