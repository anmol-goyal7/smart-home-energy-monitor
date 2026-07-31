"""Tests for the analytics module.

Run from the ``python/`` directory:

    python -m unittest discover -s tests -t .

Everything here exercises the pure analysis functions, so the suite needs neither MySQL nor
the MySQL driver — which is the point of keeping the arithmetic separate from the fetching.
"""
