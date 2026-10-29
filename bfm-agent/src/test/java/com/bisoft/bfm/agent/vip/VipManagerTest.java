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
}
