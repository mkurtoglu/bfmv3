package com.bisoft.bfm.watcher.api;

import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.FleetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** What spans clusters: the list of clusters with their health, and the hosts with their instances. */
@RestController
@RequestMapping("/api/v1")
public class FleetApiController {

    private final ClusterHub hub;
    private final FleetService fleet;

    public FleetApiController(ClusterHub hub, FleetService fleet) {
        this.hub = hub;
        this.fleet = fleet;
    }

    /** Every cluster this watcher runs, worst first. */
    @GetMapping("/clusters")
    public List<FleetService.ClusterSummary> clusters() {
        return fleet.clusters();
    }

    /** One cluster's overview (the same as {@code /api/v1/clusters/{cluster}/cluster}). */
    @GetMapping("/clusters/{cluster}")
    public ConsoleService.Overview cluster(@PathVariable String cluster) {
        return hub.require(cluster).console().overview();
    }

    /** Every host the agents report, with the instances of every cluster on it. */
    @GetMapping("/hosts")
    public List<FleetService.Host> hosts() {
        return fleet.hosts();
    }

    @ExceptionHandler(ClusterHub.UnknownClusterException.class)
    public ResponseEntity<Map<String, Object>> unknownCluster(ClusterHub.UnknownClusterException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
