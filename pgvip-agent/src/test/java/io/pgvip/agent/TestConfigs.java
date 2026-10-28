package io.pgvip.agent;

import java.nio.file.Path;
import java.util.Properties;

public final class TestConfigs {

    private TestConfigs() {
    }

    public static Properties base(Path stateDir) {
        Properties p = new Properties();
        p.setProperty("node.name", "pg-a2");
        p.setProperty("tls.enabled", "false");
        p.setProperty("api.token", "test-token");
        p.setProperty("state.dir", stateDir.toString());
        p.setProperty("pg.bin", "/usr/lib/postgresql/16/bin");
        p.setProperty("pg.data", stateDir.resolve("data").toString());
        p.setProperty("vip.enabled", "true");
        p.setProperty("vip.address", "192.168.0.50");
        p.setProperty("vip.prefix", "24");
        p.setProperty("vip.interface", "eth0");
        return p;
    }

    public static AgentConfig config(Path stateDir) {
        return AgentConfig.of(base(stateDir));
    }
}
