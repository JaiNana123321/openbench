-- Settings module: every Capacity Planner setting as data, with a change log (design Sections 4.3 and 6).

-- Periods are contiguous days, inclusive at both ends, and never overlap.
CREATE TABLE period (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    starts_on  date        NOT NULL,
    ends_on    date        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    CHECK (starts_on <= ends_on),
    CONSTRAINT period_no_overlap EXCLUDE USING gist (daterange(starts_on, ends_on, '[]') WITH &&)
);

-- A Team's Grant for a Period: that Period's row, or else the Team's most recent earlier one.
CREATE TABLE grant_plan (
    team_id    bigint        NOT NULL REFERENCES team,
    period_id  bigint        NOT NULL REFERENCES period,
    amount     numeric(12,2) NOT NULL CHECK (amount >= 0),
    created_at timestamptz   NOT NULL DEFAULT now(),
    created_by bigint        REFERENCES member,
    PRIMARY KEY (team_id, period_id)
);

-- Exclusive and Metered types only. bump_cutoff_hours is null for a Metered type, which has no Bump.
CREATE TABLE type_pricing (
    type_id           bigint        PRIMARY KEY REFERENCES resource_type,
    unit_price        numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    bump_cutoff_hours integer       CHECK (bump_cutoff_hours >= 0),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        bigint        REFERENCES member
);

-- weekdays holds ISO day numbers, 1 (Monday) to 7 (Sunday): '12345' is Monday to Friday, '67' the
-- weekend. The band covers [start_time, end_time) on those days; an end_time of 00:00 means the
-- midnight that ends the day, so 00:00 to 00:00 is the whole day.
CREATE TABLE price_band (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_id    bigint       NOT NULL REFERENCES resource_type,
    weekdays   text         NOT NULL CHECK (weekdays ~ '^[1-7]{1,7}$'),
    start_time time         NOT NULL,
    end_time   time         NOT NULL,
    multiplier numeric(6,3) NOT NULL CHECK (multiplier > 0),
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint       REFERENCES member,
    CHECK (start_time < end_time OR end_time = '00:00')
);

CREATE INDEX price_band_type_id ON price_band (type_id);

-- One step of a Refund Schedule: a Release at least min_lead_hours before the start returns fraction
-- of the Charge. A Refund never reaches 100%.
CREATE TABLE refund_step (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_id        bigint       NOT NULL REFERENCES resource_type,
    min_lead_hours integer      NOT NULL CHECK (min_lead_hours >= 0),
    fraction       numeric(4,3) NOT NULL CHECK (fraction >= 0 AND fraction < 1),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     bigint       REFERENCES member,
    UNIQUE (type_id, min_lead_hours)
);

-- An optional cap on the units of one Metered type a Team may hold concurrently.
CREATE TABLE quota (
    team_id    bigint      NOT NULL REFERENCES team,
    type_id    bigint      NOT NULL REFERENCES resource_type,
    max_units  integer     NOT NULL CHECK (max_units > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member,
    PRIMARY KEY (team_id, type_id)
);

-- Single-valued settings, as text parsed by SettingsService.
CREATE TABLE planner_setting (
    key        text        PRIMARY KEY
               CHECK (key IN ('day_rate', 'priority_premium', 'objection_window_working_days', 'period_months')),
    value      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member
);

-- One row per setting change, written in the same transaction as the change. Insert-only (V7).
CREATE TABLE setting_change (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key        text        NOT NULL,
    old_value  text,
    new_value  text,
    actor_id   bigint      REFERENCES member,
    at         timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by bigint      REFERENCES member
);
