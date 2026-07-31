package com.smarthome.energy.client.view;

import com.smarthome.energy.client.model.ApplianceState;
import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the strip chart's arithmetic — the part that can be wrong without looking wrong.
 *
 * <p>Painting is not tested here; a pixel comparison would break on every font change and
 * prove nothing about the number being drawn. What is tested is what the panel believes the
 * house is drawing, because that is the claim on screen.</p>
 *
 * <p>These construct Swing components without showing them, which is safe on a headless
 * machine: a {@code JPanel} that is never realised needs no display.</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
class LiveChartPanelTest {

    @Test
    @DisplayName("a sample totals the latest power of every reporting appliance")
    void sumsTheFleet() {
        LiveChartPanel panel = new LiveChartPanel();
        panel.sample(List.of(
                applianceDrawing(1, 100.0),
                applianceDrawing(2, 250.5),
                applianceDrawing(3, 49.5)));

        assertEquals(400.0, panel.getLatestWatts(), 0.001);
        assertEquals(1, panel.getSampleCount());
    }

    @Test
    @DisplayName("a stale appliance contributes nothing to the total")
    void excludesStaleAppliances() {
        ApplianceState appliance = applianceDrawing(2, 920.0);
        Instant reportedAt = appliance.getLastUpdatedAt();

        LiveChartPanel whileFresh = new LiveChartPanel();
        whileFresh.sample(List.of(appliance), reportedAt.plusSeconds(1));
        assertEquals(920.0, whileFresh.getLatestWatts(), 0.001,
                "a meter that reported a second ago is part of the house's load");

        LiveChartPanel onceStale = new LiveChartPanel();
        onceStale.sample(List.of(appliance),
                reportedAt.plus(ApplianceState.STALE_AFTER).plusSeconds(1));
        assertEquals(0.0, onceStale.getLatestWatts(), 0.001,
                "a meter nobody has heard from is not evidence about the load now — leaving it "
                        + "in would show the house drawing steady power from a dead appliance");
    }

    @Test
    @DisplayName("an appliance that has never reported is not counted")
    void ignoresAppliancesWithNoReading() {
        LiveChartPanel panel = new LiveChartPanel();
        panel.sample(List.of(new ApplianceState(1, "Never Reported")));

        assertEquals(0.0, panel.getLatestWatts(), 0.001);
        assertEquals(1, panel.getSampleCount(), "the sample was still taken; it was just zero");
    }

    @Test
    @DisplayName("the ring holds a fixed window however long the dashboard stays open")
    void ringIsBounded() {
        LiveChartPanel panel = new LiveChartPanel();
        for (int i = 0; i < LiveChartPanel.WINDOW_SECONDS * 3; i++) {
            panel.sample(List.of(applianceDrawing(1, i)));
        }

        assertEquals(LiveChartPanel.WINDOW_SECONDS, panel.getSampleCount());
        assertEquals(LiveChartPanel.WINDOW_SECONDS * 3 - 1, panel.getLatestWatts(), 0.001,
                "the newest sample should be the last one taken");
    }

    @Test
    @DisplayName("clearing forgets everything, including the peak")
    void clearResetsTheWindow() {
        LiveChartPanel panel = new LiveChartPanel();
        panel.sample(List.of(applianceDrawing(1, 5000.0)));
        panel.clear();

        assertEquals(0, panel.getSampleCount());
        assertEquals(0.0, panel.getLatestWatts(), 0.001);
    }

    @Test
    @DisplayName("sampling a null fleet is refused rather than charted as zero")
    void refusesNull() {
        assertThrows(NullPointerException.class, () -> new LiveChartPanel().sample(null));
    }

    /** An appliance whose latest reading draws the given power, applied just now. */
    private static ApplianceState applianceDrawing(int deviceId, double watts) {
        ApplianceState state = new ApplianceState(deviceId, "Device " + deviceId);
        state.apply(new Reading(deviceId, Instant.now(), 230.0, watts / 230.0, watts));
        return state;
    }
}
