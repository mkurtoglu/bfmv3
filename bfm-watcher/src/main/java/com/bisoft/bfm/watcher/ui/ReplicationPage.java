package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.monitor.LagHistory;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.ConsoleService.ReplicaRow;
import com.bisoft.bfm.watcher.service.ConsoleService.Replication;
import com.bisoft.bfm.watcher.service.NotificationService;
import com.bisoft.bfm.watcher.service.Terms;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replication in detail: what the primary's pg_stat_replication says about each standby, what each replica's own
 * agent reports, and the slots. The two sides differ while WAL is in flight, and that difference is what a crash
 * failover would lose.
 */
class ReplicationPage extends Div {

    private final ConsoleService console;
    private final NotificationService notifications;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;

    private final Div banners = new Div();
    private final Div stats = Ui.div("pg-grid-auto");
    private final Grid<ReplicaRow> primarySide = new Grid<>();
    private final Grid<ReplicaRow> replicaSide = new Grid<>();
    private final Grid<ConsoleService.SlotRow> slots = new Grid<>();
    private final Select<String> lagNode = new Select<>();
    private final Div lagChart = new Div();
    private final Span refreshed = Ui.text("pg-note", "");
    /** Seconds between refreshes of this page, as this browser chose (0: off). */
    private final com.vaadin.flow.component.select.Select<Integer> refreshEvery = RefreshChoice.select();
    private final Set<String> openRows = new HashSet<>();
    private Grid.Column<ReplicaRow> actionsColumn;
    private StableRows<ReplicaRow> replicaRows;
    private final StableRows.Kept<Component> menus = new StableRows.Kept<>();
    private Registration poll;
    private Replication last;

