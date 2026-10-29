package com.bisoft.bfm.watcher.ui;

import com.bisoft.bfm.watcher.service.Terms;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The small pieces of wording and arithmetic the console relies on. */
class ConsoleTextTest {

    @Test
    void sizesAreReadTheWayOperatorsWriteThem() {
        assertEquals(512L * 1024, SizeText.parse("512 KB"));
        assertEquals(16L * 1024 * 1024, SizeText.parse("16MB"));
        assertEquals(1536L * 1024 * 1024, SizeText.parse("1.5 GB"));
        assertEquals(1536L * 1024 * 1024, SizeText.parse("1,5 gb"));
        assertEquals(2L * 1024 * 1024, SizeText.parse("2 MiB"));
        assertEquals("Add a unit: KB, MB or GB",
                assertThrows(IllegalArgumentException.class, () -> SizeText.parse("512")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> SizeText.parse("lots"));
        assertThrows(IllegalArgumentException.class, () -> SizeText.parse("0 KB"));
    }

    @Test
    void chartScaleIsARoundNumber() {
        assertEquals(1024L, LagChart.niceCeiling(700));
        assertEquals(2L * 1024 * 1024, LagChart.niceCeiling(1100L * 1024));
        assertEquals(1024L * 1024, LagChart.niceCeiling(900L * 1024));
        assertEquals(20L * 1024 * 1024, LagChart.niceCeiling(16L * 1024 * 1024 * 5 / 4));
        assertEquals(500L * 1024, LagChart.niceCeiling(300L * 1024));
    }

    @Test
    void durationsReadNaturally() {
        assertEquals("0.4 s", Ui.duration(400L));
        assertEquals("12 s", Ui.duration(12_400L));
        assertEquals("3 min 05 s", Ui.duration(185_000L));
        assertEquals("1 h 02 min", Ui.duration(3_720_000L));
        assertEquals("—", Ui.duration(null));
        assertEquals("14 s", Ui.ago(1_000, 15_000));
        assertEquals("", Ui.ago(0, 15_000));
        assertEquals("UTC", Ui.zone(ZoneId.of("UTC")));
    }

    @Test
    void oneNamePerOperation() {
        assertEquals("Re-initialize", Terms.operation("REINIT"));
        assertEquals("Re-initialize", Terms.operation("reinit"));
        assertEquals("Rejoin", Terms.operation("demote"));
        assertEquals("Automatic failover", Terms.operation("FAILOVER"));
        assertEquals("Streaming", Terms.capitalize("streaming"));
        assertEquals("Admin", Terms.role(true));
        assertEquals("Viewer", Terms.role(false));
    }

    @Test
    void environmentsAreMarkedByHowCarefulOneMustBe() {
        assertEquals(Ui.Tone.CRIT, Ui.envTone("production"));
        assertEquals(Ui.Tone.CRIT, Ui.envTone("Prod"));
        assertEquals(Ui.Tone.WARN, Ui.envTone("staging"));
        assertEquals(Ui.Tone.INFO, Ui.envTone("lab"));
        assertEquals(Ui.Tone.NEUTRAL, Ui.envTone("dr-site"));
    }
}
