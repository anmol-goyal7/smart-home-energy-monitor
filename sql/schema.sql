-- Smart Home Energy Monitor — database schema
-- Target: MySQL 8.x
--
-- Load with:
--   mysql -u root -p < sql/schema.sql
--
-- Creates the database, the four core tables, and the indexes that back the
-- dashboard history queries and the Python analytics scans.

CREATE DATABASE IF NOT EXISTS smart_home_energy
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE smart_home_energy;

-- Drop in dependency order so the script is re-runnable during development.
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS thresholds;
DROP TABLE IF EXISTS readings;
DROP TABLE IF EXISTS devices;

-- ---------------------------------------------------------------------------
-- devices: the appliance catalogue. Relatively static reference data; one row
-- per monitored smart meter.
-- ---------------------------------------------------------------------------
CREATE TABLE devices (
    device_id          INT           NOT NULL AUTO_INCREMENT,
    name               VARCHAR(64)   NOT NULL,               -- human label, e.g. "Kitchen Refrigerator"
    appliance_type     VARCHAR(32)   NOT NULL,               -- category for grouping/analytics
    location           VARCHAR(64)   NOT NULL,               -- room or circuit
    rated_voltage      DECIMAL(6,2)  NOT NULL,               -- nominal operating voltage (V)
    rated_power_watts  DECIMAL(10,2) NOT NULL,               -- manufacturer power rating (W)
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (device_id),
    UNIQUE KEY uq_devices_name (name)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- readings: the high-volume time-series table. One row per meter reading.
-- reading_ts is the measurement time reported by the meter; received_at is the
-- server ingest time (the two differ by network/queue latency).
-- ---------------------------------------------------------------------------
CREATE TABLE readings (
    reading_id    BIGINT        NOT NULL AUTO_INCREMENT,
    device_id     INT           NOT NULL,
    reading_ts    DATETIME(3)   NOT NULL,                    -- meter-side timestamp (millis precision)
    voltage       DECIMAL(6,2)  NOT NULL,                    -- RMS volts
    current_amp   DECIMAL(6,2)  NOT NULL,                    -- RMS amperes
    power_watts   DECIMAL(10,2) NOT NULL,                    -- real power (W)
    received_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (reading_id),
    KEY idx_readings_device_ts (device_id, reading_ts),      -- history-by-device-and-window queries
    CONSTRAINT fk_readings_device
        FOREIGN KEY (device_id) REFERENCES devices (device_id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- thresholds: the limits the rule engine evaluates against. A NULL device_id is
-- a global default applied to any device without a specific override. min_value
-- and max_value are each optional so a row can bound one or both sides.
-- ---------------------------------------------------------------------------
CREATE TABLE thresholds (
    threshold_id  INT           NOT NULL AUTO_INCREMENT,
    device_id     INT           NULL,                        -- NULL => global default
    metric        ENUM('VOLTAGE','CURRENT','POWER') NOT NULL,
    min_value     DECIMAL(10,2) NULL,                        -- lower bound (sag/under)
    max_value     DECIMAL(10,2) NULL,                        -- upper bound (spike/overload)
    description   VARCHAR(128)  NULL,
    PRIMARY KEY (threshold_id),
    UNIQUE KEY uq_threshold_scope (device_id, metric),       -- one row per (device, metric)
    CONSTRAINT fk_thresholds_device
        FOREIGN KEY (device_id) REFERENCES devices (device_id)
        ON DELETE CASCADE
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- events: power-quality alerts raised by the rule engine. Each event links to
-- the device and, when known, to the reading that triggered it.
-- ---------------------------------------------------------------------------
CREATE TABLE events (
    event_id             BIGINT        NOT NULL AUTO_INCREMENT,
    device_id            INT           NOT NULL,
    triggering_reading_id BIGINT       NULL,                 -- the reading that tripped the rule
    event_type           ENUM('VOLTAGE_SPIKE','VOLTAGE_SAG','LOAD_OVERLOAD') NOT NULL,
    severity             ENUM('INFO','WARNING','CRITICAL') NOT NULL,
    measured_value       DECIMAL(10,2) NOT NULL,             -- the value observed
    threshold_value      DECIMAL(10,2) NOT NULL,             -- the limit that was crossed
    detail               VARCHAR(255)  NULL,
    detected_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    KEY idx_events_device_time (device_id, detected_at),
    KEY idx_events_recent (detected_at),                     -- "most recent alerts" query
    CONSTRAINT fk_events_device
        FOREIGN KEY (device_id) REFERENCES devices (device_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_events_reading
        FOREIGN KEY (triggering_reading_id) REFERENCES readings (reading_id)
        ON DELETE SET NULL
) ENGINE = InnoDB;
