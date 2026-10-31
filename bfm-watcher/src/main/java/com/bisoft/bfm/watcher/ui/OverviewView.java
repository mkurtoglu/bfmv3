package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/overview}: the cluster at a glance. */
@Route(value = "c/:cluster/overview", layout = MainLayout.class)
@PermitAll
public class OverviewView extends ClusterRoute {

    private final AuthenticationContext auth;
    private final BfmProperties props;

    public OverviewView(ClusterHub hub, AuthenticationContext auth, BfmProperties props) {
        super(hub, "Overview");
        this.auth = auth;
        this.props = props;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        return new OverviewPage(cluster.console(), cluster.notifications(), auth, props.zone());
    }
}
