package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Applies the registered {@link DetectionRule}s to each incoming reading and returns the
 * {@code Event}s they raise.
 *
 * <p>The engine holds an immutable list of rules ({@link VoltageSpikeRule},
 * {@link VoltageSagRule}, {@link LoadOverloadRule}) and a {@link RuleContext} of cached
 * thresholds. For each reading it is given it runs every rule and collects whatever fires.</p>
 *
 * <p>The engine deliberately sits <em>off</em> the socket read path: readings reach it via
 * the dispatcher's worker rather than directly from a {@code ClientHandler}, so evaluation
 * cost never back-pressures meter ingestion (rationale in {@code docs/DESIGN.md}).</p>
 *
 * <h2>Why it evaluates but does not persist</h2>
 *
 * <p>The scaffold had this class write its events through {@code EventDao} itself. It does
 * not, because an event's {@code triggering_reading_id} is the key of a row that is being
 * inserted in the same transaction — so whoever owns that transaction has to own the event
 * insert too, or the two cannot be atomic. That owner is
 * {@code com.smarthome.energy.server.PersistenceSink}: it inserts the reading, calls
 * {@link #evaluate(Reading, Long)} with the key it got back, writes the events, and commits
 * once. The engine is left as a pure function from a reading to the alerts it deserves,
 * which is also what makes it testable without a database.</p>
 *
 * <h2>Reloading</h2>
 *
 * <p>The context is {@code volatile} and replaced wholesale by {@link #reload(RuleContext)},
 * never mutated in place. A worker mid-evaluation therefore sees one consistent set of
 * thresholds — the old one or the new one, never half of each — and the next reading picks
 * up the edit. This is what the Phase 4 threshold editor commits into.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (composition, strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class RuleEngine {

    private final List<DetectionRule> rules;
    private final AtomicLong evaluated = new AtomicLong();
    private final AtomicLong raised = new AtomicLong();

    private volatile RuleContext context;

    /**
     * Creates an engine with the three rules the system ships.
     *
     * @param context the thresholds to evaluate against; must not be null
     * @throws NullPointerException if {@code context} is null
     */
    public RuleEngine(RuleContext context) {
        this(context, List.of(new VoltageSpikeRule(), new VoltageSagRule(), new LoadOverloadRule()));
    }

    /**
     * Creates an engine with a specific set of rules, which is how a test isolates one and
     * how a new detector is introduced without touching this class.
     *
     * @param context the thresholds to evaluate against; must not be null
     * @param rules   the rules to apply, in order; must not be null or empty
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if {@code rules} is empty
     */
    public RuleEngine(RuleContext context, List<DetectionRule> rules) {
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("an engine with no rules would silently detect nothing");
        }
        this.rules = List.copyOf(rules);
    }

    /**
     * Runs every rule against one reading.
     *
     * <p>Rules are independent: one reading can be both a voltage sag and an overload, and
     * both events are raised. A rule that throws is not allowed to suppress the others — the
     * failure is reported and evaluation continues, because a bug in one detector should cost
     * that detector's alerts and not the whole reading's.</p>
     *
     * @param reading   the reading to evaluate; must not be null
     * @param readingId the generated {@code reading_id} to bind the events to, or null when
     *                  the reading was not persisted
     * @return the events raised, in rule order; empty if the reading is nominal
     * @throws NullPointerException if {@code reading} is null
     */
    public List<Event> evaluate(Reading reading, Long readingId) {
        Objects.requireNonNull(reading, "reading");
        RuleContext current = context;
        List<Event> events = new ArrayList<>(2);

        for (DetectionRule rule : rules) {
            Optional<Event> fired;
            try {
                fired = rule.evaluate(reading, current);
            } catch (RuntimeException e) {
                System.err.println("[rules] " + rule.name() + " failed on " + reading + ": " + e);
                continue;
            }
            fired.ifPresent(event ->
                    events.add(readingId == null ? event : event.withTriggeringReadingId(readingId)));
        }

        evaluated.incrementAndGet();
        raised.addAndGet(events.size());
        return events;
    }

    /**
     * Evaluates a reading that was not persisted, so its events carry no reading id.
     *
     * @param reading the reading to evaluate; must not be null
     * @return the events raised, in rule order
     * @throws NullPointerException if {@code reading} is null
     */
    public List<Event> evaluate(Reading reading) {
        return evaluate(reading, null);
    }

    /**
     * Swaps in freshly loaded thresholds, taking effect on the next reading evaluated.
     *
     * @param fresh the new context; must not be null
     * @throws NullPointerException if {@code fresh} is null
     */
    public void reload(RuleContext fresh) {
        this.context = Objects.requireNonNull(fresh, "fresh");
        System.out.println("[rules] thresholds reloaded — " + fresh);
    }

    /** @return the thresholds currently in force. */
    public RuleContext getContext() {
        return context;
    }

    /** @return the rules applied to every reading, in order. */
    public List<DetectionRule> getRules() {
        return rules;
    }

    /** @return how many readings have been evaluated since start-up. */
    public long getEvaluatedCount() {
        return evaluated.get();
    }

    /** @return how many events have been raised since start-up. */
    public long getRaisedCount() {
        return raised.get();
    }

    @Override
    public String toString() {
        return "RuleEngine[rules=" + rules.size() + ", " + context
                + ", evaluated=" + evaluated.get() + ", raised=" + raised.get() + "]";
    }
}
