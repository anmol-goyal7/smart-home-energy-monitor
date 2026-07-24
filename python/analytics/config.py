"""Configuration for the analytics module.

Loads MySQL connection parameters from environment variables (falling back to sensible
local defaults) so credentials stay out of source control, mirroring the Java side's
``db.properties`` approach.

Syllabus mapping: Unit IV — Python scripting.
"""


def load_db_config():
    """Return a dict of MySQL connection parameters.

    Intended keys: ``host``, ``port``, ``user``, ``password``, ``database``.
    Read from environment variables such as ``ENERGY_DB_HOST`` / ``ENERGY_DB_USER``,
    defaulting to localhost / the ``smart_home_energy`` database.
    """
    raise NotImplementedError("implemented by the author")
