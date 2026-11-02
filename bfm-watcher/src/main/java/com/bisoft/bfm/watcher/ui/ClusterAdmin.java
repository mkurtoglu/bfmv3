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
        d.setHeaderTitle(Ui.t("adm.addTitle", cluster));
        TextField name = new TextField(Ui.t("adm.nodeName"));
        TextField agent = new TextField(Ui.t("ca.col.agent"));
        agent.setPlaceholder("https://db04:8008");
        TextField pgHost = new TextField(Ui.t("adm.pgAddress"));
        IntegerField pgPort = new IntegerField(Ui.t("adm.port"));
        pgPort.setValue(5432);
        TextField dc = new TextField(Ui.t("adm.dc"));
        dc.setValue("DC1");
        IntegerField prio = new IntegerField(Ui.t("adm.priority"));
        prio.setValue(100);
        Checkbox never = new Checkbox(Ui.t("adm.never"));
        d.add(Ui.text("pg-note", Ui.t("adm.addNote")), Ui.div("pg-grid-auto", name, agent, pgHost, pgPort, dc, prio),
                never);
        Button add = new Button(Ui.t("adm.addButton"), e -> {
            ClusterSpec.NodeSpec n = new ClusterSpec.NodeSpec(name.getValue().trim(), agent.getValue().trim(),
                    pgHost.getValue().trim(), pgPort.getValue() == null ? 5432 : pgPort.getValue(),
                    dc.getValue().isBlank() ? "DC1" : dc.getValue().trim(), prio.getValue() == null ? 100
                    : prio.getValue(), never.getValue());
            EnrollmentService.Issued[] t = new EnrollmentService.Issued[1];
            if (Ui.attempt(() -> wizard.addNode(cluster, n, user))) {
                d.close();
                Ui.ok(Ui.t("adm.added", n.name(), cluster));
                if (Ui.attempt(() -> t[0] = wizard.token(cluster, n.name(), user))) {
                    EnrollDialog.open(t[0], tls, zone);
                }
            }
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button(Ui.t("common.cancel"), e -> d.close()), add);
        d.setWidth("min(760px, 95vw)");
        d.open();
    }

    void removeNode(String node, Runnable after) {
        Ui.confirmTyped(node, Ui.t("adm.removeTitle", node, cluster), Ui.t("adm.removeText", node, cluster),
                Ui.t("adm.removeButton"), true, () -> {
                    if (Ui.attempt(() -> wizard.removeNode(cluster, node, user))) {
                        Ui.ok(Ui.t("adm.removed", node, cluster));
                    }
                    if (after != null) {
                        after.run();
                    }
                });
    }

    void detach() {
        Ui.confirmTyped(cluster, Ui.t("adm.detachTitle", cluster), Ui.t("adm.detachText"), Ui.t("adm.detachButton"),
                true, () -> {
                    List<String>[] out = new List[1];
                    if (Ui.attempt(() -> out[0] = wizard.detach(cluster, cluster, user))) {
                        Dialog d = new Dialog();
                        d.setHeaderTitle(Ui.t("adm.detached", cluster));
                        UnorderedList ul = new UnorderedList();
                        out[0].forEach(l -> ul.add(new ListItem(Ui.tr(l))));
                        d.add(new Div(ul));
                        d.getFooter().add(new Button(Ui.t("adm.toFleet"), e -> {
                            d.close();
                            UI.getCurrent().navigate(FleetView.class);
                        }));
                        d.open();
                    }
                });
    }
}
