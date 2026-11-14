-- Bumps (SRS Exclusive.Priority.Bump, issue #9): among instances needing as many Bumps, the plan
-- Bumps the latest-submitted lines. Allocation reads only its own tables, so each occurrence carries
-- its Request's submission time, copied when the row is written, like team_id, request_id, and
-- priority. A row written by hand (a repair, a test) may leave it null; the plan then treats its line
-- as submitted first, the one it Bumps last.
ALTER TABLE exclusive_occurrence ADD COLUMN submitted_at timestamptz;

UPDATE exclusive_occurrence o SET submitted_at = r.submitted_at
FROM request r
WHERE r.id = o.request_id;
