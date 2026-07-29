package com.smarthome.energy.client.model;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the MVC model: that it notifies its listeners, keeps its appliances in device order
 * whether or not a catalogue was loaded, and refuses to be mutated off the event dispatch
 * thread.
 *
 * <p>These run headless — the model touches only {@code SwingUtilities.isEventDispatchThread},
 * never a component — so the suite stays green on a build machine without a display.</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
class DashboardModelTest {

    private static Reading reading(int deviceId, double watts) {
        return new Reading(deviceId, Instant.ofEpochMilli(1_721_817_600_000L), 230.0,
                watts / 230.0, watts);
    }

    /** Runs an action on the EDT and rethrows whatever it threw, so assertions still report. */
    private static void onEventDispatchThread(Runnable action) throws Exception {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    @Test
    @DisplayName("mutating the model off the event dispatch thread fails immediately")
    void refusesMutationOffTheEventDispatchThread() {
        DashboardModel model = new DashboardModel();
        assertFalse(SwingUtilities.isEventDispatchThread(), "the test itself must not be on the EDT");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> model.applyReading(reading(1, 100)));
        assertTrue(thrown.getMessage().contains("event dispatch thread"), thrown.getMessage());

        assertThrows(IllegalStateException.class, () -> model.setStatus("nope"));
        assertThrows(IllegalStateException.class, () -> model.setConnected(true, "nope"));
        assertThrows(IllegalStateException.class, () -> model.setEvents(List.of()));
        assertThrows(IllegalStateException.class, () -> model.setHistory(1, List.of()));
    }

    @Test
    @DisplayName("a reading creates its appliance and updates it thereafter")
    void createsThenUpdatesAnAppliance() throws Exception {
        DashboardModel model = new DashboardModel();
        List<String> notifications = new ArrayList<>();
        model.addListener(new DashboardModel.Listener() {
            @Override
            public void applianceUpdated(ApplianceState state) {
                notifications.add("updated:" + state.getDeviceId());
            }

            @Override
            public void appliancesReset(List<ApplianceState> appliances) {
                notifications.add("reset:" + appliances.size());
            }
        });

        onEventDispatchThread(() -> {
            model.applyReading(reading(2, 100));
            model.applyReading(reading(2, 120));
        });

        assertEquals(List.of("reset:1", "updated:2"), notifications);
        assertEquals(2, model.getReadingsReceived());

        ApplianceState state = model.getAppliance(2);
        assertEquals(120.0, state.getLatest().getPowerWatts(), 1e-9);
        assertEquals(2, state.getReadingCount());
        assertEquals(120.0, state.getPeakPower(), 1e-9);
    }

    @Test
    @DisplayName("appliances stay in device-id order whatever order their readings arrive in")
    void ordersAppliancesByDeviceId() throws Exception {
        DashboardModel model = new DashboardModel();

        onEventDispatchThread(() -> {
            model.applyReading(reading(5, 10));
            model.applyReading(reading(1, 10));
            model.applyReading(reading(3, 10));
        });

        assertEquals(List.of(1, 3, 5),
                model.getAppliances().stream().map(ApplianceState::getDeviceId).toList());
    }

    @Test
    @DisplayName("the catalogue names appliances without discarding the readings already seen")
    void catalogueRenamesWithoutLosingState() throws Exception {
        DashboardModel model = new DashboardModel();

        onEventDispatchThread(() -> {
            model.applyReading(reading(1, 180));
            assertEquals("Device 1", model.getAppliance(1).getName());

            model.setCatalogue(List.of(
                    new Device(1, "Kitchen Refrigerator", "REFRIGERATOR", "Kitchen", 230, 350),
                    new Device(2, "Living Room HVAC", "HVAC", "Living Room", 230, 2000)));
        });

        assertEquals("Kitchen Refrigerator", model.getAppliance(1).getName());
        assertEquals(1, model.getAppliance(1).getReadingCount());
        assertEquals(2, model.getAppliances().size());
        // Device 2 is in the catalogue but has not reported, so it has a tile and no data.
        assertEquals(0, model.getAppliance(2).getReadingCount());
    }

    @Test
    @DisplayName("a reading for the wrong device is a programming error, not silent corruption")
    void refusesAReadingForAnotherDevice() {
        ApplianceState state = new ApplianceState(1, "Kitchen Refrigerator");
        assertThrows(IllegalArgumentException.class, () -> state.apply(reading(2, 100)));
    }

    @Test
    @DisplayName("the sparkline history is capped rather than growing all night")
    void boundsTheSparklineHistory() {
        ApplianceState state = new ApplianceState(1, "Kitchen Refrigerator");
        for (int i = 0; i < ApplianceState.HISTORY_SAMPLES * 3; i++) {
            state.apply(reading(1, i));
        }

        double[] samples = state.getRecentPower();
        assertEquals(ApplianceState.HISTORY_SAMPLES, samples.length);
        // The window keeps the newest samples, so it ends on the last one applied.
        assertEquals(ApplianceState.HISTORY_SAMPLES * 3 - 1, samples[samples.length - 1], 1e-9);
    }

    @Test
    @DisplayName("an appliance with no readings is stale, and a fresh one is not")
    void reportsStaleness() {
        ApplianceState state = new ApplianceState(1, "Kitchen Refrigerator");
        assertTrue(state.isStale(Instant.now()));

        state.apply(reading(1, 100));
        assertFalse(state.isStale(Instant.now()));
        assertTrue(state.isStale(Instant.now().plus(ApplianceState.STALE_AFTER).plusSeconds(1)));
    }
}
