package io.pgvip.watcher.config;

import io.pgvip.common.tls.TlsSupport;
import io.pgvip.core.TimeSource;
import io.pgvip.core.Watcher;
import io.pgvip.core.agent.AgentClientFactory;
import io.pgvip.core.agent.HttpAgentClient;
import io.pgvip.core.notify.NotificationDispatcher;
import io.pgvip.core.notify.SecretBox;
import io.pgvip.core.state.LocalStateStore;
import io.pgvip.core.state.StateStore;
import io.pgvip.raft.RaftStateStore;
import io.pgvip.watcher.mail.MailNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wires the pgVIP core into Spring: state store, agent clients, the watcher loop and mail. */
@Configuration
@EnableConfigurationProperties(PgvipProperties.class)
public class WatcherConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(WatcherConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TimeSource timeSource() {
        return TimeSource.SYSTEM;
    }

    @Bean
    public SecretBox secretBox(PgvipProperties props) {
        return SecretBox.fromKeyFile(props.dataDir().resolve("secret.key"));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StateStore stateStore(PgvipProperties props) throws IOException {
        if (!props.raft().enabled()) {
            LOG.info("standalone watcher {}: state in {}", props.watcherId(), props.dataDir());
            return new LocalStateStore(props.watcherId(), props.dataDir());
        }
        List<RaftStateStore.Peer> peers = new ArrayList<>();
        for (PgvipProperties.Peer p : props.raft().peers()) {
            peers.add(new RaftStateStore.Peer(p.id(), p.host(), p.port()));
        }
        KeyManager[] km = null;
        TrustManager[] tm = null;
        PgvipProperties.Tls tls = props.agent().tls();
        if (props.raft().tls() && tls.enabled()) {
            km = TlsSupport.keyManagers(tls.keystore(), secret(tls.keystorePasswordFile()));
            tm = TlsSupport.trustManagers(tls.truststore(), secret(tls.truststorePasswordFile()));
        }
        RaftStateStore store = new RaftStateStore(new RaftStateStore.Settings(props.watcherId(), peers,
                props.dataDir().resolve("raft"), props.toClusterConfig().name(),
                Duration.ofMillis(props.toClusterConfig().timing().agentTimeoutMs()), km, tm));
        store.start();
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentClientFactory agentClientFactory(PgvipProperties props) {
        PgvipProperties.Tls tls = props.agent().tls();
        SSLContext ctx = null;
        if (tls.enabled()) {
            ctx = TlsSupport.context(tls.keystore(), secret(tls.keystorePasswordFile()), tls.truststore(),
                    secret(tls.truststorePasswordFile()));
        }
        String token = props.agent().tokenFile() == null ? null : new String(secret(props.agent().tokenFile()));
        if (ctx == null && token == null) {
            throw new IllegalStateException("pgvip.agent: enable TLS or set a token file");
        }
        return HttpAgentClient.factory(ctx, token,
                Duration.ofMillis(props.toClusterConfig().timing().agentTimeoutMs()));
    }

    @Bean(destroyMethod = "close")
    public Watcher watcher(PgvipProperties props, StateStore store, AgentClientFactory agents, TimeSource time,
                           MailNotifier notifier) {
        Watcher w = new Watcher(props.toClusterConfig(), store, agents, time);
        ExecutorService mail = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pgvip-mail");
            t.setDaemon(true);
            return t;
        });
        w.eventLog().addListener(new NotificationDispatcher(w.config().name(), store, w.notificationSettings(),
                notifier, mail, time, props.zone()));
        return w;
    }

    /** Starts polling once the application is up. */
    @Bean
    public ApplicationRunner startWatcher(Watcher watcher, PgvipProperties props) {
        return (ApplicationArguments args) -> {
            if (!Boolean.getBoolean("pgvip.watcher.manual")) {
                watcher.start();
                LOG.info("watcher {} polling {} nodes of cluster {}", props.watcherId(),
                        watcher.config().nodes().size(), watcher.config().name());
            }
        };
    }

    static char[] secret(Path file) {
        if (file == null) {
            return new char[0];
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim().toCharArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
