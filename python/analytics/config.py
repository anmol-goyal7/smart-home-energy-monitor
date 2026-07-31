"""Configuration for the analytics module.

Loads MySQL connection parameters from environment variables (falling back to sensible
local defaults) so credentials stay out of source control, mirroring the Java side's
``db.properties`` approach.

The defaults match ``docker-compose.yml``, so ``python -m analytics`` works against the
project's own database with nothing exported. That is the opposite of the Java side's rule,
where :class:`ConnectionFactory` refuses to invent a URL — and deliberately so: this module
only ever reads, so the cost of guessing wrong here is a connection error, not a write to
somebody else's database.

Syllabus mapping: Unit IV — Python scripting.
"""

import os

#: Prefix every override shares: ``ENERGY_DB_HOST``, ``ENERGY_DB_USER``, and so on.
ENV_PREFIX = "ENERGY_DB_"

#: Connection parameters used when the environment says nothing, matching docker-compose.yml.
DEFAULTS = {
    "host": "localhost",
    "port": 3306,
    "user": "energy_app",
    "password": "change_me",
    "database": "smart_home_energy",
}


def load_db_config(env=None):
    """Return a dict of MySQL connection parameters.

    Keys: ``host``, ``port``, ``user``, ``password``, ``database``. Each is read from
    ``ENERGY_DB_<KEY>`` in the environment, falling back to :data:`DEFAULTS`.

    :param env: mapping to read instead of ``os.environ``, which is what the tests pass.
    :raises ValueError: if ``ENERGY_DB_PORT`` is set to something that is not a number.
    """
    source = os.environ if env is None else env
    config = dict(DEFAULTS)

    for key in DEFAULTS:
        value = source.get(ENV_PREFIX + key.upper())
        if value is not None and value != "":
            config[key] = value

    try:
        config["port"] = int(config["port"])
    except (TypeError, ValueError):
        raise ValueError(
            f"{ENV_PREFIX}PORT must be a whole number, was {config['port']!r}"
        ) from None

    return config


def describe(config):
    """Return a one-line, password-free description of a connection, for the report header.

    :param config: a dict as returned by :func:`load_db_config`.
    """
    return "{user}@{host}:{port}/{database}".format(**config)
