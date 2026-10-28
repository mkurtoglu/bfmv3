package io.pgvip.watcher.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import io.pgvip.core.ops.OperationProgress;

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
            add(Ui.text("pg-note", "Choose an operation."));
            return;
        }
        H2 title = new H2(label(s.type()) + (s.target() == null ? "" : " · " + s.target()));
        add(Ui.div("pg-spread", title, Ui.chip(switch (s.state()) {
            case RUNNING -> "Running";
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed";
        }, Ui.tone(s.state()))));
        add(Ui.text("pg-note", "Started " + Ui.full(s.startedAtMillis(), zone) + " by " + s.startedBy()
                + (s.epochTo() > 0 ? " · epoch " + s.epochFrom() + " → " + s.epochTo() : "")
                + (s.finishedAtMillis() == null ? "" : " · finished " + Ui.time(s.finishedAtMillis(), zone))));
        if (s.result() != null && !s.running()) {
            add(Ui.banner(s.result(), s.state() == OperationProgress.State.SUCCEEDED ? Ui.Tone.OK : Ui.Tone.CRIT));
        }
        UnorderedList steps = new UnorderedList();
        steps.addClassName("pg-steps");
        for (OperationProgress.Step st : s.steps()) {
            ListItem li = new ListItem();
            li.addClassName(st.state().name());
            Span dot = new Span();
            dot.addClassNames("pg-dot", st.state().name());
            dot.getElement().setAttribute("aria-hidden", "true");
            Span name = new Span(st.title());
            name.getStyle().set("color", "var(--pg-ink)").set("font-weight", st.state()
                    == OperationProgress.StepState.RUNNING ? "700" : "500");
            li.add(dot, name, Ui.text("pg-note", switch (st.state()) {
                case PENDING -> "Pending";
                case RUNNING -> "Running";
                case DONE -> "Done";
                case FAILED -> "Failed";
                case SKIPPED -> "Skipped";
            }));
            if (st.detail() != null && !st.detail().isBlank()) {
                li.add(Ui.text("pg-step-detail", st.detail()));
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
            pre.addClassName("pg-log");
            add(pre);
        }
    }

    static String label(String type) {
        return switch (type) {
            case "FAILOVER" -> "Automatic failover";
            case "SWITCHOVER" -> "Switchover";
            case "START_PRIMARY" -> "Start as primary";
            case "REJOIN" -> "Rejoin";
            case "REINIT" -> "Re-initialize";
            case "FOLLOW" -> "Restart replica";
            default -> type;
        };
    }
}
