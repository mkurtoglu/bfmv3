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
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.ConsoleService.ControlChange;
import com.bisoft.bfm.watcher.service.ConsoleService.NodeRow;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import com.bisoft.bfm.watcher.service.NotificationService;
import com.bisoft.bfm.watcher.service.Terms;
import jakarta.annotation.security.PermitAll;

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
@Route(value = "", layout = MainLayout.class)
@RouteAlias(value = "overview", layout = MainLayout.class)
@PageTitle("Overview · BFM")
@PermitAll
public class OverviewView extends Div {

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
    private final Checkbox autoFailover = new Checkbox("Auto failover");
    private final Checkbox mail = new Checkbox("Mail notifications");
    private final Checkbox maintenance = new Checkbox("Maintenance mode");
    private final Checkbox autoReinit = new Checkbox("Auto re-initialize");
    private final Span scope = Ui.text("pg-note", "");
    private Grid.Column<NodeRow> actionsColumn;
    private Registration poll;
    private Overview last;

    public OverviewView(ConsoleService console, NotificationService notifications, AuthenticationContext auth,
                        BfmProperties props) {
        this.console = console;
        this.notifications = notifications;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = props.zone();
        addClassName("pg-page");

        H1 title = new H1("Cluster Overview");
        title.addClassName("pg-title");
        String env = props.cluster() == null ? null : props.cluster().environment();
        Span eyebrow = Ui.text("pg-eyebrow", console.watcher().config().name()
                + (env == null || env.isBlank() ? "" : " · " + Terms.capitalize(env)));
        Div head = Ui.div("pg-spread", new Div(eyebrow, title), headStatus);
        headStatus.addClassName("pg-row");

        buildGrid();
        Button refresh = new Button("Refresh", VaadinIcon.REFRESH.create(), e -> refresh());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        grid.addClassName("pg-node-grid");
        Div nodesCard = Ui.card(null);
        nodesCard.add(Ui.div("pg-spread", heading("Nodes"), Ui.div("pg-row", refreshed, refresh)), grid, nodeCards);

        Div topoCard = Ui.card("Topology");
        topoCard.add(topology);

        Div eventsCard = Ui.card(null);
        eventsCard.add(Ui.div("pg-spread", heading("Recent events"), new Anchor("events", "View all")));
        events.addClassName("pg-events");
        eventsCard.add(events);

        Div controlsCard = Ui.card("Cluster controls");
        controlsCard.add(controlsBlock());

        Div watchersCard = Ui.card("Watchers");
        watchersCard.add(watchers);

        Div left = Ui.div("pg-col", topoCard, eventsCard);
        Div right = Ui.div("pg-col", controlsCard, watchersCard);
        banners.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        health.getElement().setAttribute("aria-live", "polite");
        add(banners, head, health, stats, nodesCard, Ui.div("pg-grid-2", left, right));
        refresh();
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
        }).setHeader("Node").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<NodeRow> address = grid.addColumn(NodeRow::address).setHeader("Server address").setAutoWidth(true);
        Grid.Column<NodeRow> dc = grid.addColumn(NodeRow::dc).setHeader("DC").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(r -> roleChip(r)).setHeader("Role").setAutoWidth(true);
        grid.addComponentColumn(r -> Ui.div("pg-row", Ui.chip(r.state(), stateTone(r)),
                Ui.text("pg-since", sinceText(r)))).setHeader("State").setAutoWidth(true);
        Grid.Column<NodeRow> wal = grid.addComponentColumn(r -> Ui.text("pg-mono", r.lsn() == null ? "—" : r.lsn()))
                .setHeader("Last WAL position").setAutoWidth(true);
        grid.addColumn(this::lagText).setHeader("Replay lag").setAutoWidth(true);
        Grid.Column<NodeRow> tl = grid.addColumn(r -> r.timeline() == null ? "—" : r.timeline().toString())
                .setHeader("Timeline").setAutoWidth(true).setTextAlign(ColumnTextAlign.END);
        Grid.Column<NodeRow> sync = grid.addColumn(r -> r.sync() == null ? "—" : Terms.capitalize(r.sync()))
                .setHeader("Sync").setAutoWidth(true);
        Grid.Column<NodeRow> agent = grid.addComponentColumn(r -> Ui.chip(agentLabel(r),
                r.agentOnline() ? Ui.Tone.OK : Ui.Tone.CRIT)).setHeader("Agent").setAutoWidth(true);
        // The actions stay on screen whatever the other columns do: in an incident they are what is needed.
        actionsColumn = grid.addComponentColumn(this::actions).setHeader("Actions").setAutoWidth(true)
                .setFlexGrow(0).setFrozenToEnd(true);
        Ui.hideBelow(grid, 1500, wal, tl);
        Ui.hideBelow(grid, 980, address, sync, agent, dc);
        grid.addItemClickListener(e -> {
            if (e.getColumn() == actionsColumn) {
                return;
            }
            toggle(e.getItem());
        });
        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::details));
    }

    private void toggle(NodeRow r) {
        if (!openRows.remove(r.name())) {
            openRows.add(r.name());
        }
        grid.setDetailsVisible(r, openRows.contains(r.name()));
        grid.getDataProvider().refreshItem(r);
    }

    private Component details(NodeRow r) {
        Div d = new Div();
        d.getStyle().set("padding", "4px 8px 8px 48px");
        if (r.error() != null) {
            d.add(new Paragraph("Error: " + r.error()));
        }
        if (r.fenced()) {
            d.add(new Paragraph("Fenced: " + r.fencedReason()));
        }
        d.add(new Paragraph("Upstream: " + (r.upstream() == null ? "—" : r.upstream()) + " · epoch " + r.epoch()
                + (r.vipAddress() == null ? " · no VIP managed" : " · VIP " + r.vipAddress()
                + (r.vipHeld() ? " up here" : " not here")) + (r.noFailover() ? " · never promoted automatically" : "")
                + " · priority " + r.priority() + " · last answer " + Ui.time(r.lastCheckMillis(), zone) + " "
                + Ui.zone(zone)));
        return d;
    }

    private Span roleChip(NodeRow r) {
        String label = r.role() + (r.isPrimary() || !r.role().equals("Replica") ? "" : dcSuffix(r));
        return Ui.chip(label, r.role().equals("Primary") ? Ui.Tone.OK : r.role().equals("Replica")
                ? Ui.Tone.INFO : Ui.Tone.CRIT);
    }

    private String lagText(NodeRow r) {
        return r.isPrimary() ? "—" : ConsoleService.bytes(r.lagBytes())
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
        return "for " + Ui.ago(r.stateSinceMillis(), last.observedAtMillis());
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
        boolean noPrimaryRunning = last != null && last.nodes().stream().noneMatch(n -> "Primary".equals(n.role()));
        return NodeActions.menu(console, user, admin, new NodeActions.Node(r.name(), r.isPrimary(), r.agentOnline(),
                "Replica".equals(r.role()), noPrimaryRunning,
                console.watcher().identity().foreignData().contains(r.name())), () -> toggle(r), this::refresh);
    }

    private void renderNodeCards(Overview o) {
        nodeCards.removeAll();
        for (NodeRow r : o.nodes()) {
            Ui.Tone t = stateTone(r);
            Div card = Ui.div("pg-node-card");
            if (t == Ui.Tone.CRIT || t == Ui.Tone.WARN) {
                card.addClassName(t.name().toLowerCase());
            }
            Span name = new Span(r.name());
            name.getStyle().set("font-weight", "800").set("color", "var(--pg-ink)").set("font-size", "16px");
            Div head = Ui.div("pg-node-card-head", name, roleChip(r), Ui.chip(r.state(), t), actions(r));
            String since = sinceText(r);
            card.add(head,
                    Ui.text("pg-note", r.address() + " · " + r.dc() + (since.isEmpty() ? "" : " · " + since)),
                    Ui.text("pg-note", (r.isPrimary() ? "WAL " + (r.lsn() == null ? "—" : r.lsn())
                            : "Lag " + lagText(r)) + " · timeline " + (r.timeline() == null ? "—" : r.timeline())
                            + (r.sync() == null ? "" : " · " + Terms.capitalize(r.sync()))
                            + (r.agentOnline() ? "" : " · agent " + agentLabel(r).toLowerCase())));
            if (r.error() != null) {
                card.add(Ui.text("pg-note", r.error()));
            }
            nodeCards.add(card);
        }
    }

    // ---------------------------------------------------------------- controls

    private Component controlsBlock() {
        Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "10px");
        for (Checkbox cb : List.of(autoFailover, mail, maintenance, autoReinit)) {
            cb.setReadOnly(!admin);
            box.add(cb);
        }
        autoFailover.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            boolean on = e.getValue();
            autoFailover.setValue(!on);
            String p = primaryName();
            if (!on) {
                Ui.confirm("Turn off automatic failover?", "If " + p + " fails, no replica takes over until it is"
                        + " turned on again. The change is written to the event log with your name.",
                        "Turn off", true, () -> setControl(false, null, null, null));
            } else if (primaryDown()) {
                Ui.confirm("Turn on automatic failover?", p + " is down: the watcher starts an automatic failover"
                        + " as soon as this is on.", "Turn on and fail over", true,
                        () -> setControl(true, null, null, null));
            } else {
                setControl(true, null, null, null);
            }
        });
        maintenance.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            boolean on = e.getValue();
            maintenance.setValue(!on);
            if (on) {
                Ui.confirm("Turn on maintenance mode?", "Automatic failover pauses until maintenance mode is off"
                        + " again. Manual switchover and re-initialize stay available.", "Turn on", true,
                        () -> setControl(null, true, null, null));
            } else if (primaryDown()) {
                Ui.confirm("Turn off maintenance mode?", primaryName() + " is down: the watcher starts an automatic"
                        + " failover as soon as maintenance mode is off.", "Turn off and fail over", true,
                        () -> setControl(null, false, null, null));
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
                Ui.confirm("Turn on auto re-initialize?", "A replica that cannot rejoin with pg_rewind will have its"
                        + " data directory moved aside and copied again from the primary, without asking.",
                        "Turn on", true, () -> setControl(null, null, null, true));
            } else {
                setControl(null, null, null, false);
            }
        });
        box.add(Ui.div("pg-row", Ui.text("pg-stat-label", "Failover scope"), scope));
        return box;
    }

    private String primaryName() {
        return last == null || last.primary() == null ? "the primary" : last.primary();
    }

    private boolean primaryDown() {
        return last != null && "Primary down".equals(last.health());
    }

    private void setControl(Boolean af, Boolean mm, Boolean ml, Boolean ar) {
        if (Ui.attempt(() -> console.watcher().setControls(af, mm, ml, ar, user))) {
            Ui.ok("Saved");
        }
        refresh();
    }

    private void renderControls(Overview o) {
        autoFailover.setValue(o.autoFailover());
        mail.setValue(o.mailEnabled());
        maintenance.setValue(o.maintenance());
        autoReinit.setValue(o.autoReinit());
        help(autoFailover, "Promote a replica when the primary is lost", o.controls().get("auto failover"));
        help(mail, "SMTP alerts to the recipients on the Notifications page", o.controls().get("mail notifications"));
        help(maintenance, "Pause automatic failover during planned work", o.controls().get("maintenance mode"));
        help(autoReinit, "Copy a replica again with pg_basebackup when pg_rewind fails",
                o.controls().get("auto reinit"));
        scope.setText((o.scope().equals("DC_LOCAL") ? "DC-local only" : "Any data center")
                + (o.selfFence() ? " · self-fencing on" : ""));
    }

    private void help(Checkbox cb, String text, ControlChange c) {
        cb.setHelperText(c == null ? text : text + " · " + (c.on() ? "on" : "off") + " since "
                + Ui.shortTime(c.atMillis(), zone) + ", by " + c.by());
    }

    // ---------------------------------------------------------------- refreshing

    private void refresh() {
        Overview o = console.overview();
        last = o;
        renderBanners(o);
        renderHead(o);
        renderHealth(o);
        renderStats(o);
        grid.setItems(o.nodes());
        for (NodeRow r : o.nodes()) {
            if (openRows.contains(r.name())) {
                grid.setDetailsVisible(r, true);
            }
        }
        renderNodeCards(o);
        refreshed.setText("Refreshed " + Ui.time(o.observedAtMillis(), zone) + " " + Ui.zone(zone) + " · every "
                + console.watcher().config().timing().pollIntervalMs() / 1000 + " s");
        renderTopology(o);
        renderEvents();
        renderWatchers(o);
        renderControls(o);
    }

    private void renderBanners(Overview o) {
        banners.removeAll();
        if (!o.leader()) {
            Div b = Ui.banner("This watcher (" + o.watcherId() + ") follows" + (o.leaderId() == null
                    ? " no leader right now; changes are not possible until a leader is elected."
                    : " watcher " + o.leaderId() + ". Operations run on the leader."), Ui.Tone.INFO);
            if (o.leaderUrl() != null) {
                b.add(Ui.div("pg-banner-actions", new Anchor(o.leaderUrl(), "Open the leader's console")));
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
                follow = new Anchor("operations", "Follow it");
            } else if (o.operationConsoleUrl() != null) {
                // The steps and log are on the watcher running it, not here.
                follow = new Anchor(o.operationConsoleUrl(), "Follow it on watcher " + owner);
            } else {
                follow = Ui.text("pg-note", "Its steps are on watcher " + owner + "; set raft.peers[].url to link"
                        + " there.");
            }
            banners.add(Ui.banner(Terms.operation(o.operation().type()) + (o.operation().target() == null ? ""
                    : " on " + o.operation().target()) + " is running, started by " + o.operation().startedBy()
                    + " " + Ui.ago(o.operation().startedAtMillis(), o.observedAtMillis()) + " ago.", Ui.Tone.INFO,
                    follow));
        }
        if ("Primary down".equals(o.health())) {
            banners.add(primaryDownBanner(o));
        } else if ("Primary unverified".equals(o.health())) {
            banners.add(Ui.banner(o.primary() + " cannot be checked: " + console.watcher().identity()
                    .problem(o.primary()) + ". The watcher may be looking at the wrong agent while " + o.primary()
                    + " runs fine, so it starts no failover and renews no lease"
                    + (o.selfFence() ? " (with self-fencing on, " + o.primary() + " stops itself when its lease"
                    + " runs out)" : "") + ". Correct the agent-url of " + o.primary()
                    + " in watcher.yml (or that agent's settings) and restart the watcher.", Ui.Tone.CRIT));
        } else if (o.maintenance()) {
            banners.add(Ui.banner("Maintenance mode is on. Automatic failover is paused; manual switchover and"
                    + " re-initialize stay available.", Ui.Tone.WARN));
        }
        if ("No primary".equals(o.health())) {
            banners.add(Ui.banner("No primary is recorded. Start one node as primary from its Actions menu.",
                    Ui.Tone.WARN));
        }
    }

    private Div roleChangeBanner(RoleChange rc) {
        boolean failover = "FAILOVER".equals(rc.type());
        String loss = rc.lostBytes() == null ? "data loss unknown (no WAL position was seen on " + rc.from() + ")"
                : rc.lostBytes() == 0 ? "no data lost"
                : Lsn.bytes(rc.lostBytes()) + " of WAL from " + rc.from() + " is not on " + rc.to();
        String text = (failover ? "Automatic failover " : "Switchover ") + (rc.from() == null ? "" : rc.from() + " → ")
                + rc.to() + " at " + Ui.shortTime(rc.atMillis(), zone) + " " + Ui.zone(zone) + " · took "
                + Ui.duration(rc.durationMs()) + " · " + loss + " · epoch " + rc.epoch()
                + (failover ? "" : " · by " + rc.by());
        Ui.Tone tone = rc.lostBytes() != null && rc.lostBytes() > 0 ? Ui.Tone.CRIT
                : failover ? Ui.Tone.WARN : Ui.Tone.INFO;
        Anchor details = new Anchor("events?q=" + URLEncoder.encode(rc.to(), StandardCharsets.UTF_8), "Details");
        if (!admin) {
            return Ui.banner(text, tone, details, Ui.text("pg-note", "An admin acknowledges it."));
        }
        Button ack = new Button("Acknowledge", e -> {
            if (Ui.attempt(() -> console.watcher().acknowledgeRoleChange(user))) {
                Ui.ok("Acknowledged");
            }
            refresh();
        });
        ack.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        return Ui.banner(text, tone, details, ack);
    }

    private Div primaryDownBanner(Overview o) {
        NodeRow p = o.primaryRow();
        String state = p == null ? "not polled yet" : p.state().toLowerCase() + (p.stateSinceMillis() > 0
                ? " for " + Ui.ago(p.stateSinceMillis(), o.observedAtMillis()) : "");
        String text = o.primary() + " is not answering as primary (" + state + ").";
        if (o.operation() != null) {
            return Ui.banner(text, Ui.Tone.CRIT);
        }
        if (o.maintenance()) {
            return Ui.banner(text + " Automatic failover is paused because maintenance mode is on.", Ui.Tone.CRIT,
                    controlButton("Turn off maintenance mode", () -> maintenanceOffNow()));
        }
        if (!o.autoFailover()) {
            return Ui.banner(text + " Automatic failover is off.", Ui.Tone.CRIT,
                    controlButton("Turn on automatic failover", () -> autoFailoverOnNow()));
        }
        if (o.failoverBlockedReason() != null) {
            return Ui.banner(text + " Automatic failover did not start: " + o.failoverBlockedReason() + ".",
                    Ui.Tone.CRIT, new Anchor("events", "Events"));
        }
        var t = console.watcher().config().timing();
        return Ui.banner(text + " Automatic failover is armed: it starts after " + t.failureThreshold()
                + " failed polls (about " + t.failureThreshold() * t.pollIntervalMs() / 1000 + " s).", Ui.Tone.CRIT);
    }

    private Component controlButton(String label, Runnable action) {
        if (!admin) {
            return Ui.text("pg-note", "An admin can change it.");
        }
        Button b = new Button(label, e -> action.run());
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        return b;
    }

    private void maintenanceOffNow() {
        Ui.confirm("Turn off maintenance mode?", primaryName() + " is down: the watcher starts an automatic failover"
                + " as soon as maintenance mode is off.", "Turn off and fail over", true,
                () -> setControl(null, false, null, null));
    }

    private void autoFailoverOnNow() {
        Ui.confirm("Turn on automatic failover?", primaryName() + " is down: the watcher starts an automatic failover"
                + " as soon as this is on.", "Turn on and fail over", true, () -> setControl(true, null, null, null));
    }

    private void renderHead(Overview o) {
        headStatus.removeAll();
        headStatus.add(Ui.chip("Auto failover: " + (o.failoverActive() ? "Active" : "Paused"),
                        o.failoverActive() ? Ui.Tone.OK : Ui.Tone.WARN),
                plain("Epoch " + o.epoch()),
                plain("Timeline " + (o.timeline() == null ? "—" : o.timeline())));
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
        health.setClassName("pg-health " + t.name().toLowerCase());
        String summary;
        if ("Healthy".equals(o.health())) {
            long replicas = o.nodes().stream().filter(n -> !n.isPrimary()).count();
            long streaming = o.nodes().stream().filter(n -> !n.isPrimary() && n.streaming()).count();
            summary = o.primary() + " is primary · " + streaming + "/" + replicas + " replicas streaming"
                    + (o.vipHolder() == null ? "" : " · VIP on " + o.vipHolder())
                    + " · max lag " + ConsoleService.bytes(o.maxLagBytes());
        } else if ("Primary down".equals(o.health())) {
            NodeRow p = o.primaryRow();
            summary = p == null || p.stateSinceMillis() <= 0 ? "" : "since " + Ui.time(p.stateSinceMillis(), zone)
                    + " " + Ui.zone(zone) + " (" + Ui.ago(p.stateSinceMillis(), o.observedAtMillis()) + ")";
        } else {
            int n = o.healthReasons().size();
            summary = n == 0 ? "" : n + (n == 1 ? " problem" : " problems");
        }
        health.add(Ui.text("pg-health-state", o.health()), Ui.text("pg-health-summary", summary));
        if (!o.healthReasons().isEmpty() && !"Healthy".equals(o.health())) {
            UnorderedList ul = new UnorderedList();
            for (String r : o.healthReasons()) {
                ul.add(new ListItem(r));
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
            return "Online";
        }
        return console.watcher().identity().problem(r.name()) != null ? "Wrong agent" : "Unreachable";
    }

    private void renderStats(Overview o) {
        stats.removeAll();
        NodeRow p = o.primaryRow();
        boolean down = "Primary down".equals(o.health());
        stats.add(Ui.stat("Primary", o.primary() == null ? "—" : o.primary(), down && p != null
                        ? p.state().toLowerCase() + (p.stateSinceMillis() > 0 ? " for "
                        + Ui.ago(p.stateSinceMillis(), o.observedAtMillis()) : "")
                        : (o.primaryAddress() == null ? "" : o.primaryAddress())
                        + (o.primaryDc() == null ? "" : " · " + o.primaryDc()),
                down ? Ui.Tone.CRIT : o.primary() == null ? Ui.Tone.WARN : null));
        String vipSub;
        Ui.Tone vipTone = null;
        boolean unverified = "Primary unverified".equals(o.health());
        if (o.vipAddress() == null) {
            vipSub = "agents run with vip.enabled=false";
        } else if (o.vipHolder() == null && unverified) {
            vipSub = "unknown: " + o.primary() + " cannot be checked";
            vipTone = Ui.Tone.WARN;
        } else if (o.vipHolder() == null) {
            vipSub = "not up on any node: clients on the VIP cannot connect";
            vipTone = Ui.Tone.CRIT;
        } else if (!o.vipHolder().equals(o.primary())) {
            vipSub = "on " + o.vipHolder() + ", which is not the primary";
            vipTone = Ui.Tone.WARN;
        } else {
            vipSub = "on " + o.vipHolder() + " · via sudo ip addr";
        }
        stats.add(Ui.stat("Virtual IP", o.vipAddress() == null ? "not managed" : o.vipAddress(), vipSub, vipTone));
        long alert = notifications.current().lagAlertBytes();
        boolean lagging = o.maxLagBytes() != null && o.maxLagBytes() > alert;
        boolean anyStreaming = o.nodes().stream().anyMatch(n -> !n.isPrimary() && n.streaming());
        stats.add(Ui.stat("Max replay lag", ConsoleService.bytes(o.maxLagBytes()), o.maxLagNode() == null
                ? (anyStreaming ? "unknown: the primary's position is not known" : "no streaming replica")
                : o.maxLagBytes() == 0 ? "every replica has replayed everything"
                : o.maxLagNode() + (o.maxLagDelayMs() == null ? "" : " · " + Ui.seconds(o.maxLagDelayMs())
                + " behind") + (lagging ? " · alert at " + Lsn.bytes(alert) : ""), lagging ? Ui.Tone.WARN : null));
        stats.add(Ui.stat("Primary lease", o.lease() == null || !o.lease().granted() ? "—"
                        : Math.round(o.lease().remainingMs() / 1000.0) + " s left",
                "TTL " + console.watcher().config().timing().leaseTtlMs() / 1000 + " s · "
                        + (o.selfFence() ? "self-fencing on" : "self-fencing off")));
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
            t.add(Ui.text("pg-topo-vip warn", "VIP " + vip + " · unknown"));
        } else if (vip != null && holder == null) {
            t.add(Ui.text("pg-topo-vip crit", "VIP " + vip + " · down"));
        } else if (vip != null && p != null && holder.equals(p.name())) {
            t.add(Ui.text("pg-topo-vip", "VIP " + vip), Ui.div("pg-topo-link"));
        }
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
            Div n = node(r, cls, (r.streaming() ? (r.sync() == null ? "async" : r.sync()) : r.state().toLowerCase())
                    + " · " + r.dc());
            if (vip != null && Objects.equals(holder, r.name())) {
                // A VIP on a replica: show it where it is, so the mismatch cannot be missed.
                row.add(Ui.div("pg-topo-cell", Ui.text("pg-topo-vip warn", "VIP " + vip + " · not the primary"),
                        Ui.div("pg-topo-link"), n));
            } else {
                row.add(n);
            }
        }
        t.add(row);
        topology.add(t, Ui.text("pg-note", "Dashed: other data center. "
                + ("DC_LOCAL".equals(o.scope()) ? "The VIP never moves to another DC on its own; promotion there"
                + " needs a manual switchover." : "Automatic failover may promote in any DC.")));
        if (o.clusterId() != null) {
            // What the agents are bound to and what every data directory must hold; needed to re-bind an agent.
            Div ids = new Div();
            ids.addClassName("pg-note");
            ids.setText("Cluster id " + o.clusterId() + " · PostgreSQL system identifier "
                    + (o.systemIdentifier() == null ? "not recorded yet" : o.systemIdentifier()));
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
            events.add(new ListItem("No events yet."));
        }
        for (ClusterEvent e : recent) {
            ListItem li = new ListItem();
            li.add(Ui.text("pg-time", Ui.shortTime(e.timeMillis(), zone)),
                    Ui.chip(switch (e.severity()) {
                        case CRITICAL -> "Critical";
                        case WARNING -> "Warning";
                        case INFO -> "Info";
                    }, Ui.tone(e.severity())), Ui.text(null, Ui.arrows(e.message())));
            events.add(li);
        }
    }

    private void renderWatchers(Overview o) {
        watchers.removeAll();
        watchers.add(Ui.text("pg-note", "STANDALONE".equals(o.mode()) ? "Consensus: none (single watcher)"
                : "Consensus: Raft · term " + o.term()));
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
        watchers.add(ul);
        if ("STANDALONE".equals(o.mode())) {
            watchers.add(Ui.text("pg-note", "With Raft off, one watcher runs alone and asks the replicas whether"
                    + " they still see the primary before any failover."));
        }
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
