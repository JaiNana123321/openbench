-- Members module: Teams, Members, roles, skills, and availability (design Sections 4.1 and 6).
-- Conventions for every migration: bigint identity keys, timestamptz instants, half-open windows,
-- inclusive date ranges, numeric(12,2) credits, created_at and created_by on every row (created_by
-- is null when a job writes the row), and states and kinds as text with a CHECK.

-- GiST support for scalar equality ("resource_id WITH =") in exclusion constraints. The design has
-- the first migration create it; it is a trusted extension shipped in the postgres image.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE team (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              text        NOT NULL UNIQUE,
    manager_member_id bigint,
    retired_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        bigint
);

CREATE TABLE member (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id       bigint      NOT NULL REFERENCES team,
    name          text        NOT NULL,
    email         text        NOT NULL UNIQUE,
    username      text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    active        boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    bigint      REFERENCES member,
    -- Target of the composite keys that keep a Team's Manager and a Member's roles in their Team.
    UNIQUE (id, team_id)
);

ALTER TABLE team
    ADD FOREIGN KEY (created_by) REFERENCES member,
    -- Every Team's Manager is a Member of that Team.
    ADD CONSTRAINT team_manager_in_team FOREIGN KEY (manager_member_id, id) REFERENCES member (id, team_id);

-- Stored roles only: every active Member also holds MEMBER, which is never stored.
CREATE TABLE member_role (
    member_id  bigint      NOT NULL,
    role       text        NOT NULL CHECK (role IN ('MANAGER', 'RESOURCE_OWNER', 'PLANNER', 'ADMIN')),
    team_id    bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    PRIMARY KEY (member_id, role),
    -- Moving a Member to another Team moves their roles with them.
    FOREIGN KEY (member_id, team_id) REFERENCES member (id, team_id) ON UPDATE CASCADE
);

-- At most one Manager per Team, mirrored in team.manager_member_id.
CREATE UNIQUE INDEX member_role_one_manager ON member_role (team_id) WHERE role = 'MANAGER';

CREATE TABLE skill (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text        NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member
);

-- Skill names are unique ignoring case (SKILL_NAME_TAKEN).
CREATE UNIQUE INDEX skill_name_unique ON skill (lower(name));

CREATE TABLE member_skill (
    member_id  bigint      NOT NULL REFERENCES member,
    skill_id   bigint      NOT NULL REFERENCES skill,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    PRIMARY KEY (member_id, skill_id)
);

CREATE TABLE availability_range (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  bigint      NOT NULL REFERENCES member,
    from_date  date        NOT NULL,
    to_date    date        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    CHECK (from_date <= to_date)
);

CREATE INDEX availability_range_member ON availability_range (member_id);
