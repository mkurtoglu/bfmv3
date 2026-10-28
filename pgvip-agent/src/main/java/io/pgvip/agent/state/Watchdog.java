package io.pgvip.agent.state;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional Linux watchdog: armed while this node is primary. If the agent stops petting it (the JVM hangs or
 * is killed), the kernel resets the machine, which takes the primary down with it. Disarmed with the magic
 * close character when the node is no longer primary.
 */
public class Watchdog {

    private static final Logger LOG = Logger.getLogger(Watchdog.class.getName());

    private final Path device;
    private FileOutputStream out;

    public Watchdog(Path device) {
        this.device = device;
    }

    public synchronized void keepAlive(boolean primary) {
        try {
            if (primary) {
                if (out == null) {
                    out = new FileOutputStream(device.toFile());
                    LOG.info("watchdog armed on " + device);
                }
                out.write('1');
                out.flush();
            } else if (out != null) {
                out.write('V');
                out.close();
                out = null;
                LOG.info("watchdog disarmed");
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "watchdog " + device + ": " + e.getMessage());
            out = null;
        }
    }
}
