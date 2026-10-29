package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.router.QueryParameters;
import com.bisoft.bfm.watcher.service.ConsoleService;

import java.util.List;
import java.util.Map;

/**
 * The one place a node's actions live: the same "…" menu on the Overview and on the Replication page, so an
 * operator finds rejoin and re-initialize in the same spot everywhere, and the destructive one looks destructive.
 */
final class NodeActions {

    /** What the menu needs to know about the node. */
    record Node(String name, boolean isPrimary, boolean agentOnline, boolean runsAsReplica,
                boolean noPrimaryRunning) {
    }

    private NodeActions() {
    }

    /**
     * @param details toggles the row's details, or null when the row has none
     * @param after   called after an action was started (to refresh the page)
     */
    static MenuBar menu(ConsoleService console, String user, boolean admin, Node n, Runnable details,
                        Runnable after) {
        MenuBar mb = new MenuBar();
        mb.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE, MenuBarVariant.LUMO_SMALL);
        MenuItem root = mb.addItem(VaadinIcon.ELLIPSIS_DOTS_H.create());
        root.setAriaLabel("Actions for " + n.name());
        root.getElement().setAttribute("title", "Actions for " + n.name());
        SubMenu sub = root.getSubMenu();
        if (details != null) {
            sub.addItem("Details", e -> details.run());
        }
        if (admin && !n.isPrimary()) {
            sub.addItem("Switch over to " + n.name() + "…", e -> UI.getCurrent().navigate(SwitchoverView.class,
                    new QueryParameters(Map.of("target", List.of(n.name())))));
            sub.addItem("Rejoin (pg_rewind)…", e -> Ui.confirmTyped(n.name(), "Rejoin " + n.name(),
                    n.name() + " is stopped, rewound with pg_rewind against the primary and started as a replica."
                            + " Its data directory is kept.", "Rejoin", false,
                    () -> start(() -> console.watcher().rejoin(n.name(), user), "Rejoin of " + n.name(), after)));
            MenuItem reinit = sub.addItem("Re-initialize…", e -> Ui.confirmTyped(n.name(),
                    "Re-initialize " + n.name(), "The data directory of " + n.name() + " is moved aside and copied"
                            + " again from the primary with pg_basebackup. Use it when a rejoin cannot work. This"
                            + " can take hours on a large database.", "Re-initialize", true,
                    () -> start(() -> console.watcher().reinit(n.name(), user), "Re-initialization of " + n.name(),
                            after)));
            reinit.addClassName("pg-danger");
        }
        if (admin && n.noPrimaryRunning() && n.agentOnline() && !n.runsAsReplica()) {
            sub.addItem("Start as primary…", e -> Ui.confirmTyped(n.name(), "Start " + n.name() + " as primary",
                    "No node runs as primary. The VIP comes up on " + n.name() + " first, then PostgreSQL starts"
                            + " there as primary.", "Start", true,
                    () -> start(() -> console.watcher().startPrimary(n.name(), user), n.name() + " as primary",
                            after)));
        }
        if (!admin) {
            MenuItem none = sub.addItem("Only admins can start operations");
            none.setEnabled(false);
        }
        return mb;
    }

    private static void start(Runnable action, String what, Runnable after) {
        if (Ui.attempt(action)) {
            Ui.ok(what + " started; follow it under Operations");
        }
        if (after != null) {
            after.run();
        }
    }
}
