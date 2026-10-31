package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.ConsoleService.NodeRow;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Planned switchover: choose the target, review the checks, confirm with the cluster name, follow the steps. */
@Route(value = "switchover", layout = MainLayout.class)
@PageTitle("Switchover · BFM")
@PermitAll
public class SwitchoverView extends Div implements BeforeEnterObserver {

    private final ConsoleService console;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;

    private final Div review = new Div();
    private final Span currentPrimary = Ui.text("pg-stat-value", "");
    private final Span currentSub = Ui.text("pg-stat-sub", "");
    private final Select<String> target = new Select<>();
    private final Checkbox checkpoint = new Checkbox("Run CHECKPOINT on the primary first", true);
    private final Checkbox restartOld = new Checkbox("Start the old primary again as a replica", true);
    private final Checkbox mail = new Checkbox("Send a mail when it finishes", true);
    private final Grid<PreCheck> checks = new Grid<>();
    private final TextField confirm = new TextField();
    private final Span confirmError = new Span();
    private final Button start = new Button("Start switchover");
    private final Div changes = Ui.card("What will change");
    private final OperationPanel panel;
    private final Div running = new Div();
    private String operationId;
    private Registration poll;

    public SwitchoverView(ConsoleService console, AuthenticationContext auth, BfmProperties props) {
        this.console = console;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = props.zone();
        this.panel = new OperationPanel(zone);
        addClassName("pg-page");

        H1 title = new H1("Switchover");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Planned role change"), title));

        String cluster = console.watcher().config().name();
        Div step1 = Ui.card("Choose the new primary and review the checks");
        Div primaryBox = new Div(Ui.text("pg-stat-label", "Current primary"), new Div(currentPrimary),
                new Div(currentSub));
        target.setLabel("New primary");
        target.setWidthFull();
        target.addValueChangeListener(e -> renderChecks());
        restartOld.addValueChangeListener(e -> renderChanges(console.overview(), target.getValue()));
        Div options = new Div(Ui.text("pg-stat-label", "Options"), checkpoint, restartOld, mail);
        options.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px");
        Div top = Ui.div("pg-grid-auto", primaryBox, target);

        checks.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER,
                GridVariant.LUMO_WRAP_CELL_CONTENT);
        checks.setAllRowsVisible(true);
        checks.addColumn(PreCheck::title).setHeader("Check").setFlexGrow(2);
        checks.addComponentColumn(c -> {
            Div d = Ui.div("pg-row", Ui.chip(switch (c.level()) {
                case PASS -> "Pass";
                case WARN -> "Warning";
                case FAIL -> "Blocked";
            }, Ui.tone(c.level())));
            if (c.detail() != null && !c.detail().equals("Pass")) {
                d.add(Ui.text("pg-note", c.detail()));
            }
            d.getStyle().set("flex-wrap", "nowrap").set("align-items", "baseline");
            return d;
        }).setHeader("Result").setFlexGrow(3);

