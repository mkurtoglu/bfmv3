package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.watcher.service.Terms;

import java.time.ZoneId;

/** Step list, result and log of one operation. */
class OperationPanel extends Div {

    private final ZoneId zone;

    OperationPanel(ZoneId zone) {
        this.zone = zone;
        addClassName("pg-card");
    }

    void show(OperationProgress.Snapshot s) {
        removeAll();
        if (s == null) {
            add(Ui.text("pg-note", Ui.t("op.choose")));
            return;
        }
        H2 title = new H2(label(s.type()) + (s.target() == null ? "" : " · " + s.target()));
        add(Ui.div("pg-spread", title, Ui.chip(switch (s.state()) {
            case RUNNING -> Ui.t("op.running");
            case SUCCEEDED -> Ui.t("op.succeeded");
            case FAILED -> Ui.t("op.failed");
        }, Ui.tone(s.state()))));
        add(Ui.text("pg-note", Ui.t("op.startedBy", Ui.full(s.startedAtMillis(), zone) + " " + Ui.zone(zone),
                s.startedBy()) + (s.epochTo() > 0 ? " · " + Ui.t("op.epochs", s.epochFrom(), s.epochTo()) : "")
                + " · " + (s.finishedAtMillis() == null ? Ui.t("op.runningFor", Ui.ago(s.startedAtMillis(),
                System.currentTimeMillis())) : Ui.t("op.finished", Ui.time(s.finishedAtMillis(), zone),
                Ui.duration(s.durationMs())))));
        if (s.result() != null && !s.running()) {
            add(Ui.banner(Ui.arrows(Ui.tr(s.result())), s.state() == OperationProgress.State.SUCCEEDED ? Ui.Tone.OK
                    : Ui.Tone.CRIT));
        }
        UnorderedList steps = new UnorderedList();
        steps.addClassName("pg-steps");
        for (OperationProgress.Step st : s.steps()) {
            ListItem li = new ListItem();
            li.addClassName(st.state().name());
            Span dot = new Span();
            dot.addClassNames("pg-dot", st.state().name());
            dot.getElement().setAttribute("aria-hidden", "true");
            Span name = new Span(Ui.tr(st.title()));
            name.getStyle().set("color", "var(--pg-ink)").set("font-weight", st.state()
                    == OperationProgress.StepState.RUNNING ? "700" : "500");
            String took = st.durationMs() != null && st.state() != OperationProgress.StepState.SKIPPED
                    ? " · " + Ui.duration(st.durationMs())
                    : st.state() == OperationProgress.StepState.RUNNING && st.startedAtMillis() != null
                    ? " · " + Ui.ago(st.startedAtMillis(), System.currentTimeMillis()) : "";
            li.add(dot, name, Ui.text("pg-note", switch (st.state()) {
                case PENDING -> Ui.t("op.pending");
                case RUNNING -> Ui.t("op.running");
                case DONE -> Ui.t("op.done");
                case FAILED -> Ui.t("op.failed");
                case SKIPPED -> Ui.t("op.skipped");
            } + took));
            if (st.detail() != null && !st.detail().isBlank()) {
                li.add(Ui.text("pg-step-detail", Ui.tr(st.detail())));
            }
            steps.add(li);
        }
        add(steps);
        if (!s.log().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (OperationProgress.LogLine l : s.log()) {
                sb.append(Ui.time(l.timeMillis(), zone)).append(' ').append(l.source()).append(' ')
                        .append(l.text()).append('\n');
            }
            Pre pre = new Pre(sb.toString());
            pre.getElement().setAttribute("dir", "ltr"); // log lines stay as the programs wrote them
            pre.addClassName("pg-log");
            add(pre);
        }
    }

    static String label(String type) {
        return Ui.tr(Terms.operation(type));
    }
}
