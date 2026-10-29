package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import com.bisoft.bfm.watcher.service.Terms;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.Cookie;

import java.util.EnumMap;
import java.util.Map;

/**
 * Header and navigation. Every page shows which cluster this is, in which environment, and whether it is healthy,
 * so an operator on the Events page still sees the primary go down.
 */
@PermitAll
public class MainLayout extends AppLayout {

    static final String SCHEME_COOKIE = "bfm-scheme";

    private final ConsoleService console;
    private final Span watchers = new Span();
    private final Anchor health = new Anchor("", "");
    private final Map<ColorScheme.Value, MenuItem> schemeItems = new EnumMap<>(ColorScheme.Value.class);
    private Registration poll;

    public MainLayout(ConsoleService console, AuthenticationContext auth, BfmProperties props) {
        this.console = console;
        setPrimarySection(Section.NAVBAR);

        Div brandText = Ui.div("pg-brand-text", Ui.text("pg-brand-name", "BFM"),
                Ui.text("pg-brand-sub pg-wide-only", "PostgreSQL Failover Manager"));
        brandText.getStyle().set("display", "flex").set("flex-direction", "column");
        Div brand = new Div(Ui.logo(), brandText);
        brand.addClassName("pg-brand");

        HorizontalLayout cluster = new HorizontalLayout(Ui.text("pg-cluster-name", console.watcher().config().name()));
        cluster.setSpacing(false);
        cluster.getStyle().set("gap", "8px").set("align-items", "center");
        String env = props.cluster() == null ? null : props.cluster().environment();
        if (env != null && !env.isBlank()) {
            cluster.add(Ui.env(env));
            if (Ui.envTone(env) == Ui.Tone.CRIT) {
                addClassName("pg-env-production");
            }
        }
        health.addClassName("pg-health-chip");
        cluster.add(health);

        boolean admin = auth.hasRole("ADMIN");
        Span user = Ui.text("pg-note", auth.getPrincipalName().orElse("") + " · " + Terms.role(admin));
        user.addClassName("pg-wide-only");
        Button logout = new Button(VaadinIcon.SIGN_OUT.create(), e -> auth.logout());
        logout.setAriaLabel("Log out");
        logout.setTooltipText("Log out");
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout right = new HorizontalLayout(watchers, user, schemeMenu(), logout);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("margin-left", "auto").set("padding-right", "16px").set("flex-wrap", "nowrap");

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
        applyStoredScheme();
        refresh();
    }

    // ---------------------------------------------------------------- light / dark

    private MenuBar schemeMenu() {
        MenuBar mb = new MenuBar();
        mb.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE);
        MenuItem root = mb.addItem(VaadinIcon.ADJUST.create());
        root.setAriaLabel("Colour scheme");
        root.getElement().setAttribute("title", "Light or dark");
        SubMenu sub = root.getSubMenu();
        add(sub, ColorScheme.Value.LIGHT_DARK, "Match the system");
        add(sub, ColorScheme.Value.LIGHT, "Light");
        add(sub, ColorScheme.Value.DARK, "Dark");
        return mb;
    }

    private void add(SubMenu sub, ColorScheme.Value v, String label) {
        MenuItem item = sub.addItem(label, e -> setScheme(v));
        item.setCheckable(true);
        schemeItems.put(v, item);
    }

    private void setScheme(ColorScheme.Value v) {
        UI ui = UI.getCurrent();
        ui.getPage().setColorScheme(v);
        VaadinRequest req = VaadinRequest.getCurrent();
        boolean secure = req != null && req.isSecure();
        ui.getPage().executeJs("document.cookie = $0", SCHEME_COOKIE + "=" + v.name()
                + "; path=/; max-age=31536000; SameSite=Strict" + (secure ? "; Secure" : ""));
        schemeItems.forEach((k, item) -> item.setChecked(k == v));
    }

    /** The operator's choice from an earlier visit; the system's choice otherwise. */
    private void applyStoredScheme() {
        ColorScheme.Value v = ColorScheme.Value.LIGHT_DARK;
        VaadinRequest req = VaadinRequest.getCurrent();
        Cookie[] cookies = req == null ? null : req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (SCHEME_COOKIE.equals(c.getName())) {
                    try {
                        v = ColorScheme.Value.valueOf(c.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // an old or edited cookie: keep the default
                    }
                }
            }
        }
        UI ui = UI.getCurrent();
        if (ui != null) {
            ui.getPage().setColorScheme(v);
        }
        ColorScheme.Value chosen = v;
        schemeItems.forEach((k, item) -> item.setChecked(k == chosen));
    }

    // ---------------------------------------------------------------- status

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

        Overview o = console.overview();
        health.removeAll();
        health.add(Ui.chip(o.health(), OverviewView.healthTone(o.health())));
        health.getElement().setAttribute("title", o.healthReasons().isEmpty() ? "Cluster status"
                : String.join("\n", o.healthReasons()));
        health.getElement().setAttribute("aria-label", "Cluster status: " + o.health());
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
