package com.smarthome.energy.client;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.db.ThresholdDao;
import com.smarthome.energy.model.Threshold;

import java.util.List;
import java.util.Objects;

/**
 * The dashboard's one write path: reading and editing the detection thresholds.
 *
 * <p>{@link HistoryQueryService} is read-only by construction, which is a property worth
 * keeping — nothing that populates a chart should be able to change what it is charting. The
 * threshold editor genuinely does need to write, so it gets its own narrow service instead of
 * a {@code save} method bolted onto the read path.</p>
 *
 * <p>Every method here blocks on MySQL, so <strong>none of them may be called from the event
 * dispatch thread</strong>. The controller calls them from inside a {@code SwingWorker}, the
 * same rule the history queries follow and for the same reason.</p>
 *
 * <h2>Committing is only half of an edit</h2>
 *
 * <p>An {@code upsert} here changes the table, and the running server does not read the table
 * again on its own — its {@code RuleContext} was built at start-up. The edit therefore takes
 * effect in two steps: this service commits it, and the controller then sends
 * {@code RELOAD} up the live feed so the server rebuilds its context. Doing only the first
 * produces the worst possible outcome, an editor that reports success and changes nothing, so
 * the controller treats an unacknowledged reload as a failed edit and says so.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC (the UI's write path).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class ThresholdService {

    private final ThresholdDao thresholds;

    /**
     * @param connections the factory the DAO takes its connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public ThresholdService(ConnectionFactory connections) {
        Objects.requireNonNull(connections, "connections");
        this.thresholds = new ThresholdDao(connections);
    }

    /**
     * Builds a service from {@code db.properties}.
     *
     * @return the service
     * @throws DataAccessException if the JDBC settings are missing
     */
    public static ThresholdService fromDefaultConfig() {
        return new ThresholdService(ConnectionFactory.fromDefaultConfig());
    }

    /**
     * Reads every threshold row.
     *
     * @return the thresholds, global defaults before device overrides within each metric
     * @throws DataAccessException if the query fails
     */
    public List<Threshold> loadAll() {
        return thresholds.findAll();
    }

    /**
     * Stores one threshold, replacing any existing row for the same device and metric.
     *
     * @param threshold the limits to commit; must not be null
     * @throws DataAccessException  if the statement fails
     * @throws NullPointerException if {@code threshold} is null
     */
    public void save(Threshold threshold) {
        thresholds.upsert(Objects.requireNonNull(threshold, "threshold"));
    }
}