        confirm.setLabel("Type the cluster name " + cluster + " to confirm");
        confirm.setWidthFull();
        confirm.setMaxWidth("420px");
        confirm.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);
        confirm.addValueChangeListener(e -> confirmError.setText(""));
        confirmError.addClassName("pg-field-error");
        confirmError.getElement().setAttribute("role", "alert");
        start.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        start.addClickListener(e -> startSwitchover(cluster));
        Button recheck = new Button("Re-run checks", e -> renderChecks());
        recheck.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button cancel = new Button("Cancel", e -> UI.getCurrent().navigate(OverviewView.class));
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        step1.add(top, options, Ui.div("pg-spread", new H2("Pre-checks"), recheck), checks, confirm, confirmError,
                Ui.div("pg-row", start, cancel));
        if (!admin) {
            step1.add(Ui.text("pg-note", "Viewers can review the checks but cannot start a switchover."));
        }
        review.add(Ui.div("pg-grid-2", step1, changes));

        running.add(panel);
        add(review, running);
        running.setVisible(false);
        refreshReview();
        renderChecks();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Map<String, List<String>> q = event.getLocation().getQueryParameters().getParameters();
        if (q.containsKey("operation")) {
            operationId = q.get("operation").get(0);
        }
        if (q.containsKey("target")) {
            String t = q.get("target").get(0);
            refreshReview();
            if (target.getListDataView().getItems().anyMatch(t::equals)) {
                target.setValue(t);
            }
        }
        showMode();
    }

    private void refreshReview() {
        Overview o = console.overview();
        currentPrimary.setText(o.primary() == null ? "—" : o.primary());
        currentSub.setText(o.primary() == null ? "" : (o.primaryAddress() + " · " + o.primaryDc()));
        List<String> replicas = o.nodes().stream().filter(n -> !n.isPrimary()).map(NodeRow::name).toList();
        if (!replicas.equals(target.getListDataView().getItems().toList())) {
            String keep = target.getValue();
            target.setItems(replicas);
            target.setValue(keep != null && replicas.contains(keep) ? keep : replicas.isEmpty() ? null
                    : replicas.get(0));
        }
        target.setItemLabelGenerator(n -> o.nodes().stream().filter(r -> r.name().equals(n)).findFirst()
                .map(r -> r.name() + " · " + (r.sync() == null ? "async" : r.sync()) + " · " + r.dc()).orElse(n));
    }

    private void renderChecks() {
        String t = target.getValue();
        List<PreCheck> list = t == null ? List.of(PreCheck.fail("Target node", "no replica to switch to"))
                : console.watcher().switchoverChecks(t);
        checks.setItems(list);
        start.setEnabled(admin && PreCheck.allowed(list));
        renderChanges(console.overview(), t);
    }

    private void renderChanges(Overview o, String t) {
        changes.removeAll();
        changes.add(new H2("What will change"));
        if (t == null || o.primary() == null) {
            changes.add(Ui.text("pg-note", "Choose a target."));
            return;
        }
        NodeRow tr = o.nodes().stream().filter(r -> r.name().equals(t)).findFirst().orElse(null);
        NodeRow pr = o.nodes().stream().filter(NodeRow::isPrimary).findFirst().orElse(null);
        boolean sameVip = tr != null && pr != null && Objects.equals(tr.vipAddress(), pr.vipAddress());
        Div kv = new Div();
        kv.addClassName("pg-kv");
        add(kv, "Primary", o.primary() + " → " + t);
        add(kv, "VIP", tr == null || tr.vipAddress() == null ? "not managed"
                : sameVip ? tr.vipAddress() + " moves from " + o.primary() + " to " + t
                : (pr == null || pr.vipAddress() == null ? "" : pr.vipAddress() + " stays down; ") + tr.vipAddress()
                + " comes up on " + t);
        add(kv, "Safety", "The VIP comes up first; no promote without it");
        add(kv, "Promotion", "Only after " + t + " has replayed the final WAL position of " + o.primary()
                + ": nothing written before the stop is lost");
        add(kv, "Old primary", restartOld.getValue() ? "Restarts as a replica; no rewind after a clean stop"
                : "Stays stopped");
        add(kv, "Epoch", o.epoch() + " → " + (o.epoch() + 1) + "; agents reject older commands");
        add(kv, "Writes", "Paused from the stop of " + o.primary() + " until promotion");
        changes.add(kv, Ui.text("pg-note", "Every step is written to the event log with your user name."));
    }

    private static void add(Div kv, String k, String v) {
        Span dt = new Span(k);
        dt.getStyle().set("color", "var(--pg-muted)");
        Span dd = new Span(v);
        dd.getStyle().set("color", "var(--pg-ink)").set("font-weight", "600");
        kv.add(dt, dd);
    }

    private void startSwitchover(String cluster) {
        if (!cluster.equals(confirm.getValue().trim())) {
            confirmError.setText("Enter " + cluster + " exactly to start the switchover.");
            return;
        }
        String t = target.getValue();
        SwitchoverOperation.Options opts = new SwitchoverOperation.Options(checkpoint.getValue(),
                restartOld.getValue(), mail.getValue());
        OperationProgress.Snapshot[] started = new OperationProgress.Snapshot[1];
        if (Ui.attempt(() -> started[0] = console.watcher().switchover(t, opts, user, confirm.getValue()))) {
            operationId = started[0].id();
            confirm.clear();
            UI.getCurrent().navigate(SwitchoverView.class, new QueryParameters(Map.of("operation",
                    List.of(operationId))));
            showMode();
        }
    }

    private void showMode() {
        Optional<OperationProgress.Snapshot> op = operationId == null ? Optional.empty()
                : console.watcher().operation(operationId);
        review.setVisible(op.isEmpty());
        running.setVisible(op.isPresent());
        op.ifPresent(panel::show);
        if (op.isPresent() && running.getComponentCount() == 1) {
            Button back = new Button("Back to overview", e -> UI.getCurrent().navigate(OverviewView.class));
            back.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button another = new Button("New switchover", e -> {
                operationId = null;
                UI.getCurrent().navigate(SwitchoverView.class);
                showMode();
            });
            another.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            running.add(Ui.div("pg-row", back, another));
            running.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "12px");
        }
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        e.getUI().setPollInterval((int) Math.max(1000, console.watcher().config().timing().pollIntervalMs()));
        poll = e.getUI().addPollListener(p -> {
            if (operationId != null) {
                showMode();
            } else {
                refreshReview();
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
