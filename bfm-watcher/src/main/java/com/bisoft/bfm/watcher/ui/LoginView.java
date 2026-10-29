package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.bisoft.bfm.watcher.config.BfmProperties;

@Route("login")
@PageTitle("Sign in · BFM")
@AnonymousAllowed
public class LoginView extends Div implements BeforeEnterObserver {

    private final LoginForm form = new LoginForm();

    public LoginView(BfmProperties props) {
        addClassName("pg-login");
        form.setAction("login");
        form.setForgotPasswordButtonVisible(false);
        Div brand = new Div(Ui.logo(), Ui.text("pg-brand-name", "BFM"));
        brand.addClassName("pg-brand");
        // Which cluster this console belongs to, before anyone types a password into the wrong one.
        Div cluster = Ui.div("pg-env-login");
        if (props.cluster() != null && props.cluster().name() != null) {
            cluster.add(Ui.text("pg-cluster-name", props.cluster().name()));
        }
        String env = props.cluster() == null ? null : props.cluster().environment();
        if (env != null && !env.isBlank()) {
            cluster.add(Ui.env(env));
        }
        Div card = Ui.div("pg-login-card", brand,
                Ui.text("pg-note", "PostgreSQL Failover Manager"), cluster, form);
        add(card);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        form.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
