"""Database access helpers for the analytics module.

Thin wrappers over ``mysql-connector-python`` that open a connection from
:func:`analytics.config.load_db_config` and run the read-only queries the analytics
functions need. Each worker process opens its own connection (connections are not
shareable across processes).

Syllabus mapping: Unit IV — Python scripting; Unit III — the same JDBC-backed schema,
read from Python.
"""


def connect():
    """Open and return a new MySQL connection using the loaded config."""
    raise NotImplementedError("implemented by the author")


def fetch_device_ids():
    """Return the list of device ids from the ``devices`` table."""
    raise NotImplementedError("implemented by the author")


def fetch_readings_for_device(device_id, since=None):
    """Return the reading rows for one device, optionally limited to a time window.

    Used by the per-device trend workers; each worker calls this on its own connection.
    """
    raise NotImplementedError("implemented by the author")


def fetch_all_readings(since=None):
    """Return reading rows across all devices for the whole-home peak-hour analysis."""
    raise NotImplementedError("implemented by the author")
