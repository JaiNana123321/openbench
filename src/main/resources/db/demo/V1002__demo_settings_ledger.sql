-- Demo world: Periods, planner settings, prices, Price Bands, Refund steps, bump cutoffs, the Quota,
-- and the October Grants with their Accounts (.scratch/demo1/demo-world.md). Runs only under the
-- Spring profile `demo`. Teams come from V1000 and Resource Types from V1001, found by name, never
-- by id; the Capacity Planner erin is recorded as the creator of the settings.

-- Fail loudly if an earlier seed lacks a name this one looks up, instead of seeding nothing for it.
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(expected.name || ' (' || expected.allocation_class || ')', ', ') INTO missing
    FROM (VALUES ('Meeting room', 'EXCLUSIVE'), ('Confocal microscope', 'EXCLUSIVE'), ('GPU', 'METERED'))
             AS expected(name, allocation_class)
    LEFT JOIN resource_type ON resource_type.name = expected.name
                           AND resource_type.allocation_class = expected.allocation_class
    WHERE resource_type.id IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V1002 needs these Resource Types from V1001: %', missing;
    END IF;

    SELECT string_agg(expected.name, ', ') INTO missing
    FROM (VALUES ('Platform'), ('Research'), ('Operations')) AS expected(name)
    LEFT JOIN team ON team.name = expected.name
    WHERE team.id IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'V1002 needs these Teams from V1000: %', missing;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM member WHERE username = 'erin') THEN
        RAISE EXCEPTION 'V1002 needs the demo Member erin from V1000';
    END IF;
END $$;

INSERT INTO period (starts_on, ends_on, created_by)
SELECT p.starts_on, p.ends_on, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES (DATE '2026-10-01', DATE '2026-10-31'), (DATE '2026-11-01', DATE '2026-11-30')) AS p(starts_on, ends_on)
ORDER BY p.starts_on;

-- V20 installs defaults under the same keys; these are the demo world's values.
INSERT INTO planner_setting (key, value, created_by)
SELECT s.key, s.value, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES
        ('period_months', '1'),
        ('priority_premium', '2.0'),
        ('objection_window_working_days', '2'),
        ('day_rate', '400.00')) AS s(key, value)
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, created_by = EXCLUDED.created_by;

-- Unit Prices per instance-hour (Exclusive) or unit-hour (Metered); a Metered type has no bump cutoff.
INSERT INTO type_pricing (type_id, unit_price, bump_cutoff_hours, created_by)
SELECT resource_type.id, p.unit_price, p.bump_cutoff_hours, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES
        ('Meeting room',        10.00, 24),
        ('Confocal microscope', 40.00, 24),
        ('GPU',                  5.00, NULL)) AS p(type_name, unit_price, bump_cutoff_hours)
JOIN resource_type ON resource_type.name = p.type_name;

-- The same bands for each type: Monday to Friday 09:00 to 17:00 at 1.5, and the whole of Saturday
-- and Sunday at 0.5 (an end of 00:00 is the midnight that ends the day). Other hours count at 1.0.
INSERT INTO price_band (type_id, weekdays, start_time, end_time, multiplier, created_by)
SELECT resource_type.id, b.weekdays, b.start_time, b.end_time, b.multiplier, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES ('Meeting room'), ('Confocal microscope'), ('GPU')) AS t(type_name)
CROSS JOIN (VALUES
        ('12345', TIME '09:00', TIME '17:00', 1.5),
        ('67',    TIME '00:00', TIME '00:00', 0.5)) AS b(weekdays, start_time, end_time, multiplier)
JOIN resource_type ON resource_type.name = t.type_name
ORDER BY resource_type.id, b.weekdays;

-- The same Refund Schedule for each type: 75% at seven days (168 hours) or more, 25% below that.
INSERT INTO refund_step (type_id, min_lead_hours, fraction, created_by)
SELECT resource_type.id, s.min_lead_hours, s.fraction, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES ('Meeting room'), ('Confocal microscope'), ('GPU')) AS t(type_name)
CROSS JOIN (VALUES (168, 0.75), (0, 0.25)) AS s(min_lead_hours, fraction)
JOIN resource_type ON resource_type.name = t.type_name
ORDER BY resource_type.id, s.min_lead_hours DESC;

-- Research may hold at most 8 GPU units concurrently.
INSERT INTO quota (team_id, type_id, max_units, created_by)
SELECT team.id, resource_type.id, 8, (SELECT id FROM member WHERE username = 'erin')
FROM team JOIN resource_type ON resource_type.name = 'GPU'
WHERE team.name = 'Research';

-- The October Grants: planned (a plan recurs until changed), then posted as Grant Transactions at
-- the start of October in the company zone by the system (no actor), with the Accounts that sum them.
INSERT INTO grant_plan (team_id, period_id, amount, created_by)
SELECT team.id, period.id, g.amount, (SELECT id FROM member WHERE username = 'erin')
FROM (VALUES ('Platform', 5000.00), ('Research', 600.00), ('Operations', 1000.00)) AS g(team_name, amount)
JOIN team ON team.name = g.team_name
JOIN period ON period.starts_on = DATE '2026-10-01'
ORDER BY team.id;

INSERT INTO transaction (team_id, kind, amount, granted_part, earned_part, period_id, posted_at)
SELECT g.team_id, 'GRANT', g.amount, g.amount, 0, g.period_id, TIMESTAMP '2026-10-01 00:00' AT TIME ZONE 'America/New_York'
FROM grant_plan g JOIN period ON period.id = g.period_id
WHERE period.starts_on = DATE '2026-10-01'
ORDER BY g.team_id;

INSERT INTO account (team_id, granted, earned, held)
SELECT g.team_id, g.amount, 0, 0
FROM grant_plan g JOIN period ON period.id = g.period_id
WHERE period.starts_on = DATE '2026-10-01'
ORDER BY g.team_id;
