package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/events}, with {@code ?q=} for a filtered log. */
@Route(value = "c/:cluster/events", layout = MainLayout.class)
@PermitAll
public class EventsView extends ClusterRoute {

    private final BfmProperties props;

    public EventsView(ClusterHub hub, BfmProperties props) {
        super(hub, "Events");
        this.props = props;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        EventsPage page = new EventsPage(cluster.console(), props.zone());
        page.enter(location);
        return page;
    }
}
