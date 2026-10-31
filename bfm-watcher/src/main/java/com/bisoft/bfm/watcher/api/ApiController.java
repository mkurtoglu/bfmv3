package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.core.NotLeaderException;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.monitor.LagHistory;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateUnavailableException;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API of one cluster: {@code /api/v1/clusters/{cluster}/...}, and {@code /api/v1/...} while the watcher runs a
 * single cluster (with several, those answer 409 with the cluster names). GET needs any user; everything that
 * changes something needs ADMIN. Every change is written to the cluster's event log with the user name.
 */
@RestController
@RequestMapping({"/api/v1", "/api/v1/clusters/{cluster}"})
public class ApiController {

    // ---- reads ----

    @GetMapping("/cluster")
    public ConsoleService.Overview cluster(ClusterContext c) {
        return c.console().overview();
    }

    @GetMapping("/replication")
    public ConsoleService.Replication replication(ClusterContext c) {
        return c.console().replication();
    }

    @GetMapping("/events")
    public List<ClusterEvent> events(ClusterContext c, @RequestParam(defaultValue = "100") int limit) {
        return c.watcher().events(Math.min(Math.max(limit, 1), 1000));
    }

    @GetMapping("/operations")
    public List<OperationProgress.Snapshot> operations(ClusterContext c) {
        return c.watcher().operations();
    }

    @GetMapping("/operations/{id}")
    public ResponseEntity<OperationProgress.Snapshot> operation(ClusterContext c, @PathVariable String id) {
        return c.watcher().operation(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/switchover/checks")
    public List<PreCheck> checks(ClusterContext c, @RequestParam String target) {
        return c.watcher().switchoverChecks(target);
    }

    @GetMapping("/lag/{node}")
    public List<LagHistory.Sample> lag(ClusterContext c, @PathVariable String node,
                                       @RequestParam(defaultValue = "30") int minutes) {
        return c.watcher().lagHistory().series(node, Math.min(Math.max(minutes, 1), 60), System.currentTimeMillis());
    }

    @GetMapping("/notifications")
    public NotificationService.SettingsView notificationSettings(ClusterContext c) {
        return c.notifications().view();
    }

    // ---- commands ----

    public record SwitchoverRequest(String target, String confirm, Boolean checkpointFirst,
                                    Boolean restartOldAsReplica, Boolean mail) {
    }

    @PostMapping("/switchover")
    public ResponseEntity<OperationProgress.Snapshot> switchover(ClusterContext c, @RequestBody SwitchoverRequest r,
                                                                 Authentication a) {
        SwitchoverOperation.Options o = new SwitchoverOperation.Options(
                r.checkpointFirst() == null || r.checkpointFirst(),
                r.restartOldAsReplica() == null || r.restartOldAsReplica(),
                r.mail() == null || r.mail());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(c.watcher().switchover(r.target(), o, a.getName(),
                r.confirm()));
    }

    @PostMapping("/nodes/{node}/rejoin")
    public ResponseEntity<OperationProgress.Snapshot> rejoin(ClusterContext c, @PathVariable String node,
                                                             Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(c.watcher().rejoin(node, a.getName()));
    }

    @PostMapping("/nodes/{node}/reinit")
    public ResponseEntity<OperationProgress.Snapshot> reinit(ClusterContext c, @PathVariable String node,
                                                             Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(c.watcher().reinit(node, a.getName()));
    }

    @PostMapping("/nodes/{node}/start-primary")
    public ResponseEntity<OperationProgress.Snapshot> startPrimary(ClusterContext c, @PathVariable String node,
                                                                   Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(c.watcher().startPrimary(node, a.getName()));
    }

    /** The last failover or switchover; the console shows it until someone acknowledges it. 204 when none. */
    @GetMapping("/role-change")
    public ResponseEntity<RoleChange> roleChange(ClusterContext c) {
        RoleChange rc = c.watcher().lastRoleChange();
        return rc == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(rc);
    }

    @PostMapping("/role-change/acknowledge")
    public RoleChange acknowledgeRoleChange(ClusterContext c, Authentication a) {
        return c.watcher().acknowledgeRoleChange(a.getName());
    }

    public record Controls(Boolean autoFailover, Boolean maintenance, Boolean mailEnabled, Boolean autoReinit) {
    }

    @PutMapping("/controls")
    public ClusterState controls(ClusterContext c, @RequestBody Controls body, Authentication a) {
        return c.watcher().setControls(body.autoFailover(), body.maintenance(), body.mailEnabled(),
                body.autoReinit(), a.getName());
    }

    @DeleteMapping("/slots/{name}")
    public ResponseEntity<Void> dropSlot(ClusterContext c, @PathVariable String name, Authentication a) {
        c.watcher().dropSlot(name, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/notifications/smtp")
    public NotificationService.SettingsView smtp(ClusterContext c, @RequestBody NotificationService.SmtpChange body,
                                                 Authentication a) {
        c.notifications().saveSmtp(body, a.getName());
        return c.notifications().view();
    }

    @PutMapping("/notifications/recipients")
    public NotificationService.SettingsView recipients(ClusterContext c,
                                                       @RequestBody List<NotificationSettings.Recipient> r,
                                                       Authentication a) {
        c.notifications().saveRecipients(r, a.getName());
        return c.notifications().view();
    }

    @PutMapping("/notifications/rules")
    public NotificationService.SettingsView rules(ClusterContext c,
                                                  @RequestBody Map<MailRule, NotificationSettings.Rule> r,
                                                  Authentication a) {
        c.notifications().saveRules(r, a.getName());
        return c.notifications().view();
    }

    public record Thresholds(long lagAlertBytes, long slotAlertBytes) {
    }

    @PutMapping("/notifications/thresholds")
    public NotificationService.SettingsView thresholds(ClusterContext c, @RequestBody Thresholds t, Authentication a) {
        c.notifications().saveThresholds(t.lagAlertBytes(), t.slotAlertBytes(), a.getName());
        return c.notifications().view();
    }

    @PostMapping("/notifications/test")
    public Map<String, Object> testMail(ClusterContext c, Authentication a) throws Exception {
        return Map.of("sentTo", c.notifications().sendTest(a.getName()));
    }

    // ---- errors ----

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String leader) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (leader != null) {
            body.put("leader", leader);
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ClusterHub.UnknownClusterException.class)
    public ResponseEntity<Map<String, Object>> unknownCluster(ClusterHub.UnknownClusterException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(ClusterHub.ClusterRequiredException.class)
    public ResponseEntity<Map<String, Object>> clusterRequired(ClusterHub.ClusterRequiredException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("clusters", e.clusters());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<Map<String, Object>> notLeader(NotLeaderException e) {
        return error(HttpStatus.CONFLICT, e.getMessage(), e.leaderId());
    }

    @ExceptionHandler({StateConflictException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(AgentCallException.class)
    public ResponseEntity<Map<String, Object>> agent(AgentCallException e) {
        return error(HttpStatus.BAD_GATEWAY, e.getMessage(), null);
    }

    @ExceptionHandler(StateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> unavailable(StateUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), null);
    }
}