    ReplicationPage(ConsoleService console, NotificationService notifications, AuthenticationContext auth,
                    ZoneId zone) {
        this.console = console;
        this.notifications = notifications;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = zone;
        addClassName("pg-page");

        H1 title = new H1(Ui.t("nav.replication"));
        title.addClassName("pg-title");
        stats.addClassName("pg-stats");
        Button refreshNow = new Button(Ui.t("ov.refresh"), VaadinIcon.REFRESH.create(), e -> refresh());
        refreshNow.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Div refreshRow = Ui.div("pg-row", refreshed, refreshEvery, refreshNow);
        refreshRow.getStyle().set("flex-wrap", "wrap");
        add(banners, Ui.div("pg-spread", new Div(Ui.text("pg-eyebrow", Ui.t("rp.eyebrow")), title),
                refreshRow), stats);

        buildPrimarySide();
        buildReplicaSide();
        buildSlots();

        Div fromPrimary = Ui.card(null);
        fromPrimary.add(Ui.div("pg-spread", heading(Ui.t("rp.fromPrimary")),
                Ui.text("pg-note", Ui.t("rp.fromPrimaryNote"))), primarySide);

        Div fromReplicas = Ui.card(null);
        fromReplicas.add(Ui.div("pg-spread", heading(Ui.t("rp.fromReplicas")),
                Ui.text("pg-note", Ui.t("rp.fromReplicasNote"))), replicaSide);

        Div lagCard = Ui.card(Ui.t("rp.lagOverTime"));
        lagNode.setAriaLabel(Ui.t("rp.replica"));
        lagNode.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                renderLag(last);
            }
        });
        lagCard.add(lagNode, lagChart);

        Div slotsCard = Ui.card(null);
        slotsCard.add(Ui.div("pg-spread", heading(Ui.t("rp.slots")),
                Ui.text("pg-note", Ui.t("rp.slotsNote"))), slots);

        add(fromPrimary, fromReplicas, Ui.div("pg-grid-2", lagCard, slotsCard));
        refresh();
    }

    private static Span heading(String text) {
        Span s = new Span(text);
        s.getStyle().set("font-size", "18px").set("font-weight", "700").set("color", "var(--pg-ink)");
        return s;
    }

    // ---------------------------------------------------------------- tables

    private void buildPrimarySide() {
        primarySide.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        primarySide.setAllRowsVisible(true);
        primarySide.addComponentColumn(r -> bold(r.node())).setHeader(Ui.t("rp.col.standby")).setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ReplicaRow> client = primarySide.addColumn(r -> r.clientAddr() == null || r.clientAddr().isBlank()
                ? "—" : r.clientAddr()).setHeader(Ui.t("rp.col.client")).setAutoWidth(true);
        primarySide.addComponentColumn(r -> r.peerState() == null
                        ? Ui.chip(Ui.t("rp.notConnected"), Ui.Tone.CRIT)
                        : Ui.chip(Ui.tr(Terms.capitalize(r.peerState())), "streaming".equalsIgnoreCase(r.peerState())
                        ? Ui.Tone.OK : Ui.Tone.WARN))
                .setHeader(Ui.t("rp.col.walsender")).setAutoWidth(true);
        Grid.Column<ReplicaRow> sync = primarySide.addComponentColumn(r -> r.syncState() == null ? Ui.text(null, "—")
                        : Ui.chip(Ui.tr(Terms.capitalize(r.syncState())), "sync".equalsIgnoreCase(r.syncState())
                        || "quorum".equalsIgnoreCase(r.syncState()) ? Ui.Tone.OK : Ui.Tone.NEUTRAL))
                .setHeader(Ui.t("ov.col.sync")).setAutoWidth(true);
        Grid.Column<ReplicaRow> sent = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.sentLsn())))
                .setHeader(Ui.t("rp.col.sent")).setAutoWidth(true);
        Grid.Column<ReplicaRow> written = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.writeLsn())))
                .setHeader(Ui.t("rp.col.written")).setAutoWidth(true);
        Grid.Column<ReplicaRow> flushed = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.flushLsn())))
                .setHeader(Ui.t("rp.col.flushed")).setAutoWidth(true);
        Ui.hideBelow(primarySide, 1100, sent, written, flushed);
        Ui.hideBelow(primarySide, 700, client, sync);
        primarySide.addComponentColumn(r -> {
            Span s = Ui.text(null, Ui.size(r.flushBehindBytes()));
            if (r.flushBehindBytes() != null && r.flushBehindBytes() > 0) {
                s.getStyle().set("color", "var(--pg-warn-fg)").set("font-weight", "600");
            }
            return s;
        }).setHeader(Ui.t("rp.col.atRisk")).setAutoWidth(true)
                .setTooltipGenerator(r -> Ui.t("rp.atRiskTip", r.node()));
        primarySide.addColumn(r -> Ui.seconds(r.peerReplayLagMs())).setHeader(Ui.t("ov.col.lag")).setAutoWidth(true);
    }

    private void buildReplicaSide() {
        replicaSide.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        replicaSide.setAllRowsVisible(true);
        replicaSide.setDetailsVisibleOnClick(false);
        replicaSide.addComponentColumn(r -> Ui.chevron(openRows.contains(r.node()))).setWidth("40px")
                .setFlexGrow(0).setHeader("");
        Grid.Column<ReplicaRow> nodeCol = replicaSide.addComponentColumn(r -> bold(r.node())).setHeader(Ui.t("ov.col.node"))
                .setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ReplicaRow> stacked = replicaSide.addComponentColumn(r -> {
            Div d = new Div(Ui.div("pg-row", bold(r.node()), stateChip(r)), Ui.text("pg-note",
                    Ui.t("ov.behind", Ui.size(r.replayBehindBytes())) + " · " + (r.slotActive() == null
                            ? Ui.t("rp.noSlotLower") : Ui.t(r.slotActive() ? "rp.slotActive" : "rp.slotInactive", r.slot()))));
            d.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px");
            return d;
        }).setHeader(Ui.t("rp.replica")).setFlexGrow(1);
        Grid.Column<ReplicaRow> dc = replicaSide.addColumn(ReplicaRow::dc).setHeader(Ui.t("ov.col.dc")).setAutoWidth(true)
                .setFlexGrow(0);
        Grid.Column<ReplicaRow> state = replicaSide.addComponentColumn(this::stateChip).setHeader(Ui.t("ov.col.state"))
                .setAutoWidth(true);
        Grid.Column<ReplicaRow> from = replicaSide.addColumn(r -> or(r.upstream())).setHeader(Ui.t("rp.col.streamsFrom"))
                .setAutoWidth(true);
        Grid.Column<ReplicaRow> tl = replicaSide.addColumn(r -> r.timeline() == null ? "—" : r.timeline().toString())
                .setHeader(Ui.t("ov.col.timeline")).setAutoWidth(true);
        Grid.Column<ReplicaRow> received = replicaSide.addComponentColumn(r -> Ui.text("pg-mono", or(r.receiveLsn())))
                .setHeader(Ui.t("rp.col.received")).setAutoWidth(true);
        Grid.Column<ReplicaRow> replayed = replicaSide.addComponentColumn(r -> Ui.text("pg-mono", or(r.replayLsn())))
                .setHeader(Ui.t("rp.col.replayed")).setAutoWidth(true);
        Grid.Column<ReplicaRow> behind = replicaSide.addColumn(r -> Ui.size(r.replayBehindBytes())
                + (r.replayDelayMs() == null || r.replayBehindBytes() == null || r.replayBehindBytes() == 0 ? ""
                : " · " + Ui.seconds(r.replayDelayMs()))).setHeader(Ui.t("rp.col.behind")).setAutoWidth(true);
        Grid.Column<ReplicaRow> slot = replicaSide.addComponentColumn(r -> {
            if (r.slotActive() == null) {
                return Ui.chip(Ui.t("rp.noSlot"), Ui.Tone.WARN);
            }
            return Ui.chip(Ui.t(r.slotActive() ? "rp.slotActive" : "rp.slotInactive", r.slot()),
                    r.slotActive() ? Ui.Tone.OK : Ui.Tone.WARN);
        }).setHeader(Ui.t("rp.col.slot")).setAutoWidth(true);
        // Kept across refreshes: a menu built again while it is open would close under the pointer.
        actionsColumn = replicaSide.addColumn(StableRows.Kept.renderer(menus, ReplicaRow::node, this::menuSpec,
                        this::actions)).setHeader(Ui.t("ov.col.actions")).setAutoWidth(true)
                .setFlexGrow(0).setFrozenToEnd(true);
        replicaRows = new StableRows<>(replicaSide, ReplicaRow::node);
        Ui.hideBelow(replicaSide, 1180, received, replayed, tl);
        Ui.hideBelow(replicaSide, 900, from, dc);
        Ui.switchAt(replicaSide, 640, List.of(nodeCol, state, behind, slot), List.of(stacked));
        replicaSide.addItemClickListener(e -> {
            if (e.getColumn() != actionsColumn) {
                toggle(e.getItem().node());
            }
        });
        replicaSide.setItemDetailsRenderer(new ComponentRenderer<>(r -> {
            Div d = new Div();
            if (r.error() != null) {
                d.add(new Paragraph(Ui.t("ov.error", Ui.tr(r.error()))));
            }
            if (r.fenced()) {
                d.add(new Paragraph(Ui.t("ov.fenced", Ui.tr(r.fencedReason()))));
            }
            if (r.busy() != null) {
                d.add(new Paragraph(Ui.t("rp.busyWith", Ui.tr(r.busy()))));
            }
            d.getStyle().set("padding-block", "4px 8px").set("padding-inline", "48px 8px");
            d.add(new Paragraph(Ui.t("rp.detailLine", r.address(), or(r.upstream()),
                    r.timeline() == null ? "—" : r.timeline(), or(r.receiveLsn()), or(r.replayLsn()))));
            d.add(new Paragraph(Ui.t("rp.slotLine", r.slot())
                    + (r.slotRetainedBytes() == null ? "" : " " + Ui.t("rp.holding", Ui.size(r.slotRetainedBytes())))
                    + (r.noFailover() ? " · " + Ui.t("rp.neverPromoted") : "")));
            return d;
        }));
    }

    private void buildSlots() {
        slots.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        slots.setAllRowsVisible(true);
        slots.addColumn(ConsoleService.SlotRow::name).setHeader(Ui.t("rp.col.slot")).setAutoWidth(true);
        slots.addColumn(ConsoleService.SlotRow::consumer).setHeader(Ui.t("rp.col.consumer")).setAutoWidth(true);
        slots.addComponentColumn(s -> Ui.chip(Ui.t(s.active() ? "rp.active" : "rp.inactive"),
                s.active() ? Ui.Tone.OK : Ui.Tone.WARN)).setHeader(Ui.t("rp.col.status")).setAutoWidth(true);
        slots.addColumn(s -> Ui.size(s.retainedBytes())).setHeader(Ui.t("rp.col.retained")).setAutoWidth(true);
        slots.addComponentColumn(s -> {
            if (!admin || s.active() || !"none".equals(s.consumer())) {
                return new Span();
            }
            Button drop = new Button(Ui.t("rp.drop"), e -> {
                ConfirmDialog d = new ConfirmDialog(Ui.t("rp.dropTitle", s.name()), Ui.t("rp.dropText"), Ui.t("rp.drop"), ev -> {
                    if (Ui.attempt(() -> console.watcher().dropSlot(s.name(), user))) {
                        Ui.ok(Ui.t("rp.dropped", s.name()));
                    }
                    refresh();
                });
                d.setCancelable(true);
                d.setConfirmButtonTheme("error primary");
                d.open();
            });
            drop.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            return drop;
        }).setAutoWidth(true);
    }

    private Span stateChip(ReplicaRow r) {
        return Ui.chip(Ui.tr(r.state()), r.fenced() || !r.agentOnline() ? Ui.Tone.CRIT
                : r.streaming() ? Ui.Tone.OK : Ui.Tone.WARN);
    }

    /** Shows or hides a row's details; by name, as a kept menu may have been built for an older row. */
    private void toggle(String node) {
        if (!openRows.remove(node)) {
            openRows.add(node);
        }
        replicaRows.find(node).ifPresent(r -> {
            replicaSide.setDetailsVisible(r, openRows.contains(node));
            replicaSide.getDataProvider().refreshItem(r);
        });
    }

    private static boolean runsAsReplica(ReplicaRow r) {
        return "Streaming".equals(r.state()) || "Not streaming".equals(r.state());
    }

    /** What a node's menu offers; the menu is built again only when this changes. */
    private Object menuSpec(ReplicaRow r) {
        return List.of(r.agentOnline(), runsAsReplica(r), last != null && !last.primaryRunning(),
                console.watcher().identity().foreignData().contains(r.node()));
    }

    private Component actions(ReplicaRow r) {
        String node = r.node();
        return NodeActions.menu(console, user, admin, new NodeActions.Node(node, false, r.agentOnline(),
                runsAsReplica(r), last != null && !last.primaryRunning(),
                console.watcher().identity().foreignData().contains(node), null), () -> toggle(node),
                this::refresh);
    }

    // ---------------------------------------------------------------- refreshing

    private void refresh() {
        Replication r = console.replication();
        last = r;
        renderBanners(r);
        renderStats(r);
        primarySide.setItems(r.replicas());
        replicaRows.set(r.replicas());
        for (ReplicaRow row : r.replicas()) {
            if (openRows.contains(row.node())) {
                replicaSide.setDetailsVisible(row, true);
            }
        }
        slots.setItems(r.slots());
        refreshed.setText(Ui.t("rp.refreshed", Ui.time(r.observedAtMillis(), zone) + " " + Ui.zone(zone)));
        renderLag(r);
    }

    private void renderBanners(Replication r) {
        banners.removeAll();
        banners.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        if (r.primary() == null) {
            banners.add(Ui.banner(Ui.t("rp.noPrimary"), Ui.Tone.WARN));
            return;
        }
        if (!r.primaryRunning()) {
            banners.add(Ui.banner(Ui.t("rp.primaryDown", r.primary()), Ui.Tone.CRIT));
        }
        List<String> notConnected = r.replicas().stream()
                .filter(x -> !x.connectedToPrimary() && x.agentOnline() && !x.fenced() && x.busy() == null)
                .map(ReplicaRow::node).toList();
        if (r.primaryRunning() && !notConnected.isEmpty()) {
            banners.add(Ui.banner(Ui.t(notConnected.size() == 1 ? "rp.noWalsenderOne" : "rp.noWalsender",
                    String.join(", ", notConnected), r.primary()), Ui.Tone.WARN));
        }
        ClusterState.Replication m = r.mode();
        int sync = syncStreaming(r);
        if (m.synchronous() && m.strict() && sync < m.count()) {
            banners.add(Ui.banner(Ui.t("ov.strictWaitingBanner", r.primary(), sync, m.count()), Ui.Tone.CRIT));
        } else if (m.synchronous() && !m.strict() && m.degraded()) {
            banners.add(Ui.banner(Ui.t("ov.syncDegradedBanner", sync, m.count()), Ui.Tone.WARN));
        }
    }

    /** Synchronous replicas the primary streams to (walsender caught up). */
    private static int syncStreaming(Replication r) {
        return (int) r.replicas().stream().filter(x -> r.mode().standbys().contains(x.node())
                && "streaming".equalsIgnoreCase(x.peerState())).count();
    }

    private void renderStats(Replication r) {
        stats.removeAll();
        stats.add(Ui.stat(Ui.t("ov.stat.primary"), r.primary() == null ? "—" : r.primary(),
                r.primaryRunning() ? r.primaryAddress() + " · " + Ui.t("ov.cardTimeline", r.primaryTimeline())
                        : Ui.t("rp.notRunning")));
        stats.add(Ui.stat(Ui.t("rp.stat.streaming"), r.streamingCount() + " / " + r.replicas().size(),
                Ui.t(r.replicas().isEmpty() ? "rp.noReplicas" : "rp.receiving")));
        stats.add(Ui.stat(Ui.t("rp.stat.risk"), Ui.size(r.worstFlushBehindBytes()),
                Ui.t(r.worstFlushBehindBytes() == null ? "rp.noConnected"
                        : r.worstFlushBehindBytes() == 0 ? "rp.allOnDisk" : "rp.notDurable")));
        stats.add(Ui.stat(Ui.t("rp.stat.walRate"), r.walRatePerMinute() == null ? "—"
                        : Ui.t("rp.perMinute", Ui.size(r.walRatePerMinute())),
                r.primaryLsn() == null ? Ui.t("rp.positionUnknown") : Ui.t("rp.primaryAt", r.primaryLsn())));
        ClusterState.Replication m = r.mode();
        String value = !m.synchronous() ? Ui.t("ov.repl.async")
                : Ui.t("ov.repl." + m.mode()) + " · " + Ui.t("ov.replAny", m.count());
        String sub = r.syncStandbys() == null ? Ui.t("rp.syncUnknown")
                : r.syncStandbys().isEmpty() ? Ui.t("rp.syncNone") : Ui.ltr(r.syncStandbys());
        int sync = syncStreaming(r);
        stats.add(Ui.stat(Ui.t("rp.stat.commits"), value, sub,
                m.synchronous() && !m.strict() && m.degraded() ? Ui.Tone.WARN
                        : m.synchronous() && m.strict() && sync < m.count() ? Ui.Tone.CRIT : null));
    }

    private void renderLag(Replication r) {
        if (r == null) {
            return;
        }
        List<String> names = r.replicas().stream().map(ReplicaRow::node).toList();
        if (!names.equals(lagNode.getListDataView().getItems().toList())) {
            lagNode.setItems(names);
        }
        if (lagNode.getValue() == null || !names.contains(lagNode.getValue())) {
            lagNode.setValue(names.isEmpty() ? null : names.get(0));
        }
        lagChart.removeAll();
        if (lagNode.getValue() == null) {
            lagChart.add(Ui.text("pg-note", Ui.t("rp.noReplicasConfigured")));
            return;
        }
        NotificationSettings ns = notifications.current();
        long now = System.currentTimeMillis();
        List<LagHistory.Sample> samples = console.watcher().lagHistory().series(lagNode.getValue(), 60, now);
        lagChart.add(LagChart.render(samples, ns.lagAlertBytes(), lagNode.getValue(), Ui.t("rp.lastHour"), zone, now));
    }

    private static Span bold(String text) {
        Span s = new Span(text);
        s.getStyle().set("font-weight", "700").set("color", "var(--pg-ink)");
        return s;
    }

    private static String or(String s) {
        return s == null ? "—" : s;
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
