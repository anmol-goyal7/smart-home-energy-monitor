"""Database access helpers for the analytics module.

Thin wrappers over ``mysql-connector-python`` that open a connection from
:func:`analytics.config.load_db_config` and run the read-only queries the analytics
functions need. Each worker process opens its own connection (connections are not
shareable across processes — a socket handed to a forked child is a socket two processes
are reading, and the driver does not survive that).

Every query here is a ``SELECT``. The Java side owns every write to this schema, and a
report that could modify the data it is reporting on is a report nobody can trust twice.

The driver is imported inside :func:`connect` rather than at module scope on purpose: the
pure analysis functions in this package — the ones the tests exercise — do not need MySQL
installed to run, and an import at the top would make them need it.

Syllabus mapping: Unit IV — Python scripting; Unit III — the same JDBC-backed schema,
read from Python.
"""

from analytics import config

#: Columns every reading query returns, in the order the analysis functions expect them.
READING_COLUMNS = "device_id, reading_ts, voltage, current_amp, power_watts"


def connect(db_config=None):
    """Open and return a new MySQL connection using the loaded config.

    :param db_config: connection parameters; defaults to :func:`config.load_db_config`.
    :raises RuntimeError: if the driver is not installed, naming the install command
        rather than leaving a bare ``ModuleNotFoundError``.
    """
    try:
        import mysql.connector
    except ModuleNotFoundError as missing:
        raise RuntimeError(
            "the MySQL driver is not installed: pip install -r requirements.txt"
        ) from missing

    return mysql.connector.connect(**(db_config or config.load_db_config()))


def fetch_devices(connection=None):
    """Return the device catalogue as a list of dicts, ordered by id.

    :param connection: an open connection to reuse; one is opened and closed if omitted.
    """
    return _query(
        "SELECT device_id, name, appliance_type, location, rated_power_watts"
        "  FROM devices ORDER BY device_id",
        (),
        connection,
    )


def fetch_device_ids(connection=None):
    """Return the list of device ids from the ``devices`` table.

    :param connection: an open connection to reuse; one is opened and closed if omitted.
    """
    return [row["device_id"] for row in fetch_devices(connection)]


def fetch_readings_for_device(device_id, since=None, connection=None):
    """Return the reading rows for one device, optionally limited to a time window.

    Used by the per-device trend workers; each worker calls this on its own connection.
    The ``(device_id, reading_ts)`` index makes this the query the schema was shaped for.

    :param device_id: the device to read.
    :param since: a ``datetime`` lower bound on ``reading_ts``, or None for all history.
    :param connection: an open connection to reuse; one is opened and closed if omitted.
    """
    if since is None:
        return _query(
            f"SELECT {READING_COLUMNS} FROM readings WHERE device_id = %s"
            "  ORDER BY reading_ts",
            (device_id,),
            connection,
        )
    return _query(
        f"SELECT {READING_COLUMNS} FROM readings"
        "  WHERE device_id = %s AND reading_ts >= %s ORDER BY reading_ts",
        (device_id, since),
        connection,
    )


def fetch_all_readings(since=None, connection=None):
    """Return reading rows across all devices for the whole-home peak-hour analysis.

    :param since: a ``datetime`` lower bound on ``reading_ts``, or None for all history.
    :param connection: an open connection to reuse; one is opened and closed if omitted.
    """
    if since is None:
        return _query(
            f"SELECT {READING_COLUMNS} FROM readings ORDER BY reading_ts", (), connection
        )
    return _query(
        f"SELECT {READING_COLUMNS} FROM readings WHERE reading_ts >= %s ORDER BY reading_ts",
        (since,),
        connection,
    )


def count_readings(connection=None):
    """Return the total number of stored readings, for the report's header line."""
    rows = _query("SELECT COUNT(*) AS total FROM readings", (), connection)
    return rows[0]["total"] if rows else 0


def _query(sql, params, connection=None):
    """Run one parameterised query and return its rows as dicts.

    Parameters are always bound, never formatted into the SQL — the same rule the Java
    DAOs follow with ``PreparedStatement``, for the same reason.
    """
    owned = connection is None
    conn = connect() if owned else connection
    try:
        cursor = conn.cursor(dictionary=True)
        try:
            cursor.execute(sql, params)
            return cursor.fetchall()
        finally:
            cursor.close()
    finally:
        if owned:
            conn.close()
