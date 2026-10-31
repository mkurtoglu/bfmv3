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
import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.core.monitor.LagHistory;
import com.bisoft.bfm.core.notify.NotificationSettings;
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
    private final Set<String> openRows = new HashSet<>();
    private Grid.Column<ReplicaRow> actionsColumn;
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

        H1 title = new H1("Replication");
        title.addClassName("pg-title");
        stats.addClassName("pg-stats");
        add(banners, Ui.div("pg-spread", new Div(Ui.text("pg-eyebrow", "Streaming state, lag and slots"), title),
                refreshed), stats);

        buildPrimarySide();
        buildReplicaSide();
        buildSlots();

        Div fromPrimary = Ui.card(null);
        fromPrimary.add(Ui.div("pg-spread", heading("As the primary sees them"),
                Ui.text("pg-note", "pg_stat_replication: one row per connected walsender")), primarySide);

        Div fromReplicas = Ui.card(null);
        fromReplicas.add(Ui.div("pg-spread", heading("As each replica reports itself"),
                Ui.text("pg-note", "the replica's own WAL receiver and recovery state")), replicaSide);

        Div lagCard = Ui.card("Replay lag over time");
        lagNode.setAriaLabel("Replica");
        lagNode.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                renderLag(last);
            }
        });
        lagCard.add(lagNode, lagChart);

        Div slotsCard = Ui.card(null);
        slotsCard.add(Ui.div("pg-spread", heading("Replication slots"),
                Ui.text("pg-note", "an inactive slot keeps WAL on the primary until it is dropped")), slots);

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
        primarySide.addComponentColumn(r -> bold(r.node())).setHeader("Standby").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ReplicaRow> client = primarySide.addColumn(r -> r.clientAddr() == null || r.clientAddr().isBlank()
                ? "—" : r.clientAddr()).setHeader("Client address").setAutoWidth(true);
        primarySide.addComponentColumn(r -> r.peerState() == null
                        ? Ui.chip("Not connected", Ui.Tone.CRIT)
                        : Ui.chip(Terms.capitalize(r.peerState()), "streaming".equalsIgnoreCase(r.peerState())
                        ? Ui.Tone.OK : Ui.Tone.WARN))
                .setHeader("Walsender").setAutoWidth(true);
        Grid.Column<ReplicaRow> sync = primarySide.addComponentColumn(r -> r.syncState() == null ? Ui.text(null, "—")
                        : Ui.chip(Terms.capitalize(r.syncState()), "sync".equalsIgnoreCase(r.syncState())
                        || "quorum".equalsIgnoreCase(r.syncState()) ? Ui.Tone.OK : Ui.Tone.NEUTRAL))
                .setHeader("Sync").setAutoWidth(true);
        Grid.Column<ReplicaRow> sent = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.sentLsn())))
                .setHeader("Sent").setAutoWidth(true);
        Grid.Column<ReplicaRow> written = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.writeLsn())))
                .setHeader("Written").setAutoWidth(true);
        Grid.Column<ReplicaRow> flushed = primarySide.addComponentColumn(r -> Ui.text("pg-mono", or(r.flushLsn())))
                .setHeader("Flushed").setAutoWidth(true);
        Ui.hideBelow(primarySide, 1100, sent, written, flushed);
        Ui.hideBelow(primarySide, 700, client, sync);
        primarySide.addComponentColumn(r -> {
            Span s = Ui.text(null, ConsoleService.bytes(r.flushBehindBytes()));
            if (r.flushBehindBytes() != null && r.flushBehindBytes() > 0) {
                s.getStyle().set("color", "var(--pg-warn-fg)").set("font-weight", "600");
            }
            return s;
        }).setHeader("At risk").setAutoWidth(true)
                .setTooltipGenerator(r -> "Written on the primary but not yet durable on " + r.node()
                        + ": what a crash failover to it would lose");
        primarySide.addColumn(r -> Ui.seconds(r.peerReplayLagMs())).setHeader("Replay lag").setAutoWidth(true);
    }

    private void buildReplicaSide() {
        replicaSide.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        replicaSide.setAllRowsVisible(true);
        replicaSide.setDetailsVisibleOnClick(false);
        replicaSide.addComponentColumn(r -> Ui.chevron(openRows.contains(r.node()))).setWidth("40px")
                .setFlexGrow(0).setHeader("");
        Grid.Column<ReplicaRow> nodeCol = replicaSide.addComponentColumn(r -> bold(r.node())).setHeader("Node")
                .setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ReplicaRow> stacked = replicaSide.addComponentColumn(r -> {
            Div d = new Div(Ui.div("pg-row", bold(r.node()), stateChip(r)), Ui.text("pg-note",
                    ConsoleService.bytes(r.replayBehindBytes()) + " behind · " + (r.slotActive() == null ? "no slot"
                            : r.slot() + (r.slotActive() ? " active" : " inactive"))));
            d.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px");
            return d;
        }).setHeader("Replica").setFlexGrow(1);
        Grid.Column<ReplicaRow> dc = replicaSide.addColumn(ReplicaRow::dc).setHeader("DC").setAutoWidth(true)
                .setFlexGrow(0);
        Grid.Column<ReplicaRow> state = replicaSide.addComponentColumn(this::stateChip).setHeader("State")
                .setAutoWidth(true);
        Grid.Column<ReplicaRow> from = replicaSide.addColumn(r -> or(r.upstream())).setHeader("Streams from")
                .setAutoWidth(true);
        Grid.Column<ReplicaRow> tl = replicaSide.addColumn(r -> r.timeline() == null ? "—" : r.timeline().toString())
                .setHeader("Timeline").setAutoWidth(true);
        Grid.Column<ReplicaRow> received = replicaSide.addComponentColumn(r -> Ui.text("pg-mono", or(r.receiveLsn())))
                .setHeader("Received").setAutoWidth(true);
        Grid.Column<ReplicaRow> replayed = replicaSide.addComponentColumn(r -> Ui.text("pg-mono", or(r.replayLsn())))
                .setHeader("Replayed").setAutoWidth(true);
        Grid.Column<ReplicaRow> behind = replicaSide.addColumn(r -> ConsoleService.bytes(r.replayBehindBytes())
                + (r.replayDelayMs() == null || r.replayBehindBytes() == null || r.replayBehindBytes() == 0 ? ""
                : " · " + Ui.seconds(r.replayDelayMs()))).setHeader("Behind the primary").setAutoWidth(true);
        Grid.Column<ReplicaRow> slot = replicaSide.addComponentColumn(r -> {
            if (r.slotActive() == null) {
                return Ui.chip("No slot", Ui.Tone.WARN);
            }
            return Ui.chip(r.slot() + (r.slotActive() ? " active" : " inactive"),
                    r.slotActive() ? Ui.Tone.OK : Ui.Tone.WARN);
        }).setHeader("Slot").setAutoWidth(true);
        actionsColumn = replicaSide.addComponentColumn(this::actions).setHeader("Actions").setAutoWidth(true)
                .setFlexGrow(0).setFrozenToEnd(true);
        Ui.hideBelow(replicaSide, 1180, received, replayed, tl);
        Ui.hideBelow(replicaSide, 900, from, dc);
        Ui.switchAt(replicaSide, 640, List.of(nodeCol, state, behind, slot), List.of(stacked));
        replicaSide.addItemClickListener(e -> {
            if (e.getColumn() != actionsColumn) {
                toggle(e.getItem());
            }
        });
        replicaSide.setItemDetailsRenderer(new ComponentRenderer<>(r -> {
            Div d = new Div();
            d.getStyle().set("padding", "4px 8px 8px");
            if (r.error() != null) {
                d.add(new Paragraph("Error: " + r.error()));
            }
            if (r.fenced()) {
                d.add(new Paragraph("Fenced: " + r.fencedReason()));
            }
            if (r.busy() != null) {
                d.add(new Paragraph("Busy with: " + r.busy()));
            }
            d.getStyle().set("padding", "4px 8px 8px 48px");
            d.add(new Paragraph(r.address() + " · streams from " + or(r.upstream()) + " · timeline "
                    + (r.timeline() == null ? "—" : r.timeline()) + " · received " + or(r.receiveLsn())
                    + " · replayed " + or(r.replayLsn())));
            d.add(new Paragraph("Slot " + r.slot()
                    + (r.slotRetainedBytes() == null ? "" : " holding " + Lsn.bytes(r.slotRetainedBytes()))
                    + (r.noFailover() ? " · never promoted automatically (noFailover)" : "")));
            return d;
        }));
    }

    private void buildSlots() {
        slots.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        slots.setAllRowsVisible(true);
        slots.addColumn(ConsoleService.SlotRow::name).setHeader("Slot").setAutoWidth(true);
        slots.addColumn(ConsoleService.SlotRow::consumer).setHeader("Consumer").setAutoWidth(true);
        slots.addComponentColumn(s -> Ui.chip(s.active() ? "Active" : "Inactive",
                s.active() ? Ui.Tone.OK : Ui.Tone.WARN)).setHeader("Status").setAutoWidth(true);
        slots.addColumn(s -> ConsoleService.bytes(s.retainedBytes())).setHeader("Retained WAL").setAutoWidth(true);
        slots.addComponentColumn(s -> {
            if (!admin || s.active() || !"none".equals(s.consumer())) {
                return new Span();
            }
            Button drop = new Button("Drop", e -> {
                ConfirmDialog d = new ConfirmDialog("Drop slot " + s.name(), "The slot is removed from the primary"
                        + " and stops holding WAL.", "Drop", ev -> {
                    if (Ui.attempt(() -> console.watcher().dropSlot(s.name(), user))) {
                        Ui.ok("Slot " + s.name() + " dropped");
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
        return Ui.chip(r.state(), r.fenced() || !r.agentOnline() ? Ui.Tone.CRIT
                : r.streaming() ? Ui.Tone.OK : Ui.Tone.WARN);
    }

    private void toggle(ReplicaRow r) {
        if (!openRows.remove(r.node())) {
            openRows.add(r.node());
        }
        replicaSide.setDetailsVisible(r, openRows.contains(r.node()));
        replicaSide.getDataProvider().refreshItem(r);
    }

    private Component actions(ReplicaRow r) {
        boolean runsAsReplica = "Streaming".equals(r.state()) || "Not streaming".equals(r.state());
        return NodeActions.menu(console, user, admin, new NodeActions.Node(r.node(), false, r.agentOnline(),
                runsAsReplica, last != null && !last.primaryRunning(),
                console.watcher().identity().foreignData().contains(r.node())), () -> toggle(r), this::refresh);
    }

    // ---------------------------------------------------------------- refreshing

    private void refresh() {
        Replication r = console.replication();
        last = r;
        renderBanners(r);
        renderStats(r);
        primarySide.setItems(r.replicas());
        replicaSide.setItems(r.replicas());
        for (ReplicaRow row : r.replicas()) {
            if (openRows.contains(row.node())) {
                replicaSide.setDetailsVisible(row, true);
            }
        }
        slots.setItems(r.slots());
        refreshed.setText("Refreshed " + Ui.time(r.observedAtMillis(), zone) + " " + Ui.zone(zone));
        renderLag(r);
    }

    private void renderBanners(Replication r) {
        banners.removeAll();
        banners.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        if (r.primary() == null) {
            banners.add(Ui.banner("No primary is recorded, so there is nothing to stream from.", Ui.Tone.WARN));
            return;
        }
        if (!r.primaryRunning()) {
            banners.add(Ui.banner("The primary " + r.primary() + " is not answering; the numbers below are the"
                    + " last ones each replica reported.", Ui.Tone.CRIT));
        }
        List<String> notConnected = r.replicas().stream()
                .filter(x -> !x.connectedToPrimary() && x.agentOnline() && !x.fenced() && x.busy() == null)
                .map(ReplicaRow::node).toList();
        if (r.primaryRunning() && !notConnected.isEmpty()) {
            banners.add(Ui.banner(String.join(", ", notConnected) + (notConnected.size() == 1 ? " has" : " have")
                    + " no walsender on " + r.primary() + ": not streaming right now.", Ui.Tone.WARN));
        }
    }

    private void renderStats(Replication r) {
        stats.removeAll();
        stats.add(Ui.stat("Primary", r.primary() == null ? "—" : r.primary(),
                r.primaryRunning() ? r.primaryAddress() + " · timeline " + r.primaryTimeline()
                        : "not running"));
        stats.add(Ui.stat("Streaming replicas", r.streamingCount() + " / " + r.replicas().size(),
                r.replicas().isEmpty() ? "no replicas configured" : "connected and receiving WAL"));
        stats.add(Ui.stat("Data at risk", ConsoleService.bytes(r.worstFlushBehindBytes()),
                r.worstFlushBehindBytes() == null ? "no connected replica"
                        : r.worstFlushBehindBytes() == 0 ? "every replica has it on disk"
                        : "not yet durable on the furthest-behind replica"));
        stats.add(Ui.stat("WAL rate", r.walRatePerMinute() == null ? "—"
                        : Lsn.bytes(r.walRatePerMinute()) + "/min",
                r.primaryLsn() == null ? "primary position unknown" : "primary at " + r.primaryLsn()));
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
            lagChart.add(Ui.text("pg-note", "No replicas configured."));
            return;
        }
        NotificationSettings ns = notifications.current();
        long now = System.currentTimeMillis();
        List<LagHistory.Sample> samples = console.watcher().lagHistory().series(lagNode.getValue(), 60, now);
        lagChart.add(LagChart.render(samples, ns.lagAlertBytes(), lagNode.getValue(), "last hour", zone, now));
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
        e.getUI().setPollInterval((int) Math.max(1000, console.watcher().config().timing().pollIntervalMs()));
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
