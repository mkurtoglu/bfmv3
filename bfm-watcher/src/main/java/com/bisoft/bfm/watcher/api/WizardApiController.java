package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.core.NotLeaderException;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
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
 * What the console's Add cluster, Add node and Detach do, for scripts: drafts, enrollment tokens, checks, creation;
 * nodes added to or removed from a cluster created this way; detaching it. Changes need ADMIN.
 */
@RestController
@RequestMapping("/api/v1")
public class WizardApiController {

    private final ClusterWizardService wizard;
    private final ConsoleTls tls;

    public WizardApiController(ClusterWizardService wizard, ConsoleTls tls) {
        this.wizard = wizard;
        this.tls = tls;
    }

    @GetMapping("/drafts")
    public List<ClusterWizardService.Draft> drafts() {
        return wizard.drafts();
    }

    public record NewDraft(String name, String environment) {
    }

    @PostMapping("/drafts")
    public ResponseEntity<ClusterWizardService.Draft> createDraft(@RequestBody NewDraft r, Authentication a) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wizard.createDraft(r.name(), r.environment(),
                a.getName()));
    }

    /**
     * A node as scripts send it: the name, the agent's address and PostgreSQL's address are required; the port
     * defaults to 5432, the data center to "default", the priority to 100.
     */
    public record NodeInput(String name, String agentUrl, String pgHost, Integer pgPort, String dc, Integer priority,
                            Boolean noFailover) {

        ClusterSpec.NodeSpec spec() {
            return new ClusterSpec.NodeSpec(name, agentUrl, pgHost, pgPort == null ? 5432 : pgPort,
                    dc == null || dc.isBlank() ? "default" : dc.trim(), priority == null ? 100 : priority,
                    Boolean.TRUE.equals(noFailover));
        }
    }

    /** Basics and nodes; any field left out keeps its value. */
    public record DraftChange(String environment, FailoverScope scope, Boolean selfFence, Long pollIntervalMs,
                              Integer failureThreshold, Long maxLagBytes, Boolean allowColocation,
                              List<NodeInput> nodes) {
    }

    @PutMapping("/drafts/{name}")
    public ClusterWizardService.Draft saveDraft(@PathVariable String name, @RequestBody DraftChange r) {
        ClusterSpec s = wizard.draft(name).spec();
        wizard.saveBasics(name, r.environment() != null ? r.environment() : s.environment(),
                r.scope() != null ? r.scope() : s.scope(), r.selfFence() != null ? r.selfFence() : s.selfFence(),
                r.pollIntervalMs() != null ? r.pollIntervalMs() : s.pollIntervalMs(),
                r.failureThreshold() != null ? r.failureThreshold() : s.failureThreshold(),
                r.maxLagBytes() != null ? r.maxLagBytes() : s.maxLagBytes(),
                r.allowColocation() != null ? r.allowColocation() : s.allowColocation());
        if (r.nodes() != null) {
            wizard.saveNodes(name, r.nodes().stream().map(NodeInput::spec).toList());
        }
        return wizard.draft(name);
    }

    public record NodeRef(String node) {
    }

    @PostMapping("/drafts/{name}/enrollments")
    public ResponseEntity<Map<String, Object>> draftToken(@PathVariable String name, @RequestBody NodeRef r,
                                                          Authentication a, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(token(wizard.token(name, r.node(), a.getName()),
                request));
    }

    @GetMapping("/drafts/{name}/checks")
    public List<ClusterWizardService.Check> checks(@PathVariable String name) {
        ClusterWizardService.Draft d = wizard.draft(name);
        return wizard.checks(d.id(), d.spec());
    }

    /**
     * @param mode    ADOPT (a node runs as primary) or BUILD
     * @param primary the node to start as primary with BUILD
     * @param confirm the cluster name, typed
     */
    public record Create(ClusterWizardService.StartMode mode, String primary, String confirm) {
    }

    @PostMapping("/drafts/{name}/create")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String name, @RequestBody Create r,
                                                      Authentication a) {
        ClusterHub.ClusterContext c = wizard.create(name, r.mode() == null ? ClusterWizardService.StartMode.ADOPT
                : r.mode(), r.primary(), r.confirm(), a.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("cluster", c.name(), "id", c.id()));
    }

    @DeleteMapping("/drafts/{name}")
    public ResponseEntity<Void> discard(@PathVariable String name, Authentication a) {
        wizard.discard(name, a.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/clusters/{cluster}/nodes")
    public ResponseEntity<Map<String, Object>> addNode(@PathVariable String cluster,
                                                       @RequestBody NodeInput input, Authentication a,
                                                       HttpServletRequest request) {
        ClusterSpec.NodeSpec node = input.spec();
        wizard.addNode(cluster, node, a.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(token(wizard.token(cluster, node.name(), a.getName()),
                request));
    }

    @DeleteMapping("/clusters/{cluster}/nodes/{node}")
    public ResponseEntity<Void> removeNode(@PathVariable String cluster, @PathVariable String node,
                                           Authentication a) {
        wizard.removeNode(cluster, node, a.getName());
        return ResponseEntity.noContent().build();
    }

    /** Detach: {@code DELETE /api/v1/clusters/erp?confirm=erp}. */
    @DeleteMapping("/clusters/{cluster}")
    public Map<String, Object> detach(@PathVariable String cluster, @RequestParam String confirm, Authentication a) {
        return Map.of("cluster", cluster, "detached", wizard.detach(cluster, confirm, a.getName()));
    }

    private Map<String, Object> token(EnrollmentService.Issued t, HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        String fp = tls.fingerprint();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster", t.cluster());
        out.put("node", t.node());
        out.put("token", t.token());
        out.put("expiresAtMillis", t.expiresAtMillis());
        out.put("command", "sudo minipgagent setup --enroll " + base + " --token " + t.token()
                + (fp == null ? " --insecure-http" : " --fingerprint " + fp));
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus s, String message) {
        return ResponseEntity.status(s).body(Map.of("error", message == null ? s.getReasonPhrase() : message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, StateConflictException.class})
    public ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotLeaderException.class)
    public ResponseEntity<Map<String, Object>> notLeader(NotLeaderException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ClusterHub.UnknownClusterException.class)
    public ResponseEntity<Map<String, Object>> unknown(ClusterHub.UnknownClusterException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
