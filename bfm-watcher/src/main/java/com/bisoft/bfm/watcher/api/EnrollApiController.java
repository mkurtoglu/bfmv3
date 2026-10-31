package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/v1/enroll}: a database host trades a one-time token and its certificate request for its
 * certificate and settings. No user login: the token is the credential, and it works once.
 */
@RestController
@RequestMapping("/api/v1")
public class EnrollApiController {

    private final EnrollmentService enrollment;
    private final WatcherCa ca;

    public EnrollApiController(EnrollmentService enrollment, WatcherCa ca) {
        this.enrollment = enrollment;
        this.ca = ca;
    }

    /**
     * @param hostname the host's name, for the event log
     * @param hostId   its /etc/machine-id, for the event log
     */
    public record EnrollRequest(String token, String csr, String hostname, String hostId) {
    }

    @PostMapping("/enroll")
    public EnrollmentService.Enrollment enroll(@RequestBody EnrollRequest r) {
        return enrollment.enroll(r.token(), r.csr(), r.hostname(), r.hostId());
    }

    /** The CA certificate agents trust (any user). */
    @GetMapping("/ca")
    public ResponseEntity<Map<String, Object>> ca() {
        if (!ca.exists()) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certificate", ca.certificatePem());
        out.put("description", ca.describe());
        return ResponseEntity.ok(out);
    }

    /**
     * Creates the CA (empty body) or takes over an existing one ({@code certificate} and {@code key}, PEM, as
     * {@code bfm-make-certs} wrote them). ADMIN; written to the event log of every cluster.
     */
    public record CaRequest(String certificate, String key) {
    }

    @PostMapping("/ca")
    public ResponseEntity<Map<String, Object>> createCa(@RequestBody(required = false) CaRequest r,
                                                        org.springframework.security.core.Authentication a) {
        if (r != null && r.certificate() != null && r.key() != null) {
            ca.importPem(r.certificate(), r.key());
        } else {
            ca.create("BFM CA");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("certificate", ca.certificatePem());
        out.put("description", ca.describe());
        out.put("by", a.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @ExceptionHandler(EnrollmentService.EnrollmentException.class)
    public ResponseEntity<Map<String, Object>> refused(EnrollmentService.EnrollmentException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
