package com.bisoft.bfm.watcher.ui;

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

    /**
     * What the menu needs to know about the node.
     *
     * @param otherDatabase its agent is this cluster's, but its data directory holds another database
     * @param remove        takes it out of a cluster created in the console, or null
     */
    record Node(String name, boolean isPrimary, boolean agentOnline, boolean runsAsReplica,
                boolean noPrimaryRunning, boolean otherDatabase, Runnable remove) {
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
        root.setAriaLabel(Ui.t("na.actionsFor", n.name()));
        root.getElement().setAttribute("title", Ui.t("na.actionsFor", n.name()));
        SubMenu sub = root.getSubMenu();
        if (details != null) {
            sub.addItem(Ui.t("ov.details"), e -> details.run());
        }
        if (admin && !n.isPrimary()) {
            sub.addItem(Ui.t("na.switchTo", n.name()), e -> ClusterRoutes.go(console.watcher().config().name(),
                    "switchover", new QueryParameters(Map.of("target", List.of(n.name())))));
            sub.addItem(Ui.t("na.rejoin"), e -> Ui.confirmTyped(n.name(), Ui.t("na.rejoinTitle", n.name()),
                    Ui.t("na.rejoinText", n.name()), Ui.t("na.rejoinButton"), false,
                    () -> start(() -> console.watcher().rejoin(n.name(), user), Ui.t("na.rejoinOf", n.name()), after)));
            MenuItem reinit = sub.addItem(Ui.t("na.reinit"), e -> Ui.confirmTyped(n.name(),
                    Ui.t("na.reinitTitle", n.name()), Ui.t("na.reinitText", n.name()), Ui.t("na.reinitButton"), true,
                    () -> start(() -> console.watcher().reinit(n.name(), user), Ui.t("na.reinitOf", n.name()),
                            after)));
            reinit.addClassName("pg-danger");
        }
        if (admin && n.noPrimaryRunning() && (n.agentOnline() && !n.runsAsReplica() || n.otherDatabase())) {
            String text = n.otherDatabase()
                    ? Ui.t("na.otherDb", n.name(), Ui.tr(console.watcher().identity().problem(n.name())))
                    : Ui.t("na.noPrimary", n.name());
            sub.addItem(Ui.t("na.startPrimary"), e -> Ui.confirmTyped(n.name(), Ui.t("na.startPrimaryTitle", n.name()),
                    text, Ui.t("na.startButton"), true,
                    () -> start(() -> console.watcher().startPrimary(n.name(), user), Ui.t("na.asPrimary", n.name()),
                            after)));
        }
        if (admin && n.remove() != null) {
            MenuItem rm = sub.addItem(Ui.t("na.remove"), e -> n.remove().run());
            rm.addClassName("pg-danger");
        }
        if (!admin) {
            MenuItem none = sub.addItem(Ui.t("na.adminsOnly"));
            none.setEnabled(false);
        }
        return mb;
    }

    private static void start(Runnable action, String what, Runnable after) {
        if (Ui.attempt(action)) {
            Ui.ok(Ui.t("na.started", what));
        }
        if (after != null) {
            after.run();
        }
    }
}
