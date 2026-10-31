package com.bisoft.bfm.core.agent;

import com.bisoft.bfm.core.config.NodeConfig;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/**
 * Checks, during the TLS handshake, that an agent's certificate names the node the watcher means to reach (its
 * common name or a DNS name in it equals the node name, as {@code bfm-make-certs} and enrollment issue them).
 *
 * <p>With several clusters on one host every agent's certificate comes from the same CA, so the CA check alone
 * accepts the wrong agent behind a mistyped port. This check fails the handshake instead, before any request is
 * sent; the watcher then leaves the node alone (it does not count as a lost node).
 *
 * <p>The node is looked up by the host and port the connection was opened to, exactly as written in its agent URL.
 */
public final class NodeNameTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager delegate;
    private final Map<String, String> expected;

    private NodeNameTrustManager(X509ExtendedTrustManager delegate, Map<String, String> expected) {
        this.delegate = delegate;
        this.expected = Map.copyOf(expected);
    }

    /** The certificate did not name the expected node. */
    public static final class NodeNameMismatchException extends CertificateException {
        public NodeNameMismatchException(String message) {
            super(message);
        }
    }

    /** Wraps the X.509 trust managers among {@code managers}; others are kept as they are. */
    public static TrustManager[] wrap(TrustManager[] managers, List<NodeConfig> nodes) {
        Map<String, String> byAddress = expectedNames(nodes);
        TrustManager[] out = new TrustManager[managers.length];
        for (int i = 0; i < managers.length; i++) {
            out[i] = managers[i] instanceof X509ExtendedTrustManager x
                    ? new NodeNameTrustManager(x, byAddress) : managers[i];
        }
        return out;
    }

    /** "host:port" of each node's agent URL, lower case, mapped to the node name. */
    static Map<String, String> expectedNames(List<NodeConfig> nodes) {
        Map<String, String> out = new HashMap<>();
        for (NodeConfig n : nodes) {
            try {
                URI u = URI.create(n.agentUrl());
                if (u.getHost() == null) {
                    continue;
                }
                int port = u.getPort() != -1 ? u.getPort() : "http".equalsIgnoreCase(u.getScheme()) ? 80 : 443;
                out.put(key(u.getHost(), port), n.name());
            } catch (IllegalArgumentException ignored) {
                // a bad URL fails the call itself, with its own message
            }
        }
        return out;
    }

    private static String key(String host, int port) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h + ":" + port;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        if (engine != null) {
            check(chain, engine.getPeerHost(), engine.getPeerPort());
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        if (socket instanceof SSLSocket s && s.getHandshakeSession() != null) {
            check(chain, s.getHandshakeSession().getPeerHost(), s.getHandshakeSession().getPeerPort());
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    private void check(X509Certificate[] chain, String host, int port) throws CertificateException {
        if (host == null || chain == null || chain.length == 0) {
            return;
        }
        String node = expected.get(key(host, port));
        if (node == null) {
            return;
        }
        Set<String> names = names(chain[0]);
        for (String n : names) {
            if (n.equalsIgnoreCase(node)) {
                return;
            }
        }
        throw new NodeNameMismatchException("the agent at " + host + ":" + port + " presents a certificate for "
                + (names.isEmpty() ? "no name" : String.join(", ", names)) + ", not for node " + node
                + " (check the agent URL and port, or turn off bfm.agent.tls.verify-node-name for certificates"
                + " issued without node names)");
    }

    /** The common name and the DNS names of a certificate. */
    static Set<String> names(X509Certificate cert) {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    out.add(String.valueOf(rdn.getValue()));
                }
            }
        } catch (InvalidNameException ignored) {
            // no readable subject: only the alternative names count
        }
        try {
            Collection<List<?>> alt = cert.getSubjectAlternativeNames();
            if (alt != null) {
                List<String> dns = new ArrayList<>();
                for (List<?> entry : alt) {
                    if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                        dns.add(String.valueOf(entry.get(1)));
                    }
                }
                out.addAll(dns);
            }
        } catch (CertificateParsingException ignored) {
            // no readable alternative names
        }
        return out;
    }
}
