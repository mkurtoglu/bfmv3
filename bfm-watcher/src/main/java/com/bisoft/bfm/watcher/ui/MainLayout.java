package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.service.ConsoleService;
import jakarta.annotation.security.PermitAll;

/** Header with the cluster and watcher status, and the navigation. */
@PermitAll
public class MainLayout extends AppLayout {

    private final ConsoleService console;
    private final Span watchers = new Span();
    private Registration poll;

    public MainLayout(ConsoleService console, AuthenticationContext auth) {
        this.console = console;
        setPrimarySection(Section.NAVBAR);

        Div brandText = Ui.div("pg-brand-text", Ui.text("pg-brand-name", "BFM"),
                Ui.text("pg-brand-sub pg-wide-only", "PostgreSQL Failover Manager"));
        brandText.getStyle().set("display", "flex").set("flex-direction", "column");
        Div brand = new Div(Ui.logo(), brandText);
        brand.addClassName("pg-brand");

        Span cluster = Ui.chip(console.watcher().config().name(), Ui.Tone.OK);
        cluster.addClassName("plain");
        Span user = Ui.text("pg-note", auth.getPrincipalName().orElse("") + (auth.hasRole("ADMIN") ? " · operator"
                : " · read only"));
        user.addClassName("pg-wide-only");
        Button logout = new Button(VaadinIcon.SIGN_OUT.create(), e -> auth.logout());
        logout.setAriaLabel("Log out");
        logout.setTooltipText("Log out");
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout right = new HorizontalLayout(watchers, user, logout);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("margin-left", "auto").set("padding-right", "16px").set("flex-wrap", "wrap");

        HorizontalLayout bar = new HorizontalLayout(new DrawerToggle(), brand, cluster, right);
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.setWidthFull();
        bar.getStyle().set("padding-left", "8px");
        addToNavbar(bar);

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Overview", OverviewView.class, VaadinIcon.DASHBOARD.create()));
        nav.addItem(new SideNavItem("Replication", ReplicationView.class, VaadinIcon.CLUSTER.create()));
        nav.addItem(new SideNavItem("Switchover", SwitchoverView.class, VaadinIcon.EXCHANGE.create()));
        nav.addItem(new SideNavItem("Operations", OperationsView.class, VaadinIcon.TASKS.create()));
        nav.addItem(new SideNavItem("Events", EventsView.class, VaadinIcon.LIST.create()));
        nav.addItem(new SideNavItem("Notifications", NotificationsView.class, VaadinIcon.ENVELOPE.create()));
        nav.getStyle().set("padding", "12px");
        addToDrawer(nav);
        refresh();
    }

    private void refresh() {
        var peers = console.watcher().peers();
        var lead = console.watcher().leadership();
        if (console.watcher().store().mode() == StateStore.Mode.STANDALONE) {
            watchers.setText("Watcher " + console.watcher().store().selfId() + " · standalone");
        } else {
            long up = peers.stream().filter(p -> p.self() || (p.lastContactMs() != null
                    && p.lastContactMs() < console.watcher().config().timing().observationFreshMs())).count();
            watchers.setText("Watchers " + up + "/" + peers.size() + " · leader "
                    + (lead.leaderId() == null ? "none" : lead.leaderId()));
        }
        watchers.setClassName("pg-note");
        watchers.addClassName("pg-wide-only");
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        poll = e.getUI().addPollListener(p -> refresh());
    }

    @Override
    protected void onDetach(DetachEvent e) {
        if (poll != null) {
            poll.remove();
        }
        super.onDetach(e);
    }
}
