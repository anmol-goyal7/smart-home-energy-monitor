"""Per-device usage trend analysis — the unit of work parallelised across processes.

Each call analyses the history of exactly one device: daily average power, min/max,
duty-cycle estimate, and a simple direction-of-trend (rising/steady/falling) over the
window. Because the work partitions cleanly by device, the runner maps this function over
all devices with a ``multiprocessing.Pool`` — one worker process per device.

Syllabus mapping: Unit IV — Python scripting and multiprocessing (the mapped worker).
"""


def analyze_device(device_id):
    """Compute the trend summary for a single device and return it.

    Runs inside a worker process, so it opens its own database connection (via
    :mod:`analytics.db`), fetches that device's readings, computes the summary, and returns
    a plain, picklable result the parent process can collect.
    """
    raise NotImplementedError("implemented by the author")
