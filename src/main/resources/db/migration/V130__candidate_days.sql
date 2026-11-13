-- The days a Candidate is asked for (issue #22), copied from the line as request_id and team_id are, so
-- that Allocation can tell, without reading request_line, whether a Person is already selected on
-- another line of the same Request whose days overlap: such a Person is no Candidate for this line. A
-- Person line runs from midnight of its first day to midnight after its last, in the company zone.
ALTER TABLE candidate ADD COLUMN starts_on date, ADD COLUMN ends_on date;

UPDATE candidate c
SET starts_on = (l.starts_at AT TIME ZONE '${zone}')::date,
    ends_on   = ((l.ends_at - interval '1 microsecond') AT TIME ZONE '${zone}')::date
FROM request_line l
WHERE l.id = c.line_id;

ALTER TABLE candidate
    ALTER COLUMN starts_on SET NOT NULL,
    ALTER COLUMN ends_on SET NOT NULL,
    ADD CHECK (starts_on <= ends_on);
