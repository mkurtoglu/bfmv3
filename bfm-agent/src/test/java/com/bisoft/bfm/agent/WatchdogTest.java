package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.state.LeaseGuard;
import com.bisoft.bfm.agent.state.Watchdog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The watchdog device is a plain file here: what reaches it is what the kernel driver would see. */
class WatchdogTest {

    @TempDir
    Path dir;

    private String written(Path device) throws IOException {
        return Files.readString(device, StandardCharsets.ISO_8859_1);
    }

    @Test
    void armsWhilePrimaryAndClosesWithTheMagicCharacterWhenNot() throws IOException {
        Path device = dir.resolve("watchdog");
        Watchdog w = new Watchdog(device);
        assertFalse(w.armed());

        w.keepAlive(true);
        w.keepAlive(true);
        assertTrue(w.armed());
        assertEquals("11", written(device));

        // Not primary any more: the magic close is the last byte, so the driver stops the timer.
        w.keepAlive(false);
        assertFalse(w.armed());
        assertEquals("11V", written(device));

        // Disarming again is a no-op, not a second V and not an error.
        w.disarm();
        w.keepAlive(false);
        assertEquals("11V", written(device));
    }

    @Test
    void aCleanAgentStopDisarmsAnArmedWatchdog() throws IOException {
        Path device = dir.resolve("watchdog");
        Properties p = TestConfigs.base(dir);
        p.setProperty("watchdog.enabled", "true");
        p.setProperty("watchdog.device", device.toString());
        AgentConfig cfg = AgentConfig.of(p);
        AgentService svc = new AgentService(cfg, new FakeRunner(argv -> FakeRunner.exit(argv, 3, "not running")),
                new LeaseGuard());

        // The node became primary at some point and the loop armed the device.
        svc.watchdog().keepAlive(true);
        assertTrue(svc.watchdog().armed());

        // systemctl stop: the timer must be off before the process goes away, or the machine resets.
        svc.stop();
        assertFalse(svc.watchdog().armed());
        assertEquals("1V", written(device));
    }
}
