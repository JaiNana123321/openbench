-- Dashboard module: requested against used units (SRS Metered.Usage.Compare, Dashboard.Usage; issue
-- #24). One row per ALLOCATED Metered allocation with its Team, its type, the owners of the type's
-- in-service Resources (the Resource Owner's scope, as in v_type_hours), its units, and the units
-- recorded as used, null until recorded. As in V170, no time bound is read here: the query clips
-- each window to the Period it is given.
CREATE VIEW v_metered_usage AS
SELECT m.id AS allocation_id, m.team_id, tm.name AS team_name, m.type_id, t.name AS type_name,
       coalesce((SELECT array_agg(DISTINCT r.owner_member_id) FROM resource r
                 WHERE r.type_id = m.type_id AND r.retired_at IS NULL), '{}') AS owner_ids,
       m.units, m.used_units, m.starts_at, m.ends_at
FROM metered_allocation m
JOIN team tm ON tm.id = m.team_id
JOIN resource_type t ON t.id = m.type_id
WHERE m.state = 'ALLOCATED';

-- Read-only to the application, as the views of V170.
REVOKE INSERT, UPDATE, DELETE ON v_metered_usage FROM openbench_app;
