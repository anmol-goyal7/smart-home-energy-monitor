"""Whole-home peak-hour demand analysis.

Buckets every reading by hour-of-day (00..23), sums/averages the real power across all
devices in each bucket, and identifies the hours at which the home draws the most power.
Answers the question "when does this household peak?" — useful for load shifting and for
the report's demand narrative.

Syllabus mapping: Unit IV — Python scripting (aggregation over history).
"""


def compute_peak_hours(readings):
    """Aggregate readings into a 24-slot hour-of-day power profile.

    Returns a structure mapping each hour (0..23) to its total and average power, from
    which the caller can rank the peak hours. Pure function over the rows passed in, so it
    is trivially testable without a database.
    """
    raise NotImplementedError("implemented by the author")
