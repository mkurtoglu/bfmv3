package com.bisoft.bfm.core.agent;

import com.bisoft.bfm.common.json.JsonException;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.core.config.NodeConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Agent client over HTTPS with mutual TLS (or plain HTTP with a bearer token in a lab).
 *
 * <p>Two timeouts: a short one for probes (status, lease, peer check, operation progress) and a long one for
 * commands that run PostgreSQL or VIP tools on the node (promote, stop, start, CHECKPOINT, arping). A command must
 * not time out while the agent is still working on it, or the watcher would act on an outcome it does not know.
 *
 * <p>System proxy settings are ignored on purpose: agents sit on the database network and a corporate proxy
 * in between would break mutual TLS.
 */
public class HttpAgentClient implements AgentClient {

    private final String node;
    private final String baseUrl;
    private final HttpClient http;
    private final String token;
    private final Duration timeout;
    private final Duration commandTimeout;
    private volatile String clusterId;
    private volatile String clusterName;

    public HttpAgentClient(String node, String baseUrl, HttpClient http, String token, Duration timeout,
                           Duration commandTimeout) {
        this.node = node;
        this.baseUrl = baseUrl;
        this.http = http;
        this.token = token;
        this.timeout = timeout;
        this.commandTimeout = commandTimeout;
    }

