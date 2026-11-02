package com.bisoft.bfm.watcher.i18n;

import com.vaadin.flow.component.Direction;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.Cookie;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Picks every console page's language and writing direction: the operator's choice from the language menu (cookie
 * {@value #COOKIE}), otherwise the browser's first supported language, otherwise English. Arabic pages are laid out
 * right to left.
 */
@Component
public class LanguageSetup implements VaadinServiceInitListener {

    public static final String COOKIE = "bfm-lang";

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(e -> apply(e.getUI(), chosen(VaadinRequest.getCurrent(), e.getUI().getLocale())));
    }

    /** The language for this request: the cookie's, else the browser's (as Vaadin matched it), else English. */
    static Locale chosen(VaadinRequest req, Locale browser) {
        Cookie[] cookies = req == null ? null : req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    for (Locale l : Messages.LOCALES) {
                        if (l.getLanguage().equals(c.getValue())) {
                            return l;
                        }
                    }
                }
            }
        }
        return Messages.supported(browser);
    }

    /** Sets the language and the direction of one page. */
    public static void apply(UI ui, Locale locale) {
        Locale l = Messages.supported(locale);
        ui.setLocale(l);
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setLocale(l);
        }
        ui.setDirection(Messages.rightToLeft(l) ? Direction.RIGHT_TO_LEFT : Direction.LEFT_TO_RIGHT);
        ui.getElement().executeJs("document.documentElement.lang = $0", l.getLanguage());
    }

    /** Remembers the choice for a year and reloads the page in the new language. */
    public static void choose(UI ui, Locale locale) {
        Locale l = Messages.supported(locale);
        VaadinRequest req = VaadinRequest.getCurrent();
        boolean secure = req != null && req.isSecure();
        ui.getPage().executeJs("document.cookie = $0", COOKIE + "=" + l.getLanguage()
                + "; path=/; max-age=31536000; SameSite=Strict" + (secure ? "; Secure" : ""));
        apply(ui, l);
        ui.getPage().reload();
    }
}
