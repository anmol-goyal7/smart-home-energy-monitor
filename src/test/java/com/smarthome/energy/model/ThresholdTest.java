package com.smarthome.energy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Threshold}, focused on the null-bound handling the rule engine
 * depends on. These need no database and run on every build.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class ThresholdTest {

    @Test
    @DisplayName("the seeded voltage band flags a sag below min and a spike above max")
    void voltageBandDetectsBothSides() {
        Threshold band = new Threshold(null, Metric.VOLTAGE, 207.00, 253.00, "default supply band");

        assertAll(
                () -> assertTrue(band.isBelowMin(198.00), "198 V is a sag"),
                () -> assertTrue(band.isAboveMax(262.00), "262 V is a spike"),
                () -> assertFalse(band.isBelowMin(230.00), "230 V is nominal"),
                () -> assertFalse(band.isAboveMax(230.00), "230 V is nominal"));
    }

    @Test
    @DisplayName("an absent bound cannot be violated")
    void absentBoundNeverFires() {
        // The seeded POWER rows set a ceiling and no floor.
        Threshold ceilingOnly = new Threshold(1, Metric.POWER, null, 500.00, "refrigerator ceiling");

        assertAll(
                () -> assertFalse(ceilingOnly.hasMin()),
                () -> assertTrue(ceilingOnly.hasMax()),
                () -> assertFalse(ceilingOnly.isBelowMin(0.00), "no floor, so nothing is below it"),
                () -> assertTrue(ceilingOnly.isAboveMax(540.00)));
    }

    @Test
    @DisplayName("a null device id marks the global default")
    void nullDeviceIdIsTheGlobalDefault() {
        assertTrue(new Threshold(null, Metric.VOLTAGE, 207.00, 253.00, null).isGlobalDefault());
        assertFalse(new Threshold(1, Metric.POWER, null, 500.00, null).isGlobalDefault());
    }

    @Test
    @DisplayName("a threshold bounding neither side is rejected")
    void rejectsThresholdWithNoBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Threshold(1, Metric.POWER, null, null, "bounds nothing"));
    }

    @Test
    @DisplayName("an inverted band is rejected")
    void rejectsMinAboveMax() {
        assertThrows(IllegalArgumentException.class,
                () -> new Threshold(null, Metric.VOLTAGE, 253.00, 207.00, "inverted"));
    }
}
