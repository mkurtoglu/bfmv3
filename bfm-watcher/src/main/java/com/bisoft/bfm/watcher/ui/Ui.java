package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
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

/** Small building blocks shared by the views. */
final class Ui {

    enum Tone { OK, WARN, CRIT, INFO, NEUTRAL }

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE HH:mm");
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
        d.addClassName(cls);
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

    static Div banner(String text, Tone tone) {
        Div d = new Div();
        d.addClassNames("pg-banner", tone.name().toLowerCase());
        d.setText(text);
        return d;
    }

    static Div stat(String label, String value, String sub) {
        Div d = card(null);
        d.add(text("pg-stat-label", label), text("pg-stat-value", value), text("pg-stat-sub", sub));
        return d;
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

    /** "10:39" today, "Tue 10:39" earlier. */
    static String shortTime(long millis, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(zone);
        return t.toLocalDate().equals(LocalDate.now(zone)) ? HM.format(t) : DAY.format(t);
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
