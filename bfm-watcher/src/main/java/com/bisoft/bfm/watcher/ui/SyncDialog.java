package com.bisoft.bfm.watcher.ui;

import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.state.ClusterState;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * The popup a Sync checkbox opens: quorum or strict, and how many of the synchronous replicas a commit waits for.
 * PostgreSQL has one {@code synchronous_standby_names} on the primary, so the choice holds for every synchronous
 * replica; the popup says so.
 */
final class SyncDialog {

    /** What the operator chose. */
    record Choice(String mode, int count, List<String> standbys) {
    }

    private SyncDialog() {
    }

    /**
     * @param node      the node whose box was checked, or null when the mode is changed from Cluster controls
     * @param standbys  the synchronous nodes after this change
     * @param primary   the primary now (a commit on it cannot wait for itself)
     * @param now       how commits wait now (the popup starts from it)
     * @param apply     called with the choice when the operator confirms
     */
    static void open(String node, List<String> standbys, String primary, ClusterState.Replication now,
                     java.util.function.Consumer<Choice> apply) {
        List<String> sorted = standbys.stream().distinct().sorted().toList();
        List<String> replicas = sorted.stream().filter(n -> !n.equals(primary)).toList();
        if (replicas.isEmpty()) {
            Ui.error(Ui.t("sd.noReplica", primary));
            return;
        }
        String list = String.join(", ", replicas);
        ConfirmDialog d = new ConfirmDialog();
        d.setHeader(node == null ? Ui.t("sd.changeTitle") : Ui.t("sd.title", node));
        d.setCancelable(true);
        d.setCloseOnEsc(true);

        Select<Integer> count = new Select<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 1; i <= replicas.size(); i++) {
            counts.add(i);
        }
        count.setItems(counts);
        count.setLabel(Ui.t("sd.waitFor"));
        count.setItemLabelGenerator(n -> n == replicas.size() && n > 1 ? Ui.t("sd.all", n)
                : Ui.t("sd.anyOf", n, replicas.size()));
        count.setValue(Math.min(Math.max(1, now.count()), replicas.size()));
        count.setVisible(replicas.size() > 1);

        RadioButtonGroup<String> mode = new RadioButtonGroup<>();
        mode.setLabel(Ui.t("sd.how"));
        mode.setItems("quorum", "strict");
        mode.setRenderer(new ComponentRenderer<>(m -> {
            Span title = new Span(Ui.t("sd." + m));
            title.getStyle().set("font-weight", "800").set("color", "var(--pg-ink)");
            Span text = Ui.text("pg-note", Ui.t("sd." + m + "Text", waitText(count.getValue(), replicas.size()), list));
            Div box = new Div(title, text);
            box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "2px")
                    .set("padding-block", "4px");
            return box;
        }));
        mode.setValue(now.synchronous() ? now.mode() : "quorum");
        mode.getStyle().set("width", "100%");

        Div warning = new Div();
        Span setting = Ui.text("pg-mono", "");
        setting.getStyle().set("overflow-wrap", "anywhere");
        Div preview = new Div(Ui.text("pg-stat-label", Ui.t("sd.writes")), setting);
        preview.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "4px")
                .set("padding", "10px 12px").set("border-radius", "10px").set("background", "var(--pg-surface-2)")
                .set("border", "1px solid var(--pg-border)");

        Runnable update = () -> {
            boolean strict = "strict".equals(mode.getValue());
            int n = count.getValue() == null ? 1 : count.getValue();
            warning.removeAll();
            if (strict) {
                warning.add(Ui.banner(Ui.t("sd.warnStrict", list, primary, n), Ui.Tone.CRIT));
            } else if (n == replicas.size() && n > 1) {
                warning.add(Ui.banner(Ui.t("sd.warnQuorum", list, n), Ui.Tone.WARN));
            }
            setting.setText("synchronous_standby_names = '"
                    + Watcher.syncStandbysFor(new ClusterState.Replication(strict ? "strict" : "quorum", n, false,
                    sorted)) + "'");
            d.setConfirmText(Ui.t(strict ? "sd.confirmStrict" : "sd.confirmQuorum"));
            d.setConfirmButtonTheme(strict ? "error primary" : "primary");
        };
        mode.addValueChangeListener(e -> update.run());
        count.addValueChangeListener(e -> {
            mode.getListDataView().refreshAll(); // the descriptions name the count
            update.run();
        });
        update.run();

        Div body = new Div(Ui.text("pg-note", Ui.t("sd.after", String.join(", ", sorted))), mode, count, warning,
                preview, Ui.text("pg-note", Ui.t("sd.note")));
        body.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "12px")
                .set("max-width", "560px");
        d.setText(body);
        d.addConfirmListener(e -> apply.accept(new Choice(mode.getValue(),
                count.getValue() == null ? 1 : count.getValue(), sorted)));
        d.open();
    }

    /** "ANY 1", or "all 2" when a commit waits for every synchronous replica. */
    private static String waitText(Integer n, int replicas) {
        int c = n == null ? 1 : n;
        return c == replicas && c > 1 ? Ui.t("sd.allShort", c) : "ANY " + c;
    }
}
