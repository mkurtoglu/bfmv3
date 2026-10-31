package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time tokens with which a database host gets its agent certificate and settings from the watcher, so nobody
 * copies key stores between machines.
 *
 * <p>A token is 128 random bits, shown once and stored only as its SHA-256; it is valid for 30 minutes, works once,
 * and only for the cluster and node it was issued for. The certificate it buys names that node (common name) and
 * the address the watcher reaches its agent at. Every issue, use and refusal goes to the cluster's event log.
 */
public class EnrollmentService {

    public static final String PREFIX = ClusterRegistry.GLOBAL_PREFIX + "enroll/";
    public static final long VALID_MS = 30 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClusterHub hub;
    private final WatcherCa ca;
    private final TimeSource time;

    public EnrollmentService(ClusterHub hub, WatcherCa ca, TimeSource time) {
        this.hub = hub;
        this.ca = ca;
        this.time = time;
    }

    /** A token as the console shows it, once. */
    public record Issued(String cluster, String node, String token, long expiresAtMillis, String caFingerprint) {
    }

    /** What a database host gets for a valid token. */
    public record Enrollment(String clusterId, String cluster, String environment, String node, String dc,
                             String agentHost, int agentPort, String pgHost, int pgPort, List<Peer> peers,
                             String systemIdentifier, String certificate, String caCertificate) {
    }

    public record Peer(String name, String pgHost, int pgPort) {
    }

    /** Refused enrollment: the message is safe to show to the host that asked. */
    public static final class EnrollmentException extends RuntimeException {
        public EnrollmentException(String message) {
            super(message);
        }
    }

    public boolean enabled() {
        return ca.exists();
    }

    public Issued issue(ClusterContext c, String node, String user) {
        if (!ca.exists()) {
            throw new IllegalStateException("this watcher has no CA, so it cannot enroll agents; create one with"
                    + " 'bfm-watcher ca init' (or import yours with 'bfm-watcher ca import'), or give the node a"
                    + " certificate bundle from bfm-make-certs");
        }
        NodeConfig n = c.watcher().config().node(node).orElseThrow(() ->
                new IllegalArgumentException("cluster " + c.name() + " has no node " + node));
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = time.millis();
        long expires = now + VALID_MS;
        JsonMap rec = JsonMap.create().put("clusterId", c.id()).put("cluster", c.name()).put("node", n.name())
                .put("issuedBy", user).put("issuedAt", now).put("expiresAt", expires);
        StateStore root = hub.store();
        if (!root.compareAndSet(PREFIX + hash(token), 0, rec.toJson())) {
            throw new IllegalStateException("token collision; try again");
        }
        prune(now);
        c.watcher().eventLog().publish(EventType.OPERATION, n.name(), "Enrollment token for " + n.name()
                + " issued by " + user + ", valid until " + Instant.ofEpochMilli(expires) + " (once)", user);
        return new Issued(c.name(), n.name(), token, expires, Keytool.fingerprint(ca.certificate()));
    }

