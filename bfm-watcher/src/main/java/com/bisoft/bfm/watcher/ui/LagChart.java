package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.bisoft.bfm.core.monitor.LagHistory;

import java.time.ZoneId;
import java.util.List;

/**
 * Replay lag of one replica over time: a summary line that answers the question first (what was the worst, and
 * when), then the bars with a labelled scale, the alert line and a tooltip per minute. A replica that kept up the
 * whole time gets a sentence instead of an empty chart.
 */
final class LagChart {

    static final int HEIGHT_PX = 140;

    private LagChart() {
    }

    static Component render(List<LagHistory.Sample> samples, long alert, String node, String window, ZoneId zone,
                            long now) {
        Div box = new Div();
        box.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");
        if (samples.isEmpty()) {
            box.add(Ui.text("pg-empty", Ui.t("lag.noSamples", node)));
            return box;
        }
        LagHistory.Sample peak = samples.get(0);
        for (LagHistory.Sample s : samples) {
            if (s.maxLagBytes() > peak.maxLagBytes()) {
                peak = s;
            }
        }
        LagHistory.Sample latest = samples.get(samples.size() - 1);
        if (peak.maxLagBytes() == 0) {
            box.add(Ui.text("pg-chart-summary", Ui.t("lag.none", window)),
                    Ui.text("pg-empty", Ui.t("lag.keptUp", node, Ui.size(alert))));
            return box;
        }
        box.add(Ui.text("pg-chart-summary", Ui.t("lag.peak", Ui.size(peak.maxLagBytes()),
                Ui.minute(peak.minuteMillis(), zone), Ui.size(latest.maxLagBytes()))
                + (peak.maxLagBytes() > alert ? " · " + Ui.t("lag.aboveAlert", Ui.size(alert)) : "")));

        long top = niceCeiling(Math.max(peak.maxLagBytes(), alert) * 5 / 4);
        Div bars = Ui.div("pg-bars");
        bars.getElement().setAttribute("role", "img");
        bars.getElement().setAttribute("aria-label", Ui.t("lag.aria", node, window, Ui.size(peak.maxLagBytes()),
                Ui.minute(peak.minuteMillis(), zone)));
        for (LagHistory.Sample s : samples) {
            Div b = Ui.div("pg-bar");
            boolean over = s.maxLagBytes() > alert;
            if (over) {
                b.addClassName("over");
            }
            b.getStyle().set("height", Math.max(2, Math.round(s.maxLagBytes() * (double) HEIGHT_PX / top)) + "px");
            b.setTitle(Ui.minute(s.minuteMillis(), zone) + " · " + Ui.size(s.maxLagBytes())
                    + (over ? " " + Ui.t("lag.overShort") : ""));
            bars.add(b);
        }
        long alertPx = Math.round(alert * (double) HEIGHT_PX / top);
        Div line = Ui.div("pg-threshold");
        line.getStyle().set("bottom", alertPx + "px");
        Div label = Ui.div("pg-threshold-label");
        label.setText(Ui.t("lag.alert", Ui.size(alert)));
        label.getStyle().set("bottom", alertPx + "px");
        bars.add(line, label);

        Div yaxis = Ui.div("pg-yaxis", Ui.text(null, Ui.size(top)), Ui.text(null, Ui.size(top / 2)),
                Ui.text(null, "0"));
        Div xaxis = Ui.div("pg-axis", Ui.text(null, Ui.minute(samples.get(0).minuteMillis(), zone)),
                Ui.text(null, Ui.minute(now, zone) + " " + Ui.zone(zone)));
        Div chart = Ui.div("pg-chart", yaxis, bars, xaxis);
        // time runs left to right in every language, right-to-left ones included
        chart.getElement().setAttribute("dir", "ltr");
        box.add(chart);
        return box;
    }

    /** The next 1, 2 or 5 times a power of ten in the largest unit that keeps the number below 1024. */
    static long niceCeiling(long bytes) {
        if (bytes <= 1) {
            return 1;
        }
        long unit = 1;
        while (bytes / unit >= 1024) {
            unit *= 1024;
        }
        double v = (double) bytes / unit;
        double pow = Math.pow(10, Math.floor(Math.log10(v)));
        double nice;
        double f = v / pow;
        if (f <= 1) {
            nice = 1;
        } else if (f <= 2) {
            nice = 2;
        } else if (f <= 5) {
            nice = 5;
        } else {
            nice = 10;
        }
        if (nice * pow >= 1000) {
            return 1024 * unit; // "1 MB" reads better than "1000 KB"
        }
        return Math.round(nice * pow * unit);
    }
}
