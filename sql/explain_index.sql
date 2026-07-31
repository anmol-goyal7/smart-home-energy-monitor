-- Smart Home Energy Monitor — Evidence 3: what the (device_id, reading_ts) index buys
-- Target: MySQL 8.x
--
-- Run against a database with a realistic amount of history in it:
--
--   docker compose exec -T mysql mysql -u energy_app -pchange_me smart_home_energy \
--       < sql/explain_index.sql
--
-- The query planned below is the dashboard's history query, exactly as ReadingDao issues it:
-- one device, from an instant onwards, in time order. This script plans it with the composite
-- index present, then without it, then puts everything back.
--
-- ---------------------------------------------------------------------------
-- Two things this script had to be written around, both worth knowing
-- ---------------------------------------------------------------------------
--
-- 1. The index cannot simply be dropped. fk_readings_device is a foreign key on device_id,
--    and InnoDB requires an index on the referencing column; idx_readings_device_ts is
--    currently the index satisfying that requirement, so DROP INDEX fails with error 1553.
--    A single-column index on device_id is therefore created first and removed at the end.
--    That makes the comparison a fair one rather than an artificial one: the "without" case
--    is not "no index at all", which the schema would never permit, but "only the index the
--    foreign key forces us to have anyway". The difference measured is exactly what the
--    second column of the composite index is worth.
--
-- 2. The window matters, and an all-time window measures nothing. With
--    "reading_ts BETWEEN '2000-01-01' AND '2100-01-01'" the predicate selects every row the
--    device has, the optimiser correctly works out that scanning the table beats seeking
--    ~18,000 index entries and fetching each row, and both plans come out as full scans. That
--    is not a failure of the index; it is the optimiser being right. The dashboard's default
--    window is 15 minutes, so that is what is planned here.
--
-- The index is restored at the end. If this script is interrupted part way through, re-run
-- it: the final state is always "the composite index exists and the temporary one does not".

USE smart_home_energy;

SELECT '--- how much history is being planned over ---' AS step;
SELECT COUNT(*) AS total_readings,
       COUNT(DISTINCT device_id) AS devices,
       MIN(reading_ts) AS earliest,
       MAX(reading_ts) AS latest
  FROM readings;

SELECT '--- rows the query should return ---' AS step;
SELECT COUNT(*) AS matching_rows
  FROM readings
 WHERE device_id = 3
   AND reading_ts >= NOW() - INTERVAL 15 MINUTE;

SELECT '--- 1. WITH the composite (device_id, reading_ts) index ---' AS step;

EXPLAIN
SELECT device_id, reading_ts, voltage, current_amp, power_watts
  FROM readings
 WHERE device_id = 3
   AND reading_ts >= NOW() - INTERVAL 15 MINUTE
 ORDER BY reading_ts;

EXPLAIN FORMAT=TREE
SELECT device_id, reading_ts, voltage, current_amp, power_watts
  FROM readings
 WHERE device_id = 3
   AND reading_ts >= NOW() - INTERVAL 15 MINUTE
 ORDER BY reading_ts;

SELECT '--- 2. WITHOUT it (only the single-column index the foreign key requires) ---' AS step;
ALTER TABLE readings ADD KEY idx_tmp_device_only (device_id);
ALTER TABLE readings DROP INDEX idx_readings_device_ts;

EXPLAIN
SELECT device_id, reading_ts, voltage, current_amp, power_watts
  FROM readings
 WHERE device_id = 3
   AND reading_ts >= NOW() - INTERVAL 15 MINUTE
 ORDER BY reading_ts;

EXPLAIN FORMAT=TREE
SELECT device_id, reading_ts, voltage, current_amp, power_watts
  FROM readings
 WHERE device_id = 3
   AND reading_ts >= NOW() - INTERVAL 15 MINUTE
 ORDER BY reading_ts;

SELECT '--- 3. restoring the schema ---' AS step;
ALTER TABLE readings ADD KEY idx_readings_device_ts (device_id, reading_ts);
ALTER TABLE readings DROP INDEX idx_tmp_device_only;
SHOW INDEX FROM readings WHERE Key_name = 'idx_readings_device_ts';