    /**
     * Spends a token: checks it, marks it used, and signs the host's certificate request.
     *
     * @param hostname what the host calls itself, for the event log
     */
    public Enrollment enroll(String token, String csrPem, String hostname, String hostId) {
        if (token == null || token.isBlank() || csrPem == null || csrPem.isBlank()) {
            throw new EnrollmentException("token and certificate request are required");
        }
        StateStore root = hub.store();
        String key = PREFIX + hash(token.trim());
        StateStore.Versioned v = root.get(key);
        if (v == null) {
            throw new EnrollmentException("unknown token (ask for a new one in the console)");
        }
        JsonMap rec = Json.parseObject(v.value());
        if (rec.str("node") == null) {
            throw new EnrollmentException("unknown token (ask for a new one in the console)");
        }
        ClusterContext c = hub.find(rec.str("cluster")).orElse(null);
        String node = rec.requireStr("node");
        String host = (hostname == null || hostname.isBlank() ? "an unnamed host" : hostname.trim())
                + (hostId == null || hostId.isBlank() ? "" : " (machine-id " + hostId.trim() + ")");
        if (rec.lng("usedAt", 0) > 0) {
            refuse(c, node, "an already used enrollment token for " + node + " was presented by " + host);
            throw new EnrollmentException("this token was already used; ask for a new one");
        }
        if (time.millis() > rec.lng("expiresAt", 0)) {
            refuse(c, node, "an expired enrollment token for " + node + " was presented by " + host);
            throw new EnrollmentException("this token has expired; ask for a new one");
        }
        if (c == null || !c.id().equals(rec.str("clusterId"))) {
            throw new EnrollmentException("the cluster of this token is no longer run by this watcher");
        }
        NodeConfig n = c.watcher().config().node(node).orElseThrow(() ->
                new EnrollmentException("node " + node + " is no longer part of cluster " + c.name()));
        // Spend it before signing: a second use, even at the same moment, finds it used.
        if (!root.compareAndSet(key, v.version(), rec.put("usedAt", time.millis()).put("usedBy", host).toJson())) {
            throw new EnrollmentException("this token was already used; ask for a new one");
        }
        URI agent = URI.create(n.agentUrl());
        String chain;
        try {
            chain = ca.sign(csrPem, n.name(), altNames(agent.getHost(), n.pgHost(), hostname));
        } catch (IllegalArgumentException e) {
            refuse(c, node, "enrollment of " + node + " from " + host + " refused: " + e.getMessage());
            throw new EnrollmentException(e.getMessage());
        }
        X509Certificate leaf = Keytool.parse(chain).get(0);
        c.watcher().eventLog().publish(EventType.OPERATION, node, node + " enrolled from " + host + "; its certificate"
                + " is valid until " + leaf.getNotAfter().toInstant() + " (" + Keytool.fingerprint(leaf) + ")",
                "enrollment");
        List<Peer> peers = new ArrayList<>();
        for (NodeConfig p : c.watcher().config().nodes()) {
            if (!p.name().equals(node)) {
                peers.add(new Peer(p.name(), p.pgHost(), p.pgPort()));
            }
        }
        return new Enrollment(c.id(), c.name(), c.environment(), n.name(), n.dc(), agent.getHost(),
                agent.getPort() == -1 ? 8008 : agent.getPort(), n.pgHost(), n.pgPort(), peers,
                ClusterIdentity.systemIdentifier(c.watcher().store()), chain, ca.certificatePem());
    }

    /** The certificate names a node needs: the agent URL's host, its PostgreSQL address and its host name. */
    static String altNames(String... names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            String v = n.trim();
            boolean ip = v.matches("[0-9.]+") || v.contains(":");
            if (!v.matches("[A-Za-z0-9.:_-]+")) {
                continue; // nothing keytool's syntax could misread
            }
            out.add((ip ? "IP:" : "DNS:") + v);
        }
        return String.join(",", out);
    }

    private void refuse(ClusterContext c, String node, String message) {
        if (c != null) {
            c.watcher().eventLog().publish(EventType.WRONG_IDENTITY, node, "Enrollment refused: " + message,
                    "enrollment");
        }
    }

    /** Drops tokens that expired a day ago or more; the event log keeps their story. */
    private void prune(long now) {
        StateStore root = hub.store();
        for (Map.Entry<String, StateStore.Versioned> e : root.getPrefix(PREFIX).entrySet()) {
            try {
                JsonMap rec = Json.parseObject(e.getValue().value());
                if (now - rec.lng("expiresAt", now) > 24 * 3_600_000L) {
                    root.compareAndSet(e.getKey(), e.getValue().version(), "{\"pruned\":true}");
                }
            } catch (RuntimeException ignored) {
                // an unreadable entry stays; it matches no token
            }
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
