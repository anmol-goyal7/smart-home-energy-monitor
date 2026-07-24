"""Smart Home Energy Monitor — Python analytics satellite.

A standalone, read-only companion to the Java system. It connects to the same MySQL
database and mines the accumulated reading/event history for insights the live dashboard
does not compute: peak-hour demand across the home and per-device usage trends.

The heavy per-device work is parallelised with the standard-library ``multiprocessing``
module — one worker process per device — which is the syllabus focus of this module.

Syllabus mapping: Unit IV — Python scripting and multiprocessing.
"""
