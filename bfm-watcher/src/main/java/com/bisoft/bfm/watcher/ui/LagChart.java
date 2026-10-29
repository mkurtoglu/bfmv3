package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.bisoft.bfm.common.Lsn;
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
            box.add(Ui.text("pg-empty", "No samples yet: the chart fills in as the watcher polls " + node + "."));
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
            box.add(Ui.text("pg-chart-summary", "No replay lag in the " + window),
                    Ui.text("pg-empty", node + " kept up the whole time: replay lag stayed at 0 B (alert at "
                            + Lsn.bytes(alert) + ")."));
            return box;
        }
        box.add(Ui.text("pg-chart-summary", "Peak " + Lsn.bytes(peak.maxLagBytes()) + " at "
                + Ui.minute(peak.minuteMillis(), zone) + " · now " + Lsn.bytes(latest.maxLagBytes())
                + (peak.maxLagBytes() > alert ? " · above the alert at " + Lsn.bytes(alert) : "")));

        long top = niceCeiling(Math.max(peak.maxLagBytes(), alert) * 5 / 4);
        Div bars = Ui.div("pg-bars");
        bars.getElement().setAttribute("role", "img");
        bars.getElement().setAttribute("aria-label", "Replay lag of " + node + ", " + window + ": peak "
                + Lsn.bytes(peak.maxLagBytes()) + " at " + Ui.minute(peak.minuteMillis(), zone));
        for (LagHistory.Sample s : samples) {
            Div b = Ui.div("pg-bar");
            boolean over = s.maxLagBytes() > alert;
            if (over) {
                b.addClassName("over");
            }
            b.getStyle().set("height", Math.max(2, Math.round(s.maxLagBytes() * (double) HEIGHT_PX / top)) + "px");
            b.setTitle(Ui.minute(s.minuteMillis(), zone) + " · " + Lsn.bytes(s.maxLagBytes())
                    + (over ? " (above the alert)" : ""));
            bars.add(b);
        }
        long alertPx = Math.round(alert * (double) HEIGHT_PX / top);
        Div line = Ui.div("pg-threshold");
        line.getStyle().set("bottom", alertPx + "px");
        Div label = Ui.div("pg-threshold-label");
        label.setText("alert " + Lsn.bytes(alert));
        label.getStyle().set("bottom", alertPx + "px");
        bars.add(line, label);

        Div yaxis = Ui.div("pg-yaxis", Ui.text(null, Lsn.bytes(top)), Ui.text(null, Lsn.bytes(top / 2)),
                Ui.text(null, "0"));
        Div xaxis = Ui.div("pg-axis", Ui.text(null, Ui.minute(samples.get(0).minuteMillis(), zone)),
                Ui.text(null, Ui.minute(now, zone) + " " + Ui.zone(zone)));
        Div chart = Ui.div("pg-chart", yaxis, bars, xaxis);
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
