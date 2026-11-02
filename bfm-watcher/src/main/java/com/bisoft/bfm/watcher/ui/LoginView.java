package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.i18n.LanguageSetup;
import com.bisoft.bfm.watcher.i18n.Messages;

import java.util.Comparator;
import java.util.List;

@Route("login")
@AnonymousAllowed
public class LoginView extends Div implements BeforeEnterObserver, HasDynamicTitle {

    private final LoginForm form = new LoginForm();

    public LoginView(BfmProperties props) {
        addClassName("pg-login");
        form.setAction("login");
        form.setForgotPasswordButtonVisible(false);
        LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle(Ui.t("login.title"));
        i18n.getForm().setUsername(Ui.t("login.username"));
        i18n.getForm().setPassword(Ui.t("login.password"));
        i18n.getForm().setSubmit(Ui.t("login.submit"));
        i18n.getErrorMessage().setTitle(Ui.t("login.errorTitle"));
        i18n.getErrorMessage().setMessage(Ui.t("login.errorText"));
        form.setI18n(i18n);
        Div brand = new Div(Ui.logo(), Ui.text("pg-brand-name", "BFM"));
        brand.addClassName("pg-brand");
        // Which cluster this console belongs to, before anyone types a password into the wrong one.
        Div cluster = Ui.div("pg-env-login");
        List<BfmProperties.Cluster> all = props.allClusters();
        if (all.size() == 1) {
            cluster.add(Ui.text("pg-cluster-name", all.get(0).name()));
            String env = all.get(0).environment();
            if (env != null && !env.isBlank()) {
                cluster.add(Ui.env(env));
            }
        } else {
            // Several clusters: say how many and which environments, the strictest marker first.
            cluster.add(Ui.text("pg-cluster-name", Ui.t("login.clusters", all.size())));
            all.stream().map(BfmProperties.Cluster::environment).filter(e -> e != null && !e.isBlank())
                    .map(String::toLowerCase).distinct()
                    .sorted(Comparator.comparingInt((String e) -> switch (Ui.envTone(e)) {
                        case CRIT -> 0;
                        case WARN -> 1;
                        default -> 2;
                    }))
                    .forEach(e -> cluster.add(Ui.env(e)));
        }
        // the language is chosen before signing in: each name in its own language
        Div languages = Ui.div("pg-login-languages");
        for (java.util.Locale l : Messages.LOCALES) {
            // real buttons: reachable with the keyboard and announced as such
            com.vaadin.flow.component.button.Button link = new com.vaadin.flow.component.button.Button(
                    Ui.t("lang." + l.getLanguage()), e -> LanguageSetup.choose(UI.getCurrent(), l));
            link.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY_INLINE,
                    com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
            link.getElement().setAttribute("lang", l.getLanguage());
            if (l.getLanguage().equals(Ui.locale().getLanguage())) {
                link.getElement().setAttribute("aria-current", "true");
            }
            languages.add(link);
        }
        languages.setVisible(Messages.severalLanguages());
        Div card = Ui.div("pg-login-card", brand,
                Ui.text("pg-note", Ui.t("app.subtitle")), cluster, form, languages);
        add(card);
    }

    @Override
    public String getPageTitle() {
        return Ui.t("title.login");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        form.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
