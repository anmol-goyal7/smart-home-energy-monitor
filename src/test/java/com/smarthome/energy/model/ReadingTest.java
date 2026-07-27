package com.smarthome.energy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link Reading}. These need no database and run on every build.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class ReadingTest {

    private static final Instant TS = Instant.ofEpochMilli(1_721_817_600_000L);

    @Test
    @DisplayName("accessors return what the constructor was given")
    void accessorsReturnConstructorArguments() {
        Reading r = new Reading(3, TS, 228.40, 4.10, 998.20);

        assertAll(
                () -> assertEquals(3, r.getDeviceId()),
                () -> assertEquals(TS, r.getReadingTimestamp()),
                () -> assertEquals(228.40, r.getVoltage()),
                () -> assertEquals(4.10, r.getCurrent()),
                () -> assertEquals(998.20, r.getPowerWatts()));
    }

    @Test
    @DisplayName("fromEpochMillis and getReadingEpochMillis round-trip the wire timestamp")
    void epochMillisRoundTrips() {
        long millis = TS.toEpochMilli();
        Reading r = Reading.fromEpochMillis(3, millis, 228.40, 4.10, 998.20);

        assertEquals(millis, r.getReadingEpochMillis());
        assertEquals(TS, r.getReadingTimestamp());
    }

    @Test
    @DisplayName("valueOf selects the metric the threshold bounds")
    void valueOfSelectsTheRightMetric() {
        Reading r = new Reading(3, TS, 228.40, 4.10, 998.20);

        assertAll(
                () -> assertEquals(228.40, r.valueOf(Metric.VOLTAGE)),
                () -> assertEquals(4.10, r.valueOf(Metric.CURRENT)),
                () -> assertEquals(998.20, r.valueOf(Metric.POWER)));
    }

    @Test
    @DisplayName("a non-positive device id is rejected at construction")
    void rejectsNonPositiveDeviceId() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Reading(0, TS, 230, 1, 230)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Reading(-1, TS, 230, 1, 230)));
    }

    @Test
    @DisplayName("a null timestamp is rejected at construction")
    void rejectsNullTimestamp() {
        assertThrows(NullPointerException.class, () -> new Reading(1, null, 230, 1, 230));
    }

    @Test
    @DisplayName("equal field values make equal readings with equal hash codes")
    void valueEquality() {
        Reading a = new Reading(3, TS, 228.40, 4.10, 998.20);
        Reading b = new Reading(3, TS, 228.40, 4.10, 998.20);
        Reading different = new Reading(3, TS, 228.41, 4.10, 998.20);

        assertAll(
                () -> assertEquals(a, b),
                () -> assertEquals(a.hashCode(), b.hashCode()),
                () -> assertNotEquals(a, different));
    }
}
