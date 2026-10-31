package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Location;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;

/**
 * A page of one cluster, at {@code c/<cluster>/<page>}. Finds the cluster the URL names and builds the page for it;
 * Vaadin keeps this instance when only the cluster in the URL changes, so the page is built again then.
 */
abstract class ClusterRoute extends Div implements BeforeEnterObserver, HasDynamicTitle {

    /** A page that reads its URL (query parameters) whenever it is entered again. */
    interface Enterable {
        void enter(Location location);
    }

    private final ClusterHub hub;
    private final String title;
    private ClusterContext cluster;
    private Component page;

    protected ClusterRoute(ClusterHub hub, String title) {
        this.hub = hub;
        this.title = title;
        getStyle().set("display", "contents");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        String name = e.getRouteParameters().get(ClusterRoutes.PARAM).orElse(null);
        ClusterContext c = hub.find(name).orElse(null);
        if (c == null) {
            // An unknown or not yet registered cluster: the fleet (or the only cluster) instead.
            e.forwardTo(hub.multi() ? FleetView.class : LegacyRedirect.class);
            return;
        }
        if (cluster == null || !cluster.name().equals(c.name())) {
            cluster = c;
            removeAll();
            page = build(c, e.getLocation());
            add(page);
        } else if (page instanceof Enterable en) {
            en.enter(e.getLocation());
        }
    }

    /** The page for {@code cluster}, built for the URL it was opened with. */
    protected abstract Component build(ClusterContext cluster, Location location);

    @Override
    public String getPageTitle() {
        return title + (cluster == null ? "" : " · " + cluster.name()) + " · BFM";
    }
}
