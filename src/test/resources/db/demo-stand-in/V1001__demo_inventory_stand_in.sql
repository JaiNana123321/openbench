-- TEST ONLY. A stand-in for db/demo/V1001__demo_inventory.sql (brief 03, Inventory) with just the
-- Resource Types that V1002 looks up by name. DemoSeedTest applies it only while the real V1001 is
-- not on the classpath; once V1001 is merged, delete this file and that condition.
INSERT INTO resource_type (name, allocation_class) VALUES
    ('Meeting room', 'EXCLUSIVE'),
    ('Confocal microscope', 'EXCLUSIVE'),
    ('GPU', 'METERED');
