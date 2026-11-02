package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.ConsoleService.ControlChange;
import com.bisoft.bfm.watcher.service.ConsoleService.NodeRow;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import com.bisoft.bfm.watcher.service.NotificationService;
import com.bisoft.bfm.watcher.service.Terms;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The cluster at a glance. What matters in an incident comes first: whether the cluster is healthy and why not,
 * whether a role change just happened, and what an operator can do about it. Replication detail lives on the
 * Replication page and the switchover form on its own page.
 */
class OverviewPage extends Div {

    private final ConsoleService console;
    private final NotificationService notifications;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;

    private final Div banners = new Div();
    private final Span headStatus = new Span();
    private final Div health = new Div();
    private final Div stats = Ui.div("pg-grid-auto pg-stats");
    private final Grid<NodeRow> grid = new Grid<>(NodeRow.class, false);
    private final Div nodeCards = Ui.div("pg-node-cards");
    private final Set<String> openRows = new HashSet<>();
    private final Span refreshed = Ui.text("pg-note", "");
    private final Div topology = new Div();
    private final UnorderedList events = new UnorderedList();
    private final Div watchers = new Div();
    private final Checkbox mail = new Checkbox(Ui.t("ov.mailNotifications"));
    private final Checkbox maintenance = new Checkbox(Ui.t("ov.maintenanceMode"));
    private final Checkbox autoReinit = new Checkbox(Ui.t("ov.autoReinit"));
    private final Span scope = Ui.text("pg-note", "");
    /** Which nodes automatic failover may promote (chosen per node in the Nodes table). */
    private final Span afSummary = Ui.text(null, "");
    /** How commits wait for replicas, and the state of it. */
    private final Span syncSummary = Ui.text(null, "");
    private final Span syncState = Ui.text("pg-note", "");
    private final Button syncChange = new Button(Ui.t("ov.syncChange"));
    /** Seconds between refreshes of this page, as this browser chose (0: off). */
    private final Select<Integer> refreshEvery = RefreshChoice.select();
    private Grid.Column<NodeRow> actionsColumn;
    private StableRows<NodeRow> nodeRows;
    private final StableRows.Kept<Component> gridMenus = new StableRows.Kept<>();
    private final StableRows.Kept<Component> cardMenus = new StableRows.Kept<>();
    /** Node name → the card's parts that change: the head's name and chips, and the lines under it. */
    private final java.util.LinkedHashMap<String, Div[]> cards = new java.util.LinkedHashMap<>();
    private Registration poll;
    private Overview last;
    private final ClusterAdmin clusterAdmin;

    OverviewPage(ConsoleService console, NotificationService notifications, AuthenticationContext auth,
                 ZoneId zone, ClusterAdmin clusterAdmin) {
        this.console = console;
        this.notifications = notifications;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = zone;
        addClassName("pg-page");

        H1 title = new H1(Ui.t("ov.title"));
        title.addClassName("pg-title");
        String env = console.environment();
        Span eyebrow = Ui.text("pg-eyebrow", console.watcher().config().name()
                + (env == null || env.isBlank() ? "" : " · " + Ui.tr(Terms.capitalize(env))));
        this.clusterAdmin = clusterAdmin;
        Div head = Ui.div("pg-spread", new Div(eyebrow, title), Ui.div("pg-row", headStatus, manageMenu()));
        headStatus.addClassName("pg-row");

        buildGrid();
        Button refresh = new Button(Ui.t("ov.refresh"), VaadinIcon.REFRESH.create(), e -> refresh());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        grid.addClassName("pg-node-grid");
        Div nodesCard = Ui.card(null);
        Div refreshRow = Ui.div("pg-row", refreshed, refreshEvery, refresh);
        refreshRow.getStyle().set("flex-wrap", "wrap");
        nodesCard.add(Ui.div("pg-spread", heading(Ui.t("ov.nodes")), refreshRow), grid, nodeCards);

        Div topoCard = Ui.card(Ui.t("ov.topology"));
        topoCard.add(topology);

        Div eventsCard = Ui.card(null);
        eventsCard.add(Ui.div("pg-spread", heading(Ui.t("ov.recentEvents")), new Anchor(link("events"), Ui.t("ov.viewAll"))));
        events.addClassName("pg-events");
        eventsCard.add(events);

        Div controlsCard = Ui.card(Ui.t("ov.controls"));
        controlsCard.add(controlsBlock());

        Div watchersCard = Ui.card(Ui.t("ov.watchers"));
        watchersCard.add(watchers);

        Div left = Ui.div("pg-col", topoCard, eventsCard);
        Div right = Ui.div("pg-col", controlsCard, watchersCard);
        banners.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        health.getElement().setAttribute("aria-live", "polite");
        add(banners, head, health, stats, nodesCard, Ui.div("pg-grid-2", left, right));
        refresh();
    }

    /** Another page of this cluster. */
    private String link(String page) {
        return ClusterRoutes.path(console.watcher().config().name(), page);
    }

    /** Add node and Detach, for a cluster created in the console; one defined in watcher.yml says so. */
    private Component manageMenu() {
        com.vaadin.flow.component.menubar.MenuBar mb = new com.vaadin.flow.component.menubar.MenuBar();
        if (!admin) {
            return mb;
        }
        mb.addThemeVariants(com.vaadin.flow.component.menubar.MenuBarVariant.LUMO_TERTIARY,
                com.vaadin.flow.component.menubar.MenuBarVariant.LUMO_SMALL);
        var root = mb.addItem(Ui.t("ov.clusterMenu"));
        var sub = root.getSubMenu();
        if (clusterAdmin == null) {
            var hint = sub.addItem(Ui.t("ov.definedInYml"));
            hint.setEnabled(false);
            return mb;
        }
        sub.addItem(Ui.t("ov.addNode"), e -> clusterAdmin.addNode());
        var detach = sub.addItem(Ui.t("ov.detach"), e -> clusterAdmin.detach());
        detach.addClassName("pg-danger");
        return mb;
    }

