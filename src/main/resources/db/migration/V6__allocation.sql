-- Allocation module: Exclusive occurrences, Metered allocations, and the Waitlist (design Sections
-- 4.5 and 6.2, ADR-0006). A Hold is the state HELD of a row. Candidates, approvals, and Loans come
-- with Matched allocation in the next increment.

-- One row per occurrence and instance. No two live (HELD or ALLOCATED) rows on one Resource overlap,
-- on every code path; RELEASED, BUMPED, and EXPIRED rows stay as history and block nothing. Windows
-- are half-open, so back-to-back occurrences do not conflict. The constraint is NOT DEFERRABLE: a
-- Bump marks the old rows BUMPED and flushes before it inserts the Priority occurrence.
CREATE TABLE exclusive_occurrence (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id     bigint      NOT NULL REFERENCES request_line,
    team_id     bigint      NOT NULL REFERENCES team,
    request_id  bigint      NOT NULL REFERENCES request,
    resource_id bigint      NOT NULL REFERENCES resource,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    state       text        NOT NULL CHECK (state IN ('HELD', 'ALLOCATED', 'RELEASED', 'BUMPED', 'EXPIRED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  bigint      REFERENCES member,
    CHECK (starts_at < ends_at),
    CONSTRAINT exclusive_occurrence_no_overlap EXCLUDE USING gist (
        resource_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (state IN ('HELD', 'ALLOCATED'))
);

CREATE INDEX exclusive_occurrence_line_id ON exclusive_occurrence (line_id);

-- One row per Metered line: units held concurrently over the window. A sum over rows cannot be a
-- constraint, so Pool Capacity and Quota rely on the type lock and the hourly query (design 6.2).
CREATE TABLE metered_allocation (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id    bigint      NOT NULL REFERENCES request_line,
    team_id    bigint      NOT NULL REFERENCES team,
    request_id bigint      NOT NULL REFERENCES request,
    type_id    bigint      NOT NULL REFERENCES resource_type,
    units      integer     NOT NULL CHECK (units > 0),
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL,
    state      text        NOT NULL CHECK (state IN ('HELD', 'ALLOCATED', 'RELEASED', 'EXPIRED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    CHECK (starts_at < ends_at)
);

-- Serves the overlap test of the hourly sum over live rows.
CREATE INDEX metered_allocation_live ON metered_allocation
    USING gist (type_id, tstzrange(starts_at, ends_at, '[)'))
    WHERE state IN ('HELD', 'ALLOCATED');

CREATE INDEX metered_allocation_line_id ON metered_allocation (line_id);

-- One entry per waiting Required line, deleted when it leaves. joined_at holds the Request's
-- submission time, which orders the Waitlist after bumped and Priority.
CREATE TABLE waitlist_entry (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id    bigint      NOT NULL UNIQUE REFERENCES request_line,
    type_id    bigint      NOT NULL REFERENCES resource_type,
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL,
    priority   boolean     NOT NULL DEFAULT false,
    bumped     boolean     NOT NULL DEFAULT false,
    joined_at  timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    CHECK (starts_at < ends_at)
);

CREATE INDEX waitlist_entry_type_id ON waitlist_entry (type_id);
