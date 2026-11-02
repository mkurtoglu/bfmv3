package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/overview}: the cluster at a glance. */
@Route(value = "c/:cluster/overview", layout = MainLayout.class)
@PermitAll
public class OverviewView extends ClusterRoute {

    private final AuthenticationContext auth;
    private final BfmProperties props;

    private final ClusterWizardService wizard;
    private final ConsoleTls tls;

    public OverviewView(ClusterHub hub, AuthenticationContext auth, BfmProperties props, ClusterWizardService wizard,
                        ConsoleTls tls) {
        super(hub, "nav.overview");
        this.auth = auth;
        this.props = props;
        this.wizard = wizard;
        this.tls = tls;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        ClusterAdmin admin = cluster.fromConsole() ? new ClusterAdmin(cluster.name(), wizard, tls, props.zone(),
                auth.getPrincipalName().orElse("unknown")) : null;
        return new OverviewPage(cluster.console(), cluster.notifications(), auth, props.zone(), admin);
    }
}
