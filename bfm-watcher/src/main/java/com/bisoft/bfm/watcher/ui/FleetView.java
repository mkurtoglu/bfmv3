package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.FleetService;
import com.bisoft.bfm.watcher.service.FleetService.ClusterSummary;
import com.bisoft.bfm.watcher.service.Terms;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Every cluster in one line, worst first: is it healthy, where is its primary, when did it last change. */
@Route(value = "fleet", layout = MainLayout.class)
@PageTitle("Fleet · BFM")
@PermitAll
public class FleetView extends Div {

    private final FleetService fleet;
    private final ClusterWizardService wizard;
    private final boolean admin;
    private final Div drafts = new Div();
    private final ZoneId zone;
    private final Grid<ClusterSummary> grid = new Grid<>();
    private final Select<String> environment = new Select<>();
    private final Div summary = Ui.div("pg-row");
    private final com.vaadin.flow.component.html.Span empty = Ui.text("pg-empty", "This watcher runs no cluster yet:"
            + " add one here (Add cluster), or define it under bfm.clusters in watcher.yml.");
    private Registration poll;

    public FleetView(FleetService fleet, BfmProperties props, ClusterWizardService wizard,
                     com.vaadin.flow.spring.security.AuthenticationContext auth) {
        this.fleet = fleet;
        this.zone = props.zone();
        this.wizard = wizard;
        this.admin = auth.hasRole("ADMIN");
        addClassName("pg-page");
        H1 title = new H1("Fleet");
        title.addClassName("pg-title");
        environment.setLabel("Environment");
        environment.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                refresh();
            }
        });
        Div right = Ui.div("pg-row", environment);
        if (admin) {
            com.vaadin.flow.component.button.Button addCluster = new com.vaadin.flow.component.button.Button(
                    "Add cluster", com.vaadin.flow.component.icon.VaadinIcon.PLUS.create(),
                    e -> com.vaadin.flow.component.UI.getCurrent().navigate("clusters/new"));
            addCluster.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
            right.add(addCluster);
        }
        right.getStyle().set("align-items", "end");
        add(Ui.div("pg-spread", new Div(Ui.text("pg-eyebrow", "Every cluster this watcher runs, worst first"),
                title), right), summary, drafts);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setAllRowsVisible(true);
        grid.addComponentColumn(s -> new Anchor(ClusterRoutes.path(s.name(), "overview"), s.name()))
                .setHeader("Cluster").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ClusterSummary> env = grid.addComponentColumn(s -> s.environment() == null
                || s.environment().isBlank() ? Ui.text(null, "—") : Ui.env(s.environment()))
                .setHeader("Environment").setAutoWidth(true);
        grid.addComponentColumn(s -> {
            Div d = Ui.div("pg-row", Ui.chip(s.health(), OverviewPage.healthTone(s.health())));
            if (!s.failoverActive() && !"Starting".equals(s.health())) {
                d.add(Ui.chip("Auto failover paused", Ui.Tone.WARN));
            }
            if (!s.healthReasons().isEmpty() && !"Healthy".equals(s.health())) {
                d.getElement().setAttribute("title", String.join("\n", s.healthReasons()));
            }
            return d;
        }).setHeader("State").setAutoWidth(true);
        grid.addColumn(s -> s.primary() == null ? "—" : s.primary() + (s.primaryHost() == null ? ""
                : " (" + s.primaryHost() + ")")).setHeader("Primary (host)").setAutoWidth(true);
        Grid.Column<ClusterSummary> replicas = grid.addColumn(s -> s.streaming() + "/" + s.replicas()
                + " streaming").setHeader("Replicas").setAutoWidth(true);
        Grid.Column<ClusterSummary> lag = grid.addColumn(s -> s.maxLagBytes() == null ? "—"
                : Lsn.bytes(s.maxLagBytes())).setHeader("Max lag").setAutoWidth(true);
        Grid.Column<ClusterSummary> change = grid.addColumn(s -> roleChange(s.roleChange()))
                .setHeader("Last role change").setFlexGrow(1);
        Ui.hideBelow(grid, 900, env, lag, change);
        Ui.hideBelow(grid, 600, replicas);
        Div card = Ui.card(null);
        card.add(empty, grid, Ui.text("pg-note", "⚑ not acknowledged yet. Hover a state for its reasons."));
        add(card);
        refresh();
    }

    private String roleChange(RoleChange rc) {
        if (rc == null) {
            return "—";
        }
        return ("FAILOVER".equals(rc.type()) ? "failover " : "switchover ") + Ui.shortTime(rc.atMillis(), zone)
                + (rc.acknowledged() ? "" : " ⚑");
    }

    private void refresh() {
        List<ClusterSummary> all = fleet.clusters();
        List<String> envs = new ArrayList<>();
        envs.add("All environments");
        TreeSet<String> names = new TreeSet<>();
        for (ClusterSummary s : all) {
            if (s.environment() != null && !s.environment().isBlank()) {
                names.add(Terms.capitalize(s.environment()));
            }
        }
        envs.addAll(names);
        String chosen = environment.getValue();
        if (!envs.equals(environment.getListDataView().getItems().toList())) {
            environment.setItems(envs);
        }
        environment.setValue(chosen != null && envs.contains(chosen) ? chosen : envs.get(0));
        environment.setVisible(names.size() > 1);
        String filter = environment.getValue();
        List<ClusterSummary> shown = all.stream().filter(s -> filter == null || filter.startsWith("All")
                || filter.equalsIgnoreCase(s.environment())).toList();
        grid.setItems(shown);
        grid.setVisible(!all.isEmpty());
        empty.setVisible(all.isEmpty());
        renderDrafts();

        summary.removeAll();
        long healthy = all.stream().filter(s -> "Healthy".equals(s.health())).count();
        summary.add(Ui.chip(healthy + " healthy", Ui.Tone.OK));
        all.stream().map(ClusterSummary::health).filter(h -> !"Healthy".equals(h)).distinct().forEach(h ->
                summary.add(Ui.chip(all.stream().filter(s -> s.health().equals(h)).count() + " "
                        + h.toLowerCase(), OverviewPage.healthTone(h))));
    }

    /** Clusters whose Add cluster wizard is not finished: anyone with admin rights may continue them. */
    private void renderDrafts() {
        drafts.removeAll();
        List<ClusterWizardService.Draft> list = wizard.drafts();
        if (list.isEmpty()) {
            return;
        }
        Div card = Ui.card("Being created");
        for (ClusterWizardService.Draft d : list) {
            Div row = Ui.div("pg-spread", Ui.div("pg-row", Ui.text("pg-node-name", d.name()), Ui.text("pg-note",
                    d.spec().nodes().size() + " nodes · drafted " + Ui.shortTime(d.createdMillis(), zone))));
            if (admin) {
                row.add(new Anchor("clusters/new/" + d.name() + "?step=" + (d.spec().nodes().size() < 2 ? 2 : 3),
                        "Continue"));
            }
            card.add(row);
        }
        drafts.add(card);
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        e.getUI().setPollInterval(3000);
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
