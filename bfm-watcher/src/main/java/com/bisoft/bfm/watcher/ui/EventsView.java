package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.Severity;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** The event and audit log shared by every watcher. */
@Route(value = "events", layout = MainLayout.class)
@PageTitle("Events · BFM")
@PermitAll
public class EventsView extends Div implements BeforeEnterObserver {

    private final ConsoleService console;
    private final Grid<ClusterEvent> grid = new Grid<>();
    private final Select<String> severity = new Select<>();
    private final TextField search = new TextField();
    private final Set<String> open = new HashSet<>();
    private Registration poll;

    public EventsView(ConsoleService console, BfmProperties props) {
        this.console = console;
        ZoneId zone = props.zone();
        addClassName("pg-page");
        H1 title = new H1("Events");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Failovers, switchovers, alerts and every operator action"), title));

        severity.setLabel("Severity");
        severity.setItems("All", "Critical", "Warning", "Info");
        severity.setValue("All");
        severity.addValueChangeListener(e -> refresh());
        search.setLabel("Search");
        search.setPlaceholder("node, user or text");
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setClearButtonVisible(true);
        search.addValueChangeListener(e -> refresh());

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setDetailsVisibleOnClick(false);
        grid.addComponentColumn(e -> Ui.chevron(open.contains(e.id()))).setWidth("56px").setFlexGrow(0)
                .setHeader("");
        Grid.Column<ClusterEvent> time = grid.addColumn(e -> Ui.full(e.timeMillis(), zone))
                .setHeader("Time (" + Ui.zone(zone) + ")").setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ClusterEvent> sev = grid.addComponentColumn(e -> severityChip(e)).setHeader("Severity")
                .setAutoWidth(true).setFlexGrow(0);
        // On a phone one column carries it all, stacked: time and severity on top, the event below.
        Grid.Column<ClusterEvent> stacked = grid.addComponentColumn(e -> {
            Div d = new Div(Ui.div("pg-row", Ui.text("pg-note", Ui.shortTime(e.timeMillis(), zone)),
                    severityChip(e), Ui.text("pg-note", e.node() == null ? "" : e.node())),
                    Ui.text(null, Ui.arrows(e.message())));
            d.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px")
                    .set("white-space", "normal");
            return d;
        }).setHeader("Event (" + Ui.zone(zone) + ")").setFlexGrow(1);
        Grid.Column<ClusterEvent> node = grid.addColumn(e -> e.node() == null ? "—" : e.node()).setHeader("Node")
                .setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ClusterEvent> message = grid.addColumn(e -> Ui.arrows(e.message())).setHeader("Event")
                .setFlexGrow(1);
        Grid.Column<ClusterEvent> by = grid.addColumn(ClusterEvent::actor).setHeader("By").setAutoWidth(true)
                .setFlexGrow(0);
        Ui.switchAt(grid, 760, List.of(time, sev, node, message, by), List.of(stacked));
        grid.addItemClickListener(e -> {
            if (!open.remove(e.getItem().id())) {
                open.add(e.getItem().id());
            }
            grid.setDetailsVisible(e.getItem(), open.contains(e.getItem().id()));
            grid.getDataProvider().refreshItem(e.getItem());
        });
        grid.setEmptyStateText("No events match.");
        grid.setItemDetailsRenderer(new ComponentRenderer<>(e -> {
            Pre p = new Pre(e.type() + (e.detail() == null ? "" : "\n\n" + e.detail()));
            p.addClassName("pg-log");
            return p;
        }));
        Div card = Ui.card(null);
        card.add(Ui.div("pg-row", severity, search), grid);
        add(card);
        refresh();
    }

    private static com.vaadin.flow.component.html.Span severityChip(ClusterEvent e) {
        return Ui.chip(switch (e.severity()) {
            case CRITICAL -> "Critical";
            case WARNING -> "Warning";
            case INFO -> "Info";
        }, Ui.tone(e.severity()));
    }

    private void refresh() {
        String sev = severity.getValue();
        String q = search.getValue() == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<ClusterEvent> list = console.watcher().events(1000).stream()
                .filter(e -> "All".equals(sev) || e.severity() == Severity.valueOf(sev.toUpperCase(Locale.ROOT)))
                .filter(e -> q.isEmpty() || (e.message() + " " + e.node() + " " + e.actor() + " " + e.type())
                        .toLowerCase(Locale.ROOT).contains(q))
                .toList();
        grid.setItems(list);
        // A short list is shown whole; a long one scrolls inside the card instead of stretching the page.
        if (list.size() <= 12) {
            grid.setAllRowsVisible(true);
        } else {
            grid.setAllRowsVisible(false);
            grid.setHeight("70vh");
        }
        for (ClusterEvent e : list) {
            if (open.contains(e.id())) {
                grid.setDetailsVisible(e, true);
            }
        }
    }

    /** {@code events?q=pg-a2} opens the log filtered, as the role-change banner's Details link does. */
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> q = event.getLocation().getQueryParameters().getParameters().get("q");
        if (q != null && !q.isEmpty()) {
            search.setValue(q.get(0));
        }
    }

    @Override
    protected void onAttach(AttachEvent e) {
        super.onAttach(e);
        e.getUI().setPollInterval(5000);
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
