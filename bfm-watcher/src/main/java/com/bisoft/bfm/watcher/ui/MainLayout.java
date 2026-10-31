package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
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
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import com.bisoft.bfm.watcher.service.FleetService;
import com.bisoft.bfm.watcher.service.Terms;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.Cookie;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Header and navigation. Every cluster page shows which cluster this is, in which environment, and whether it is
 * healthy, so an operator on the Events page still sees the primary go down. A watcher with several clusters adds a
 * cluster switcher (worst first), a fleet summary, and the Fleet page; with one cluster the console looks as it
 * always did.
 */
@PermitAll
public class MainLayout extends AppLayout implements BeforeEnterObserver {

    static final String SCHEME_COOKIE = "bfm-scheme";
    private static final String LAST_CLUSTER = "bfm.last-cluster";
    private static final String[][] PAGES = {
            {"overview", "Overview"}, {"replication", "Replication"}, {"switchover", "Switchover"},
            {"operations", "Operations"}, {"events", "Events"}, {"notifications", "Notifications"}};
    private static final VaadinIcon[] ICONS = {VaadinIcon.DASHBOARD, VaadinIcon.CLUSTER, VaadinIcon.EXCHANGE,
            VaadinIcon.TASKS, VaadinIcon.LIST, VaadinIcon.ENVELOPE};

    private final ClusterHub hub;
    private final FleetService fleet;
    private final Span watchers = new Span();
    private final Span clusterName = Ui.text("pg-cluster-name", "");
    private final Span environment = new Span();
    private final Anchor health = new Anchor("", "");
    private final Anchor fleetSummary = new Anchor("fleet", "");
    private final ComboBox<String> switcher = new ComboBox<>();
    private final SideNav nav = new SideNav();
    private final Map<ColorScheme.Value, MenuItem> schemeItems = new EnumMap<>(ColorScheme.Value.class);
    private final Map<String, FleetService.ClusterSummary> summaries = new LinkedHashMap<>();
    private Registration poll;
    private ClusterContext current;
    private boolean navBuilt;
    /** The page type shown now ("overview", "replication", ...), or null outside a cluster. */
    private String page;

    public MainLayout(ClusterHub hub, FleetService fleet, AuthenticationContext auth) {
        this.hub = hub;
        this.fleet = fleet;
        setPrimarySection(Section.NAVBAR);

        Div brandText = Ui.div("pg-brand-text", Ui.text("pg-brand-name", "BFM"),
                Ui.text("pg-brand-sub pg-wide-only", "PostgreSQL Failover Manager"));
        brandText.getStyle().set("display", "flex").set("flex-direction", "column");
        Div brand = new Div(Ui.logo(), brandText);
        brand.addClassName("pg-brand");

        HorizontalLayout cluster = new HorizontalLayout();
        cluster.setSpacing(false);
        cluster.getStyle().set("gap", "8px").set("align-items", "center");
        if (hub.multi()) {
            buildSwitcher();
            cluster.add(switcher);
        } else {
            cluster.add(clusterName);
        }
        health.addClassName("pg-health-chip");
        cluster.add(environment, health);

        boolean admin = auth.hasRole("ADMIN");
        Span user = Ui.text("pg-note", auth.getPrincipalName().orElse("") + " · " + Terms.role(admin));
        user.addClassName("pg-wide-only");
        Button logout = new Button(VaadinIcon.SIGN_OUT.create(), e -> auth.logout());
        logout.setAriaLabel("Log out");
        logout.setTooltipText("Log out");
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        fleetSummary.addClassName("pg-note");
        fleetSummary.addClassName("pg-wide-only");
        fleetSummary.setVisible(hub.multi());
        HorizontalLayout right = new HorizontalLayout(fleetSummary, watchers, user, schemeMenu(), logout);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("margin-left", "auto").set("padding-right", "16px").set("flex-wrap", "nowrap");

        HorizontalLayout bar = new HorizontalLayout(new DrawerToggle(), brand, cluster, right);
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.setWidthFull();
        bar.getStyle().set("padding-left", "8px");
        addToNavbar(bar);

        nav.getStyle().set("padding", "12px");
        addToDrawer(nav);
        applyStoredScheme();
    }

