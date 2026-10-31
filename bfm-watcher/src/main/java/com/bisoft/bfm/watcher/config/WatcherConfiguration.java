package com.bisoft.bfm.watcher.config;

import com.bisoft.bfm.common.tls.TlsSupport;
import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.agent.HttpAgentClient;
import com.bisoft.bfm.core.agent.NodeNameTrustManager;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.raft.RaftStateStore;
import com.bisoft.bfm.watcher.mail.MailNotifier;
import com.bisoft.bfm.watcher.service.ClusterHub;
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

/** Wires the BFM core into Spring: state store, agent clients, the watcher loop and mail. */
@Configuration
@EnableConfigurationProperties(BfmProperties.class)
public class WatcherConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(WatcherConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TimeSource timeSource() {
        return TimeSource.SYSTEM;
    }

    @Bean
    public SecretBox secretBox(BfmProperties props) {
        return SecretBox.fromKeyFile(props.dataDir().resolve("secret.key"));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StateStore stateStore(BfmProperties props) throws IOException {
        if (!props.raft().enabled()) {
            LOG.info("standalone watcher {}: state in {}", props.watcherId(), props.dataDir());
            return new LocalStateStore(props.watcherId(), props.dataDir());
        }
        List<RaftStateStore.Peer> peers = new ArrayList<>();
        for (BfmProperties.Peer p : props.raft().peers()) {
            peers.add(new RaftStateStore.Peer(p.id(), p.host(), p.port()));
        }
        KeyManager[] km = null;
        TrustManager[] tm = null;
        BfmProperties.Tls tls = props.agent().tls();
        if (props.raft().tls() && tls.enabled()) {
            km = TlsSupport.keyManagers(tls.keystore(), secret(tls.keystorePasswordFile()));
            tm = TlsSupport.trustManagers(tls.truststore(), secret(tls.truststorePasswordFile()));
        }
        RaftStateStore store = new RaftStateStore(new RaftStateStore.Settings(props.watcherId(), peers,
                props.dataDir().resolve("raft"), props.raftGroupName(),
                Duration.ofMillis(maxAgentTimeoutMs(props)), km, tm));
        store.start();
        return store;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentClientFactory agentClientFactory(BfmProperties props) {
        BfmProperties.Tls tls = props.agent().tls();
        SSLContext ctx = null;
        if (tls.enabled()) {
            if (tls.verifyNodeName()) {
                List<NodeConfig> nodes = new ArrayList<>();
                for (BfmProperties.Cluster c : props.allClusters()) {
                    nodes.addAll(BfmProperties.toClusterConfig(c).nodes());
                }
                TrustManager[] tm = NodeNameTrustManager.wrap(TlsSupport.trustManagers(tls.truststore(),
                        secret(tls.truststorePasswordFile())), nodes);
                KeyManager[] km = tls.keystore() == null ? null
                        : TlsSupport.keyManagers(tls.keystore(), secret(tls.keystorePasswordFile()));
                ctx = TlsSupport.context(km, tm);
            } else {
                ctx = TlsSupport.context(tls.keystore(), secret(tls.keystorePasswordFile()), tls.truststore(),
                        secret(tls.truststorePasswordFile()));
            }
        }
        String token = props.agent().tokenFile() == null ? null : new String(secret(props.agent().tokenFile()));
        if (ctx == null && token == null) {
            throw new IllegalStateException("bfm.agent: enable TLS or set a token file");
        }
        return HttpAgentClient.factory(ctx, token, Duration.ofMillis(maxAgentTimeoutMs(props)),
                props.agent().commandTimeout());
    }

    /** One HTTP client serves every cluster's agents; it waits as long as the most patient cluster wants. */
    private static long maxAgentTimeoutMs(BfmProperties props) {
        long max = 3000;
        for (BfmProperties.Cluster c : props.allClusters()) {
            max = Math.max(max, BfmProperties.toClusterConfig(c).timing().agentTimeoutMs());
        }
        return max;
    }

    @Bean(destroyMethod = "close")
    public ClusterHub clusterHub(BfmProperties props, StateStore store, AgentClientFactory agents, TimeSource time,
                                 MailNotifier notifier, SecretBox secrets) {
        return new ClusterHub(props, store, agents, time, notifier, secrets);
    }

    /** Starts polling once the application is up. */
    @Bean
    public ApplicationRunner startWatcher(ClusterHub hub, BfmProperties props) {
        return (ApplicationArguments args) -> {
            if (!Boolean.getBoolean("bfm.watcher.manual")) {
                hub.start();
                LOG.info("watcher {} running {} cluster(s): {}", props.watcherId(), hub.names().size(),
                        String.join(", ", hub.names()));
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
