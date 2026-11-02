package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.select.SelectVariant;
import com.vaadin.flow.server.VaadinRequest;
import jakarta.servlet.http.Cookie;

import java.util.List;

/**
 * How often a live page (Overview, Replication) refreshes itself, as this browser chose: a select next to the
 * page's Refresh button, kept in a cookie like the language. The watcher's own polling of the nodes does not
 * change with it.
 */
final class RefreshChoice {

    static final String COOKIE = "bfm-refresh";
    static final int DEFAULT_SECONDS = 30;
    static final List<Integer> CHOICES = List.of(0, 5, 10, 30, 60);

    private RefreshChoice() {
    }

    /** The select, set to this browser's choice; a change is stored and applied to the page at once. */
    static Select<Integer> select() {
        Select<Integer> s = new Select<>();
        s.setItems(CHOICES);
        s.setItemLabelGenerator(n -> n == 0 ? Ui.t("ov.refreshOff") : Ui.t("ov.refreshEvery", n));
        s.setValue(stored());
        s.setAriaLabel(Ui.t("ov.autoRefresh"));
        s.setPrefixComponent(Ui.text("pg-note", Ui.t("ov.autoRefresh")));
        s.addThemeVariants(SelectVariant.LUMO_SMALL);
        s.setWidth("15em"); // room for the longest label ("التحديث التلقائي 30 ث")
        s.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                store(e.getValue());
                UI ui = UI.getCurrent();
                if (ui != null) {
                    apply(ui, e.getValue());
                }
            }
        });
        return s;
    }

    /** Polls the page every {@code seconds}; 0 stops it (the Refresh button still works). */
    static void apply(UI ui, Integer seconds) {
        ui.setPollInterval(seconds == null || seconds <= 0 ? -1 : seconds * 1000);
    }

    static int stored() {
        VaadinRequest req = VaadinRequest.getCurrent();
        Cookie[] cookies = req == null ? null : req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    try {
                        int v = Integer.parseInt(c.getValue());
                        if (CHOICES.contains(v)) {
                            return v;
                        }
                    } catch (NumberFormatException ignored) {
                        // an old or edited cookie: the default
                    }
                }
            }
        }
        return DEFAULT_SECONDS;
    }

    private static void store(int seconds) {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        VaadinRequest req = VaadinRequest.getCurrent();
        boolean secure = req != null && req.isSecure();
        ui.getPage().executeJs("document.cookie = $0", COOKIE + "=" + seconds
                + "; path=/; max-age=31536000; SameSite=Strict" + (secure ? "; Secure" : ""));
    }
}
