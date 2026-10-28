package io.pgvip.agent.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.pgvip.agent.AgentConfig;
import io.pgvip.agent.AgentException;
import io.pgvip.agent.AgentService;
import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonException;
import io.pgvip.common.json.JsonMap;
import io.pgvip.common.json.JsonWritable;
import io.pgvip.common.model.ApiResult;
import io.pgvip.common.tls.TlsSupport;

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
            Thread t = new Thread(r, "pgvip-agent-http");
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
            if (!authorized(ex)) {
                status = 401;
                reply = ApiResult.fail("unauthorized");
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
