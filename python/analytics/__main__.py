"""Command-line entry point: ``python -m analytics``.

Delegates to :func:`analytics.runner.run`. Guarded by ``if __name__ == "__main__"`` — this
is required for ``multiprocessing`` to spawn worker processes safely on all platforms.

Syllabus mapping: Unit IV — Python scripting and multiprocessing.
"""

import sys

from analytics.runner import run

if __name__ == "__main__":
    # The exit status is the runner's: a report that could not read the database must not
    # look like a successful run to whatever called it.
    sys.exit(run(sys.argv[1:]))
