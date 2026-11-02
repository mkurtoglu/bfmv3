package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent certificates: the CA that signs them at enrollment, and one-time enrollment commands for each node. A site
 * that keeps its CA offline ({@code bfm-make-certs}) can leave this page alone.
 */
@Route(value = "certificates", layout = MainLayout.class)
@PermitAll
public class CertificatesView extends Div implements HasDynamicTitle {

    private final ClusterHub hub;
    private final WatcherCa ca;
    private final EnrollmentService enrollment;
    private final ConsoleTls consoleTls;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;
    private final Div caCard = Ui.card(Ui.t("ca.title"));

    record NodeRow(String cluster, String node, String agent) {
    }

    public CertificatesView(ClusterHub hub, WatcherCa ca, EnrollmentService enrollment, ConsoleTls consoleTls,
                            AuthenticationContext auth, BfmProperties props) {
        this.hub = hub;
        this.ca = ca;
        this.enrollment = enrollment;
        this.consoleTls = consoleTls;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        this.zone = props.zone();
        addClassName("pg-page");
        H1 title = new H1(Ui.t("nav.certificates"));
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", Ui.t("ca.eyebrow")), title));
        add(caCard, nodesCard());
        renderCa();
    }

    private void renderCa() {
        caCard.removeAll();
        caCard.add(new com.vaadin.flow.component.html.H2(Ui.t("ca.title")));
        if (ca.exists()) {
            List<String> d = ca.describe();
            caCard.add(Ui.text("pg-note", Ui.t("ca.exists")));
            Pre pre = new Pre(String.join("\n", d));
            pre.getElement().setAttribute("dir", "ltr");
            pre.addClassName("pg-mono");
            caCard.add(pre);
            return;
        }
        caCard.add(Ui.text("pg-note", Ui.t("ca.none")));
        if (!admin) {
            return;
        }
        Button create = new Button(Ui.t("ca.create"), e -> Ui.confirm(Ui.t("ca.createTitle"), Ui.t("ca.createText"),
                Ui.t("ca.createButton"), false, () -> {
                    if (Ui.attempt(() -> ca.create("BFM CA"))) {
                        Ui.ok(Ui.t("ca.created"));
                    }
                    renderCa();
                }));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button take = new Button(Ui.t("ca.takeOver"), e -> importDialog());
        take.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        caCard.add(Ui.div("pg-row", create, take));
    }

    private void importDialog() {
        Dialog d = new Dialog();
        d.setHeaderTitle(Ui.t("ca.importTitle"));
        TextArea crt = new TextArea("ca.crt (PEM)");
        TextArea key = new TextArea("ca.key (PEM)");
        crt.setWidthFull();
        key.setWidthFull();
        crt.setHeight("140px");
        key.setHeight("140px");
        d.add(Ui.text("pg-note", Ui.t("ca.importNote")), crt, key);
        crt.getElement().setAttribute("dir", "ltr");
        key.getElement().setAttribute("dir", "ltr");
        Button ok = new Button(Ui.t("ca.importButton"), e -> {
            if (Ui.attempt(() -> ca.importPem(crt.getValue(), key.getValue()))) {
                Ui.ok(Ui.t("ca.imported"));
                d.close();
                renderCa();
            }
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button(Ui.t("common.cancel"), e -> d.close()), ok);
        d.open();
    }

    private Div nodesCard() {
        Div card = Ui.card(Ui.t("ca.enroll"));
        card.add(Ui.text("pg-note", Ui.t("ca.enrollNote")));
        Grid<NodeRow> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.addColumn(NodeRow::cluster).setHeader(Ui.t("nav.cluster")).setAutoWidth(true);
        grid.addColumn(NodeRow::node).setHeader(Ui.t("ov.col.node")).setAutoWidth(true);
        grid.addColumn(NodeRow::agent).setHeader(Ui.t("ca.col.agent")).setFlexGrow(1);
        if (admin) {
            grid.addComponentColumn(r -> {
                Button b = new Button(Ui.t("ca.command"), e -> issue(r));
                b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
                b.setEnabled(enrollment.enabled());
                return b;
            }).setAutoWidth(true).setFlexGrow(0);
        }
        List<NodeRow> rows = new ArrayList<>();
        for (ClusterHub.ClusterContext c : hub.clusters()) {
            for (NodeConfig n : c.watcher().config().nodes()) {
                rows.add(new NodeRow(c.name(), n.name(), n.agentUrl()));
            }
        }
        grid.setItems(rows);
        card.add(grid);
        return card;
    }

    private void issue(NodeRow r) {
        EnrollmentService.Issued[] t = new EnrollmentService.Issued[1];
        if (Ui.attempt(() -> t[0] = enrollment.issue(hub.require(r.cluster()), r.node(), user))) {
            EnrollDialog.open(t[0], consoleTls, zone);
        }
    }

    @Override
    public String getPageTitle() {
        return Ui.t("title.certificates");
    }
}
