package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import jakarta.annotation.security.PermitAll;

/** {@code c/<cluster>/switchover}, with {@code ?target=} or {@code ?operation=}. */
@Route(value = "c/:cluster/switchover", layout = MainLayout.class)
@PermitAll
public class SwitchoverView extends ClusterRoute {

    private final AuthenticationContext auth;
    private final BfmProperties props;

    public SwitchoverView(ClusterHub hub, AuthenticationContext auth, BfmProperties props) {
        super(hub, "Switchover");
        this.auth = auth;
        this.props = props;
    }

    @Override
    protected Component build(ClusterHub.ClusterContext cluster, Location location) {
        SwitchoverPage page = new SwitchoverPage(cluster.console(), auth, props.zone());
        page.enter(location);
        return page;
    }
}
