package com.bisoft.bfm.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.bisoft.bfm.agent.AgentConfig;
import com.bisoft.bfm.agent.AgentException;
import com.bisoft.bfm.agent.AgentService;
import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonException;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.tls.TlsSupport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The agent's HTTPS API. With TLS on, clients must present a certificate signed by the configured CA
 * (mutual TLS). An optional bearer token adds a second check, and is required when TLS is off (lab use).
 */
public class AgentHttpServer {

    private static final Logger LOG = Logger.getLogger(AgentHttpServer.class.getName());
    private static final int MAX_BODY = 64 * 1024;

    private final AgentConfig cfg;
    private final Map<String, Function<JsonMap, JsonWritable>> routes = new LinkedHashMap<>();
    private final AgentService svc;
    private HttpServer server;
    private ExecutorService executor;

    public AgentHttpServer(AgentConfig cfg, AgentService svc) {
        this.cfg = cfg;
        this.svc = svc;
        routes.put("GET /v1/health", b -> ApiResult.ok("ok"));
        routes.put("GET /v1/status", b -> svc.status());
        routes.put("POST /v1/lease", svc::grantLease);
        routes.put("POST /v1/vip/acquire", svc::vipAcquire);
        routes.put("POST /v1/vip/release", svc::vipRelease);
        routes.put("GET /v1/vip/check", b -> svc.vipCheck());
        routes.put("POST /v1/pg/promote", svc::promote);
        routes.put("POST /v1/pg/checkpoint", svc::checkpoint);
        routes.put("POST /v1/pg/sync-standbys", svc::setSyncStandbys);
        routes.put("POST /v1/pg/stop", svc::stopPg);
        routes.put("POST /v1/pg/start", svc::startPg);
        routes.put("POST /v1/pg/follow", svc::follow);
        routes.put("POST /v1/pg/demote", svc::demote);
        routes.put("POST /v1/pg/reinit", svc::reinit);
        routes.put("POST /v1/fence", svc::fence);
        routes.put("POST /v1/fence/clear", svc::clearFence);
        routes.put("POST /v1/peer-check", svc::peerCheck);
        routes.put("POST /v1/slots/ensure", svc::ensureSlots);
        routes.put("POST /v1/slots/drop", svc::dropSlot);
        routes.put("POST /v1/checks", svc::checks);
        routes.put("POST /v1/unbind", svc::unbind);
    }

    public void start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(cfg.listenAddress, cfg.listenPort);
        if (cfg.tlsEnabled) {
            SSLContext ctx = TlsSupport.context(cfg.keyStore, cfg.keyStorePassword, cfg.trustStore, cfg.trustStorePassword);
            HttpsServer https = HttpsServer.create(addr, 64);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sp = getSSLContext().getDefaultSSLParameters();
                    sp.setNeedClientAuth(true);
                    sp.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(sp);
                }
            });
            server = https;
        } else {
            LOG.warning("TLS is disabled; the API is protected by the bearer token only");
            server = HttpServer.create(addr, 64);
        }
        executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "minipgagent-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        LOG.info("agent API listening on " + (cfg.tlsEnabled ? "https" : "http") + "://" + cfg.listenAddress + ":" + cfg.listenPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        Object reply;
        try {
            String caller = callerCn(ex);
            if (!authorized(ex)) {
                status = 401;
                reply = ApiResult.fail("unauthorized");
            } else if (!cfg.allowedClients.isEmpty() && (caller == null || !cfg.allowedClients.contains(caller))) {
                // tls.allowed.clients (user decision 2026-11-02): another database host's agent certificate, signed by
                // the same CA, must not be able to promote or fence this node
                LOG.warning("refused a call from certificate CN=" + caller + " (not in tls.allowed.clients)");
                status = 403;
                reply = ApiResult.fail("certificate CN=" + caller + " may not call this agent: add it to"
                        + " tls.allowed.clients in agent.properties and restart the agent");
            } else {
                String path = ex.getRequestURI().getPath();
                String key = ex.getRequestMethod() + " " + path;
                if (ex.getRequestMethod().equals("GET") && path.startsWith("/v1/operations/")) {
                    reply = svc.operation(path.substring("/v1/operations/".length()));
                } else {
                    Function<JsonMap, JsonWritable> route = routes.get(key);
                    if (route == null) {
                        status = 404;
                        reply = ApiResult.fail("no such endpoint: " + key);
                    } else {
                        JsonWritable result = route.apply(readBody(ex));
                        reply = result;
                        if (result instanceof ApiResult r && !r.ok()) {
                            status = 409;
                        }
                    }
                }
            }
        } catch (AgentException e) {
            status = e.httpStatus();
            reply = ApiResult.fail(e.getMessage());
        } catch (JsonException | IllegalArgumentException e) {
            status = 400;
            reply = ApiResult.fail(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "request failed", e);
            status = 500;
            reply = ApiResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        byte[] bytes = Json.write(reply).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** The CN of the caller's client certificate, or null (no TLS, no certificate, no CN). */
    static String callerCn(HttpExchange ex) {
        if (!(ex instanceof com.sun.net.httpserver.HttpsExchange hx) || hx.getSSLSession() == null) {
            return null;
        }
        try {
            java.security.cert.Certificate[] chain = hx.getSSLSession().getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof java.security.cert.X509Certificate x)) {
                return null;
            }
            return cn(x.getSubjectX500Principal().getName());
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            return null;
        }
    }

    /** The CN of an RFC 2253 distinguished name, or null. */
    public static String cn(String dn) {
        try {
            for (javax.naming.ldap.Rdn rdn : new javax.naming.ldap.LdapName(dn).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (javax.naming.InvalidNameException e) {
            return null;
        }
        return null;
    }

    private boolean authorized(HttpExchange ex) {
        if (cfg.apiToken == null) {
            return true;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + cfg.apiToken;
        return header != null && MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonMap readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY + 1);
            if (data.length > MAX_BODY) {
                throw new IllegalArgumentException("request body too large");
            }
            String text = new String(data, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? JsonMap.create() : Json.parseObject(text);
        }
    }
}
