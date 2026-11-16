-- Keep the original proposal and its addressee independently of later Request edits or Member moves.
ALTER TABLE suggestion
    ADD COLUMN team_id bigint REFERENCES team,
    ADD COLUMN manager_id bigint REFERENCES member,
    ADD COLUMN recurrence integer,
    ADD COLUMN skills bigint[],
    ADD COLUMN decided_by bigint REFERENCES member,
    ADD COLUMN decided_at timestamptz;

UPDATE suggestion s SET team_id = m.team_id, manager_id = t.manager_member_id
FROM member m JOIN team t ON t.id = m.team_id WHERE m.id = s.member_id;
ALTER TABLE suggestion ALTER COLUMN team_id SET NOT NULL;
CREATE INDEX suggestion_member ON suggestion (member_id, created_at DESC, id DESC);
CREATE INDEX suggestion_manager ON suggestion (manager_id, team_id, state, created_at DESC, id DESC);
CREATE INDEX suggestion_accepted_line ON suggestion (accepted_line_id);
