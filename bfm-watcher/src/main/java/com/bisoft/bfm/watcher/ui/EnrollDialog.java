package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.server.VaadinRequest;
import com.bisoft.bfm.watcher.service.ConsoleTls;
import com.bisoft.bfm.watcher.service.EnrollmentService;

import java.time.ZoneId;

/** The one-time setup command for a node, shown once, with a copy button. */
final class EnrollDialog {

    private EnrollDialog() {
    }

    static void open(EnrollmentService.Issued t, ConsoleTls tls, ZoneId zone) {
        VaadinRequest req = VaadinRequest.getCurrent();
        String scheme = req != null && req.isSecure() ? "https://" : "http://";
        String host = req == null || req.getHeader("Host") == null ? "localhost" : req.getHeader("Host");
        String fp = tls.fingerprint();
        String command = "sudo minipgagent setup --enroll " + scheme + host + " --token " + t.token()
                + (fp == null ? " --insecure-http" : " --fingerprint " + fp);
        Dialog d = new Dialog();
        d.setHeaderTitle("Enroll " + t.node() + " of " + t.cluster());
        Pre pre = new Pre(command);
        pre.addClassName("pg-mono");
        pre.getStyle().set("white-space", "pre-wrap").set("word-break", "break-all");
        d.add(Ui.text("pg-note", "Run it as root on the node's database host. Shown once; works once, for "
                + t.node() + " only, until " + Ui.full(t.expiresAtMillis(), zone) + " " + Ui.zone(zone)
                + "." + (fp == null ? "" : " --fingerprint pins this console's certificate, so the token goes to"
                + " no one else.") + " The node's certificate will be signed by the BFM CA " + t.caFingerprint()
                + "."), pre);
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
