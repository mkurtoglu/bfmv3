package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.shared.Registration;
import com.bisoft.bfm.core.NotLeaderException;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.event.Severity;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateUnavailableException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Small building blocks shared by the views. */
final class Ui {

    enum Tone { OK, WARN, CRIT, INFO, NEUTRAL }

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Ui() {
    }

    static Div card(String title) {
        Div d = new Div();
        d.addClassName("pg-card");
        if (title != null) {
            d.add(new H2(title));
        }
        return d;
    }

    static Div div(String cls, Component... children) {
        Div d = new Div(children);
        d.addClassNames(cls.trim().split("\\s+"));
        return d;
    }

    static Span text(String cls, String text) {
        Span s = new Span(text == null ? "" : text);
        if (cls != null) {
            s.addClassNames(cls.trim().split("\\s+"));
        }
        return s;
    }

    static Span chip(String text, Tone tone) {
        Span s = new Span(text);
        s.addClassNames("pg-chip", tone.name().toLowerCase());
        return s;
    }

    static Tone tone(Severity s) {
        return switch (s) {
            case CRITICAL -> Tone.CRIT;
            case WARNING -> Tone.WARN;
            case INFO -> Tone.NEUTRAL;
        };
    }

    static Tone tone(PreCheck.Level l) {
        return switch (l) {
            case PASS -> Tone.OK;
            case WARN -> Tone.WARN;
            case FAIL -> Tone.CRIT;
        };
    }

    static Tone tone(OperationProgress.State s) {
        return switch (s) {
            case RUNNING -> Tone.INFO;
            case SUCCEEDED -> Tone.OK;
            case FAILED -> Tone.CRIT;
        };
    }

    /** A banner: its text, then any actions passed (links or buttons) at the end of the line. */
    static Div banner(String text, Tone tone, Component... actions) {
        Div d = new Div();
        d.addClassNames("pg-banner", tone.name().toLowerCase());
        d.add(new Span(text));
        if (actions.length > 0) {
            d.add(div("pg-banner-actions", actions));
        }
        if (tone == Tone.CRIT) {
            d.getElement().setAttribute("role", "alert");
        }
        return d;
    }

    static Div stat(String label, String value, String sub) {
        return stat(label, value, sub, null);
    }

    /** A stat tile; with a WARN or CRIT tone the whole tile turns that colour, so trouble shows without reading. */
    static Div stat(String label, String value, String sub, Tone tone) {
        Div d = card(null);
        d.add(text("pg-stat-label", label), text("pg-stat-value", value), text("pg-stat-sub", sub));
        if (tone == Tone.CRIT) {
            d.addClassName("pg-stat-crit");
        } else if (tone == Tone.WARN) {
            d.addClassName("pg-stat-warn");
        }
        return d;
    }

    /** How a configured environment is marked: production loud, test and staging amber, development blue. */
    static Tone envTone(String env) {
        String e = env == null ? "" : env.toLowerCase(Locale.ROOT);
        if (e.startsWith("prod")) {
            return Tone.CRIT;
        }
        if (e.startsWith("stag") || e.startsWith("test") || e.startsWith("uat") || e.startsWith("pre")) {
            return Tone.WARN;
        }
        if (e.startsWith("dev") || e.startsWith("lab") || e.startsWith("local")) {
            return Tone.INFO;
        }
        return Tone.NEUTRAL;
    }

    static Span env(String env) {
        Span s = text("pg-env " + envTone(env).name().toLowerCase(), env);
        s.getElement().setAttribute("title", "Environment");
        return s;
    }

    static Icon chevron(boolean open) {
        Icon i = VaadinIcon.ANGLE_RIGHT.create();
        i.addClassName("pg-chevron");
        if (open) {
            i.addClassName("open");
        }
        i.getElement().setAttribute("aria-hidden", "true");
        return i;
    }

    /**
     * Hides the given columns while the browser window is narrower than {@code minWidth}, so the columns that
     * matter stay on a phone screen instead of behind a horizontal scroll.
     */
    @SafeVarargs
    static <T> void hideBelow(Grid<T> grid, int minWidth, Grid.Column<T>... columns) {
        grid.addAttachListener(ev -> {
            UI ui = ev.getUI();
            List<Grid.Column<T>> cols = List.of(columns);
            ui.getPage().retrieveExtendedClientDetails(d -> cols.forEach(c -> c.setVisible(
                    d.getWindowInnerWidth() <= 0 || d.getWindowInnerWidth() >= minWidth)));
            Registration r = ui.getPage().addBrowserWindowResizeListener(e -> cols.forEach(c -> c.setVisible(
                    e.getWidth() >= minWidth)));
            Registration[] self = new Registration[1];
            self[0] = grid.addDetachListener(d -> {
                r.remove();
                self[0].remove();
            });
        });
    }

