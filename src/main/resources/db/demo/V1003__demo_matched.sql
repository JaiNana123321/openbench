-- Demo world: the Matched data, item 5 of the test world (test case document, Section "Test World").
-- Runs only under the Spring profile `demo`. The Administrator carol keeps the skill list; each
-- Member enters their own skills and availability, so they are recorded as the creators. Members are
-- found by username, never by id.
--
-- Availability runs from the day the database is created to D+60, where D is the first Wednesday at
-- least 14 days later, in the company zone (${zone}): the demo's Day D. Every other Member has no
-- skills and no availability.

DO $$
BEGIN
    IF (SELECT count(*) FROM member WHERE username IN ('carol', 'ben', 'rita', 'dave')) <> 4 THEN
        RAISE EXCEPTION 'V1003 needs the demo Members carol, ben, rita, and dave from V1000';
    END IF;
END $$;

INSERT INTO skill (name, created_by)
SELECT s.name, (SELECT id FROM member WHERE username = 'carol')
FROM (VALUES (1, 'Statistics'), (2, 'Java'), (3, 'Microscopy')) AS s(position, name)
ORDER BY s.position;

INSERT INTO member_skill (member_id, skill_id, created_by)
SELECT member.id, skill.id, member.id
FROM (VALUES
        ('ben',  'Java'),
        ('rita', 'Statistics'),
        ('rita', 'Microscopy'),
        ('dave', 'Statistics')) AS ms(username, skill_name)
JOIN member ON member.username = ms.username
JOIN skill ON skill.name = ms.skill_name;

WITH today AS (
    SELECT (now() AT TIME ZONE '${zone}')::date AS day
), day_d AS (
    SELECT day AS today,
           day + 14 + (3 - extract(isodow FROM day + 14)::int + 7) % 7 AS d
    FROM today
)
INSERT INTO availability_range (member_id, from_date, to_date, created_by)
SELECT member.id, day_d.today, day_d.d + 60, member.id
FROM member CROSS JOIN day_d
WHERE member.username IN ('ben', 'rita', 'dave')
ORDER BY member.id;
