package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/operations}. */
@Route(value = "c/:cluster/operations", layout = MainLayout.class)
@PermitAll
public class OperationsView extends ClusterRoute {

    private final BfmProperties props;

    public OperationsView(ClusterHub hub, BfmProperties props) {
        super(hub, "Operations");
        this.props = props;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        return new OperationsPage(cluster.console(), props.zone());
    }
}
