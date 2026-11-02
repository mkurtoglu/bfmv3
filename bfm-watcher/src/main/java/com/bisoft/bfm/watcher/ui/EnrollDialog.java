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
        d.setHeaderTitle(Ui.t("en.title", t.node(), t.cluster()));
        Pre pre = new Pre(command);
        pre.addClassName("pg-mono");
        pre.getStyle().set("white-space", "pre-wrap").set("word-break", "break-all");
        pre.getElement().setAttribute("dir", "ltr"); // a shell command
        d.add(Ui.text("pg-note", Ui.t("en.runIt", t.node(), Ui.full(t.expiresAtMillis(), zone) + " " + Ui.zone(zone))
                + (fp == null ? "" : " " + Ui.t("en.pins")) + " " + Ui.t("en.signedBy", t.caFingerprint())), pre);
        Button copy = new Button(Ui.t("en.copy"), e -> {
            UI.getCurrent().getPage().executeJs("navigator.clipboard.writeText($0)", command);
            Ui.ok(Ui.t("en.copied"));
        });
        copy.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(new Button(Ui.t("common.close"), e -> d.close()), copy);
        d.setWidth("min(720px, 95vw)");
        d.open();
    }
}
