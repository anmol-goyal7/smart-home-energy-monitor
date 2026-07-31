package com.smarthome.energy.client.view;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Severity;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Swing panel that lists power-quality alerts as they are raised.
 *
 * <p>Displays recent {@link Event}s in a table — timestamp, device, type, severity, and the
 * measured-vs-threshold values — with severity-based colouring. Seeded from history through
 * {@code HistoryQueryService} and appended to live as new events arrive via the model.</p>
 *
 * <p>A {@code JTable} here rather than more custom painting, because a table is what this
 * data is: sortable columns, a scrollbar, and selection all come for free and would all have
 * to be rebuilt by hand. The colour is applied by a cell renderer, which is the piece worth
 * understanding — one renderer instance is reused for every cell rather than a component
 * existing per row, which is why a table with a thousand alerts in it still scrolls.</p>
 *
 * <p>The table is backfilled from the {@code events} table when the window opens and grows
 * from the live alert channel after that, so on a fresh database with nothing yet detected it
 * is legitimately empty and says so.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT ({@code JTable}, rendering).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class EventLogPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final EventTableModel tableModel = new EventTableModel();
    private final JTable table = new JTable(tableModel);
    private final JLabel emptyLabel = new JLabel("no alerts yet", JLabel.CENTER);

    /** Creates an empty alert log. */
    public EventLogPanel() {
        super(new BorderLayout());
        setBackground(DashboardView.PANEL);

        table.setFillsViewportHeight(true);
        table.setRowHeight(22);
        table.setBackground(DashboardView.PANEL);
        table.setForeground(DashboardView.TEXT);
        table.setGridColor(DashboardView.GRID);
        table.setSelectionBackground(DashboardView.GRID);
        table.setSelectionForeground(DashboardView.TEXT);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setBackground(DashboardView.BACKGROUND);
        table.getTableHeader().setForeground(DashboardView.MUTED);
        table.setDefaultRenderer(Object.class, new SeverityRenderer());

        emptyLabel.setForeground(DashboardView.MUTED);
        emptyLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

        JScrollPane scroller = new JScrollPane(table);
        scroller.getViewport().setBackground(DashboardView.PANEL);
        scroller.setBorder(null);

        add(scroller, BorderLayout.CENTER);
        add(emptyLabel, BorderLayout.NORTH);
        showEmptyMessage("no alerts yet");
    }

    /**
     * Replaces the alerts on show.
     *
     * @param events the alerts, newest first; must not be null
     * @throws NullPointerException if {@code events} is null
     */
    public void setEvents(List<Event> events) {
        tableModel.setEvents(Objects.requireNonNull(events, "events"));
        emptyLabel.setVisible(events.isEmpty());
    }

    /**
     * Supplies human names for the device column.
     *
     * @param names device id to display name; must not be null
     * @throws NullPointerException if {@code names} is null
     */
    public void setDeviceNames(Map<Integer, String> names) {
        tableModel.setDeviceNames(Objects.requireNonNull(names, "names"));
    }

    /**
     * Shows an explanation in place of the table's contents.
     *
     * @param message why there is nothing to show; must not be null
     * @throws NullPointerException if {@code message} is null
     */
    public void showEmptyMessage(String message) {
        emptyLabel.setText(Objects.requireNonNull(message, "message"));
        emptyLabel.setVisible(tableModel.getRowCount() == 0);
    }

    /** Adapts the alert list to {@code JTable}'s row/column view of the world. */
    private static final class EventTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private static final String[] COLUMNS =
                {"Time", "Device", "Type", "Severity", "Measured", "Limit", "Detail"};

        // The domain types are not Serializable and this model is never serialised — it exists
        // only for the duration of the window — so the fields are marked transient rather than
        // dragging Serializable through the model package to satisfy a contract nothing uses.
        private final transient List<Event> events = new ArrayList<>();
        private transient Map<Integer, String> deviceNames = new HashMap<>();

        void setEvents(List<Event> newest) {
            events.clear();
            events.addAll(newest);
            fireTableDataChanged();
        }

        void setDeviceNames(Map<Integer, String> names) {
            this.deviceNames = new HashMap<>(names);
            fireTableDataChanged();
        }

        Severity severityAt(int row) {
            return events.get(row).getSeverity();
        }

        @Override
        public int getRowCount() {
            return events.size();
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            Event event = events.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> CLOCK.format(event.getDetectedAt());
                case 1 -> deviceNames.getOrDefault(event.getDeviceId(), "Device " + event.getDeviceId());
                case 2 -> event.getType();
                case 3 -> event.getSeverity();
                case 4 -> String.format(Locale.ROOT, "%.2f", event.getMeasuredValue());
                case 5 -> String.format(Locale.ROOT, "%.2f", event.getThresholdValue());
                default -> event.getDetail() == null ? "" : event.getDetail();
            };
        }
    }

    /** Paints every cell of a row in the colour of that row's severity. */
    private final class SeverityRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component cell = super.getTableCellRendererComponent(source, value, selected, focused,
                    row, column);
            int modelRow = source.convertRowIndexToModel(row);
            cell.setForeground(colourFor(tableModel.severityAt(modelRow)));
            return cell;
        }

        private Color colourFor(Severity severity) {
            return switch (severity) {
                case CRITICAL -> DashboardView.CRITICAL;
                case WARNING -> DashboardView.WARNING;
                case INFO -> DashboardView.TEXT;
            };
        }
    }
}
