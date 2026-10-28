package io.pgvip.agent;

import io.pgvip.agent.http.AgentHttpServer;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/** {@code java -jar pgvip-agent.jar /etc/pgvip/agent.properties} */
public final class AgentMain {

    public static final String VERSION = "0.1.0";
    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("java.util.logging.SimpleFormatter.format") == null) {
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %3$s - %5$s%6$s%n");
        }
        Path config = Path.of(args.length > 0 ? args[0] : "/etc/pgvip/agent.properties");
        AgentConfig cfg = AgentConfig.load(config);
        AgentService svc = new AgentService(cfg);
        AgentHttpServer http = new AgentHttpServer(cfg, svc);
        svc.start();
        http.start();
        LOG.info("pgvip-agent " + VERSION + " started for node " + cfg.nodeName);
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop();
            svc.stop();
            done.countDown();
        }, "pgvip-shutdown"));
        done.await();
    }
}
