-- Weekly recurrence of Exclusive lines (design Section 6, request_line.recurrence; SRS Draft.Line.Add).
-- recurrence is the number of weekly occurrences, the first included: the window [starts_at, ends_at)
-- is the first, and the same company-time hours recur each week. Null means a single occurrence.
ALTER TABLE request_line
    ADD COLUMN recurrence integer CHECK (recurrence BETWEEN 2 AND 52),
    ADD CHECK (recurrence IS NULL OR allocation_class = 'EXCLUSIVE');
