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
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.Severity;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.service.ConsoleService;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** The event and audit log shared by every watcher. */
@Route(value = "events", layout = MainLayout.class)
@PageTitle("Events · BFM")
@PermitAll
public class EventsView extends Div {

    private final ConsoleService console;
    private final Grid<ClusterEvent> grid = new Grid<>();
    private final Select<String> severity = new Select<>();
    private final TextField search = new TextField();
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
        grid.setHeight("70vh");
        grid.addColumn(e -> Ui.full(e.timeMillis(), zone)).setHeader("Time").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(e -> Ui.chip(switch (e.severity()) {
            case CRITICAL -> "Critical";
            case WARNING -> "Warning";
            case INFO -> "Info";
        }, Ui.tone(e.severity()))).setHeader("Severity").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(e -> e.node() == null ? "—" : e.node()).setHeader("Node").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(ClusterEvent::message).setHeader("Event").setFlexGrow(1);
        grid.addColumn(ClusterEvent::actor).setHeader("By").setAutoWidth(true).setFlexGrow(0);
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

    private void refresh() {
        String sev = severity.getValue();
        String q = search.getValue() == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        List<ClusterEvent> list = console.watcher().events(1000).stream()
                .filter(e -> "All".equals(sev) || e.severity() == Severity.valueOf(sev.toUpperCase(Locale.ROOT)))
                .filter(e -> q.isEmpty() || (e.message() + " " + e.node() + " " + e.actor() + " " + e.type())
                        .toLowerCase(Locale.ROOT).contains(q))
                .toList();
        grid.setItems(list);
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
