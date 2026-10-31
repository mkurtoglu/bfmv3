package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/notifications}. */
@Route(value = "c/:cluster/notifications", layout = MainLayout.class)
@PermitAll
public class NotificationsView extends ClusterRoute {

    private final ClusterHub hub;
    private final AuthenticationContext auth;

    public NotificationsView(ClusterHub hub, AuthenticationContext auth) {
        super(hub, "Mail notifications");
        this.hub = hub;
        this.auth = auth;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        return new NotificationsPage(cluster.notifications(), auth, hub.multi());
    }
}
