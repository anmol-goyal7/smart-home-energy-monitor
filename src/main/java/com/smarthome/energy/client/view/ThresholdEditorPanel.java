package com.smarthome.energy.client.view;

import com.smarthome.energy.client.controller.DashboardController;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Threshold;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Editable per-device detection limits, committed to MySQL and reloaded into the engine.
 *
 * <p>The thresholds the rule engine evaluates against live in the {@code thresholds} table and
 * were, until this panel existed, editable only with a SQL client and a server restart. Here
 * they are a table: one row per {@code (device, metric)} pair, with the minimum and maximum
 * editable in place. Committing a row writes it through {@code ThresholdDao} and then asks the
 * server to rebuild its {@code RuleContext}, so the next reading is judged against the new
 * number.</p>
 *
 * <h2>What a blank cell means</h2>
 *
 * <p>A bound is genuinely optional — the seeded power thresholds have a ceiling and no floor,
 * because a refrigerator drawing less than expected is not a fault. So a blank cell is
 * {@code NULL}, "this side is not bounded", and is not the same as {@code 0}, which would
 * mean "alert whenever the value drops below zero" and therefore never. The renderer draws
 * {@code —} for an absent bound rather than an empty cell, so the difference between "no
 * limit" and "not loaded yet" is visible.</p>
 *
 * <h2>Why the commit is one row at a time</h2>
 *
 * <p>An editor with a single Save button that writes every row is simpler to build and worse
 * to defend: it turns one intended change into six {@code UPDATE}s, and a demo that edits the
 * refrigerator's ceiling would silently rewrite the HVAC's at the same moment. Each row
 * therefore commits on its own, and the panel tracks which rows differ from what was loaded so
 * the operator can see what is about to change before pressing anything.</p>
 *
 * <p>Every value the operator types is validated here — a bound must be a non-negative number
 * or blank, and a minimum may not exceed its maximum — because the alternative is a database
 * constraint violation surfacing as a stack trace three layers down.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (tables, cell editors);
 * Unit III — the UI's JDBC write path.</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class ThresholdEditorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** What a bound that is not set is drawn as. */
    private static final String NO_BOUND = "—";

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final transient DashboardController controller;
    private final ThresholdTableModel tableModel = new ThresholdTableModel();
    private final JTable table = new JTable(tableModel);
    private final JLabel hint = new JLabel(" ");
    private final JButton commit = new JButton("Commit row");
    private final JButton revert = new JButton("Revert row");

    /**
     * @param controller where a commit is sent; must not be null
     * @throws NullPointerException if {@code controller} is null
     */
    public ThresholdEditorPanel(DashboardController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
        build();
    }

    /**
     * Replaces the displayed thresholds with what the database currently holds.
     *
     * <p>Called on the event dispatch thread when the model's thresholds change — which
     * includes immediately after a commit, so the table always shows committed values rather
     * than what was typed.</p>
     *
     * @param thresholds the rows to show; must not be null
     * @param deviceNames device id to display name, for the scope column; must not be null
     * @throws NullPointerException if either argument is null
     */
    public void setThresholds(List<Threshold> thresholds, Map<Integer, String> deviceNames) {
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(deviceNames, "deviceNames");
        tableModel.replace(thresholds, deviceNames);
        updateButtons();
    }

    /**
     * Disables editing and says why.
     *
     * @param message what to show instead of the controls; must not be null
     * @throws NullPointerException if {@code message} is null
     */
    public void showDisabled(String message) {
        hint.setText(Objects.requireNonNull(message, "message"));
        table.setEnabled(false);
        commit.setEnabled(false);
        revert.setEnabled(false);
    }

    // ---------------------------------------------------------------- construction

    private void build() {
        setLayout(new BorderLayout(0, 6));
        setBackground(DashboardView.PANEL);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setBackground(DashboardView.PANEL);
        table.setForeground(DashboardView.TEXT);
        table.setGridColor(DashboardView.GRID);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(DashboardView.GRID);
        table.setSelectionForeground(DashboardView.TEXT);
        table.getTableHeader().setBackground(DashboardView.BACKGROUND);
        table.getTableHeader().setForeground(DashboardView.MUTED);
        table.setFillsViewportHeight(true);

        BoundRenderer boundRenderer = new BoundRenderer();
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_MIN)
                .setCellRenderer(boundRenderer);
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_MAX)
                .setCellRenderer(boundRenderer);
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_MIN)
                .setCellEditor(new BoundEditor());
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_MAX)
                .setCellEditor(new BoundEditor());
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_SCOPE).setPreferredWidth(220);
        table.getColumnModel().getColumn(ThresholdTableModel.COLUMN_DESCRIPTION)
                .setPreferredWidth(280);

        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        tableModel.addTableModelListener(e -> updateButtons());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(DashboardView.GRID));
        scroll.getViewport().setBackground(DashboardView.PANEL);

        add(buildControls(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);

        hint.setForeground(DashboardView.MUTED);
        hint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        updateButtons();
    }

    private JComponent buildControls() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.LINE_AXIS));
        controls.setBackground(DashboardView.PANEL);

        JLabel caption = new JLabel("Detection limits — edit a cell, then commit the row");
        caption.setForeground(DashboardView.MUTED);
        caption.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        controls.add(caption);
        controls.add(Box.createHorizontalGlue());

        commit.addActionListener(e -> commitSelectedRow());
        revert.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                tableModel.revert(row);
            }
        });

        JButton reload = new JButton("Reload from database");
        reload.addActionListener(e -> controller.refreshThresholds());

        controls.add(revert);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(commit);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(reload);
        return controls;
    }

    // ---------------------------------------------------------------- behaviour

    /** Validates the selected row and hands it to the controller. */
    private void commitSelectedRow() {
        // An edit still in the cell editor has not reached the table model yet, and a Commit
        // pressed straight after typing would otherwise store the previous value.
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        int row = table.getSelectedRow();
        if (row < 0) {
            hint.setText("select a row to commit");
            return;
        }
        Row edited = tableModel.getRow(row);
        String problem = edited.validate();
        if (problem != null) {
            hint.setText(problem);
            hint.setForeground(DashboardView.CRITICAL);
            return;
        }
        hint.setForeground(DashboardView.MUTED);
        controller.saveThreshold(edited.toThreshold());
    }

    /** Enables the buttons only when there is something for them to do. */
    private void updateButtons() {
        if (!controller.canEditThresholds()) {
            return;
        }
        int row = table.getSelectedRow();
        boolean dirty = row >= 0 && tableModel.getRow(row).isDirty();
        commit.setEnabled(dirty);
        revert.setEnabled(dirty);
        if (row < 0) {
            hint.setText("select a row to edit its limits");
        } else if (dirty) {
            hint.setForeground(DashboardView.WARNING);
            hint.setText("row edited but not committed — the engine is still using the stored value");
        } else {
            hint.setForeground(DashboardView.MUTED);
            hint.setText("showing the values the engine is evaluating against");
        }
    }

    // ---------------------------------------------------------------- table plumbing

    /**
     * One editable row: the threshold as loaded, plus whatever has been typed over it.
     *
     * <p>Keeping the loaded values alongside the edited ones is what lets the panel say "this
     * row differs from the database" without going back to the database to ask.</p>
     */
    private static final class Row {

        private final Integer deviceId;
        private final Metric metric;
        private final String scope;
        private final String description;
        private final Double loadedMin;
        private final Double loadedMax;

        private Double min;
        private Double max;

        Row(Threshold threshold, String scope) {
            this.deviceId = threshold.getDeviceId();
            this.metric = threshold.getMetric();
            this.scope = scope;
            this.description = threshold.getDescription() == null ? "" : threshold.getDescription();
            this.loadedMin = threshold.getMinValue();
            this.loadedMax = threshold.getMaxValue();
            this.min = loadedMin;
            this.max = loadedMax;
        }

        boolean isDirty() {
            return !Objects.equals(min, loadedMin) || !Objects.equals(max, loadedMax);
        }

        void revert() {
            this.min = loadedMin;
            this.max = loadedMax;
        }

        /** @return what is wrong with the edited values, or null if they are storable. */
        String validate() {
            if (min != null && min < 0) {
                return "a minimum may not be negative";
            }
            if (max != null && max < 0) {
                return "a maximum may not be negative";
            }
            if (min != null && max != null && min > max) {
                return "the minimum (" + format(min) + ") is above the maximum (" + format(max)
                        + "), which would alert on every reading";
            }
            if (min == null && max == null) {
                return "a threshold with neither bound would never fire; leave one set";
            }
            return null;
        }

        Threshold toThreshold() {
            return new Threshold(deviceId, metric, min, max,
                    description.isEmpty() ? null : description);
        }

        static String format(Double value) {
            return value == null ? NO_BOUND : String.format(Locale.ROOT, "%.2f", value);
        }
    }

    /** The table's data: the threshold rows, with the two bound columns editable. */
    private static final class ThresholdTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        static final int COLUMN_SCOPE = 0;
        static final int COLUMN_METRIC = 1;
        static final int COLUMN_MIN = 2;
        static final int COLUMN_MAX = 3;
        static final int COLUMN_DESCRIPTION = 4;

        private static final String[] COLUMNS = {"applies to", "metric", "min", "max", "description"};

        private final transient List<Row> rows = new ArrayList<>();

        void replace(List<Threshold> thresholds, Map<Integer, String> deviceNames) {
            rows.clear();
            for (Threshold threshold : thresholds) {
                rows.add(new Row(threshold, scopeOf(threshold, deviceNames)));
            }
            fireTableDataChanged();
        }

        Row getRow(int index) {
            return rows.get(index);
        }

        void revert(int index) {
            rows.get(index).revert();
            fireTableRowsUpdated(index, index);
        }

        private static String scopeOf(Threshold threshold, Map<Integer, String> deviceNames) {
            if (threshold.isGlobalDefault()) {
                return "every device (default)";
            }
            int id = threshold.getDeviceId();
            return id + " · " + deviceNames.getOrDefault(id, "device " + id);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == COLUMN_MIN || columnIndex == COLUMN_MAX;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            return switch (columnIndex) {
                case COLUMN_SCOPE -> row.scope;
                case COLUMN_METRIC -> row.metric.name();
                case COLUMN_MIN -> row.min;
                case COLUMN_MAX -> row.max;
                default -> row.description;
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            Double bound = (Double) value;
            if (columnIndex == COLUMN_MIN) {
                row.min = bound;
            } else if (columnIndex == COLUMN_MAX) {
                row.max = bound;
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    /** Draws a bound, or {@code —} when it is not set, and marks an uncommitted row. */
    private final class BoundRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        BoundRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component rendered = super.getTableCellRendererComponent(owner, value, selected,
                    focused, row, column);
            setText(Row.format((Double) value));
            setFont(MONO);

            boolean dirty = tableModel.getRow(owner.convertRowIndexToModel(row)).isDirty();
            Color foreground = dirty ? DashboardView.WARNING : DashboardView.TEXT;
            rendered.setForeground(selected && !dirty ? DashboardView.TEXT : foreground);
            return rendered;
        }
    }

    /**
     * Edits a bound as text, turning blank into {@code NULL} and refusing anything that is
     * neither.
     *
     * <p>{@code JTable}'s default editor for a {@code Double} column rejects an empty string,
     * which would make an unbounded threshold impossible to express — the one thing the schema
     * is most careful to allow.</p>
     */
    private final class BoundEditor extends AbstractCellEditor implements TableCellEditor {

        private static final long serialVersionUID = 1L;

        private final JTextField field = new JTextField();

        BoundEditor() {
            field.setFont(MONO);
            field.setHorizontalAlignment(SwingConstants.RIGHT);
            field.setBackground(DashboardView.BACKGROUND);
            field.setForeground(DashboardView.TEXT);
            field.setCaretColor(DashboardView.TEXT);
            field.setBorder(BorderFactory.createLineBorder(DashboardView.ACCENT));
        }

        @Override
        public Component getTableCellEditorComponent(JTable owner, Object value, boolean selected,
                                                     int row, int column) {
            field.setText(value == null ? "" : String.format(Locale.ROOT, "%.2f", (Double) value));
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            String typed = field.getText().trim();
            if (typed.isEmpty() || NO_BOUND.equals(typed)) {
                return null;
            }
            try {
                return Double.valueOf(typed);
            } catch (NumberFormatException e) {
                // Keeping the previous value is friendlier than a dialog: the row stays
                // uncommitted, and the hint below the table explains what is wrong.
                hint.setForeground(DashboardView.CRITICAL);
                hint.setText("'" + typed + "' is not a number — leave a bound blank to remove it");
                return null;
            }
        }

        @Override
        public boolean isCellEditable(EventObject event) {
            return controller.canEditThresholds();
        }
    }
}
