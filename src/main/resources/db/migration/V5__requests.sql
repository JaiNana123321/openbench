-- Requests module: Requests, Request Lines, Suggestions, and the state history (design Sections 4.8,
-- 5.2, and 6). Columns for weekly recurrence and a Matched line's skills come with those features in
-- a later increment.

CREATE TABLE request (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id        bigint      NOT NULL REFERENCES team,
    state          text        NOT NULL DEFAULT 'DRAFT'
                   CHECK (state IN ('DRAFT', 'SUBMITTED', 'PENDING', 'WAITING', 'ALLOCATED', 'WITHDRAWN', 'REFUSED')),
    submitted_at   timestamptz,
    window_ends_at timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     bigint      REFERENCES member
);

CREATE INDEX request_team_id ON request (team_id);

-- The window is the half-open [starts_at, ends_at). quantity counts instances (Exclusive) or People
-- (Matched); units is what a Metered line holds concurrently. named_resource_id is the specific
-- Resource an Exclusive line may name (SRS Exclusive.Line.Form), a column the design leaves out.
CREATE TABLE request_line (
    id                bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id        bigint        NOT NULL REFERENCES request,
    type_id           bigint        NOT NULL REFERENCES resource_type,
    allocation_class  text          NOT NULL CHECK (allocation_class IN ('MATCHED', 'EXCLUSIVE', 'METERED')),
    quantity          integer       NOT NULL DEFAULT 1 CHECK (quantity > 0),
    units             integer       CHECK (units > 0),
    starts_at         timestamptz   NOT NULL,
    ends_at           timestamptz   NOT NULL,
    required          boolean       NOT NULL DEFAULT true,
    priority          boolean       NOT NULL DEFAULT false,
    named_resource_id bigint        REFERENCES resource,
    named_member_id   bigint        REFERENCES member,
    locked_quote      numeric(12,2),
    state             text          NOT NULL DEFAULT 'DRAFT'
                      CHECK (state IN ('DRAFT', 'EVALUATED', 'HELD', 'ALLOCATED', 'WAITING', 'RELEASED',
                                       'PARTLY_RELEASED', 'BUMPED', 'AWAITING_APPROVAL', 'APPROVED', 'LOANED',
                                       'REFUSED', 'DROPPED', 'CANCELED')),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint        REFERENCES member,
    CHECK (starts_at < ends_at),
    CHECK (NOT priority OR allocation_class = 'EXCLUSIVE')
);

CREATE INDEX request_line_request_id ON request_line (request_id);

-- A line a Member proposes to their own Manager. The proposal columns mirror a line; never deleted.
CREATE TABLE suggestion (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id         bigint      NOT NULL REFERENCES member,
    type_id           bigint      NOT NULL REFERENCES resource_type,
    quantity          integer     NOT NULL DEFAULT 1 CHECK (quantity > 0),
    units             integer     CHECK (units > 0),
    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz NOT NULL,
    named_resource_id bigint      REFERENCES resource,
    named_member_id   bigint      REFERENCES member,
    note              text,
    state             text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'ACCEPTED', 'DECLINED')),
    accepted_line_id  bigint      REFERENCES request_line,
    reason            text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        bigint      REFERENCES member,
    CHECK (starts_at < ends_at)
);

-- One row per state change of a Request or a line, in the same transaction. Insert-only (V7). A null
-- actor is the system, named in the note.
CREATE TABLE request_event (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id bigint      NOT NULL REFERENCES request,
    line_id    bigint      REFERENCES request_line,
    from_state text,
    to_state   text        NOT NULL,
    actor_id   bigint      REFERENCES member,
    at         timestamptz NOT NULL,
    note       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member
);

CREATE INDEX request_event_request_id ON request_event (request_id);

-- The Ledger's read-only references to request_line, created here because this migration creates it.
ALTER TABLE transaction ADD FOREIGN KEY (line_id) REFERENCES request_line;
ALTER TABLE held_credit ADD FOREIGN KEY (line_id) REFERENCES request_line;
