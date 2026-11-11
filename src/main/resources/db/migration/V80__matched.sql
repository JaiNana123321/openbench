-- Matched lines (design Sections 4.5, 4.8, and 6; ADR-0001, ADR-0004, ADR-0006): a Person line's
-- skills, and Allocation's Candidates, approvals, and Loans.

-- The skills a skill-based Person line asks for, as skill ids; null on every other line and on a line
-- naming one Person (named_member_id, from V5).
ALTER TABLE request_line ADD COLUMN skills bigint[];

-- A Person selected for a Matched line, with their place on the Shortlist when selected (0 for a named
-- Person, who is not chosen from a Shortlist). request_id and team_id (the requesting Team) are copied
-- from the line, as on exclusive_occurrence, so Allocation reads only its own tables.
CREATE TABLE candidate (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id    bigint      NOT NULL REFERENCES request_line,
    request_id bigint      NOT NULL REFERENCES request,
    team_id    bigint      NOT NULL REFERENCES team,
    member_id  bigint      NOT NULL REFERENCES member,
    position   integer     NOT NULL CHECK (position >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    UNIQUE (line_id, member_id)
);

CREATE INDEX candidate_request_id ON candidate (request_id);

-- One approval per Candidate, addressed to the Manager of the Candidate's Team. escalated is a flag,
-- not a state: set on an Outstanding approval when the Objection Window lapses, it stays set after
-- the decision.
CREATE TABLE approval (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id       bigint      NOT NULL UNIQUE REFERENCES candidate,
    approver_member_id bigint      NOT NULL REFERENCES member,
    state              text        NOT NULL DEFAULT 'OUTSTANDING'
                       CHECK (state IN ('OUTSTANDING', 'APPROVED', 'REFUSED', 'CANCELED')),
    decided_by         bigint      REFERENCES member,
    decided_at         timestamptz,
    reason             text,
    escalated          boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    created_by         bigint      REFERENCES member,
    CHECK ((state IN ('APPROVED', 'REFUSED')) = (decided_at IS NOT NULL))
);

CREATE INDEX approval_outstanding ON approval (approver_member_id) WHERE state = 'OUTSTANDING';

-- A Person lent to another Team for whole days, inclusive at both ends. days_lent counts the Monday to
-- Friday days. A Person is in at most one Loan on any day, on every code path (RC-1): the inclusive
-- daterange makes a Loan ending on a Friday overlap one starting that Friday. An early end shortens
-- ends_on and sets ended_early; the Loan stays in the constraint for the days it lent.
CREATE TABLE loan (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id           bigint      NOT NULL REFERENCES request_line,
    member_id         bigint      NOT NULL REFERENCES member,
    lending_team_id   bigint      NOT NULL REFERENCES team,
    borrowing_team_id bigint      NOT NULL REFERENCES team,
    starts_on         date        NOT NULL,
    ends_on           date        NOT NULL,
    days_lent         integer     NOT NULL CHECK (days_lent >= 0),
    ended_early       boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        bigint      REFERENCES member,
    CHECK (starts_on <= ends_on),
    CHECK (lending_team_id <> borrowing_team_id),
    CONSTRAINT loan_one_at_a_time EXCLUDE USING gist (
        member_id WITH =,
        daterange(starts_on, ends_on, '[]') WITH &&
    )
);

CREATE INDEX loan_line_id ON loan (line_id);
