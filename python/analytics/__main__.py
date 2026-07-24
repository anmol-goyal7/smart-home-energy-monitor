"""Command-line entry point: ``python -m analytics``.

Delegates to :func:`analytics.runner.run`. Guarded by ``if __name__ == "__main__"`` — this
is required for ``multiprocessing`` to spawn worker processes safely on all platforms.

Syllabus mapping: Unit IV — Python scripting and multiprocessing.
"""

from analytics.runner import run

if __name__ == "__main__":
    run()
