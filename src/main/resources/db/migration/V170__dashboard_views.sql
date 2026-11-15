-- Dashboard module: the three read-only views the showback dashboard reads (design Section 4.7,
-- Dashboard, "Data owned"; Section 6, the Dashboard views). Plain views, not materialized: each read
-- sees every row committed before its statement began (SRS Dashboard.Fresh). No view reads now():
-- every time bound is a parameter of the query that reads the view, from the injected Clock.

-- Allocated spans per Exclusive or Metered type. One row per Allocated occurrence or Metered
-- allocation (a type with none has one row with null span columns), carrying its type's capacity
-- and the owners of its Resources for the Resource Owner's scope. A query clips each span to its
-- window and sums amount x hours: instance-hours for Exclusive, unit-hours for Metered. Capacity is
-- the count of in-service instances for Exclusive and the current Pool Capacity for Metered (unit
-- changes keep no history). Only ALLOCATED rows count: a Hold is not utilization.
CREATE VIEW v_type_hours AS
WITH types AS (
    SELECT t.id, t.name, t.allocation_class, t.retired_at,
           CASE t.allocation_class WHEN 'EXCLUSIVE' THEN count(r.id) ELSE coalesce(sum(r.units), 0) END AS capacity,
           coalesce(array_agg(DISTINCT r.owner_member_id) FILTER (WHERE r.id IS NOT NULL), '{}') AS owner_ids
    FROM resource_type t
    LEFT JOIN resource r ON r.type_id = t.id AND r.retired_at IS NULL
    WHERE t.allocation_class IN ('EXCLUSIVE', 'METERED')
    GROUP BY t.id, t.name, t.allocation_class, t.retired_at
), spans AS (
    SELECT r.type_id, 1 AS amount, o.starts_at, o.ends_at
    FROM exclusive_occurrence o JOIN resource r ON r.id = o.resource_id
    WHERE o.state = 'ALLOCATED'
    UNION ALL
    SELECT m.type_id, m.units AS amount, m.starts_at, m.ends_at
    FROM metered_allocation m
    WHERE m.state = 'ALLOCATED'
)
SELECT types.id AS type_id, types.name AS type_name, types.allocation_class, types.retired_at,
       types.capacity, types.owner_ids, spans.amount, spans.starts_at, spans.ends_at
FROM types LEFT JOIN spans ON spans.type_id = types.id;

-- Waiting lines: one row per line on a Waitlist, with its type and window start.
CREATE VIEW v_waiting AS
SELECT line_id, type_id, starts_at, joined_at FROM waitlist_entry;

-- Credits per Team and Period: the Period's Transactions summed by kind; the granted credits that
-- expired when it closed (period_close, as the Statement reads them); the closing granted and earned
-- credits, folded from every Transaction and close up to and including the Period, as the Statement
-- folds them; the Account as it stands now (current Balance and Available Balance); and the
-- person-days lent and borrowed: the Monday-to-Friday days of each Loan that fall in the Period,
-- which are its days_lent when the Loan lies inside it (an early end shortens ends_on).
CREATE VIEW v_team_credits AS
SELECT t.id AS team_id, t.name AS team_name, p.id AS period_id, p.starts_on, p.ends_on,
       coalesce(k.grants, 0)::numeric(12,2) AS grants,
       coalesce(k.charges, 0)::numeric(12,2) AS charges,
       coalesce(k.earnings, 0)::numeric(12,2) AS earnings,
       coalesce(k.refunds, 0)::numeric(12,2) AS refunds,
       coalesce(pc.expired, 0)::numeric(12,2) AS expired,
       (coalesce(f.granted, 0) - coalesce(e.expired, 0))::numeric(12,2) AS closing_granted,
       coalesce(f.earned, 0)::numeric(12,2) AS closing_earned,
       coalesce(a.granted, 0)::numeric(12,2) AS current_granted,
       coalesce(a.earned, 0)::numeric(12,2) AS current_earned,
       coalesce(a.held, 0)::numeric(12,2) AS current_held,
       coalesce(d.lent, 0)::integer AS person_days_lent,
       coalesce(d.borrowed, 0)::integer AS person_days_borrowed
FROM team t
CROSS JOIN period p
LEFT JOIN LATERAL (
    SELECT sum(x.amount) FILTER (WHERE x.kind = 'GRANT') AS grants,
           sum(x.amount) FILTER (WHERE x.kind = 'CHARGE') AS charges,
           sum(x.amount) FILTER (WHERE x.kind = 'EARNING') AS earnings,
           sum(x.amount) FILTER (WHERE x.kind = 'REFUND') AS refunds
    FROM transaction x
    WHERE x.team_id = t.id AND x.period_id = p.id
) k ON true
LEFT JOIN period_close pc ON pc.team_id = t.id AND pc.period_id = p.id
LEFT JOIN LATERAL (
    SELECT sum(CASE x.kind WHEN 'CHARGE' THEN -x.granted_part ELSE x.granted_part END) AS granted,
           sum(CASE x.kind WHEN 'CHARGE' THEN -x.earned_part ELSE x.earned_part END) AS earned
    FROM transaction x JOIN period q ON q.id = x.period_id
    WHERE x.team_id = t.id AND q.starts_on <= p.starts_on
) f ON true
LEFT JOIN LATERAL (
    SELECT sum(c.expired) AS expired
    FROM period_close c JOIN period q ON q.id = c.period_id
    WHERE c.team_id = t.id AND q.starts_on <= p.starts_on
) e ON true
LEFT JOIN account a ON a.team_id = t.id
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE l.lending_team_id = t.id) AS lent,
           count(*) FILTER (WHERE l.borrowing_team_id = t.id) AS borrowed
    FROM loan l
    CROSS JOIN LATERAL generate_series(greatest(l.starts_on, p.starts_on), least(l.ends_on, p.ends_on),
                                       interval '1 day') AS day(at)
    WHERE (l.lending_team_id = t.id OR l.borrowing_team_id = t.id)
      AND l.starts_on <= p.ends_on AND l.ends_on >= p.starts_on
      AND extract(isodow FROM day.at) < 6
) d ON true
WHERE t.retired_at IS NULL OR t.retired_at >= p.starts_on;

-- Read-only to the application: the default privileges of V7 would otherwise let it write through
-- the simple view v_waiting into waitlist_entry.
REVOKE INSERT, UPDATE, DELETE ON v_type_hours, v_waiting, v_team_credits FROM openbench_app;
