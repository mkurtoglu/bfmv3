package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;

import java.time.ZoneId;
import java.util.List;

/** What the console may do to a cluster it created: add or remove a node, detach the cluster. */
final class ClusterAdmin {

    private final String cluster;
    private final ClusterWizardService wizard;
    private final ConsoleTls tls;
    private final ZoneId zone;
    private final String user;

    ClusterAdmin(String cluster, ClusterWizardService wizard, ConsoleTls tls, ZoneId zone, String user) {
        this.cluster = cluster;
        this.wizard = wizard;
        this.tls = tls;
        this.zone = zone;
        this.user = user;
    }

    void addNode() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Add a node to " + cluster);
        TextField name = new TextField("Node name");
        TextField agent = new TextField("Agent address");
        agent.setPlaceholder("https://db04:8008");
        TextField pgHost = new TextField("PostgreSQL address");
        IntegerField pgPort = new IntegerField("Port");
        pgPort.setValue(5432);
        TextField dc = new TextField("Data center");
        dc.setValue("DC1");
        IntegerField prio = new IntegerField("Priority");
        prio.setValue(100);
        Checkbox never = new Checkbox("Never promote automatically");
        d.add(Ui.text("pg-note", "The watchers start polling it at once; enroll it with the command shown next. It"
                + " joins as a replica: following if it already streams, otherwise rewound or re-initialized from"
                + " the primary (from its menu)."), Ui.div("pg-grid-auto", name, agent, pgHost, pgPort, dc, prio),
                never);
        Button add = new Button("Add and enroll", e -> {
            ClusterSpec.NodeSpec n = new ClusterSpec.NodeSpec(name.getValue().trim(), agent.getValue().trim(),
                    pgHost.getValue().trim(), pgPort.getValue() == null ? 5432 : pgPort.getValue(),
                    dc.getValue().isBlank() ? "DC1" : dc.getValue().trim(), prio.getValue() == null ? 100
                    : prio.getValue(), never.getValue());
            EnrollmentService.Issued[] t = new EnrollmentService.Issued[1];
            if (Ui.attempt(() -> wizard.addNode(cluster, n, user))) {
                d.close();
                Ui.ok(n.name() + " added to " + cluster);
                if (Ui.attempt(() -> t[0] = wizard.token(cluster, n.name(), user))) {
                    EnrollDialog.open(t[0], tls, zone);
                }
            }
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancel", e -> d.close()), add);
        d.setWidth("min(760px, 95vw)");
        d.open();
    }

    void removeNode(String node, Runnable after) {
        Ui.confirmTyped(node, "Remove " + node + " from " + cluster, "The watchers stop polling " + node + " and its"
                + " agent forgets the cluster. PostgreSQL on it keeps running, no longer a node of " + cluster + ".",
                "Remove", true, () -> {
                    if (Ui.attempt(() -> wizard.removeNode(cluster, node, user))) {
                        Ui.ok(node + " removed from " + cluster);
                    }
                    if (after != null) {
                        after.run();
                    }
                });
    }

    void detach() {
        Ui.confirmTyped(cluster, "Detach " + cluster, "In this order: automatic failover is paused, the watchers"
                + " stop, the primary gets a last lease that never fences (it keeps running even with self-fencing),"
                + " and every agent forgets the cluster. PostgreSQL keeps running on every node; the event log stays."
                + " Then run 'minipgagent remove' on each host, or add the nodes to another cluster.", "Detach",
                true, () -> {
                    List<String>[] out = new List[1];
                    if (Ui.attempt(() -> out[0] = wizard.detach(cluster, cluster, user))) {
                        Dialog d = new Dialog();
                        d.setHeaderTitle(cluster + " detached");
                        UnorderedList ul = new UnorderedList();
                        out[0].forEach(l -> ul.add(new ListItem(l)));
                        d.add(new Div(ul));
                        d.getFooter().add(new Button("To the fleet", e -> {
                            d.close();
                            UI.getCurrent().navigate(FleetView.class);
                        }));
                        d.open();
                    }
                });
    }
}