    private static Span heading(String text) {
        Span s = new Span(text);
        s.getStyle().set("font-size", "18px").set("font-weight", "700").set("color", "var(--pg-ink)");
        return s;
    }

    // ---------------------------------------------------------------- nodes

    private void buildGrid() {
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setAllRowsVisible(true);
        grid.setDetailsVisibleOnClick(false);
        grid.addComponentColumn(r -> Ui.chevron(openRows.contains(r.name()))).setWidth("40px").setFlexGrow(0)
                .setHeader("");
        grid.addComponentColumn(r -> {
            Span s = new Span(r.name());
            s.getStyle().set("font-weight", "700").set("color", "var(--pg-ink)");
            return s;
        }).setHeader(Ui.t("ov.col.node")).setAutoWidth(true).setFlexGrow(0);
        Grid.Column<NodeRow> address = grid.addColumn(NodeRow::address).setHeader(Ui.t("ov.col.address")).setAutoWidth(true);
        Grid.Column<NodeRow> dc = grid.addColumn(NodeRow::dc).setHeader(Ui.t("ov.col.dc")).setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(r -> roleChip(r)).setHeader(Ui.t("ov.col.role")).setAutoWidth(true);
        grid.addComponentColumn(r -> Ui.div("pg-row", Ui.chip(Ui.tr(r.state()), stateTone(r)),
                Ui.text("pg-since", sinceText(r)))).setHeader(Ui.t("ov.col.state")).setAutoWidth(true);
        Grid.Column<NodeRow> wal = grid.addComponentColumn(r -> Ui.text("pg-mono", r.lsn() == null ? "—" : r.lsn()))
                .setHeader(Ui.t("ov.col.wal")).setAutoWidth(true);
        grid.addColumn(this::lagText).setHeader(Ui.t("ov.col.lag")).setAutoWidth(true);
        Grid.Column<NodeRow> tl = grid.addColumn(r -> r.timeline() == null ? "—" : r.timeline().toString())
                .setHeader(Ui.t("ov.col.timeline")).setAutoWidth(true).setTextAlign(ColumnTextAlign.END);
        // Which nodes a commit waits for, and which ones automatic failover may promote: chosen here per node.
        // Fixed widths: auto width is measured before the cells render and left the text under the Actions column.
        Grid.Column<NodeRow> syncCol = grid.addComponentColumn(this::syncCell).setHeader(Ui.t("ov.col.sync"))
                .setWidth("150px").setFlexGrow(0);
        Grid.Column<NodeRow> afCol = grid.addComponentColumn(this::failoverCell)
                .setHeader(Ui.t("ov.col.autoFailover")).setWidth("170px").setFlexGrow(0);
        Grid.Column<NodeRow> agent = grid.addComponentColumn(r -> Ui.chip(agentLabel(r),
                r.agentOnline() ? Ui.Tone.OK : Ui.Tone.CRIT)).setHeader(Ui.t("ov.col.agent")).setAutoWidth(true);
        // The actions stay on screen whatever the other columns do: in an incident they are what is needed.
        // Kept across refreshes: a menu built again while it is open would close under the pointer.
        actionsColumn = grid.addColumn(StableRows.Kept.renderer(gridMenus, NodeRow::name, this::menuSpec,
                        this::actions)).setHeader(Ui.t("ov.col.actions")).setAutoWidth(true)
                .setFlexGrow(0).setFrozenToEnd(true);
        nodeRows = new StableRows<>(grid, NodeRow::name);
        // WAL position and timeline make room for the Sync and Auto failover choices first (both are also in a
        // node's details and on the Replication page)
        Ui.hideBelow(grid, 1750, wal, tl);
        Ui.hideBelow(grid, 980, address, agent, dc);
        grid.addItemClickListener(e -> {
            if (e.getColumn() == actionsColumn || e.getColumn() == syncCol || e.getColumn() == afCol) {
                return; // their own controls answer the click
            }
            toggle(e.getItem().name());
        });
        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::details));
    }

    /** Shows or hides a row's details; by name, as a kept menu may have been built for an older row. */
    private void toggle(String name) {
        if (!openRows.remove(name)) {
            openRows.add(name);
        }
        nodeRows.find(name).ifPresent(r -> {
            grid.setDetailsVisible(r, openRows.contains(name));
            grid.getDataProvider().refreshItem(r);
        });
    }

    private Component details(NodeRow r) {
        Div d = new Div();
        d.getStyle().set("padding-block", "4px 8px").set("padding-inline", "48px 8px");
        if (r.error() != null) {
            d.add(new Paragraph(Ui.t("ov.error", Ui.tr(r.error()))));
        }
        if (r.fenced()) {
            d.add(new Paragraph(Ui.t("ov.fenced", Ui.tr(r.fencedReason()))));
        }
        d.add(new Paragraph(Ui.t("ov.upstream", r.upstream() == null ? "—" : r.upstream(), r.epoch())
                + " · " + (r.vipAddress() == null ? Ui.t("ov.noVip") : Ui.t(r.vipHeld() ? "ov.vipHere" : "ov.vipNotHere",
                r.vipAddress())) + (r.noFailover() ? " · " + Ui.t("ov.neverPromoted") : "")
                + " · " + Ui.t("ov.priority", r.priority()) + " · " + Ui.t("ov.lastAnswer",
                Ui.time(r.lastCheckMillis(), zone) + " " + Ui.zone(zone))));
        return d;
    }

    private Span roleChip(NodeRow r) {
        String label = Ui.tr(r.role()) + (r.isPrimary() || !r.role().equals("Replica") ? "" : dcSuffix(r));
        return Ui.chip(label, r.role().equals("Primary") ? Ui.Tone.OK : r.role().equals("Replica")
                ? Ui.Tone.INFO : Ui.Tone.CRIT);
    }

    private String lagText(NodeRow r) {
        return r.isPrimary() ? "—" : Ui.size(r.lagBytes())
                + (r.replayDelayMs() == null || r.lagBytes() == null || r.lagBytes() == 0 ? ""
                : " · " + Ui.seconds(r.replayDelayMs()));
    }

    /** "for 14 s" while a node is in a state worth watching; nothing while it runs or streams normally. */
    private String sinceText(NodeRow r) {
        boolean normal = r.agentOnline() && !r.fenced() && (r.isPrimary() ? "Running".equals(r.state())
                : r.streaming());
        if (normal || r.stateSinceMillis() <= 0 || last == null) {
            return "";
        }
        return Ui.t("ov.for", Ui.ago(r.stateSinceMillis(), last.observedAtMillis()));
    }

    private String dcSuffix(NodeRow r) {
        return last != null && last.primaryDc() != null && !last.primaryDc().equals(r.dc()) ? " " + r.dc() : "";
    }

    private static Ui.Tone stateTone(NodeRow r) {
        if (!r.agentOnline() || r.fenced()) {
            return Ui.Tone.CRIT;
        }
        if (r.isPrimary()) {
            return r.role().equals("Primary") ? Ui.Tone.OK : Ui.Tone.CRIT;
        }
        return r.streaming() ? Ui.Tone.OK : r.busy() != null ? Ui.Tone.INFO : Ui.Tone.WARN;
    }

    private boolean noPrimaryRunning() {
        return last != null && last.nodes().stream().noneMatch(n -> "Primary".equals(n.role()));
    }

    /** What a node's menu offers; the menu is built again only when this changes. */
    private Object menuSpec(NodeRow r) {
        return List.of(r.isPrimary(), r.agentOnline(), "Replica".equals(r.role()), noPrimaryRunning(),
                console.watcher().identity().foreignData().contains(r.name()), clusterAdmin == null || r.isPrimary());
    }

    private Component actions(NodeRow r) {
        String name = r.name();
        return NodeActions.menu(console, user, admin, new NodeActions.Node(name, r.isPrimary(), r.agentOnline(),
                "Replica".equals(r.role()), noPrimaryRunning(),
                console.watcher().identity().foreignData().contains(name),
                clusterAdmin == null || r.isPrimary() ? null : () -> clusterAdmin.removeNode(name, null)),
                () -> toggle(name), this::refresh);
    }

    /**
     * The node cards of narrow screens. Like the grid rows, they are updated in place and keep their menus: only
     * the name and chips in the head and the lines under it are built again.
     */
    private void renderNodeCards(Overview o) {
        List<String> names = o.nodes().stream().map(NodeRow::name).toList();
        if (!names.equals(new java.util.ArrayList<>(cards.keySet()))) {
            nodeCards.removeAll();
            cards.clear();
            for (String n : names) {
                Div info = Ui.div("pg-contents");
                Div lines = Ui.div("pg-contents");
                Div menuSlot = Ui.div("pg-node-card-menu");
                Div card = Ui.div("pg-node-card", Ui.div("pg-node-card-head", info, menuSlot), lines);
                cards.put(n, new Div[]{card, info, lines, menuSlot});
                nodeCards.add(card);
            }
        }
        for (NodeRow r : o.nodes()) {
            Div[] parts = cards.get(r.name());
            Div card = parts[0];
            Ui.Tone t = stateTone(r);
            card.removeClassNames("crit", "warn");
            if (t == Ui.Tone.CRIT || t == Ui.Tone.WARN) {
                card.addClassName(t.name().toLowerCase(java.util.Locale.ROOT));
            }
            Span name = new Span(r.name());
            name.getStyle().set("font-weight", "800").set("color", "var(--pg-ink)").set("font-size", "16px");
            parts[1].removeAll();
            parts[1].add(name, roleChip(r), Ui.chip(Ui.tr(r.state()), t));
            Component menu = cardMenus.get(r.name(), menuSpec(r), () -> actions(r));
            if (parts[3].getChildren().findFirst().orElse(null) != menu) {
                parts[3].removeAll();
                parts[3].add(menu);
            }
            Div lines = parts[2];
            lines.removeAll();
            String since = sinceText(r);
            lines.add(Ui.text("pg-note", r.address() + " · " + r.dc() + (since.isEmpty() ? "" : " · " + since)),
                    Ui.text("pg-note", (r.isPrimary() ? Ui.t("ov.cardWal", r.lsn() == null ? "—" : r.lsn())
                            : Ui.t("ov.cardLag", lagText(r))) + " · " + Ui.t("ov.cardTimeline",
                            r.timeline() == null ? "—" : r.timeline())
                            + (r.sync() == null ? "" : " · " + Ui.tr(Terms.capitalize(r.sync())))
                            + (r.agentOnline() ? "" : " · " + Ui.t("ov.cardAgent", agentLabel(r)))));
            if (r.error() != null) {
                lines.add(Ui.text("pg-note", Ui.tr(r.error())));
            }
        }
    }

    // ---------------------------------------------------------------- controls

    private Component controlsBlock() {
        Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "10px");
        for (Checkbox cb : List.of(mail, maintenance, autoReinit)) {
            cb.setReadOnly(!admin);
            box.add(cb);
        }
        maintenance.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            boolean on = e.getValue();
            maintenance.setValue(!on);
            if (on) {
                Ui.confirm(Ui.t("ov.mmOnTitle"), Ui.t("ov.mmOnText"), Ui.t("ov.turnOn"), true,
                        () -> setControl(null, true, null, null));
            } else if (primaryDown()) {
                Ui.confirm(Ui.t("ov.mmOffTitle"), Ui.t("ov.mmOffDownText", primaryName()), Ui.t("ov.turnOffFailOver"),
                        true, () -> setControl(null, false, null, null));
            } else {
                setControl(null, false, null, null);
            }
        });
        mail.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                setControl(null, null, e.getValue(), null);
            }
        });
        autoReinit.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            boolean on = e.getValue();
            autoReinit.setValue(!on);
            if (on) {
                Ui.confirm(Ui.t("ov.arOnTitle"), Ui.t("ov.arOnText"), Ui.t("ov.turnOn"), true,
                        () -> setControl(null, null, null, true));
            } else {
                setControl(null, null, null, false);
            }
        });
        box.add(replicationControl());
        box.add(Ui.div("pg-row", Ui.text("pg-stat-label", Ui.t("ov.scope")), scope));
        return box;
    }

    /**
     * The cluster-wide parts of synchronous replication and automatic failover. Which nodes they cover is chosen in
     * the Nodes table; here are the summaries, and the popup to change how commits wait.
     */
    private Component replicationControl() {
        syncChange.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        syncChange.setVisible(admin);
        syncChange.addClickListener(e -> {
            if (last != null && last.replication().synchronous()) {
                openSync(null, last.replication().standbys());
            }
        });
        Div af = Ui.div("pg-row", Ui.text("pg-stat-label", Ui.t("ov.afLabel")), afSummary);
        Div sync = Ui.div("pg-row", Ui.text("pg-stat-label", Ui.t("ov.syncLabel")), syncSummary, syncChange);
        for (Div row : List.of(af, sync)) {
            row.getStyle().set("align-items", "baseline").set("flex-wrap", "wrap");
        }
        Div box = new Div(af, Ui.text("pg-note", Ui.t("ov.afWhere")), sync, syncState);
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px")
                .set("padding-top", "10px").set("border-top", "1px solid var(--pg-border)");
        return box;
    }

    /** The popup for a checked Sync box, or for a change of the mode (node null). */
    private void openSync(String node, List<String> standbys) {
        String primary = last == null ? null : last.primary();
        SyncDialog.open(node, standbys, primary, last == null ? ClusterState.Replication.ASYNC : last.replication(),
                c -> {
                    if (Ui.attempt(() -> console.watcher().setReplication(c.mode(), c.count(), c.standbys(), user))) {
                        Ui.ok(Ui.t("ov.saved"));
                    }
                    refresh();
                });
    }

    /** A Sync box clicked: checking opens the popup, clearing asks first. */
    private void syncClicked(NodeRow r, boolean checked) {
        if (last == null) {
            return;
        }
        ClusterState.Replication now = last.replication();
        List<String> standbys = new java.util.ArrayList<>(now.standbys());
        if (checked) {
            standbys.add(r.name());
            openSync(r.name(), standbys);
            refresh(); // the box shows the saved state until the popup is confirmed
            return;
        }
        standbys.remove(r.name());
        List<String> replicas = standbys.stream().filter(n -> !n.equals(last.primary())).toList();
        List<String> rest = replicas.isEmpty() ? List.of() : standbys;
        int count = Math.min(now.count(), Math.max(1, replicas.size()));
        String text = rest.isEmpty() ? Ui.t("ov.syncOffAll")
                : Ui.t("ov.syncOffRest", count, String.join(", ", replicas));
        refresh();
        Ui.confirm(Ui.t("ov.syncOffTitle", r.name()), text, Ui.t("ov.syncOffButton"), rest.isEmpty(), () -> {
            if (Ui.attempt(() -> console.watcher().setReplication(now.mode(), count, rest, user))) {
                Ui.ok(Ui.t("ov.saved"));
            }
            refresh();
        });
    }

    /** An Auto failover box clicked: clearing asks first, and warns when no replica would be left to promote. */
    private void failoverClicked(NodeRow r, boolean checked) {
        if (last == null) {
            return;
        }
        Runnable apply = () -> {
            if (Ui.attempt(() -> console.watcher().setNodeFailover(r.name(), checked, user))) {
                Ui.ok(Ui.t("ov.saved"));
            }
            refresh();
        };
        String p = primaryName();
        refresh();
        if (checked) {
            if (primaryDown() && !r.isPrimary() && !last.maintenance()) {
                Ui.confirm(Ui.t("ov.afNodeOnTitle", r.name()), Ui.t("ov.afNodeOnDown", p, r.name()),
                        Ui.t("ov.afNodeOnButton"), true, apply);
            } else {
                apply.run();
            }
            return;
        }
        boolean othersLeft = last.nodes().stream().anyMatch(n -> !n.isPrimary() && !n.name().equals(r.name())
                && !n.noFailover());
        String text = r.isPrimary() ? Ui.t("ov.afNodeOffPrimary", r.name())
                : Ui.t("ov.afNodeOffText", r.name(), p) + (othersLeft ? "" : " " + Ui.t("ov.afNodeOffLast", p));
        Ui.confirm(Ui.t("ov.afNodeOffTitle", r.name()), text, Ui.t("ov.afNodeOffButton", r.name()), true, apply);
    }

    private Component syncCell(NodeRow r) {
        Checkbox cb = new Checkbox(r.syncStandby());
        cb.setReadOnly(!admin);
        cb.setAriaLabel(Ui.t("ov.syncFor", r.name()));
        cb.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                syncClicked(r, e.getValue());
            }
        });
        Span state;
        if (r.isPrimary()) {
            state = Ui.text("pg-note", r.syncStandby() ? Ui.t("ov.whenReplica") : "");
        } else if (r.sync() == null) {
            state = Ui.text("pg-note", r.syncStandby() ? Ui.t("ov.syncNotConnected") : "—");
        } else if ("quorum".equalsIgnoreCase(r.sync()) || "sync".equalsIgnoreCase(r.sync())) {
            state = Ui.chip(Ui.t("ov.syncChip"), Ui.Tone.OK);
            state.getElement().setAttribute("title", Ui.t("ov.syncChipTitle", r.sync()));
        } else {
            state = Ui.chip(Ui.tr(Terms.capitalize(r.sync())), r.syncStandby() ? Ui.Tone.WARN : Ui.Tone.NEUTRAL);
        }
        Div d = Ui.div("pg-row", cb, state);
        d.getStyle().set("flex-wrap", "nowrap");
        return d;
    }

    private Component failoverCell(NodeRow r) {
        boolean on = !r.noFailover();
        Checkbox cb = new Checkbox(on);
        cb.setReadOnly(!admin);
        cb.setAriaLabel(Ui.t("ov.afFor", r.name()));
        cb.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                failoverClicked(r, e.getValue());
            }
        });
        Span text = r.isPrimary() ? Ui.text("pg-note", on ? Ui.t("ov.whenReplica") : Ui.t("ov.neverWhenReplica"))
                : Ui.text(null, on ? Ui.t("ov.canBePromoted") : Ui.t("ov.neverPromotedCell"));
        if (!r.isPrimary()) {
            text.getStyle().set("font-size", "13px").set("font-weight", "600")
                    .set("color", on ? "var(--pg-green-dark)" : "var(--pg-crit-fg)");
        }
        Div d = Ui.div("pg-row", cb, text);
        d.getStyle().set("flex-wrap", "nowrap");
        return d;
    }

    private static int streamingReplicas(Overview o) {
        return (int) o.nodes().stream().filter(r -> !r.isPrimary() && r.streaming() && r.syncStandby()).count();
    }

    private void renderReplication(Overview o) {
        ClusterState.Replication r = o.replication();
        List<String> promotable = o.nodes().stream().filter(n -> !n.isPrimary() && !n.noFailover())
                .map(NodeRow::name).toList();
        afSummary.setText(promotable.isEmpty() ? Ui.t("ov.afNone") : Ui.t("ov.afTo", String.join(", ", promotable)));
        afSummary.getStyle().set("color", promotable.isEmpty() ? "var(--pg-crit-fg)" : "var(--pg-ink)")
                .set("font-size", "13px");
        List<String> replicas = r.standbysOf(o.primary());
        syncSummary.getStyle().set("font-size", "13px").set("color", "var(--pg-ink)");
        syncChange.setVisible(admin && r.synchronous());
        if (!r.synchronous()) {
            syncSummary.setText(Ui.t("ov.syncNone"));
            syncState.setText(Ui.t("ov.syncWhere"));
            syncState.getStyle().remove("color");
            return;
        }
        syncSummary.setText(Ui.t(r.strict() ? "ov.syncStrictSummary" : "ov.syncQuorumSummary", r.count(),
                String.join(", ", replicas.isEmpty() ? r.standbys() : replicas)));
        int streaming = streamingReplicas(o);
        String text;
        if (!r.strict() && r.degraded()) {
            text = Ui.t("ov.replDegraded", r.count(), streaming);
        } else if (!r.strict()) {
            text = Ui.t("ov.replSyncHelp", r.count(), streaming);
        } else {
            text = Ui.t("ov.replStrictHelp", r.count(), streaming);
        }
        syncState.setText(text);
        boolean trouble = !r.strict() && r.degraded() || r.strict() && streaming < r.count();
        if (trouble) {
            syncState.getStyle().set("color", "var(--lumo-error-text-color)");
        } else {
            syncState.getStyle().remove("color");
        }
    }

    private String primaryName() {
        return last == null || last.primary() == null ? Ui.t("ov.thePrimary") : last.primary();
    }

    private boolean primaryDown() {
        return last != null && "Primary down".equals(last.health());
    }

    private void setControl(Boolean af, Boolean mm, Boolean ml, Boolean ar) {
        if (Ui.attempt(() -> console.watcher().setControls(af, mm, ml, ar, user))) {
            Ui.ok(Ui.t("ov.saved"));
        }
        refresh();
    }

    private void renderControls(Overview o) {
        mail.setValue(o.mailEnabled());
        maintenance.setValue(o.maintenance());
        autoReinit.setValue(o.autoReinit());
        help(mail, Ui.t("ov.mailHelp"), o.controls().get("mail notifications"));
        help(maintenance, Ui.t("ov.mmHelp"), o.controls().get("maintenance mode"));
        help(autoReinit, Ui.t("ov.arHelp"), o.controls().get("auto reinit"));
        renderReplication(o);
        scope.setText(Ui.t(o.scope().equals("DC_LOCAL") ? "ov.scopeLocal" : "ov.scopeAny")
                + (o.selfFence() ? " · " + Ui.t("ov.selfFencingOn") : ""));
    }

    private void help(Checkbox cb, String text, ControlChange c) {
        cb.setHelperText(c == null ? text : text + " · " + Ui.t(c.on() ? "ov.onSince" : "ov.offSince",
                Ui.shortTime(c.atMillis(), zone), c.by()));
    }

    // ---------------------------------------------------------------- refreshing

    private void refresh() {
        Overview o = console.overview();
        last = o;
        renderBanners(o);
        renderHead(o);
        renderHealth(o);
        renderStats(o);
        nodeRows.set(o.nodes());
        for (NodeRow r : o.nodes()) {
            if (openRows.contains(r.name())) {
                grid.setDetailsVisible(r, true);
            }
        }
        renderNodeCards(o);
        refreshed.setText(Ui.t("rp.refreshed", Ui.time(o.observedAtMillis(), zone) + " " + Ui.zone(zone)));
        renderTopology(o);
        renderEvents();
        renderWatchers(o);
        renderControls(o);
    }

    private void renderBanners(Overview o) {
        banners.removeAll();
        if (!o.leader()) {
            Div b = Ui.banner(o.leaderId() == null ? Ui.t("ov.noLeader", o.watcherId())
                    : Ui.t("ov.follows", o.watcherId(), o.leaderId()), Ui.Tone.INFO);
            if (o.leaderUrl() != null) {
                b.add(Ui.div("pg-banner-actions", new Anchor(o.leaderUrl(), Ui.t("ov.openLeader"))));
            }
            banners.add(b);
        }
        RoleChange rc = o.unacknowledgedRoleChange();
        if (rc != null) {
            banners.add(roleChangeBanner(rc));
        }
        if (o.operation() != null) {
            String owner = o.operation().owner();
            Component follow;
            if (owner == null || owner.equals(o.watcherId())) {
                follow = new Anchor(link("operations"), Ui.t("ov.followIt"));
            } else if (o.operationConsoleUrl() != null) {
                // The steps and log are on the watcher running it, not here.
                follow = new Anchor(o.operationConsoleUrl(), Ui.t("ov.followOn", owner));
            } else {
                follow = Ui.text("pg-note", Ui.t("ov.stepsOn", owner));
            }
            String opName = Ui.tr(Terms.operation(o.operation().type()));
            String ago = Ui.ago(o.operation().startedAtMillis(), o.observedAtMillis());
            banners.add(Ui.banner(o.operation().target() == null
                    ? Ui.t("ov.opRunning", opName, o.operation().startedBy(), ago)
                    : Ui.t("ov.opRunningOn", opName, o.operation().target(), o.operation().startedBy(), ago),
                    Ui.Tone.INFO, follow));
        }
        if ("Primary down".equals(o.health())) {
            banners.add(primaryDownBanner(o));
        } else if ("Primary unverified".equals(o.health())) {
            banners.add(Ui.banner(Ui.t(o.selfFence() ? "ov.unverifiedFence" : "ov.unverified", o.primary(),
                    Ui.tr(console.watcher().identity().problem(o.primary()))), Ui.Tone.CRIT));
        } else if (o.maintenance()) {
            banners.add(Ui.banner(Ui.t("ov.mmBanner"), Ui.Tone.WARN));
        }
        if ("No primary".equals(o.health())) {
            banners.add(Ui.banner(Ui.t("ov.noPrimaryBanner"), Ui.Tone.WARN));
        }
        ClusterState.Replication r = o.replication();
        int streaming = streamingReplicas(o);
        if (r.synchronous() && r.strict() && o.primary() != null && streaming < r.count()) {
            banners.add(Ui.banner(Ui.t("ov.strictWaitingBanner", o.primary(), streaming, r.count()), Ui.Tone.CRIT,
                    new Anchor(link("replication"), Ui.t("ov.replicationLink"))));
        } else if (r.synchronous() && !r.strict() && r.degraded()) {
            banners.add(Ui.banner(Ui.t("ov.syncDegradedBanner", streaming, r.count()), Ui.Tone.WARN,
                    new Anchor(link("replication"), Ui.t("ov.replicationLink"))));
        }
    }

    private Div roleChangeBanner(RoleChange rc) {
        boolean failover = "FAILOVER".equals(rc.type());
        String loss = rc.lostBytes() == null ? Ui.t("ov.lossUnknown", rc.from())
                : rc.lostBytes() == 0 ? Ui.t("ov.noLoss")
                : Ui.t("ov.loss", Ui.size(rc.lostBytes()), rc.from(), rc.to());
        String text = Ui.t(failover ? "ov.rcFailover" : "ov.rcSwitchover", (rc.from() == null ? "" : rc.from() + " → ")
                + rc.to(), Ui.shortTime(rc.atMillis(), zone) + " " + Ui.zone(zone), Ui.duration(rc.durationMs()))
                + " · " + loss + " · " + Ui.t("ov.epoch", rc.epoch()) + (failover ? "" : " · " + Ui.t("ov.by", rc.by()));
        Ui.Tone tone = rc.lostBytes() != null && rc.lostBytes() > 0 ? Ui.Tone.CRIT
                : failover ? Ui.Tone.WARN : Ui.Tone.INFO;
        Anchor details = new Anchor(link("events") + "?q=" + URLEncoder.encode(rc.to(), StandardCharsets.UTF_8),
                Ui.t("ov.details"));
        if (!admin) {
            return Ui.banner(text, tone, details, Ui.text("pg-note", Ui.t("ov.adminAcks")));
        }
        Button ack = new Button(Ui.t("ov.acknowledge"), e -> {
            if (Ui.attempt(() -> console.watcher().acknowledgeRoleChange(user))) {
                Ui.ok(Ui.t("ov.acknowledged"));
            }
            refresh();
        });
        ack.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        return Ui.banner(text, tone, details, ack);
    }

    private Div primaryDownBanner(Overview o) {
        NodeRow p = o.primaryRow();
        String state = p == null ? Ui.t("ov.notPolled") : Ui.tr(p.state()) + (p.stateSinceMillis() > 0
                ? " " + Ui.t("ov.for", Ui.ago(p.stateSinceMillis(), o.observedAtMillis())) : "");
        String text = Ui.t("ov.notAnswering", o.primary(), state);
        if (o.operation() != null) {
            return Ui.banner(text, Ui.Tone.CRIT);
        }
        if (o.maintenance()) {
            return Ui.banner(text + " " + Ui.t("ov.pausedByMm"), Ui.Tone.CRIT,
                    controlButton(Ui.t("ov.mmTurnOff"), () -> maintenanceOffNow()));
        }
        if (!o.autoFailover()) {
            return Ui.banner(text + " " + Ui.t("ov.afIsOff"), Ui.Tone.CRIT,
                    controlButton(Ui.t("ov.afTurnOn"), () -> autoFailoverOnNow()));
        }
        if (o.failoverBlockedReason() != null) {
            return Ui.banner(text + " " + Ui.t("ov.afBlocked", Ui.tr(o.failoverBlockedReason())),
                    Ui.Tone.CRIT, new Anchor(link("events"), Ui.t("nav.events")));
        }
        var t = console.watcher().config().timing();
        return Ui.banner(text + " " + Ui.t("ov.afArmed", t.failureThreshold(),
                t.failureThreshold() * t.pollIntervalMs() / 1000), Ui.Tone.CRIT);
    }

    private Component controlButton(String label, Runnable action) {
        if (!admin) {
            return Ui.text("pg-note", Ui.t("ov.adminCanChange"));
        }
        Button b = new Button(label, e -> action.run());
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        return b;
    }

    private void maintenanceOffNow() {
        Ui.confirm(Ui.t("ov.mmOffTitle"), Ui.t("ov.mmOffDownText", primaryName()), Ui.t("ov.turnOffFailOver"), true,
                () -> setControl(null, false, null, null));
    }

    private void autoFailoverOnNow() {
        Ui.confirm(Ui.t("ov.afOnTitle"), Ui.t("ov.afOnDownText", primaryName()), Ui.t("ov.turnOnFailOver"), true,
                () -> setControl(true, null, null, null));
    }

    private void renderHead(Overview o) {
        headStatus.removeAll();
        headStatus.add(Ui.chip(Ui.t(o.failoverActive() ? "ov.afActive" : "ov.afPaused"),
                        o.failoverActive() ? Ui.Tone.OK : Ui.Tone.WARN),
                plain(Ui.t("ov.epochChip", o.epoch())),
                plain(Ui.t("ov.timelineChip", o.timeline() == null ? "—" : o.timeline())));
    }

    private static Span plain(String text) {
        Span s = Ui.chip(text, Ui.Tone.NEUTRAL);
        s.addClassName("plain");
        return s;
    }

    /** The state of the cluster in one line, and the reasons when it is not healthy. */
    private void renderHealth(Overview o) {
        health.removeAll();
        Ui.Tone t = healthTone(o.health());
        health.setClassName("pg-health " + t.name().toLowerCase(java.util.Locale.ROOT));
        String summary;
        if ("Healthy".equals(o.health())) {
            long replicas = o.nodes().stream().filter(n -> !n.isPrimary()).count();
            long streaming = o.nodes().stream().filter(n -> !n.isPrimary() && n.streaming()).count();
            summary = Ui.t("ov.healthySummary", o.primary(), streaming, replicas)
                    + (o.vipHolder() == null ? "" : " · " + Ui.t("ov.vipOn", o.vipHolder()))
                    + " · " + Ui.t("ov.maxLag", Ui.size(o.maxLagBytes()));
        } else if ("Primary down".equals(o.health())) {
            NodeRow p = o.primaryRow();
            summary = p == null || p.stateSinceMillis() <= 0 ? "" : Ui.t("ov.since", Ui.time(p.stateSinceMillis(), zone)
                    + " " + Ui.zone(zone), Ui.ago(p.stateSinceMillis(), o.observedAtMillis()));
        } else {
            int n = o.healthReasons().size();
            summary = n == 0 ? "" : Ui.t(n == 1 ? "ov.oneProblem" : "ov.problems", n);
        }
        health.add(Ui.text("pg-health-state", Ui.tr(o.health())), Ui.text("pg-health-summary", summary));
        if (!o.healthReasons().isEmpty() && !"Healthy".equals(o.health())) {
            UnorderedList ul = new UnorderedList();
            for (String r : o.healthReasons()) {
                ul.add(new ListItem(Ui.tr(r)));
            }
            health.add(ul);
        }
    }

    static Ui.Tone healthTone(String health) {
        return switch (health) {
            case "Healthy" -> Ui.Tone.OK;
            case "Degraded", "Starting", "No primary" -> Ui.Tone.WARN;
            default -> Ui.Tone.CRIT;
        };
    }

    /** "Wrong agent" for a node left alone for its identity: it answered, only not as this cluster's node. */
    private String agentLabel(NodeRow r) {
        if (r.agentOnline()) {
            return Ui.t("ov.agentOnline");
        }
        return Ui.t(console.watcher().identity().problem(r.name()) != null ? "ov.agentWrong" : "ov.agentUnreachable");
    }

    private void renderStats(Overview o) {
        stats.removeAll();
        NodeRow p = o.primaryRow();
        boolean down = "Primary down".equals(o.health());
        stats.add(Ui.stat(Ui.t("ov.stat.primary"), o.primary() == null ? "—" : o.primary(), down && p != null
                        ? Ui.tr(p.state()) + (p.stateSinceMillis() > 0 ? " " + Ui.t("ov.for",
                        Ui.ago(p.stateSinceMillis(), o.observedAtMillis())) : "")
                        : (o.primaryAddress() == null ? "" : o.primaryAddress())
                        + (o.primaryDc() == null ? "" : " · " + o.primaryDc()),
                down ? Ui.Tone.CRIT : o.primary() == null ? Ui.Tone.WARN : null));
        String vipSub;
        Ui.Tone vipTone = null;
        boolean unverified = "Primary unverified".equals(o.health());
        if (o.vipAddress() == null) {
            vipSub = Ui.t("ov.vipDisabled");
        } else if (o.vipHolder() == null && unverified) {
            vipSub = Ui.t("ov.vipUnknown", o.primary());
            vipTone = Ui.Tone.WARN;
        } else if (o.vipHolder() == null) {
            vipSub = Ui.t("ov.vipNowhere");
            vipTone = Ui.Tone.CRIT;
        } else if (!o.vipHolder().equals(o.primary())) {
            vipSub = Ui.t("ov.vipWrongNode", o.vipHolder());
            vipTone = Ui.Tone.WARN;
        } else {
            vipSub = Ui.t("ov.vipOnVia", o.vipHolder());
        }
        stats.add(Ui.stat(Ui.t("ov.stat.vip"), o.vipAddress() == null ? Ui.t("ov.notManaged") : o.vipAddress(), vipSub,
                vipTone));
        long alert = notifications.current().lagAlertBytes();
        boolean lagging = o.maxLagBytes() != null && o.maxLagBytes() > alert;
        boolean anyStreaming = o.nodes().stream().anyMatch(n -> !n.isPrimary() && n.streaming());
        stats.add(Ui.stat(Ui.t("ov.stat.maxLag"), Ui.size(o.maxLagBytes()), o.maxLagNode() == null
                ? Ui.t(anyStreaming ? "ov.lagUnknown" : "ov.noStreaming")
                : o.maxLagBytes() == 0 ? Ui.t("ov.allReplayed")
                : o.maxLagNode() + (o.maxLagDelayMs() == null ? "" : " · " + Ui.t("ov.behind",
                Ui.seconds(o.maxLagDelayMs()))) + (lagging ? " · " + Ui.t("ov.alertAt", Ui.size(alert)) : ""),
                lagging ? Ui.Tone.WARN : null));
        stats.add(Ui.stat(Ui.t("ov.stat.lease"), o.lease() == null || !o.lease().granted() ? "—"
                        : Ui.t("ov.leaseLeft", Math.round(o.lease().remainingMs() / 1000.0)),
                Ui.t("ov.leaseTtl", console.watcher().config().timing().leaseTtlMs() / 1000) + " · "
                        + Ui.t(o.selfFence() ? "ov.selfFencingOn" : "ov.selfFencingOff")));
    }

    /**
     * Primary on top, replicas below. The VIP is drawn on the node that really holds it: on the primary when all is
     * well, in red and detached when nobody holds it, in amber over the wrong node.
     */
    private void renderTopology(Overview o) {
        topology.removeAll();
        Div t = Ui.div("pg-topo");
        NodeRow p = o.primaryRow();
        String vip = o.vipAddress();
        String holder = o.vipHolder();
        if (vip != null && holder == null && "Primary unverified".equals(o.health())) {
            t.add(Ui.text("pg-topo-vip warn", Ui.t("ov.topoVipUnknown", vip)));
        } else if (vip != null && holder == null) {
            t.add(Ui.text("pg-topo-vip crit", Ui.t("ov.topoVipDown", vip)));
        } else if (vip != null && p != null && holder.equals(p.name())) {
            t.add(Ui.text("pg-topo-vip", "VIP " + vip), Ui.div("pg-topo-link"));
        }
        if (p != null) {
            t.add(node(p, "primary" + (p.role().equals("Primary") ? "" : " down"), Ui.t("ov.topoPrimary", p.dc())));
            t.add(Ui.div("pg-topo-link"));
        }
        Div row = Ui.div("pg-topo-row");
        for (NodeRow r : o.nodes()) {
            if (r.isPrimary()) {
                continue;
            }
            boolean remote = p != null && !Objects.equals(p.dc(), r.dc());
            String cls = (remote ? "remote" : "") + (r.streaming() ? "" : " down");
            Div n = node(r, cls, (r.streaming() ? Ui.tr(r.sync() == null ? "async" : r.sync()) : Ui.tr(r.state()))
                    + " · " + r.dc());
            if (vip != null && Objects.equals(holder, r.name())) {
                // A VIP on a replica: show it where it is, so the mismatch cannot be missed.
                row.add(Ui.div("pg-topo-cell", Ui.text("pg-topo-vip warn", Ui.t("ov.topoVipNotPrimary", vip)),
                        Ui.div("pg-topo-link"), n));
            } else {
                row.add(n);
            }
        }
        t.add(row);
        topology.add(t, Ui.text("pg-note", Ui.t("ov.dashed") + " "
                + Ui.t("DC_LOCAL".equals(o.scope()) ? "ov.dcLocalNote" : "ov.anyDcNote")));
        if (o.clusterId() != null) {
            // What the agents are bound to and what every data directory must hold; needed to re-bind an agent.
            Div ids = new Div();
            ids.addClassName("pg-note");
            ids.setText(Ui.t("header.clusterId", o.clusterId()) + " · " + Ui.t("header.systemId",
                    o.systemIdentifier() == null ? Ui.t("ov.notRecorded") : o.systemIdentifier()));
            topology.add(ids);
        }
    }

    private static Div node(NodeRow r, String cls, String sub) {
        Div d = Ui.div("pg-topo-node", new Span(r.name()), Ui.text(null, sub));
        d.getChildren().findFirst().ifPresent(c -> c.getElement().setAttribute("style",
                "font-weight:700;color:var(--pg-ink)"));
        for (String c : cls.trim().split("\\s+")) {
            if (!c.isBlank()) {
                d.addClassName(c);
            }
        }
        return d;
    }

    private void renderEvents() {
        events.removeAll();
        List<ClusterEvent> recent = console.watcher().events(6);
        if (recent.isEmpty()) {
            events.add(new ListItem(Ui.t("ov.noEvents")));
        }
        for (ClusterEvent e : recent) {
            ListItem li = new ListItem();
            li.add(Ui.text("pg-time", Ui.shortTime(e.timeMillis(), zone)),
                    Ui.chip(switch (e.severity()) {
                        case CRITICAL -> Ui.t("sev.critical");
                        case WARNING -> Ui.t("sev.warning");
                        case INFO -> Ui.t("sev.info");
                    }, Ui.tone(e.severity())), Ui.text(null, Ui.arrows(Ui.tr(e.message()))));
            events.add(li);
        }
    }

    private void renderWatchers(Overview o) {
        watchers.removeAll();
        watchers.add(Ui.text("pg-note", "STANDALONE".equals(o.mode()) ? Ui.t("ov.consensusNone")
                : Ui.t("ov.consensusRaft", o.term())));
        UnorderedList ul = new UnorderedList();
        ul.addClassName("pg-events");
        for (ConsoleService.WatcherRow w : o.watchers()) {
            ListItem li = new ListItem();
            li.getStyle().set("grid-template-columns", "1fr auto");
            Div left = new Div(Ui.text(null, w.id() + (w.self() ? " " + Ui.t("ov.thisWatcher") : "")));
            left.getStyle().set("font-weight", "700").set("color", "var(--pg-ink)");
            left.add(new Div(Ui.text("pg-note", Ui.t("ov.seesPrimary", Ui.t(w.seesPrimary() == null ? "ov.unknown"
                    : w.seesPrimary() ? "ov.yes" : "ov.no")) + (w.self() || w.lastContactMs() == null ? ""
                    : " · " + Ui.t("ov.heartbeat", Ui.seconds(w.lastContactMs()))))));
            li.add(left, Ui.chip(Ui.t(w.leader() ? "ov.leader" : "ov.follower"), w.leader() ? Ui.Tone.OK : Ui.Tone.NEUTRAL));
            ul.add(li);
        }
        watchers.add(ul);
        if ("STANDALONE".equals(o.mode())) {
            watchers.add(Ui.text("pg-note", Ui.t("ov.standaloneNote")));
        }
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        RefreshChoice.apply(e.getUI(), refreshEvery.getValue());
        poll = e.getUI().addPollListener(p -> refresh());
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (poll != null) {
            poll.remove();
        }
        super.onDetach(e);
    }
}
