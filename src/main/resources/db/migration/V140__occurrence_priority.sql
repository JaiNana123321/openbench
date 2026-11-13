-- The holder timeline (SRS Inventory.View.Holder, issue #11) shows an allocated part's Priority mark.
-- Allocation reads only its own tables, so each occurrence carries the mark of its line, copied when
-- the row is written, like team_id and request_id. A line's mark is fixed once it leaves Draft.
ALTER TABLE exclusive_occurrence ADD COLUMN priority boolean NOT NULL DEFAULT false;

UPDATE exclusive_occurrence o SET priority = true
FROM request_line l
WHERE l.id = o.line_id AND l.priority;

