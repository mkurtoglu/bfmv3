package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinRequest;
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
@PageTitle("Agent certificates · BFM")
@PermitAll
public class CertificatesView extends Div {

    private final ClusterHub hub;
    private final WatcherCa ca;
    private final EnrollmentService enrollment;
    private final ConsoleTls consoleTls;
    private final boolean admin;
    private final String user;
    private final ZoneId zone;
    private final Div caCard = Ui.card("Certificate authority");

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
        H1 title = new H1("Agent certificates");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Settings"), title));
        add(caCard, nodesCard());
        renderCa();
    }

    private void renderCa() {
        caCard.removeAll();
        caCard.add(new com.vaadin.flow.component.html.H2("Certificate authority"));
        if (ca.exists()) {
            List<String> d = ca.describe();
            caCard.add(Ui.text("pg-note", "Agents enroll with a one-time token and get a certificate signed by this"
                    + " CA. Its key is kept in the watchers' shared state, encrypted with secret.key."));
            Pre pre = new Pre(String.join("\n", d));
            pre.addClassName("pg-mono");
            caCard.add(pre);
            return;
        }
        caCard.add(Ui.text("pg-note", "No CA here: agent certificates come from bfm-make-certs (offline). To let"
                + " database hosts enroll themselves, create a CA, or take over the one bfm-make-certs made so old and"
                + " new agents trust the same watchers."));
        if (!admin) {
            return;
        }
        Button create = new Button("Create a CA", e -> Ui.confirm("Create a CA?", "The watchers will sign agent"
                + " certificates at enrollment. Agents with certificates from another CA keep working only if"
                + " they trust this one too.", "Create", false, () -> {
                    if (Ui.attempt(() -> ca.create("BFM CA"))) {
                        Ui.ok("CA created");
                    }
                    renderCa();
                }));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button take = new Button("Take over ca.crt and ca.key…", e -> importDialog());
        take.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        caCard.add(Ui.div("pg-row", create, take));
    }

    private void importDialog() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Take over an existing CA");
        TextArea crt = new TextArea("ca.crt (PEM)");
        TextArea key = new TextArea("ca.key (PEM)");
        crt.setWidthFull();
        key.setWidthFull();
        crt.setHeight("140px");
        key.setHeight("140px");
        d.add(Ui.text("pg-note", "The key is stored encrypted in the watchers' shared state; keep your offline copy."),
                crt, key);
        Button ok = new Button("Take over", e -> {
            if (Ui.attempt(() -> ca.importPem(crt.getValue(), key.getValue()))) {
                Ui.ok("CA taken over");
                d.close();
                renderCa();
            }
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Cancel", e -> d.close()), ok);
        d.open();
    }

    private Div nodesCard() {
        Div card = Ui.card("Enroll a node");
        card.add(Ui.text("pg-note", "Run the command on the node's database host as root. The token works once, for"
                + " that node only, for 30 minutes; the watcher records who issued it and who used it."));
        Grid<NodeRow> grid = new Grid<>();
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.addColumn(NodeRow::cluster).setHeader("Cluster").setAutoWidth(true);
        grid.addColumn(NodeRow::node).setHeader("Node").setAutoWidth(true);
        grid.addColumn(NodeRow::agent).setHeader("Agent address").setFlexGrow(1);
        if (admin) {
            grid.addComponentColumn(r -> {
                Button b = new Button("Enrollment command…", e -> issue(r));
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
        if (!Ui.attempt(() -> t[0] = enrollment.issue(hub.require(r.cluster()), r.node(), user))) {
            return;
        }
        VaadinRequest req = VaadinRequest.getCurrent();
        String scheme = req != null && req.isSecure() ? "https://" : "http://";
        String host = req == null || req.getHeader("Host") == null ? "localhost" : req.getHeader("Host");
        String fp = consoleTls.fingerprint();
        String command = "sudo minipgagent setup --enroll " + scheme + host + " --token " + t[0].token()
                + (fp == null ? " --insecure-http" : " --fingerprint " + fp);
        Dialog d = new Dialog();
        d.setHeaderTitle("Enroll " + r.node() + " of " + r.cluster());
        Pre pre = new Pre(command);
        pre.addClassName("pg-mono");
        pre.getStyle().set("white-space", "pre-wrap").set("word-break", "break-all");
        d.add(Ui.text("pg-note", "Shown once. Valid until " + Ui.full(t[0].expiresAtMillis(), zone) + " "
                + Ui.zone(zone) + "; the CA's fingerprint is " + t[0].caFingerprint() + "."), pre);
        Button copy = new Button("Copy", e -> {
            UI.getCurrent().getPage().executeJs("navigator.clipboard.writeText($0)", command);
            Ui.ok("Copied");
        });
        copy.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button("Close", e -> d.close()), copy);
        d.setWidth("min(720px, 95vw)");
        d.open();
    }
}
