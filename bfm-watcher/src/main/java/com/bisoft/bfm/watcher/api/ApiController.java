package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.core.NotLeaderException;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.monitor.LagHistory;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateUnavailableException;
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
 * REST API, {@code /api/v1}. GET needs any user; everything that changes something needs ADMIN. Every change is
 * written to the event log with the user name.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final Watcher watcher;
    private final ConsoleService console;
    private final NotificationService notifications;

    public ApiController(Watcher watcher, ConsoleService console, NotificationService notifications) {
        this.watcher = watcher;
        this.console = console;
        this.notifications = notifications;
    }

    // ---- reads ----

    @GetMapping("/cluster")
    public ConsoleService.Overview cluster() {
        return console.overview();
    }

    @GetMapping("/replication")
    public ConsoleService.Replication replication() {
        return console.replication();
    }

    @GetMapping("/events")
    public List<ClusterEvent> events(@RequestParam(defaultValue = "100") int limit) {
        return watcher.events(Math.min(Math.max(limit, 1), 1000));
    }

    @GetMapping("/operations")
    public List<OperationProgress.Snapshot> operations() {
        return watcher.operations();
    }

    @GetMapping("/operations/{id}")
    public ResponseEntity<OperationProgress.Snapshot> operation(@PathVariable String id) {
        return watcher.operation(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/switchover/checks")
    public List<PreCheck> checks(@RequestParam String target) {
        return watcher.switchoverChecks(target);
    }

    @GetMapping("/lag/{node}")
    public List<LagHistory.Sample> lag(@PathVariable String node, @RequestParam(defaultValue = "30") int minutes) {
        return watcher.lagHistory().series(node, Math.min(Math.max(minutes, 1), 60), System.currentTimeMillis());
    }

    @GetMapping("/notifications")
    public NotificationService.SettingsView notificationSettings() {
        return notifications.view();
    }

    // ---- commands ----

    public record SwitchoverRequest(String target, String confirm, Boolean checkpointFirst,
                                    Boolean restartOldAsReplica, Boolean mail) {
    }

    @PostMapping("/switchover")
    public ResponseEntity<OperationProgress.Snapshot> switchover(@RequestBody SwitchoverRequest r, Authentication a) {
        SwitchoverOperation.Options o = new SwitchoverOperation.Options(
                r.checkpointFirst() == null || r.checkpointFirst(),
                r.restartOldAsReplica() == null || r.restartOldAsReplica(),
                r.mail() == null || r.mail());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(watcher.switchover(r.target(), o, a.getName(),
                r.confirm()));
    }

    @PostMapping("/nodes/{node}/rejoin")
    public ResponseEntity<OperationProgress.Snapshot> rejoin(@PathVariable String node, Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(watcher.rejoin(node, a.getName()));
    }

    @PostMapping("/nodes/{node}/reinit")
    public ResponseEntity<OperationProgress.Snapshot> reinit(@PathVariable String node, Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(watcher.reinit(node, a.getName()));
    }

    @PostMapping("/nodes/{node}/start-primary")
    public ResponseEntity<OperationProgress.Snapshot> startPrimary(@PathVariable String node, Authentication a) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(watcher.startPrimary(node, a.getName()));
    }

    public record Controls(Boolean autoFailover, Boolean maintenance, Boolean mailEnabled, Boolean autoReinit) {
    }

    @PutMapping("/controls")
    public ClusterState controls(@RequestBody Controls c, Authentication a) {
        return watcher.setControls(c.autoFailover(), c.maintenance(), c.mailEnabled(), c.autoReinit(), a.getName());
    }

    @DeleteMapping("/slots/{name}")
    public ResponseEntity<Void> dropSlot(@PathVariable String name, Authentication a) {
        watcher.dropSlot(name, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/notifications/smtp")
    public NotificationService.SettingsView smtp(@RequestBody NotificationService.SmtpChange c, Authentication a) {
        notifications.saveSmtp(c, a.getName());
        return notifications.view();
    }

    @PutMapping("/notifications/recipients")
    public NotificationService.SettingsView recipients(@RequestBody List<NotificationSettings.Recipient> r,
                                                       Authentication a) {
        notifications.saveRecipients(r, a.getName());
        return notifications.view();
    }

    @PutMapping("/notifications/rules")
    public NotificationService.SettingsView rules(@RequestBody Map<MailRule, NotificationSettings.Rule> r,
                                                  Authentication a) {
        notifications.saveRules(r, a.getName());
        return notifications.view();
    }

    public record Thresholds(long lagAlertBytes, long slotAlertBytes) {
    }

    @PutMapping("/notifications/thresholds")
    public NotificationService.SettingsView thresholds(@RequestBody Thresholds t, Authentication a) {
        notifications.saveThresholds(t.lagAlertBytes(), t.slotAlertBytes(), a.getName());
        return notifications.view();
    }

    @PostMapping("/notifications/test")
    public Map<String, Object> testMail(Authentication a) throws Exception {
        return Map.of("sentTo", notifications.sendTest(a.getName()));
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
