-- Smart Home Energy Monitor — seed data
-- Target: MySQL 8.x
--
-- Load AFTER schema.sql with:
--   mysql -u root -p smart_home_energy < sql/seed.sql
--
-- Seeds a small fleet of appliances and the detection thresholds the rule engine
-- evaluates against. The simulator's appliance profiles are expected to match
-- these device_ids.

USE smart_home_energy;

-- Idempotent reseed of reference data.
DELETE FROM thresholds;
DELETE FROM devices;
ALTER TABLE devices AUTO_INCREMENT = 1;
ALTER TABLE thresholds AUTO_INCREMENT = 1;

-- ---------------------------------------------------------------------------
-- Appliances. rated_voltage is the nominal supply (230 V single phase here);
-- rated_power_watts is the manufacturer rating the overload threshold builds on.
-- ---------------------------------------------------------------------------
INSERT INTO devices (device_id, name, appliance_type, location, rated_voltage, rated_power_watts) VALUES
    (1, 'Kitchen Refrigerator', 'REFRIGERATOR', 'Kitchen',     230.00,  350.00),
    (2, 'Living Room HVAC',     'HVAC',         'Living Room', 230.00, 2000.00),
    (3, 'Washing Machine',      'WASHER',       'Utility',     230.00, 2200.00),
    (4, 'Water Heater',         'HEATER',       'Bathroom',    230.00, 3000.00),
    (5, 'Home Office Desktop',  'COMPUTER',     'Office',      230.00,  450.00),
    (6, 'Network Router',       'NETWORKING',   'Office',      230.00,   18.00);

-- ---------------------------------------------------------------------------
-- Global default thresholds (device_id NULL): apply to any device without an
-- override. Voltage band is +/- ~10% around the 230 V nominal supply.
-- ---------------------------------------------------------------------------
INSERT INTO thresholds (device_id, metric, min_value, max_value, description) VALUES
    (NULL, 'VOLTAGE', 207.00, 253.00, 'Default supply band: sag below 207 V, spike above 253 V');

-- ---------------------------------------------------------------------------
-- Per-device POWER thresholds: overload = rated power plus a start-up tolerance.
-- (HVAC and the washer/heater draw large transient surges, so their headroom is
-- larger.)
-- ---------------------------------------------------------------------------
INSERT INTO thresholds (device_id, metric, min_value, max_value, description) VALUES
    (1, 'POWER', NULL,  500.00, 'Refrigerator overload ceiling'),
    (2, 'POWER', NULL, 2600.00, 'HVAC overload ceiling (incl. compressor start surge)'),
    (3, 'POWER', NULL, 2600.00, 'Washing machine overload ceiling'),
    (4, 'POWER', NULL, 3300.00, 'Water heater overload ceiling'),
    (5, 'POWER', NULL,  600.00, 'Desktop overload ceiling'),
    (6, 'POWER', NULL,   40.00, 'Router overload ceiling');
