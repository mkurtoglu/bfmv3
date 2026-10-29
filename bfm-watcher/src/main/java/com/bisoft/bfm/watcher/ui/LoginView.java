package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route("login")
@PageTitle("Sign in · BFM")
@AnonymousAllowed
public class LoginView extends Div implements BeforeEnterObserver {

    private final LoginForm form = new LoginForm();

    public LoginView() {
        addClassName("pg-login");
        form.setAction("login");
        form.setForgotPasswordButtonVisible(false);
        Div brand = new Div(Ui.logo(), Ui.text("pg-brand-name", "BFM"));
        brand.addClassName("pg-brand");
        Div card = Ui.div("pg-login-card", brand,
                Ui.text("pg-note", "PostgreSQL Failover Manager"), form);
        add(card);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        form.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
