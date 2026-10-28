package io.pgvip.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
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
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import io.pgvip.common.Lsn;
import io.pgvip.core.event.ClusterEvent;
import io.pgvip.core.monitor.LagHistory;
import io.pgvip.core.notify.NotificationSettings;
import io.pgvip.core.ops.PreCheck;
import io.pgvip.watcher.config.PgvipProperties;
import io.pgvip.watcher.service.ConsoleService;
import io.pgvip.watcher.service.ConsoleService.NodeRow;
import io.pgvip.watcher.service.ConsoleService.Overview;
import io.pgvip.watcher.service.NotificationService;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Route(value = "", layout = MainLayout.class)
@RouteAlias(value = "overview", layout = MainLayout.class)
@PageTitle("Overview · pgVIP")
@PermitAll
public class OverviewView extends Div {

    private final ConsoleService console;
    private final NotificationService notifications;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;

    private final Div banners = new Div();
    private final Span headStatus = new Span();
    private final Div stats = Ui.div("pg-grid-auto");
    private final Grid<NodeRow> grid = new Grid<>(NodeRow.class, false);
    private final Span refreshed = Ui.text("pg-note", "");
    private final Div topology = new Div();
    private final Div lagChart = new Div();
    private final Select<String> lagNode = new Select<>();
    private final UnorderedList events = new UnorderedList();
    private final Div slots = new Div();
    private final Div watchers = new Div();
    private final Checkbox autoFailover = new Checkbox("Auto failover");
    private final Checkbox mail = new Checkbox("Mail notifications");
    private final Checkbox maintenance = new Checkbox("Maintenance mode");
    private final Checkbox autoReinit = new Checkbox("Auto reinit");
    private final Span scope = Ui.text("pg-note", "");
    private final Select<String> target = new Select<>();
    private final Div targetChecks = new Div();
    private Registration poll;
    private Overview last;

