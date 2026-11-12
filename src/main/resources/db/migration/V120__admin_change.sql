-- Members module: the record of the Administrator's changes to Members, Teams, and roles (issue #12).
-- The test case adm-teams-roles expects every grant, revocation, and move "recorded with actor, old
-- and new value, and timestamp"; the design has a log only for settings (setting_change), so this
-- table is the Members module's counterpart. Written in the transaction of the change it records.

-- kind names the change; member_id is the Member changed (null for a Team change), team_id the Team
-- changed or the Member's Team after the change. old_value and new_value are readable text, null
-- where there was nothing before or after. at is when the change was made, by the application's Clock.
CREATE TABLE admin_change (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind       text        NOT NULL CHECK (kind IN ('MEMBER_CREATED', 'MEMBER_DEACTIVATED', 'MEMBER_MOVED',
                                                    'ROLE_GRANTED', 'ROLE_REVOKED',
                                                    'TEAM_CREATED', 'TEAM_RENAMED', 'TEAM_RETIRED')),
    member_id  bigint      REFERENCES member,
    team_id    bigint      REFERENCES team,
    old_value  text,
    new_value  text,
    actor_id   bigint      REFERENCES member,
    at         timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member
);

-- The Administrator reads the record newest first.
CREATE INDEX admin_change_newest ON admin_change (at DESC, id DESC);

-- Append-only by grant, like setting_change (V7).
REVOKE UPDATE, DELETE ON admin_change FROM openbench_app;