    /**
     * A factory sharing one HTTP client between all nodes.
     *
     * @param tls            mutual-TLS context, or null for plain HTTP
     * @param token          optional bearer token
     * @param timeout        timeout of a probe (and of connecting)
     * @param commandTimeout timeout of a command; longer than the slowest thing the agent runs
     */
    public static AgentClientFactory factory(SSLContext tls, String token, Duration timeout, Duration commandTimeout) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1);
        if (tls != null) {
            SSLParameters params = tls.getDefaultSSLParameters();
            params.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            b.sslContext(tls).sslParameters(params);
        }
        HttpClient http = b.build();
        return (NodeConfig n) -> new HttpAgentClient(n.name(), n.agentUrl(), http, token, timeout, commandTimeout);
    }

    @Override
    public String node() {
        return node;
    }

    @Override
    public void useCluster(String clusterId, String clusterName) {
        this.clusterName = clusterName;
        this.clusterId = clusterId;
    }

    @Override
    public NodeStatus status() {
        return NodeStatus.fromJson(call("GET", "/v1/status", null, timeout, false));
    }

    @Override
    public ApiResult grantLease(long epoch, long ttlMs, boolean fenceOnExpiry) {
        return probe("/v1/lease", body(epoch).put("ttlMs", ttlMs).put("fenceOnExpiry", fenceOnExpiry));
    }

    @Override
    public ApiResult vipAcquire(long epoch) {
        return command("/v1/vip/acquire", body(epoch));
    }

    @Override
    public ApiResult vipRelease(long epoch) {
        return command("/v1/vip/release", body(epoch));
    }

    @Override
    public ApiResult vipCheck() {
        return ApiResult.fromJson(call("GET", "/v1/vip/check", null, commandTimeout, true));
    }

    @Override
    public ApiResult promote(long epoch, long leaseTtlMs, boolean fenceOnExpiry) {
        return command("/v1/pg/promote", body(epoch).put("leaseTtlMs", leaseTtlMs)
                .put("fenceOnExpiry", fenceOnExpiry));
    }

    @Override
    public ApiResult checkpoint(long epoch) {
        return command("/v1/pg/checkpoint", body(epoch));
    }

    @Override
    public ApiResult stop(long epoch, String mode, boolean releaseVip) {
        return command("/v1/pg/stop", body(epoch).put("mode", mode).put("releaseVip", releaseVip));
    }

    @Override
    public ApiResult start(long epoch, boolean allowPrimary) {
        return command("/v1/pg/start", body(epoch).put("allowPrimary", allowPrimary));
    }

    @Override
    public ApiResult follow(long epoch, String host, int port, String slot) {
        return command("/v1/pg/follow", body(epoch).put("host", host).put("port", port).put("slot", slot));
    }

    @Override
    public ApiResult demote(long epoch, String host, int port, String slot, boolean rewind, String stopMode) {
        return command("/v1/pg/demote", body(epoch).put("host", host).put("port", port).put("slot", slot)
                .put("rewind", rewind).put("stopMode", stopMode));
    }

    @Override
    public ApiResult reinit(long epoch, String host, int port, String slot) {
        return command("/v1/pg/reinit", body(epoch).put("host", host).put("port", port).put("slot", slot));
    }

    @Override
    public OperationStatus operation(String id) {
        return OperationStatus.fromJson(call("GET", "/v1/operations/" + URLEncoder.encode(id, StandardCharsets.UTF_8),
                null, timeout, false));
    }

    @Override
    public ApiResult fence(long epoch, String reason) {
        return command("/v1/fence", body(epoch).put("reason", reason));
    }

    @Override
    public ApiResult clearFence(long epoch) {
        return command("/v1/fence/clear", body(epoch));
    }

    @Override
    public ApiResult peerCheck(String host, int port, long timeoutMs) {
        return ApiResult.fromJson(call("POST", "/v1/peer-check", JsonMap.create().put("host", host).put("port", port)
                .put("timeoutMs", timeoutMs), timeout.plusMillis(timeoutMs), false));
    }

    @Override
    public ApiResult ensureSlots(long epoch, List<String> names) {
        JsonMap b = body(epoch).put("names", names);
        return names.isEmpty() ? probe("/v1/slots/ensure", b) : command("/v1/slots/ensure", b);
    }

    @Override
    public ApiResult dropSlot(long epoch, String name) {
        return command("/v1/slots/drop", body(epoch).put("name", name));
    }

    private JsonMap body(long epoch) {
        JsonMap b = JsonMap.create().put("epoch", epoch);
        String id = clusterId;
        if (id != null) {
            b.put("clusterId", id).put("clusterName", clusterName);
        }
        return b;
    }

    private ApiResult command(String path, JsonMap body) {
        return ApiResult.fromJson(call("POST", path, body, commandTimeout, false));
    }

    private ApiResult probe(String path, JsonMap body) {
        return ApiResult.fromJson(call("POST", path, body, timeout, false));
    }

    /**
     * Sends one request. Non-2xx answers become {@link AgentCallException} with the agent's message, unless
     * {@code acceptRefusal} is set and the answer is a well-formed refusal (used by the VIP check, which
     * reports a taken address as a 409).
     */
    private String call(String method, String path, JsonMap body, Duration callTimeout, boolean acceptRefusal) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(callTimeout)
                .header("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            rb.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            rb.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toJson()));
        } else {
            rb.GET();
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            NodeNameTrustManager.NodeNameMismatchException wrong = nameMismatch(e);
            if (wrong != null) {
                // Not an unreachable node: the watcher reached some agent, only not this node's.
                throw new AgentCallException(node, com.bisoft.bfm.core.state.ClusterIdentity.WRONG_IDENTITY
                        + wrong.getMessage(), e);
            }
            throw new AgentCallException(node, "unreachable: " + describe(e, baseUrl, callTimeout), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCallException(node, "interrupted", e);
        } catch (IllegalArgumentException e) {
            throw new AgentCallException(node, "bad agent URL " + baseUrl + ": " + e.getMessage(), e);
        }
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            return resp.body();
        }
        String message;
        try {
            message = ApiResult.fromJson(resp.body()).message();
            if (acceptRefusal && status == 409) {
                return resp.body();
            }
        } catch (JsonException | IllegalArgumentException e) {
            message = "HTTP " + status;
        }
        throw new AgentCallException(node, status, message == null ? "HTTP " + status : message);
    }

    private static NodeNameTrustManager.NodeNameMismatchException nameMismatch(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof NodeNameTrustManager.NodeNameMismatchException m) {
                return m;
            }
        }
        return null;
    }

    /** What went wrong, in the words an operator needs; never a bare Java class name. */
    static String describe(IOException e, String baseUrl, Duration timeout) {
        String where = baseUrl.replaceFirst("^https?://", "");
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (e instanceof java.net.http.HttpConnectTimeoutException || root instanceof java.net.SocketTimeoutException
                && String.valueOf(root.getMessage()).contains("connect")) {
            return "no answer from " + where + " within " + timeout.toMillis() / 1000.0 + " s (host down or"
                    + " firewalled)";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "the agent at " + where + " did not reply within " + timeout.toMillis() / 1000.0 + " s";
        }
        if (root instanceof java.net.ConnectException || e instanceof java.net.ConnectException) {
            return "connection refused by " + where + " (agent not running, or wrong port)";
        }
        if (root instanceof java.net.UnknownHostException) {
            return "unknown host in " + where;
        }
        if (root instanceof java.net.NoRouteToHostException) {
            return "no route to " + where;
        }
        if (root instanceof javax.net.ssl.SSLException || e instanceof javax.net.ssl.SSLException) {
            String m = root.getMessage() == null ? "" : ": " + root.getMessage();
            return "TLS handshake with " + where + " failed (check the certificates and the CA)" + m;
        }
        String m = e.getMessage() == null || e.getMessage().isBlank()
                ? (root.getMessage() == null ? "" : root.getMessage()) : e.getMessage();
        return m.isBlank() ? "connection to " + where + " failed" : "connection to " + where + " failed: " + m;
    }
}
