package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.Terms;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.List;

/** Operations this watcher ran or is running: failovers, switchovers, rejoins, reinits. */
@Route(value = "operations", layout = MainLayout.class)
@PageTitle("Operations · BFM")
@PermitAll
public class OperationsView extends Div {

    private final ConsoleService console;
    private final Grid<OperationProgress.Snapshot> grid = new Grid<>();
    private final OperationPanel panel;
    private String selected;
    private Registration poll;

    public OperationsView(ConsoleService console, BfmProperties props) {
        this.console = console;
        ZoneId zone = props.zone();
        this.panel = new OperationPanel(zone);
        addClassName("pg-page");
        H1 title = new H1("Operations");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Run on this watcher since it started · the event log keeps every"
                + " operation for good"), title));

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.addColumn(s -> Ui.full(s.startedAtMillis(), zone)).setHeader("Started (" + Ui.zone(zone) + ")")
                .setAutoWidth(true);
        grid.addColumn(s -> Terms.operation(s.type())).setHeader("Operation").setAutoWidth(true);
        Grid.Column<OperationProgress.Snapshot> node = grid.addColumn(s -> s.target() != null ? s.target()
                : s.result() != null && s.result().contains("->") ? s.result().replace("->", "→") : "—")
                .setHeader("Node").setAutoWidth(true);
        Grid.Column<OperationProgress.Snapshot> by = grid.addColumn(OperationProgress.Snapshot::startedBy)
                .setHeader("By").setAutoWidth(true);
        grid.addComponentColumn(s -> Ui.chip(Terms.capitalize(s.state().name().toLowerCase()), Ui.tone(s.state())))
                .setHeader("State").setAutoWidth(true);
        grid.addColumn(s -> s.running() ? Ui.ago(s.startedAtMillis(), System.currentTimeMillis()) + " so far"
                : Ui.duration(s.durationMs())).setHeader("Took").setAutoWidth(true);
        Grid.Column<OperationProgress.Snapshot> result = grid.addColumn(s -> s.result() == null ? ""
                : Ui.arrows(s.result())).setHeader("Result").setFlexGrow(1)
                .setTooltipGenerator(s -> s.result() == null ? null : Ui.arrows(s.result()));
        Ui.hideBelow(grid, 900, by, result);
        Ui.hideBelow(grid, 600, node);
        grid.addSelectionListener(e -> {
            selected = e.getFirstSelectedItem().map(OperationProgress.Snapshot::id).orElse(selected);
            refresh();
        });
        Div list = Ui.card(null);
        list.add(grid);
        add(list, panel);
        refresh();
    }

    private void refresh() {
        List<OperationProgress.Snapshot> ops = console.watcher().operations();
        grid.setItems(ops);
        if (ops.isEmpty()) {
            panel.show(null);
            panel.removeAll();
            panel.add(Ui.text("pg-note", "No operations yet."));
            return;
        }
        String id = selected != null ? selected : ops.get(0).id();
        panel.show(console.watcher().operation(id).orElse(ops.get(0)));
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