    /** Shows {@code wide} columns from {@code width} pixels up and {@code narrow} ones below it. */
    static <T> void switchAt(Grid<T> grid, int width, List<Grid.Column<T>> wide, List<Grid.Column<T>> narrow) {
        grid.addAttachListener(ev -> {
            UI ui = ev.getUI();
            java.util.function.IntConsumer apply = w -> {
                boolean isWide = w <= 0 || w >= width;
                wide.forEach(c -> c.setVisible(isWide));
                narrow.forEach(c -> c.setVisible(!isWide));
            };
            ui.getPage().retrieveExtendedClientDetails(d -> apply.accept(d.getWindowInnerWidth()));
            Registration r = ui.getPage().addBrowserWindowResizeListener(e -> apply.accept(e.getWidth()));
            Registration[] self = new Registration[1];
            self[0] = grid.addDetachListener(d -> {
                r.remove();
                self[0].remove();
            });
        });
    }

    /** Event and result texts write "a -> b"; the console shows a real arrow. */
    static String arrows(String text) {
        return text == null ? null : text.replace(" -> ", " → ");
    }

    /** A yes/no confirmation. {@code danger} makes the confirm button red. */
    static void confirm(String header, String text, String confirmLabel, boolean danger, Runnable action) {
        ConfirmDialog d = new ConfirmDialog();
        d.setHeader(header);
        d.setText(text);
        d.setCancelable(true);
        d.setConfirmText(confirmLabel);
        if (danger) {
            d.setConfirmButtonTheme("error primary");
        }
        d.addConfirmListener(e -> action.run());
        d.open();
    }

    /** A confirmation that only goes ahead once the operator has typed {@code expected}, for irreversible actions. */
    static void confirmTyped(String expected, String header, String text, String confirmLabel, boolean danger,
                             Runnable action) {
        ConfirmDialog d = new ConfirmDialog();
        d.setHeader(header);
        TextField field = new TextField("Type " + expected + " to confirm");
        field.setWidthFull();
        Span err = text("pg-field-error", "");
        err.getElement().setAttribute("role", "alert");
        d.setText(new Div(new Paragraph(text), field, err));
        d.setCancelable(true);
        d.setCloseOnEsc(true);
        d.setConfirmText(confirmLabel);
        if (danger) {
            d.setConfirmButtonTheme("error primary");
        }
        d.addConfirmListener(e -> {
            if (!expected.equals(field.getValue().trim())) {
                error("Type " + expected + " exactly; nothing was started");
                return;
            }
            action.run();
        });
        d.open();
    }

    /** The zone's short name for labels: "TRT", "CET", "UTC". */
    static String zone(ZoneId zone) {
        return zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** "14 s", "5 min", "2 h 10 min", "3 d 4 h" since {@code sinceMillis}; empty when unknown. */
    static String ago(long sinceMillis, long nowMillis) {
        if (sinceMillis <= 0) {
            return "";
        }
        return span(Math.max(0, nowMillis - sinceMillis));
    }

    /** A run time: "0.4 s", "12 s", "3 min 05 s", "1 h 02 min". */
    static String duration(Long ms) {
        if (ms == null) {
            return "—";
        }
        if (ms < 10_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        }
        return span(ms);
    }

    private static String span(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " min " + String.format(Locale.ROOT, "%02d", s % 60) + " s";
        }
        long h = m / 60;
        if (h < 24) {
            return h + " h " + String.format(Locale.ROOT, "%02d", m % 60) + " min";
        }
        return h / 24 + " d " + h % 24 + " h";
    }

    static String time(long millis, ZoneId zone) {
        if (millis <= 0) {
            return "—";
        }
        return HMS.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    static String minute(long millis, ZoneId zone) {
        return HM.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** "10:39:12" today, "Tue 10:39" earlier. */
    static String shortTime(long millis, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(zone);
        return t.toLocalDate().equals(LocalDate.now(zone)) ? HMS.format(t) : DAY.format(t);
    }

    static String full(long millis, ZoneId zone) {
        return FULL.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    static String seconds(Long ms) {
        if (ms == null) {
            return "—";
        }
        return ms < 10_000 ? String.format("%.1f s", ms / 1000.0) : (ms / 1000) + " s";
    }

    static Html logo() {
        return new Html("<div class=\"pg-logo\"><svg width=\"24\" height=\"24\" viewBox=\"0 0 30 30\" fill=\"none\""
                + " aria-hidden=\"true\"><circle cx=\"15\" cy=\"7\" r=\"4\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>"
                + "<circle cx=\"7\" cy=\"22\" r=\"4\" stroke=\"#FFFFFF\" stroke-width=\"2\"/><circle cx=\"23\""
                + " cy=\"22\" r=\"4\" stroke=\"#FFFFFF\" stroke-width=\"2\"/><path d=\"M13 10.5L9 18.5M17 10.5L21"
                + " 18.5\" stroke=\"#FFFFFF\" stroke-width=\"2\" stroke-linecap=\"round\"/></svg></div>");
    }

    static void ok(String message) {
        Notification n = Notification.show(message, 4000, Notification.Position.BOTTOM_END);
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    static void error(String message) {
        Notification n = Notification.show(message, 8000, Notification.Position.BOTTOM_END);
        n.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    /** Runs a command and shows its error, if any, the way an operator needs to read it. */
    static boolean attempt(Runnable r) {
        try {
            r.run();
            return true;
        } catch (NotLeaderException | StateConflictException | IllegalArgumentException | StateUnavailableException e) {
            error(e.getMessage());
        } catch (AgentCallException e) {
            error(e.getMessage());
        }
        return false;
    }
}
