package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/**
 * The console's addresses from before it ran several clusters ({@code /}, {@code /replication}, ...) and a bare
 * {@code /c/<cluster>}: they lead to the page of the named cluster, or of the only one, so bookmarks keep working.
 * With several clusters and none named, they lead to the fleet.
 */
@Route("")
@RouteAlias("overview")
@RouteAlias("replication")
@RouteAlias("switchover")
@RouteAlias("operations")
@RouteAlias("events")
@RouteAlias("notifications")
@RouteAlias("c/:cluster")
@PermitAll
public class LegacyRedirect extends Div implements BeforeEnterObserver {

    private final ClusterHub hub;

    public LegacyRedirect(ClusterHub hub) {
        this.hub = hub;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        String named = e.getRouteParameters().get(ClusterRoutes.PARAM).orElse(null);
        if (named != null && hub.find(named).isPresent()) {
            e.forwardTo(ClusterRoutes.path(named, "overview"));
            return;
        }
        String path = e.getLocation().getPath();
        String page = path.isEmpty() || named != null ? "overview" : path;
        if (hub.names().size() != 1 || hub.clusters().isEmpty()) {
            e.forwardTo(FleetView.class);
            return;
        }
        e.forwardTo(ClusterRoutes.path(hub.clusters().get(0).name(), page), e.getLocation().getQueryParameters());
    }
}