    public OverviewView(ConsoleService console, NotificationService notifications, AuthenticationContext auth,
                        PgvipProperties props) {
        this.console = console;
        this.notifications = notifications;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = props.zone();
        addClassName("pg-page");

        H1 title = new H1("Cluster Overview");
        title.addClassName("pg-title");
        Div head = Ui.div("pg-spread", new Div(Ui.text("pg-eyebrow", "Welcome back, " + user), title), headStatus);
        headStatus.addClassName("pg-row");

        buildGrid();
        Button refresh = new Button("Refresh", VaadinIcon.REFRESH.create(), e -> refresh());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Div nodesCard = Ui.card(null);
        nodesCard.add(Ui.div("pg-spread", heading("Nodes"), Ui.div("pg-row", refreshed, refresh)), grid);

        Div topoCard = Ui.card("Topology");
        topoCard.add(topology);

        Div lagCard = Ui.card("Replay lag");
        lagNode.setAriaLabel("Replica");
        lagNode.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                renderLag(last);
            }
        });
        lagCard.add(lagNode, lagChart);

        Div eventsCard = Ui.card(null);
        Anchor all = new Anchor("events", "View all");
        eventsCard.add(Ui.div("pg-spread", heading("Recent events"), all));
        events.addClassName("pg-events");
        eventsCard.add(events);

        Div slotsCard = Ui.card("Replication slots");
        slotsCard.add(slots);

        Div controlsCard = Ui.card("Cluster controls");
        controlsCard.add(controlsBlock());

        Div switchCard = Ui.card("Switchover / Re-initialize");
        switchCard.add(switchBlock());

        Div watchersCard = Ui.card("Watchers");
        watchersCard.add(watchers);

        Div left = Ui.div("pg-col", topoCard, lagCard, eventsCard, slotsCard);
        Div right = Ui.div("pg-col", controlsCard, switchCard, watchersCard);
        add(banners, head, stats, nodesCard, Ui.div("pg-grid-2", left, right));
        refresh();
    }

    private static Span heading(String text) {
        Span s = new Span(text);
        s.getStyle().set("font-size", "18px").set("font-weight", "700").set("color", "var(--pg-ink)");
        return s;
    }

    // ---------------------------------------------------------------- building

    private void buildGrid() {
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COMPACT);
        grid.setAllRowsVisible(true);
        grid.addComponentColumn(r -> {
            Span s = new Span(r.name());
            s.getStyle().set("font-weight", "700").set("color", "var(--pg-ink)");
            return s;
        }).setHeader("Node").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(NodeRow::address).setHeader("Server address").setAutoWidth(true);
        grid.addColumn(NodeRow::dc).setHeader("DC").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(r -> Ui.chip(r.role() + (r.isPrimary() || !r.role().equals("Replica") ? ""
                        : dcSuffix(r)), r.role().equals("Primary") ? Ui.Tone.OK : r.role().equals("Replica")
                        ? Ui.Tone.INFO : Ui.Tone.CRIT))
                .setHeader("Role").setAutoWidth(true);
        grid.addComponentColumn(r -> Ui.chip(r.state(), stateTone(r))).setHeader("State").setAutoWidth(true);
        grid.addComponentColumn(r -> Ui.text("pg-mono", r.lsn() == null ? "—" : r.lsn()))
                .setHeader("Last WAL position").setAutoWidth(true);
        grid.addColumn(r -> r.isPrimary() ? "—" : ConsoleService.bytes(r.lagBytes())
                + (r.replayDelayMs() == null || r.lagBytes() == null || r.lagBytes() == 0 ? ""
                : " · " + Ui.seconds(r.replayDelayMs())))
                .setHeader("Replay lag").setAutoWidth(true);
        grid.addColumn(r -> r.timeline() == null ? "—" : r.timeline().toString()).setHeader("Timeline")
                .setAutoWidth(true).setTextAlign(ColumnTextAlign.END);
        grid.addColumn(r -> r.sync() == null ? "—" : r.sync()).setHeader("Sync").setAutoWidth(true);
        grid.addComponentColumn(r -> Ui.chip(r.agentOnline() ? "Online" : "Unreachable",
                r.agentOnline() ? Ui.Tone.OK : Ui.Tone.CRIT)).setHeader("Agent").setAutoWidth(true);
        grid.addColumn(r -> Ui.time(r.lastCheckMillis(), zone)).setHeader("Last check").setAutoWidth(true);
        grid.addComponentColumn(this::actions).setHeader("Actions").setAutoWidth(true).setFlexGrow(0);
        grid.setItemDetailsRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<>(r -> {
            Div d = new Div();
            d.getStyle().set("padding", "4px 8px 8px");
            if (r.error() != null) {
                d.add(new Paragraph("Error: " + r.error()));
            }
            if (r.fenced()) {
                d.add(new Paragraph("Fenced: " + r.fencedReason()));
            }
            d.add(new Paragraph("Upstream: " + (r.upstream() == null ? "—" : r.upstream()) + " · epoch " + r.epoch()
                    + (r.vipAddress() == null ? " · no VIP managed" : " · VIP " + r.vipAddress()
                    + (r.vipHeld() ? " up here" : " not here")) + (r.noFailover() ? " · noFailover" : "")
                    + " · priority " + r.priority()));
            return d;
        }));
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

    private Component actions(NodeRow r) {
        MenuBar mb = new MenuBar();
        mb.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE, MenuBarVariant.LUMO_SMALL);
        MenuItem root = mb.addItem(VaadinIcon.ELLIPSIS_DOTS_H.create());
        root.setAriaLabel("Actions for " + r.name());
        SubMenu sub = root.getSubMenu();
        sub.addItem("Details", e -> grid.setDetailsVisible(r, !grid.isDetailsVisible(r)));
        if (admin && !r.isPrimary()) {
            sub.addItem("Switch over to " + r.name(), e -> UI.getCurrent().navigate(SwitchoverView.class,
                    new com.vaadin.flow.router.QueryParameters(java.util.Map.of("target", List.of(r.name())))));
            sub.addItem("Rejoin (pg_rewind)", e -> confirmNode(r.name(), "Rejoin " + r.name(),
                    r.name() + " is stopped, rewound with pg_rewind against the primary and started as a replica.",
                    () -> console.watcher().rejoin(r.name(), user)));
            sub.addItem("Re-initialize", e -> confirmNode(r.name(), "Re-initialize " + r.name(),
                    "The data directory of " + r.name() + " is moved aside and rebuilt with pg_basebackup from"
                            + " the primary. This can take a long time.",
                    () -> console.watcher().reinit(r.name(), user)));
        }
        if (admin && last != null && last.nodes().stream().noneMatch(n -> "Primary".equals(n.role()))
                && r.agentOnline() && !"Replica".equals(r.role())) {
            sub.addItem("Start as primary", e -> confirmNode(r.name(), "Start " + r.name() + " as primary",
                    "No node runs as primary. The VIP comes up on " + r.name() + " first, then PostgreSQL starts"
                            + " there as primary.", () -> console.watcher().startPrimary(r.name(), user)));
        }
        return mb;
    }

    private void confirmNode(String node, String header, String text, Runnable action) {
        ConfirmDialog d = new ConfirmDialog();
        d.setHeader(header);
        TextField confirm = new TextField("Type " + node + " to confirm");
        confirm.setWidthFull();
        Div body = new Div(new Paragraph(text), confirm);
        d.setText(body);
        d.setCancelable(true);
        d.setConfirmText("Start");
        d.setCloseOnEsc(true);
        d.addConfirmListener(e -> {
            if (!node.equals(confirm.getValue().trim())) {
                Ui.error("Type " + node + " exactly");
                return;
            }
            if (Ui.attempt(action)) {
                Ui.ok(header + " started; follow it under Operations");
            }
            refresh();
        });
        d.open();
    }

    private Component controlsBlock() {
        Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "10px");
        addToggle(box, autoFailover, "Promote a replica when the primary is lost");
        addToggle(box, mail, "SMTP alerts to the recipients on the Notifications page");
        addToggle(box, maintenance, "Pause failover during planned work");
        addToggle(box, autoReinit, "Rebuild a replica when pg_rewind fails");
        autoFailover.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                setControl(e.getValue(), null, null, null);
            }
        });
        maintenance.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                setControl(null, e.getValue(), null, null);
            }
        });
        mail.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                setControl(null, null, e.getValue(), null);
            }
        });
        autoReinit.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                setControl(null, null, null, e.getValue());
            }
        });
        box.add(Ui.div("pg-row", Ui.text("pg-stat-label", "Failover scope"), scope));
        return box;
    }

    private void addToggle(Div box, Checkbox cb, String help) {
        cb.setReadOnly(!admin);
        cb.setHelperText(help);
        box.add(cb);
    }

    private void setControl(Boolean af, Boolean mm, Boolean ml, Boolean ar) {
        if (Ui.attempt(() -> console.watcher().setControls(af, mm, ml, ar, user))) {
            Ui.ok("Saved");
        }
        refresh();
    }

    private Component switchBlock() {
        Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "10px");
        target.setLabel("Target node");
        target.setWidthFull();
        target.addValueChangeListener(e -> renderTargetChecks());
        Button sw = new Button("Switchover", e -> UI.getCurrent().navigate(SwitchoverView.class,
                new com.vaadin.flow.router.QueryParameters(java.util.Map.of("target",
                        target.getValue() == null ? List.of() : List.of(target.getValue())))));
        sw.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button reinit = new Button("Re-initialize", e -> {
            if (target.getValue() != null) {
                confirmNode(target.getValue(), "Re-initialize " + target.getValue(), "The data directory of "
                        + target.getValue() + " is rebuilt with pg_basebackup from the primary.",
                        () -> console.watcher().reinit(target.getValue(), user));
            }
        });
        Button rejoin = new Button("Rejoin (pg_rewind)", e -> {
            if (target.getValue() != null) {
                confirmNode(target.getValue(), "Rejoin " + target.getValue(), target.getValue() + " is stopped,"
                        + " rewound and started as a replica of the primary.",
                        () -> console.watcher().rejoin(target.getValue(), user));
            }
        });
        sw.setEnabled(admin);
        reinit.setEnabled(admin);
        rejoin.setEnabled(admin);
        box.add(target, targetChecks, Ui.div("pg-row", sw, reinit, rejoin));
        if (!admin) {
            box.add(Ui.text("pg-note", "Viewers cannot start operations."));
        }
        return box;
    }

    // ---------------------------------------------------------------- refreshing

    private void refresh() {
        Overview o = console.overview();
        last = o;
        renderBanners(o);
        renderHead(o);
        renderStats(o);
        grid.setItems(o.nodes());
        refreshed.setText("Refreshed " + Ui.time(o.observedAtMillis(), zone) + " · every "
                + console.watcher().config().timing().pollIntervalMs() / 1000 + " s");
        renderTopology(o);
        renderLag(o);
        renderEvents();
        renderSlots(o);
        renderWatchers(o);
        autoFailover.setValue(o.autoFailover());
        mail.setValue(o.mailEnabled());
        maintenance.setValue(o.maintenance());
        autoReinit.setValue(o.autoReinit());
        scope.setText((o.scope().equals("DC_LOCAL") ? "DC-local only" : "Any data center")
                + (o.selfFence() ? " · self-fencing on" : ""));
        List<String> replicas = o.nodes().stream().filter(n -> !n.isPrimary()).map(NodeRow::name).toList();
        if (!replicas.equals(target.getListDataView().getItems().toList())) {
            String keep = target.getValue();
            target.setItems(replicas);
            target.setValue(keep != null && replicas.contains(keep) ? keep : replicas.isEmpty() ? null
                    : replicas.get(0));
        }
        target.setItemLabelGenerator(n -> o.nodes().stream().filter(r -> r.name().equals(n)).findFirst()
                .map(r -> r.name() + " · " + (r.sync() == null ? "async" : r.sync()) + " · " + r.dc()).orElse(n));
        renderTargetChecks();
    }

    private void renderBanners(Overview o) {
        banners.removeAll();
        banners.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        if (!o.leader()) {
            Div b = Ui.banner("This watcher (" + o.watcherId() + ") follows" + (o.leaderId() == null
                    ? " no leader right now; changes are not possible until a leader is elected."
                    : " watcher " + o.leaderId() + ". Operations run on the leader."), Ui.Tone.INFO);
            if (o.leaderUrl() != null) {
                b.add(new Anchor(o.leaderUrl(), "Open the leader's console"));
            }
            banners.add(b);
        }
        if (o.maintenance()) {
            banners.add(Ui.banner("Maintenance mode is on. Automatic failover is paused. Manual switchover and"
                    + " reinit stay available.", Ui.Tone.WARN));
        }
        if (o.operation() != null) {
            Div b = Ui.banner(o.operation().type() + " on " + o.operation().target() + " is running, started by "
                    + o.operation().startedBy() + ".", Ui.Tone.INFO);
            b.add(new Anchor("operations", "Follow it"));
            banners.add(b);
        }
        if ("Primary down".equals(o.health())) {
            banners.add(Ui.banner("The primary " + o.primary() + " is not answering as primary. "
                    + (o.failoverActive() ? "Automatic failover is armed." : "Automatic failover is off or paused."),
                    Ui.Tone.CRIT));
        }
        if ("No primary".equals(o.health())) {
            banners.add(Ui.banner("No primary is recorded. Start one node as primary from its Actions menu.",
                    Ui.Tone.WARN));
        }
    }

    private void renderHead(Overview o) {
        headStatus.removeAll();
        Ui.Tone t = switch (o.health()) {
            case "Healthy" -> Ui.Tone.OK;
            case "Degraded", "Starting", "No primary" -> Ui.Tone.WARN;
            default -> Ui.Tone.CRIT;
        };
        headStatus.add(Ui.chip("Cluster status: " + o.health(), t),
                Ui.chip("Auto failover: " + (o.failoverActive() ? "Active" : "Paused"),
                        o.failoverActive() ? Ui.Tone.OK : Ui.Tone.WARN),
                plain("Epoch " + o.epoch()),
                plain("Timeline " + (o.timeline() == null ? "—" : o.timeline())));
    }

    private static Span plain(String text) {
        Span s = Ui.chip(text, Ui.Tone.NEUTRAL);
        s.addClassName("plain");
        return s;
    }

    private void renderStats(Overview o) {
        stats.removeAll();
        stats.add(Ui.stat("Primary", o.primary() == null ? "—" : o.primary(),
                (o.primaryAddress() == null ? "" : o.primaryAddress()) + (o.primaryDc() == null ? "" : " · "
                        + o.primaryDc())));
        stats.add(Ui.stat("Virtual IP", o.vipAddress() == null ? "not managed" : o.vipAddress(),
                o.vipAddress() == null ? "agents run with vip.enabled=false" : o.vipHolder() == null
                        ? "not up on any node" : "on " + o.vipHolder() + " · via sudo ip addr"));
        stats.add(Ui.stat("Max replay lag", ConsoleService.bytes(o.maxLagBytes()), o.maxLagNode() == null
                ? "no streaming replica" : o.maxLagBytes() == 0 ? "every replica has replayed everything"
                : o.maxLagNode() + (o.maxLagDelayMs() == null ? "" : " · " + Ui.seconds(o.maxLagDelayMs())
                + " behind")));
        stats.add(Ui.stat("Primary lease", o.lease() == null || !o.lease().granted() ? "—"
                        : Math.round(o.lease().remainingMs() / 1000.0) + " s left",
                "TTL " + console.watcher().config().timing().leaseTtlMs() / 1000 + " s · "
                        + (o.selfFence() ? "self-fencing on" : "self-fencing off")));
    }

    private void renderTopology(Overview o) {
        topology.removeAll();
        Div t = Ui.div("pg-topo");
        if (o.vipAddress() != null) {
            t.add(Ui.text("pg-topo-vip", "VIP " + o.vipAddress()), Ui.div("pg-topo-link"));
        }
        NodeRow p = o.nodes().stream().filter(NodeRow::isPrimary).findFirst().orElse(null);
        if (p != null) {
            t.add(node(p, "primary" + (p.role().equals("Primary") ? "" : " down"), "Primary · " + p.dc()));
            t.add(Ui.div("pg-topo-link"));
        }
        Div row = Ui.div("pg-topo-row");
        for (NodeRow r : o.nodes()) {
            if (r.isPrimary()) {
                continue;
            }
            boolean remote = p != null && !Objects.equals(p.dc(), r.dc());
            String cls = (remote ? "remote" : "") + (r.streaming() ? "" : " down");
            row.add(node(r, cls, (r.sync() == null ? (r.streaming() ? "async" : r.state()) : r.sync()) + " · "
                    + r.dc()));
        }
        t.add(row);
        topology.add(t, Ui.text("pg-note", "Dashed: other data center. "
                + ("DC_LOCAL".equals(o.scope()) ? "The VIP never moves to another DC on its own; promotion there"
                + " needs a manual switchover." : "Automatic failover may promote in any DC.")));
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

    private void renderLag(Overview o) {
        if (o == null) {
            return;
        }
        List<String> replicas = o.nodes().stream().filter(n -> !n.isPrimary()).map(NodeRow::name).toList();
        if (!replicas.equals(lagNode.getListDataView().getItems().toList())) {
            lagNode.setItems(replicas);
        }
        if (lagNode.getValue() == null || !replicas.contains(lagNode.getValue())) {
            lagNode.setValue(o.maxLagNode() != null ? o.maxLagNode() : replicas.isEmpty() ? null : replicas.get(0));
        }
        lagChart.removeAll();
        if (lagNode.getValue() == null) {
            return;
        }
        NotificationSettings ns = notifications.current();
        long now = System.currentTimeMillis();
        List<LagHistory.Sample> samples = console.watcher().lagHistory().series(lagNode.getValue(), 30, now);
        long alert = ns.lagAlertBytes();
        long max = Math.max(alert * 2, samples.stream().mapToLong(LagHistory.Sample::maxLagBytes).max().orElse(0));
        Div bars = Ui.div("pg-bars");
        bars.getElement().setAttribute("role", "img");
        bars.getElement().setAttribute("aria-label", "Replay lag of " + lagNode.getValue() + ", last 30 minutes");
        for (LagHistory.Sample s : samples) {
            Div b = Ui.div("pg-bar");
            boolean over = s.maxLagBytes() > alert;
            if (over) {
                b.addClassName("over");
            }
            b.getStyle().set("height", Math.max(2, Math.round(s.maxLagBytes() * 140.0 / max)) + "px");
            b.setTitle(Ui.minute(s.minuteMillis(), zone) + " · " + Lsn.bytes(s.maxLagBytes())
                    + (over ? " (above alert)" : ""));
            bars.add(b);
        }
        Div line = Ui.div("pg-threshold");
        line.getStyle().set("bottom", Math.round(alert * 140.0 / max) + "px");
        line.setTitle("alert at " + Lsn.bytes(alert));
        bars.add(line);
        Div axis = Ui.div("pg-axis", Ui.text(null, samples.isEmpty() ? "" : Ui.minute(samples.get(0).minuteMillis(),
                zone)), Ui.text(null, "scale " + Lsn.bytes(max)), Ui.text(null, Ui.minute(now, zone)));
        lagChart.add(Ui.text("pg-note", "Last 30 min · 1-min samples · alert at " + Lsn.bytes(alert)),
                samples.isEmpty() ? Ui.text("pg-note", "No samples yet.") : bars, axis);
    }

    private void renderEvents() {
        events.removeAll();
        List<ClusterEvent> recent = console.watcher().events(6);
        if (recent.isEmpty()) {
            events.add(new ListItem("No events yet."));
        }
        for (ClusterEvent e : recent) {
            ListItem li = new ListItem();
            li.add(Ui.text("pg-time", Ui.shortTime(e.timeMillis(), zone)),
                    Ui.chip(switch (e.severity()) {
                        case CRITICAL -> "Critical";
                        case WARNING -> "Warning";
                        case INFO -> "Info";
                    }, Ui.tone(e.severity())), Ui.text(null, e.message()));
            events.add(li);
        }
    }

    private void renderSlots(Overview o) {
        slots.removeAll();
        if (o.slots().isEmpty()) {
            slots.add(Ui.text("pg-note", "No replication slots on the primary."));
            return;
        }
        Grid<ConsoleService.SlotRow> g = new Grid<>();
        g.addThemeVariants(GridVariant.LUMO_COMPACT);
        g.setAllRowsVisible(true);
        g.addColumn(ConsoleService.SlotRow::name).setHeader("Slot").setAutoWidth(true);
        g.addColumn(ConsoleService.SlotRow::consumer).setHeader("Consumer").setAutoWidth(true);
        g.addComponentColumn(s -> Ui.chip(s.active() ? "Active" : "Inactive", s.active() ? Ui.Tone.OK
                : Ui.Tone.WARN)).setHeader("Status").setAutoWidth(true);
        g.addColumn(s -> ConsoleService.bytes(s.retainedBytes())).setHeader("Retained WAL").setAutoWidth(true);
        g.addComponentColumn(s -> {
            if (!admin || s.active() || !"none".equals(s.consumer())) {
                return new Span();
            }
            Button drop = new Button("Drop slot", e -> {
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
            drop.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            return drop;
        }).setAutoWidth(true);
        g.setItems(o.slots());
        slots.add(g);
        NotificationSettings ns = notifications.current();
        o.slots().stream().filter(s -> !s.active() && s.retainedBytes() != null
                && s.retainedBytes() > ns.slotAlertBytes()).findFirst().ifPresent(s -> slots.add(Ui.banner(
                "An inactive slot is holding " + Lsn.bytes(s.retainedBytes()) + " of WAL on the primary.",
                Ui.Tone.WARN)));
    }

    private void renderWatchers(Overview o) {
        watchers.removeAll();
        watchers.add(Ui.text("pg-note", "STANDALONE".equals(o.mode()) ? "Consensus: none (single watcher)"
                : "Consensus: Raft (optional) · term " + o.term()));
        UnorderedList ul = new UnorderedList();
        ul.addClassName("pg-events");
        for (ConsoleService.WatcherRow w : o.watchers()) {
            ListItem li = new ListItem();
            li.getStyle().set("grid-template-columns", "1fr auto");
            Div left = new Div(Ui.text(null, w.id() + (w.self() ? " (this watcher)" : "")));
            left.getStyle().set("font-weight", "700").set("color", "var(--pg-ink)");
            left.add(new Div(Ui.text("pg-note", "Sees primary: " + (w.seesPrimary() == null ? "unknown"
                    : w.seesPrimary() ? "yes" : "no") + (w.self() || w.lastContactMs() == null ? ""
                    : " · heartbeat " + Ui.seconds(w.lastContactMs()) + " ago"))));
            li.add(left, Ui.chip(w.leader() ? "Leader" : "Follower", w.leader() ? Ui.Tone.OK : Ui.Tone.NEUTRAL));
            ul.add(li);
        }
        watchers.add(ul, Ui.text("pg-note", "With Raft off, one watcher runs alone and asks the replicas whether"
                + " they still see the primary before any failover."));
    }

    private void renderTargetChecks() {
        targetChecks.removeAll();
        String t = target.getValue();
        if (t == null) {
            targetChecks.add(Ui.text("pg-note", "No replica to switch to."));
            return;
        }
        List<PreCheck> checks = console.watcher().switchoverChecks(t, false);
        for (PreCheck c : checks) {
            Div row = Ui.div("pg-row", Ui.chip(switch (c.level()) {
                case PASS -> "Pass";
                case WARN -> "Warning";
                case FAIL -> "Blocked";
            }, Ui.tone(c.level())), Ui.text(null, c.title() + (c.detail() == null || c.detail().equals("Pass")
                    ? "" : ": " + c.detail())));
            row.getStyle().set("font-size", "13px").set("flex-wrap", "nowrap").set("align-items", "baseline");
            targetChecks.add(row);
        }
        targetChecks.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");
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
