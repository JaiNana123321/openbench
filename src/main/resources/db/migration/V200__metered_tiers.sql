-- Service tiers on Metered pools (SRS Metered.Tier.Define and Metered.Tier.Bump, issue #23).

-- The tiers of a Metered type, set by the Capacity Planner. A tier's premium multiplies the Unit
-- Price of a line marked with it, and ranks the tier: a line Bumps only lines of a strictly lower
-- premium, and a line without a tier ranks below every tier. Names and premiums are unique within a
-- type, so no two tiers of a type rank alike; both are checked at commit, so one change may swap two
-- tiers' premiums.
CREATE TABLE service_tier (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_id    bigint       NOT NULL REFERENCES resource_type,
    name       text         NOT NULL CHECK (length(btrim(name)) > 0),
    premium    numeric(6,3) NOT NULL CHECK (premium >= 1),
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by bigint       REFERENCES member,
    UNIQUE (type_id, name) DEFERRABLE INITIALLY DEFERRED,
    UNIQUE (type_id, premium) DEFERRABLE INITIALLY DEFERRED
);

-- A Metered line's tier, the way an Exclusive line is marked Priority. The foreign key refuses the
-- removal of a tier that a line refers to. tier_premium is the tier's premium locked at submission
-- with the Quote, the rank the line Bumps with; null while the line is a Draft or has no tier.
ALTER TABLE request_line
    ADD COLUMN tier_id      bigint       REFERENCES service_tier,
    ADD COLUMN tier_premium numeric(6,3),
    ADD CONSTRAINT request_line_tier_metered CHECK (tier_id IS NULL OR allocation_class = 'METERED');

CREATE INDEX request_line_tier_id ON request_line (tier_id) WHERE tier_id IS NOT NULL;

-- Allocation reads only its own tables, so each Metered row carries its line's tier premium and its
-- Request's submission time, copied when the row is written, as V140 and V160 did for Exclusive
-- occurrences. A row without a tier premium ranks below every tier; a null submission time counts as
-- the earliest. A row of a line Bumped by a higher-tier line becomes BUMPED.
ALTER TABLE metered_allocation
    ADD COLUMN tier_premium numeric(6,3),
    ADD COLUMN submitted_at timestamptz;

UPDATE metered_allocation m SET submitted_at = r.submitted_at
FROM request r
WHERE r.id = m.request_id;

ALTER TABLE metered_allocation DROP CONSTRAINT metered_allocation_state_check;
ALTER TABLE metered_allocation ADD CONSTRAINT metered_allocation_state_check
    CHECK (state IN ('HELD', 'ALLOCATED', 'RELEASED', 'BUMPED', 'EXPIRED'));

COMMENT ON COLUMN type_pricing.bump_cutoff_hours IS
    'Exclusive: no line starting sooner is Bumped. Metered: the same, and without one the type''s tiers set prices but never Bump.';
