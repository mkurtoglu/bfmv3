package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.core.state.StateStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Moves the CA that {@code bfm-watcher setup} made ({@code ca-import.p12} and {@code ca-import.pass} in the data
 * directory) into the shared store on the first start, once this watcher leads, and deletes the files.
 */
public final class CaBootstrap implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CaBootstrap.class.getName());

    private final WatcherCa ca;
    private final StateStore store;
    private final Path p12;
    private final Path pass;
    private ScheduledExecutorService loop;

    public CaBootstrap(WatcherCa ca, StateStore store, Path dataDir) {
        this.ca = ca;
        this.store = store;
        this.p12 = dataDir.resolve("ca-import.p12");
        this.pass = dataDir.resolve("ca-import.pass");
    }

    public void start() {
        if (!Files.exists(p12)) {
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bfm-ca-import");
            t.setDaemon(true);
            return t;
        });
        loop.scheduleWithFixedDelay(this::tryImport, 0, 2, TimeUnit.SECONDS);
    }

    synchronized void tryImport() {
        try {
            if (!Files.exists(p12)) {
                stop();
                return;
            }
            if (ca.exists()) {
                LOG.warning(p12 + " left alone: this watcher group already has a CA");
                stop();
                return;
            }
            if (!store.leadership().leader()) {
                return;
            }
            ca.importStore(p12, Files.readString(pass, StandardCharsets.UTF_8).trim());
            Files.deleteIfExists(p12);
            Files.deleteIfExists(pass);
            LOG.info("the CA made by 'bfm-watcher setup' now lives in the shared state; agents can enroll");
            stop();
        } catch (IOException | RuntimeException e) {
            LOG.fine("CA import waits: " + e.getMessage());
        }
    }

    private void stop() {
        if (loop != null) {
            loop.shutdown();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
