package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.ClusterWizardService.Check;
import com.bisoft.bfm.watcher.service.ClusterWizardService.NodeLive;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import jakarta.annotation.security.RolesAllowed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Add cluster, in six steps: basics, nodes, enroll, checks, start, review. The draft is saved after each step, so an
 * installation spread over a day can be resumed (from the Fleet page) by any admin, on any watcher.
 */
@Route(value = "clusters/new/:draft?", layout = MainLayout.class)
@PageTitle("Add cluster · BFM")
@RolesAllowed("ADMIN")
public class AddClusterView extends Div implements BeforeEnterObserver {

    private static final String[] STEPS = {"Basics", "Nodes", "Enroll", "Checks", "Start", "Review"};

    private final ClusterWizardService wizard;
    private final ConsoleTls consoleTls;
    private final String user;
    private final Div stepper = Ui.div("pg-stepper");
    private final Div body = new Div();
    private String draft;
    private int step;
    private Registration poll;
    private Runnable onPoll;

    public AddClusterView(ClusterWizardService wizard, ConsoleTls consoleTls, AuthenticationContext auth) {
        this.wizard = wizard;
        this.consoleTls = consoleTls;
        this.user = auth.getPrincipalName().orElse("unknown");
        addClassName("pg-page");
        H1 title = new H1("Add cluster");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "A cluster created here runs on every watcher of this group"), title),
                stepper, body);
        body.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "16px");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        draft = e.getRouteParameters().get("draft").orElse(null);
        List<String> s = e.getLocation().getQueryParameters().getParameters().get("step");
        step = s == null || s.isEmpty() ? (draft == null ? 0 : 1) : Math.max(0, Math.min(5,
                Integer.parseInt(s.get(0)) - 1));
        if (draft != null) {
            try {
                wizard.draft(draft);
            } catch (IllegalArgumentException ex) {
                e.forwardTo(FleetView.class);
                return;
            }
        } else {
            step = 0;
        }
        render();
    }

    private void go(int to) {
        UI.getCurrent().navigate("clusters/new/" + draft, new QueryParameters(Map.of("step",
                List.of(Integer.toString(to + 1)))));
    }

    private void render() {
        stepper.removeAll();
        for (int i = 0; i < STEPS.length; i++) {
            Span chip = Ui.text("pg-step" + (i == step ? " current" : i < step ? " done" : ""), (i + 1) + " "
                    + STEPS[i]);
            int to = i;
            if (draft != null && i != step) {
                chip.getElement().addEventListener("click", ev -> go(to));
                chip.getStyle().set("cursor", "pointer");
            }
            stepper.add(chip);
        }
        body.removeAll();
        onPoll = null;
        ClusterWizardService.Elsewhere away = wizard.elsewhere();
        if (away != null) {
            Div b = Ui.banner("This watcher (" + away.self() + ") follows " + (away.leaderId() == null
                    ? "no leader right now: clusters can be added once a leader is elected."
                    : "watcher " + away.leaderId() + ". Clusters are added, changed and detached on the leader."),
                    Ui.Tone.INFO);
            if (away.consoleUrl() != null) {
                String url = away.consoleUrl().replaceAll("/+$", "") + "/clusters/new"
                        + (draft == null ? "" : "/" + draft);
                b.add(Ui.div("pg-banner-actions", new Anchor(url, "Open the leader's console")));
            }
            body.add(b);
        }
        switch (step) {
            case 0 -> basics();
            case 1 -> nodes();
            case 2 -> enroll();
            case 3 -> checks();
            case 4 -> start();
            default -> review();
        }
    }

    private ClusterWizardService.Draft current() {
        return wizard.draft(draft);
    }

    // ---------------------------------------------------------------- 1 basics

    private void basics() {
        ClusterSpec spec = draft == null ? ClusterSpec.draft("", null) : current().spec();
        Div card = Ui.card("Basics");
        TextField name = new TextField("Name");
        name.setHelperText("Letters, digits, '.', '_' and '-'; it is part of the console's URLs and confirms"
                + " switchovers");
        name.setValue(spec.name());
        name.setReadOnly(draft != null);
        ComboBox<String> env = new ComboBox<>("Environment");
        env.setItems("production", "staging", "test", "development");
        env.setAllowCustomValue(true);
        env.addCustomValueSetListener(e -> env.setValue(e.getDetail()));
        env.setValue(spec.environment());
        env.setHelperText("Shown on every page; production gets a red bar");
        Select<FailoverScope> scope = new Select<>();
        scope.setLabel("Automatic failover");
        scope.setItems(FailoverScope.values());
        scope.setItemLabelGenerator(s -> s == FailoverScope.DC_LOCAL ? "Stays in the primary's data center"
                : "Any data center");
        scope.setValue(spec.scope());
        Checkbox fence = new Checkbox("Self-fencing (recommended with three watchers)", spec.selfFence());
        IntegerField poll = new IntegerField("Poll interval (s)");
        poll.setValue((int) Math.max(1, spec.pollIntervalMs() / 1000));
        IntegerField threshold = new IntegerField("Failed polls before failover");
        threshold.setValue(spec.failureThreshold());
        IntegerField lag = new IntegerField("Max lag for automatic promotion (MB)");
        lag.setValue((int) (spec.maxLagBytes() / (1024 * 1024)));
        Checkbox coloc = new Checkbox("Allow two nodes on one host (lab only)", spec.allowColocation());
        Details advanced = new Details("Advanced", Ui.div("pg-grid-auto", poll, threshold, lag), coloc);
        card.add(Ui.div("pg-grid-auto", name, env, scope), fence, advanced);
        Button next = new Button("Save and continue", e -> {
            if (Ui.attempt(() -> {
                if (draft == null) {
                    wizard.createDraft(name.getValue().trim(), env.getValue(), user);
                    draft = name.getValue().trim();
                }
                wizard.saveBasics(draft, env.getValue(), scope.getValue(), fence.getValue(),
                        (poll.getValue() == null ? 2 : poll.getValue()) * 1000L,
                        threshold.getValue() == null ? 3 : threshold.getValue(),
                        (lag.getValue() == null ? 16 : lag.getValue()) * 1024L * 1024, coloc.getValue());
            })) {
                go(1);
            }
        });
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        body.add(card, Ui.div("pg-row", next, cancel()));
    }

    private Button cancel() {
        Button b = new Button("Back to the fleet", e -> UI.getCurrent().navigate(FleetView.class));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        return b;
    }

    // ---------------------------------------------------------------- 2 nodes

    private void nodes() {
        List<ClusterSpec.NodeSpec> rows = new ArrayList<>(current().spec().nodes());
        Div card = Ui.card("Nodes");
        card.add(Ui.text("pg-note", "One row per PostgreSQL instance. The agent address is where the watchers reach"
                + " its agent (https://host:port: 8008, or 8009, 8010 for more instances on one host); the PostgreSQL"
                + " address is the node's own, never the VIP."));
        Grid<ClusterSpec.NodeSpec> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.addColumn(ClusterSpec.NodeSpec::name).setHeader("Node").setAutoWidth(true);
        grid.addColumn(ClusterSpec.NodeSpec::agentUrl).setHeader("Agent").setAutoWidth(true);
        grid.addColumn(n -> n.pgHost() + ":" + n.pgPort()).setHeader("PostgreSQL").setAutoWidth(true);
        grid.addColumn(ClusterSpec.NodeSpec::dc).setHeader("DC").setAutoWidth(true);
        grid.addColumn(n -> n.noFailover() ? "never automatically" : "priority " + n.priority())
                .setHeader("Promotion").setAutoWidth(true);
        grid.addComponentColumn(n -> {
            Button rm = new Button("Remove", e -> {
                rows.remove(n);
                grid.setItems(rows);
            });
            rm.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            return rm;
        }).setAutoWidth(true).setFlexGrow(0);
        grid.setItems(rows);

        TextField name = new TextField("Node name");
        name.setPlaceholder(draft + "-a1");
        TextField agent = new TextField("Agent address");
        agent.setPlaceholder("https://db01:8008");
        TextField pgHost = new TextField("PostgreSQL address");
        pgHost.setPlaceholder("10.0.0.11");
        IntegerField pgPort = new IntegerField("Port");
        pgPort.setValue(5432);
        TextField dc = new TextField("Data center");
        dc.setValue("DC1");
        IntegerField prio = new IntegerField("Priority");
        prio.setValue(100);
        Checkbox never = new Checkbox("Never promote automatically");
        Button add = new Button("Add the node", e -> {
            rows.add(new ClusterSpec.NodeSpec(name.getValue().trim(), agent.getValue().trim(),
                    pgHost.getValue().trim(), pgPort.getValue() == null ? 5432 : pgPort.getValue(),
                    dc.getValue().isBlank() ? "DC1" : dc.getValue().trim(), prio.getValue() == null ? 100
                    : prio.getValue(), never.getValue()));
            grid.setItems(rows);
            name.clear();
            agent.clear();
            pgHost.clear();
        });
        card.add(grid, Ui.div("pg-grid-auto", name, agent, pgHost, pgPort, dc, prio), never, add);
        Button next = new Button("Save and continue", e -> {
            if (Ui.attempt(() -> wizard.saveNodes(draft, rows))) {
                go(2);
            }
        });
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        body.add(card, Ui.div("pg-row", back(), next));
    }

    private Button back() {
        Button b = new Button("Back", e -> go(step - 1));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        return b;
    }

    // ---------------------------------------------------------------- 3 enroll

    private void enroll() {
        Div card = Ui.card("Enroll the nodes");
        card.add(Ui.text("pg-note", "On each node's database host, as root, run its command: minipgagent setup"
                + " finds the PostgreSQL instance, gets the node's certificate with the one-time token and starts the"
                + " agent. This list follows them live."));
        Div list = new Div();
        list.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        card.add(list);
        Runnable refresh = () -> {
            list.removeAll();
            for (NodeLive l : wizard.live(current().spec())) {
                Div row = Ui.div("pg-spread", Ui.div("pg-row", Ui.text("pg-node-name", l.node()), Ui.chip(l.online()
                        ? "enrolled" : "waiting", l.online() ? Ui.Tone.OK : Ui.Tone.WARN), Ui.text("pg-note",
                        l.online() ? describe(l) : "no agent answers yet")));
                Button cmd = new Button(l.online() ? "New token" : "Enrollment command…", e -> token(l.node()));
                cmd.addThemeVariants(ButtonVariant.LUMO_SMALL, l.online() ? ButtonVariant.LUMO_TERTIARY
                        : ButtonVariant.LUMO_PRIMARY);
                row.add(cmd);
                list.add(row);
            }
        };
        refresh.run();
        onPoll = refresh;
        Button next = new Button("Continue to the checks", e -> go(3));
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        body.add(card, Ui.div("pg-row", back(), next));
    }

    private static String describe(NodeLive l) {
        var s = l.status();
        return (s.identity().hostname() == null ? "" : s.identity().hostname() + " · ")
                + (s.serverVersionNum() == null ? "" : "PG " + s.serverVersionNum() / 10000 + " · ")
                + (s.pgRunning() ? (s.isPrimary() ? "primary" : s.isReplica() ? "replica" : "running") : "stopped");
    }

    private void token(String node) {
        EnrollmentService.Issued[] t = new EnrollmentService.Issued[1];
        if (Ui.attempt(() -> t[0] = wizard.token(draft, node, user))) {
            EnrollDialog.open(t[0], consoleTls, java.time.ZoneId.systemDefault());
        }
    }

    // ---------------------------------------------------------------- 4 checks

    private void checks() {
        Div card = Ui.card("Checks");
        Div result = new Div();
        result.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        Button rerun = new Button("Run again", e -> renderChecks(result));
        rerun.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        card.add(Ui.div("pg-spread", Ui.text("pg-note", "Across the nodes, then each node's own; a red line blocks"
                + " creation."), rerun), result);
        renderChecks(result);
        Button next = new Button("Continue", e -> go(4));
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        body.add(card, Ui.div("pg-row", back(), next));
    }

    private void renderChecks(Div into) {
        into.removeAll();
        ClusterWizardService.Draft d = current();
        List<Check> list = wizard.checks(d.id(), d.spec());
        Grid<Check> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setAllRowsVisible(true);
        grid.addColumn(c -> c.node() == null ? "all nodes" : c.node()).setHeader("Node").setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(Check::name).setHeader("Check").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(c -> Ui.chip(switch (c.level()) {
            case "OK" -> "Pass";
            case "FAIL" -> "Blocks";
            default -> "Warning";
        }, switch (c.level()) {
            case "OK" -> Ui.Tone.OK;
            case "FAIL" -> Ui.Tone.CRIT;
            default -> Ui.Tone.WARN;
        })).setHeader("Result").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(Check::detail).setHeader("Detail").setFlexGrow(1);
        grid.setItems(list);
        long blocking = list.stream().filter(Check::blocks).count();
        into.add(blocking == 0 ? Ui.banner("Nothing blocks the creation of " + draft + ".", Ui.Tone.INFO)
                : Ui.banner(blocking + " check" + (blocking == 1 ? " blocks" : "s block") + " the creation of "
                + draft + ".", Ui.Tone.CRIT), grid);
    }

    // ---------------------------------------------------------------- 5 start and 6 review

    private final RadioButtonGroup<String> mode = new RadioButtonGroup<>();
    private final Select<String> primary = new Select<>();

    /** Adopt when a node runs as primary, otherwise build from the first node; returns the running primary. */
    private String defaults() {
        List<NodeLive> live = wizard.live(current().spec());
        String running = live.stream().filter(l -> l.online() && l.status().isPrimary()).map(NodeLive::node)
                .findFirst().orElse(null);
        if (mode.getValue() == null) {
            mode.setItems("Adopt", "Build");
            mode.setValue(running != null ? "Adopt" : "Build");
        }
        if (primary.getValue() == null && !current().spec().nodes().isEmpty()) {
            primary.setItems(current().spec().nodes().stream().map(ClusterSpec.NodeSpec::name).toList());
            primary.setValue(current().spec().nodes().get(0).name());
        }
        return running;
    }

    private void start() {
        Div card = Ui.card("Start");
        String running = defaults();
        mode.setItems("Adopt", "Build");
        mode.setItemLabelGenerator(m -> m.equals("Adopt") ? (running == null ? "Adopt (no node runs as primary)"
                : "Adopt: " + running + " runs as primary; the watchers record it and keep the replicas as they are")
                : "Build: start one node as primary, then re-initialize the others from it (pg_basebackup)");
        mode.setValue(running != null ? "Adopt" : "Build");
        primary.setLabel("Primary to start");
        String chosen = primary.getValue();
        primary.setItems(current().spec().nodes().stream().map(ClusterSpec.NodeSpec::name).toList());
        if (chosen != null) {
            primary.setValue(chosen);
        } else if (!current().spec().nodes().isEmpty()) {
            primary.setValue(current().spec().nodes().get(0).name());
        }
        primary.setVisible("Build".equals(mode.getValue()));
        mode.addValueChangeListener(e -> primary.setVisible("Build".equals(e.getValue())));
        card.add(mode, primary);
        if (running == null) {
            card.add(Ui.text("pg-note", "Building replaces the data directories of the other nodes with copies of the"
                    + " primary; their old ones are kept aside."));
        }
        Button next = new Button("Continue to the review", e -> go(5));
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        body.add(card, Ui.div("pg-row", back(), next));
    }

    private void review() {
        ClusterWizardService.Draft d = current();
        ClusterSpec s = d.spec();
        defaults();
        Div card = Ui.card("Review");
        Div kv = new Div();
        kv.addClassName("pg-kv");
        kv.add(Ui.text(null, "Cluster"), Ui.text(null, s.name() + (s.environment() == null ? "" : " · "
                + s.environment())));
        kv.add(Ui.text(null, "Id"), Ui.text("pg-mono", d.id()));
        kv.add(Ui.text(null, "Failover"), Ui.text(null, (s.scope() == FailoverScope.DC_LOCAL ? "stays in the"
                + " primary's data center" : "any data center") + (s.selfFence() ? " · self-fencing" : "")
                + " · after " + s.failureThreshold() + " failed polls of " + s.pollIntervalMs() / 1000 + " s"));
        for (ClusterSpec.NodeSpec n : s.nodes()) {
            kv.add(Ui.text(null, n.name()), Ui.text(null, n.agentUrl() + " · PostgreSQL " + n.pgHost() + ":"
                    + n.pgPort() + " · " + n.dc() + (n.noFailover() ? " · never promoted automatically" : "")));
        }
        boolean build = "Build".equals(mode.getValue());
        kv.add(Ui.text(null, "Start"), Ui.text(null, build ? "build from " + primary.getValue() : "adopt the"
                + " running primary"));
        TextField confirm = new TextField("Type the cluster name " + s.name() + " to create it");
        confirm.setWidthFull();
        confirm.setMaxWidth("420px");
        Button create = new Button("Create the cluster", e -> {
            if (Ui.attempt(() -> wizard.create(s.name(), build ? ClusterWizardService.StartMode.BUILD
                    : ClusterWizardService.StartMode.ADOPT, primary.getValue(), confirm.getValue(), user))) {
                Ui.ok(s.name() + " created" + (build ? "; building it, follow it under Operations" : ""));
                UI.getCurrent().navigate(ClusterRoutes.path(s.name(), build ? "operations" : "overview"));
            }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button discard = new Button("Discard the draft", e -> Ui.confirm("Discard " + s.name() + "?", "Agents"
                + " already enrolled keep running bound to it; run 'minipgagent remove' on their hosts.", "Discard",
                true, () -> {
                    if (Ui.attempt(() -> wizard.discard(s.name(), user))) {
                        UI.getCurrent().navigate(FleetView.class);
                    }
                }));
        discard.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        card.add(kv, confirm);
        body.add(card, Ui.div("pg-row", back(), create, discard));
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        e.getUI().setPollInterval(3000);
        poll = e.getUI().addPollListener(p -> {
            if (onPoll != null) {
                onPoll.run();
            }
        });
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (poll != null) {
            poll.remove();
        }
        super.onDetach(e);
    }
}