    // ---------------------------------------------------------------- which cluster

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        String name = e.getRouteParameters().get(ClusterRoutes.PARAM).orElse(null);
        String path = e.getLocation().getPath();
        page = name == null ? null : path.substring(path.lastIndexOf('/') + 1);
        VaadinSession session = VaadinSession.getCurrent();
        ClusterContext c = name == null ? null : hub.find(name).orElse(null);
        if (c == null) {
            // Fleet or Hosts: the menu keeps the cluster last looked at.
            Object last = session == null ? null : session.getAttribute(LAST_CLUSTER);
            c = hub.multi() ? hub.find(last == null ? null : last.toString()).orElse(null)
                    : hub.clusters().stream().findFirst().orElse(null);
        } else if (session != null) {
            session.setAttribute(LAST_CLUSTER, c.name());
        }
        boolean changed = !navBuilt || (current == null) != (c == null)
                || (current != null && !current.name().equals(c.name()));
        current = c;
        if (changed) {
            buildNav();
        }
        refresh();
    }

    private void buildNav() {
        navBuilt = true;
        nav.removeAll();
        if (hub.names().size() != 1) {
            nav.addItem(new SideNavItem("Fleet", "fleet", VaadinIcon.GRID_BIG.create()));
        }
        nav.addItem(new SideNavItem("Hosts", "hosts", VaadinIcon.SERVER.create()));
        nav.addItem(new SideNavItem("Agent certificates", "certificates", VaadinIcon.KEY.create()));
        if (current == null) {
            return;
        }
        SideNavItem group = hub.multi() ? new SideNavItem(current.name()) : null;
        for (int i = 0; i < PAGES.length; i++) {
            SideNavItem item = new SideNavItem(PAGES[i][1], ClusterRoutes.path(current.name(), PAGES[i][0]),
                    ICONS[i].create());
            if (group != null) {
                group.addItem(item);
            } else {
                nav.addItem(item);
            }
        }
        if (group != null) {
            group.setExpanded(true);
            nav.addItem(group);
        }
    }

    private void buildSwitcher() {
        switcher.setAriaLabel("Cluster");
        switcher.setPlaceholder("Cluster");
        switcher.setWidth("190px");
        switcher.addClassName("pg-switcher");
        switcher.getStyle().set("--vaadin-combo-box-overlay-width", "340px");
        switcher.setRenderer(new ComponentRenderer<>(name -> {
            FleetService.ClusterSummary s = summaries.get(name);
            Div row = Ui.div("pg-row", Ui.text(null, name));
            if (s != null) {
                row.add(Ui.chip(s.health(), OverviewPage.healthTone(s.health())));
                if (s.environment() != null && !s.environment().isBlank()) {
                    row.add(Ui.env(s.environment()));
                }
            }
            row.getStyle().set("flex-wrap", "nowrap");
            return row;
        }));
        switcher.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null && (current == null || !e.getValue().equals(current.name()))) {
                ClusterRoutes.go(e.getValue(), page == null ? "overview" : page);
            }
        });
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
        StateStore store = hub.store();
        if (store.mode() == StateStore.Mode.STANDALONE) {
            watchers.setText("Watcher " + store.selfId() + " · standalone");
        } else {
            var peers = store.peers();
            var lead = store.leadership();
            long fresh = current == null ? 10_000 : current.watcher().config().timing().observationFreshMs();
            long up = peers.stream().filter(p -> p.self() || (p.lastContactMs() != null
                    && p.lastContactMs() < fresh)).count();
            watchers.setText("Watchers " + up + "/" + peers.size() + " · leader "
                    + (lead.leaderId() == null ? "none" : lead.leaderId()));
        }
        watchers.setClassName("pg-note");
        watchers.addClassName("pg-wide-only");

        if (hub.multi()) {
            List<FleetService.ClusterSummary> all = fleet.clusters();
            summaries.clear();
            all.forEach(s -> summaries.put(s.name(), s));
            long healthy = all.stream().filter(s -> "Healthy".equals(s.health())).count();
            long other = all.size() - healthy;
            fleetSummary.setText("Fleet: " + healthy + " healthy" + (other == 0 ? "" : " · " + other
                    + " need attention"));
            if (!switcher.isOpened()) {
                switcher.setItems(all.stream().map(FleetService.ClusterSummary::name).toList());
                switcher.setValue(current == null ? null : current.name());
            }
        }

        environment.removeAll();
        health.removeAll();
        removeClassName("pg-env-production");
        if (current == null) {
            health.setVisible(false);
            return;
        }
        clusterName.setText(current.name());
        String env = current.environment();
        // On the fleet and hosts pages nothing marks one cluster: those pages are about all of them.
        if (env != null && !env.isBlank() && (page != null || !hub.multi())) {
            environment.add(Ui.env(env));
            if (Ui.envTone(env) == Ui.Tone.CRIT) {
                addClassName("pg-env-production");
            }
        }
        Overview o = current.console().overview();
        health.setVisible(page != null || !hub.multi());
        health.setHref(ClusterRoutes.path(current.name(), "overview"));
        health.add(Ui.chip(o.health(), OverviewPage.healthTone(o.health())));
        health.getElement().setAttribute("title", o.healthReasons().isEmpty() ? "Cluster status"
                : String.join("\n", o.healthReasons()));
        health.getElement().setAttribute("aria-label", "Cluster status: " + o.health());
        if (o.clusterId() != null) {
            clusterName.getElement().setAttribute("title", "Cluster id " + o.clusterId()
                    + (o.systemIdentifier() == null ? "" : "\nPostgreSQL system identifier " + o.systemIdentifier()));
        }
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
