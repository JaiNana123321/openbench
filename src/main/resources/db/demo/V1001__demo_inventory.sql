-- Demo world: Resource Types, their attributes, and Resources (.scratch/demo1/demo-world.md). Runs
-- only under the Spring profile `demo`. The Administrator carol defines the types and the Resource
-- Owner dave owns every Resource; both are found by username, never by id. Person comes from V2.
-- The demo itself defines "3D printer" through the Administrator screen, so it is not seeded.

DO $$
BEGIN
    IF (SELECT count(*) FROM member WHERE username IN ('carol', 'dave')) <> 2 THEN
        RAISE EXCEPTION 'V1001 needs the demo Members carol and dave from V1000';
    END IF;
END $$;

INSERT INTO resource_type (name, allocation_class, created_by)
SELECT t.name, t.allocation_class, (SELECT id FROM member WHERE username = 'carol')
FROM (VALUES
        (1, 'Meeting room',        'EXCLUSIVE'),
        (2, 'Confocal microscope', 'EXCLUSIVE'),
        (3, 'GPU',                 'METERED')) AS t(position, name, allocation_class)
ORDER BY t.position;

INSERT INTO resource_type_attribute (type_id, name, kind, allowed_values, created_by)
SELECT resource_type.id, a.name, a.kind, a.allowed_values, (SELECT id FROM member WHERE username = 'carol')
FROM (VALUES
        (1, 'Meeting room',        'seats', 'NUMBER', NULL::text[]),
        (2, 'Meeting room',        'wing',  'LIST',   ARRAY['North', 'South']),
        (3, 'Confocal microscope', 'lab',   'TEXT',   NULL::text[]),
        (4, 'GPU',                 'model', 'LIST',   ARRAY['A100', 'H100'])) AS a(position, type_name, name, kind, allowed_values)
JOIN resource_type ON resource_type.name = a.type_name
ORDER BY a.position;

-- An Exclusive Resource has units 1; GPU's Pool Capacity is 8 + 8 = 16.
INSERT INTO resource (type_id, name, owner_member_id, units, created_by)
SELECT resource_type.id, r.name, member.id, r.units, member.id
FROM (VALUES
        (1, 'Meeting room',        'Room A',       1),
        (2, 'Meeting room',        'Room B',       1),
        (3, 'Confocal microscope', 'Microscope 1', 1),
        (4, 'GPU',                 'gpu-node-a',   8),
        (5, 'GPU',                 'gpu-node-b',   8)) AS r(position, type_name, name, units)
JOIN resource_type ON resource_type.name = r.type_name
JOIN member ON member.username = 'dave'
ORDER BY r.position;

INSERT INTO resource_attribute_value (resource_id, attribute_id, value, created_by)
SELECT resource.id, resource_type_attribute.id, v.value, resource.owner_member_id
FROM (VALUES
        ('Room A',       'seats', '8'),
        ('Room A',       'wing',  'North'),
        ('Room B',       'seats', '12'),
        ('Room B',       'wing',  'South'),
        ('Microscope 1', 'lab',   'Imaging lab'),
        ('gpu-node-a',   'model', 'H100'),
        ('gpu-node-b',   'model', 'A100')) AS v(resource_name, attribute_name, value)
JOIN resource ON resource.name = v.resource_name
JOIN resource_type_attribute ON resource_type_attribute.type_id = resource.type_id
                            AND resource_type_attribute.name = v.attribute_name;
