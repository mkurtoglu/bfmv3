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
import com.vaadin.flow.router.Location;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.Severity;
import com.bisoft.bfm.watcher.service.ConsoleService;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/** One cluster's event and audit log, shared by every watcher. */
class EventsPage extends Div implements ClusterRoute.Enterable {

    private final ConsoleService console;
    private final Grid<ClusterEvent> grid = new Grid<>();
    private final Select<String> severity = new Select<>();
    private final TextField search = new TextField();
    private final Set<String> open = new HashSet<>();
    private Registration poll;

    EventsPage(ConsoleService console, ZoneId zone) {
        this.console = console;
        addClassName("pg-page");
        H1 title = new H1(Ui.t("nav.events"));
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", Ui.t("ev.eyebrow")), title));

        severity.setLabel(Ui.t("ev.severity"));
        severity.setItems("All", "Critical", "Warning", "Info");
        severity.setItemLabelGenerator(v -> Ui.t("sev." + v.toLowerCase(Locale.ROOT)));
        severity.setValue("All");
        severity.addValueChangeListener(e -> refresh());
        search.setLabel(Ui.t("ev.search"));
        search.setPlaceholder(Ui.t("ev.searchHint"));
        search.setValueChangeMode(ValueChangeMode.LAZY);
        search.setClearButtonVisible(true);
        search.addValueChangeListener(e -> refresh());

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);
        grid.setDetailsVisibleOnClick(false);
        grid.addComponentColumn(e -> Ui.chevron(open.contains(e.id()))).setWidth("56px").setFlexGrow(0)
                .setHeader("");
        Grid.Column<ClusterEvent> time = grid.addColumn(e -> Ui.full(e.timeMillis(), zone))
                .setHeader(Ui.t("ev.col.time", Ui.zone(zone))).setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ClusterEvent> sev = grid.addComponentColumn(e -> severityChip(e)).setHeader(Ui.t("ev.severity"))
                .setAutoWidth(true).setFlexGrow(0);
        // On a phone one column carries it all, stacked: time and severity on top, the event below.
        Grid.Column<ClusterEvent> stacked = grid.addComponentColumn(e -> {
            Div d = new Div(Ui.div("pg-row", Ui.text("pg-note", Ui.shortTime(e.timeMillis(), zone)),
                    severityChip(e), Ui.text("pg-note", e.node() == null ? "" : e.node())),
                    Ui.text(null, Ui.arrows(Ui.tr(e.message()))));
            d.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px")
                    .set("white-space", "normal");
            return d;
        }).setHeader(Ui.t("ev.col.eventZone", Ui.zone(zone))).setFlexGrow(1);
        Grid.Column<ClusterEvent> node = grid.addColumn(e -> e.node() == null ? "—" : e.node()).setHeader(Ui.t("ov.col.node"))
                .setAutoWidth(true).setFlexGrow(0);
        Grid.Column<ClusterEvent> message = grid.addColumn(e -> Ui.arrows(Ui.tr(e.message()))).setHeader(Ui.t("ev.col.event"))
                .setFlexGrow(1);
        Grid.Column<ClusterEvent> by = grid.addColumn(ClusterEvent::actor).setHeader(Ui.t("ops.col.by")).setAutoWidth(true)
                .setFlexGrow(0);
        Ui.switchAt(grid, 760, List.of(time, sev, node, message, by), List.of(stacked));
        grid.addItemClickListener(e -> {
            if (!open.remove(e.getItem().id())) {
                open.add(e.getItem().id());
            }
            grid.setDetailsVisible(e.getItem(), open.contains(e.getItem().id()));
            grid.getDataProvider().refreshItem(e.getItem());
        });
        grid.setEmptyStateText(Ui.t("ev.empty"));
        grid.setItemDetailsRenderer(new ComponentRenderer<>(e -> {
            Pre p = new Pre(e.type() + (e.detail() == null ? "" : "\n\n" + e.detail()));
            p.getElement().setAttribute("dir", "ltr"); // the event's type and raw detail, as recorded
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
            case CRITICAL -> Ui.t("sev.critical");
            case WARNING -> Ui.t("sev.warning");
            case INFO -> Ui.t("sev.info");
        }, Ui.tone(e.severity()));
    }

    private void refresh() {
        String sev = severity.getValue();
        String q = search.getValue() == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<ClusterEvent> list = console.watcher().events(1000).stream()
                .filter(e -> "All".equals(sev) || e.severity() == Severity.valueOf(sev.toUpperCase(Locale.ROOT)))
                // the search finds the English text and the one on the screen
                .filter(e -> q.isEmpty() || (e.message() + " " + Ui.tr(e.message()) + " " + e.node() + " " + e.actor()
                        + " " + e.type())
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
    public void enter(Location location) {
        List<String> q = location.getQueryParameters().getParameters().get("q");
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
