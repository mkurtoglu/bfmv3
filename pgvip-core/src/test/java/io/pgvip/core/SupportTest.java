package io.pgvip.core;

import com.sun.net.httpserver.HttpServer;
import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonMap;
import io.pgvip.common.model.ApiResult;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.PgRole;
import io.pgvip.core.agent.AgentCallException;
import io.pgvip.core.agent.AgentClient;
import io.pgvip.core.agent.HttpAgentClient;
import io.pgvip.core.config.NodeConfig;
import io.pgvip.core.event.ClusterEvent;
import io.pgvip.core.event.EventType;
import io.pgvip.core.event.MailRule;
import io.pgvip.core.notify.MailMessage;
import io.pgvip.core.notify.NotificationDispatcher;
import io.pgvip.core.notify.NotificationSettings;
import io.pgvip.core.notify.NotificationSettingsRepo;
import io.pgvip.core.notify.SecretBox;
import io.pgvip.core.state.ClusterState;
import io.pgvip.core.state.ClusterStateRepo;
import io.pgvip.core.state.LocalStateStore;
import io.pgvip.core.state.StateConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportTest {

    @Test
    void httpClientSendsTokenAndMapsRefusals() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = ex.getRequestURI().getPath();
            int status = 200;
            String reply;
            if (path.equals("/v1/status")) {
                reply = new NodeStatus("pg-a1", "0.1.0", 1, true, PgRole.PRIMARY, 3, "1", 160004, "0/13000318",
                        null, null, null, null, null, null, List.of(), List.of(), "192.168.0.50", true, 14, null,
                        false, null, null, null, null, null, null).toJson();
            } else if (path.equals("/v1/pg/promote")) {
                status = 409;
                reply = ApiResult.fail("VIP 192.168.0.50 is not up on this node; refusing to promote").toJson();
            } else if (path.equals("/v1/vip/check")) {
                status = 409;
                reply = new ApiResult(false, "VIP 192.168.0.50 answers from another host (arping -D)", null,
                        JsonMap.create().put("held", false).put("free", false)).toJson();
            } else {
                reply = ApiResult.ok("ok").toJson();
            }
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            AgentClient c = HttpAgentClient.factory(null, "secret", Duration.ofSeconds(3), Duration.ofSeconds(30))
                    .create(NodeConfig.of("pg-a1", url, "10.0.0.1", 5432, "DC1"));
            NodeStatus s = c.status();
            assertEquals("Bearer secret", auth.get());
            assertEquals(PgRole.PRIMARY, s.role());
            assertEquals(14, s.epoch());

            AgentCallException e = assertThrows(AgentCallException.class, () -> c.promote(15, 30000, false));
            assertTrue(e.reachable());
            assertEquals(409, e.httpStatus());
            assertTrue(e.detail().contains("refusing to promote"));
            JsonMap sent = Json.parseObject(body.get());
            assertEquals(15L, sent.lng("epoch", 0));
            assertTrue(sent.bool("requireVip", false));

            ApiResult vc = c.vipCheck();
            assertFalse(vc.ok(), "a taken VIP is an answer, not an error");

            c.grantLease(15, 30000, true);
            assertTrue(Json.parseObject(body.get()).bool("fenceOnExpiry", false));
        } finally {
            server.stop(0);
        }
        AgentClient dead = HttpAgentClient.factory(null, null, Duration.ofMillis(500), Duration.ofSeconds(1))
                .create(NodeConfig.of("pg-x", "http://127.0.0.1:1", "10.0.0.9", 5432, "DC1"));
        AgentCallException e = assertThrows(AgentCallException.class, dead::status);
        assertFalse(e.reachable());
    }

    @Test
    void localStoreKeepsStateAndEventsAcrossRestarts(@TempDir Path dir) {
        LocalStateStore s = new LocalStateStore("w1", dir);
        ClusterStateRepo repo = new ClusterStateRepo(s);
        repo.initialise(ClusterState.initial(3, "pg-a1", 1000, "test"));
        repo.update(c -> c.epoch() == 3, "moved", c -> c.withEpoch(4).withPrimary("pg-a2"));
        assertThrows(StateConflictException.class, () -> repo.update(c -> c.epoch() == 3, "moved", c -> c));
        s.appendEvent("{\"id\":\"1\"}");
        s.appendEvent("{\"id\":\"2\"}");
        s.put("observation/w1", "{}");

        LocalStateStore again = new LocalStateStore("w1", dir);
        ClusterState c = new ClusterStateRepo(again).current();
        assertEquals(4, c.epoch());
        assertEquals("pg-a2", c.primary());
        assertEquals(List.of("{\"id\":\"2\"}", "{\"id\":\"1\"}"), again.events(10));
        assertNull(again.get("observation/w1"), "observations are not persisted");
        assertFalse(again.compareAndSet(ClusterStateRepo.KEY, 1, "{}"), "stale version refused");
    }

    @Test
    void mailIsFilteredByRuleAndRecipientAndThrottled() {
        LocalStateStore store = LocalStateStore.inMemory("w1");
        new ClusterStateRepo(store).initialise(ClusterState.initial(1, "pg-a1", 0, "t"));
        NotificationSettingsRepo repo = new NotificationSettingsRepo(store);
        repo.update(s -> s
                .withSmtp(new NotificationSettings.Smtp("smtp.example.com", 587, NotificationSettings.Security.STARTTLS,
                        "u", null, "pgvip@example.com"))
                .withRecipients(List.of(
                        new NotificationSettings.Recipient("DBA team", "dba-team@example.com",
                                NotificationSettings.Filter.ALL),
                        new NotificationSettings.Recipient("On-call", "oncall@example.com",
                                NotificationSettings.Filter.CRITICAL))));
        ManualTime time = new ManualTime();
        List<MailMessage> sent = new ArrayList<>();
        NotificationDispatcher d = new NotificationDispatcher("prod-main", store, repo, (smtp, m) -> sent.add(m),
                Runnable::run, time, ZoneId.of("UTC"));

        d.accept(ev(time, EventType.FAILOVER_FINISHED, "pg-a2", false));
        assertEquals(1, sent.size());
        assertEquals(List.of("dba-team@example.com", "oncall@example.com"), sent.get(0).to());
        assertTrue(sent.get(0).subject().startsWith("[pgVIP][prod-main] CRITICAL: "));

        d.accept(ev(time, EventType.REPLICA_LAG, "pg-dr1", false));
        assertEquals(List.of("dba-team@example.com"), sent.get(1).to(), "warnings skip critical-only recipients");
        d.accept(ev(time, EventType.REPLICA_LAG, "pg-dr1", false));
        assertEquals(2, sent.size(), "throttled within 15 minutes");
        time.advance(16 * 60_000L);
        d.accept(ev(time, EventType.REPLICA_LAG, "pg-dr1", false));
        assertEquals(3, sent.size());

        d.accept(ev(time, EventType.REJOIN_FINISHED, "pg-a1", false));
        assertEquals(3, sent.size(), "reinit / rejoin mail is off by default");
        d.accept(ev(time, EventType.SWITCHOVER_FINISHED, "pg-a2", true));
        assertEquals(3, sent.size(), "quiet events are not mailed");

        repo.update(s -> s.withRules(Map.of(MailRule.REINIT_REJOIN, new NotificationSettings.Rule(true, 0))));
        d.accept(ev(time, EventType.REJOIN_FINISHED, "pg-a1", false));
        assertEquals(4, sent.size());

        new ClusterStateRepo(store).update(s -> true, "", s -> s.withControls(true, false, false, false));
        d.accept(ev(time, EventType.FAILOVER_FAILED, "pg-a2", false));
        assertEquals(4, sent.size(), "cluster mail switch off");
    }

    private static ClusterEvent ev(ManualTime t, EventType type, String node, boolean quiet) {
        return new ClusterEvent("x", t.millis(), type, type.severity(), node, type + " on " + node, "watcher w1",
                null, quiet);
    }

    @Test
    void settingsRoundTripAndSecretBox(@TempDir Path dir) throws Exception {
        NotificationSettings s = NotificationSettings.defaults()
                .withRules(Map.of(MailRule.WATCHER, new NotificationSettings.Rule(false, 42)))
                .withThresholds(1024, 2048);
        NotificationSettings back = NotificationSettings.fromJson(s.toJson());
        assertEquals(s, back);
        assertEquals(42, back.rule(MailRule.WATCHER).throttleMs());

        Path key = dir.resolve("secret.key");
        SecretBox box = SecretBox.fromKeyFile(key);
        String enc = box.encrypt("hunter2");
        assertNotEquals("hunter2", enc);
        assertEquals("hunter2", SecretBox.fromKeyFile(key).decrypt(enc));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(key)));
        SecretBox other = SecretBox.fromKeyFile(dir.resolve("other.key"));
        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }
}
