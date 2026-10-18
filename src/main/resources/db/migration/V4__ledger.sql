-- Ledger module: Accounts, the append-only Transactions, held credits, and Period closes
-- (design Sections 4.6 and 6). The keys to request_line are added by V5, which creates that table;
-- the key from transaction.loan_id comes with the loan table in the next increment.

-- The running totals of a Team's Transactions, updated under the row's lock. Balance is granted
-- plus earned (earned may go negative after an Earning correction); Available Balance subtracts held.
CREATE TABLE account (
    team_id    bigint        PRIMARY KEY REFERENCES team,
    granted    numeric(12,2) NOT NULL DEFAULT 0 CHECK (granted >= 0),
    earned     numeric(12,2) NOT NULL DEFAULT 0,
    held       numeric(12,2) NOT NULL DEFAULT 0 CHECK (held >= 0),
    created_at timestamptz   NOT NULL DEFAULT now(),
    created_by bigint        REFERENCES member
);

-- Append-only: the application role may insert and select but never update or delete (V7). A
-- correction is a new row naming the corrected one; only an Earning correction is negative.
CREATE TABLE transaction (
    id           bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    team_id      bigint        NOT NULL REFERENCES team,
    kind         text          NOT NULL CHECK (kind IN ('GRANT', 'CHARGE', 'EARNING', 'REFUND')),
    amount       numeric(12,2) NOT NULL,
    granted_part numeric(12,2) NOT NULL,
    earned_part  numeric(12,2) NOT NULL,
    period_id    bigint        NOT NULL REFERENCES period,
    line_id      bigint,
    loan_id      bigint,
    corrects_id  bigint        REFERENCES transaction,
    reason       text,
    actor_id     bigint        REFERENCES member,
    posted_at    timestamptz   NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   bigint        REFERENCES member,
    CHECK (amount = granted_part + earned_part),
    CHECK (amount >= 0 OR (kind = 'EARNING' AND corrects_id IS NOT NULL))
);

CREATE INDEX transaction_team_period ON transaction (team_id, period_id);

-- The held amount of one line on Hold; deleted when the Hold commits or releases.
CREATE TABLE held_credit (
    line_id    bigint        PRIMARY KEY,
    team_id    bigint        NOT NULL REFERENCES team,
    amount     numeric(12,2) NOT NULL CHECK (amount >= 0),
    created_at timestamptz   NOT NULL DEFAULT now(),
    created_by bigint        REFERENCES member
);

-- One row per Team and closed Period; its key makes the rollover idempotent.
CREATE TABLE period_close (
    team_id         bigint        NOT NULL REFERENCES team,
    period_id       bigint        NOT NULL REFERENCES period,
    expired         numeric(12,2) NOT NULL CHECK (expired >= 0),
    closing_granted numeric(12,2) NOT NULL,
    closing_earned  numeric(12,2) NOT NULL,
    closed_at       timestamptz   NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      bigint        REFERENCES member,
    PRIMARY KEY (team_id, period_id)
);
