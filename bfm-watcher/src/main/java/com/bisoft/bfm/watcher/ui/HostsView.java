package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.watcher.service.FleetService;
import com.bisoft.bfm.watcher.service.FleetService.Host;
import com.bisoft.bfm.watcher.service.FleetService.Instance;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * Which PostgreSQL instances run on which server, across clusters. It shows the distribution and nothing more: no
 * warning for primaries sharing a host. Two nodes of the same cluster on one host are marked, because losing that
 * host takes both.
 */
@Route(value = "hosts", layout = MainLayout.class)
@PageTitle("Hosts · BFM")
@PermitAll
public class HostsView extends Div {

    private final FleetService fleet;
    private final Div list = new Div();
    private List<Host> last;
    private Registration poll;

    public HostsView(FleetService fleet) {
        this.fleet = fleet;
        addClassName("pg-page");
        H1 title = new H1("Hosts");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Servers as their agents report them (/etc/machine-id), with every"
                + " instance on each"), title));
        list.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "16px");
        add(list);
        refresh();
    }

    private void refresh() {
        List<Host> hosts = fleet.hosts();
        if (hosts.equals(last)) {
            return;
        }
        last = hosts;
        list.removeAll();
        if (hosts.isEmpty()) {
            list.add(Ui.text("pg-empty", "No agent has answered yet."));
        }
        for (Host h : hosts) {
            Div card = Ui.card(null);
            String sub = h.primaries() + (h.primaries() == 1 ? " primary" : " primaries") + " · " + h.replicas()
                    + (h.replicas() == 1 ? " replica" : " replicas");
            card.add(Ui.div("pg-spread", Ui.div("pg-row", Ui.text("pg-host-name", h.name()),
                            Ui.text("pg-note", h.hostId() == null ? "agents not answering: placed by address"
                                    : "machine-id " + h.hostId().substring(0, Math.min(8, h.hostId().length()))
                                    + "…")),
                    Ui.text("pg-note", sub)));
            for (String c : h.sameCluster()) {
                card.add(Ui.banner(c + " has more than one node on " + h.name() + ": losing this host takes all of"
                        + " them. Move one to another host.", Ui.Tone.WARN));
            }
            Grid<Instance> grid = new Grid<>();
            grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
            grid.setAllRowsVisible(true);
            grid.addComponentColumn(i -> new Anchor(ClusterRoutes.path(i.cluster(), "overview"), i.cluster()))
                    .setHeader("Cluster").setAutoWidth(true);
            grid.addColumn(Instance::node).setHeader("Node").setAutoWidth(true);
            grid.addComponentColumn(i -> Ui.chip(i.role(), i.role().equals("Primary") ? Ui.Tone.OK
                    : i.role().equals("Replica") ? Ui.Tone.NEUTRAL : Ui.Tone.WARN)).setHeader("Role")
                    .setAutoWidth(true);
            Grid.Column<Instance> state = grid.addColumn(Instance::state).setHeader("State").setAutoWidth(true);
            grid.addColumn(i -> "PG " + i.pgPort()).setHeader("PostgreSQL").setAutoWidth(true);
            Grid.Column<Instance> agent = grid.addColumn(i -> i.agent() + (i.instance() == null ? ""
                    : " · " + i.instance())).setHeader("Agent").setAutoWidth(true);
            Grid.Column<Instance> vip = grid.addColumn(i -> i.vip() == null ? "—" : i.vip()
                    + (i.vipHeld() ? " (up)" : "")).setHeader("VIP").setFlexGrow(1);
            Ui.hideBelow(grid, 900, state, vip);
            Ui.hideBelow(grid, 600, agent);
            grid.setItems(h.instances());
            card.add(grid);
            list.add(card);
        }
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        e.getUI().setPollInterval(5000);
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
