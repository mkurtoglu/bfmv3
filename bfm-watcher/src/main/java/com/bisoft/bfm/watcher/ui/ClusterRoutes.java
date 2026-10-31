package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.QueryParameters;

/** URLs of cluster pages: {@code c/<cluster>/<page>}, relative to the console's root. */
final class ClusterRoutes {

    static final String PARAM = "cluster";

    private ClusterRoutes() {
    }

    static String path(String cluster, String page) {
        return "c/" + cluster + "/" + page;
    }

    static void go(String cluster, String page) {
        UI.getCurrent().navigate(path(cluster, page));
    }

    static void go(String cluster, String page, QueryParameters query) {
        UI.getCurrent().navigate(path(cluster, page), query);
    }
}
