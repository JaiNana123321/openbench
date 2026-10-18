-- Demo world: Teams, Members, and roles (.scratch/demo1/demo-world.md). Runs only under the Spring
-- profile `demo`. Every Member's password is `demo`; emails are <username>@openbench.example.
-- Later seeds find these rows by name or username, never by id.

INSERT INTO team (name)
SELECT name FROM (VALUES (1, 'Platform'), (2, 'Research'), (3, 'Operations')) AS t(position, name)
ORDER BY position;

-- bcrypt of "demo", in the format of Spring's delegating password encoder.
INSERT INTO member (team_id, name, email, username, password_hash)
SELECT team.id, m.name, m.username || '@openbench.example', m.username,
       '{bcrypt}$2a$10$bmSkvXCL4jiOSsfu3cK9t.bhrMZOKz/fIbhCL4JxQQ2oZSJ5fcUB6'
FROM (VALUES
        (1, 'alice', 'Alice Chen',  'Platform'),
        (2, 'ben',   'Ben Ortiz',   'Platform'),
        (3, 'bob',   'Bob Patel',   'Research'),
        (4, 'rita',  'Rita Gomez',  'Research'),
        (5, 'oscar', 'Oscar Reed',  'Operations'),
        (6, 'carol', 'Carol Davis', 'Operations'),
        (7, 'dave',  'Dave Kim',    'Operations'),
        (8, 'erin',  'Erin Walsh',  'Operations')) AS m(position, username, name, team_name)
JOIN team ON team.name = m.team_name
ORDER BY m.position;

-- Roles besides MEMBER, which every Member holds without a row.
INSERT INTO member_role (member_id, team_id, role)
SELECT member.id, member.team_id, r.role
FROM (VALUES
        ('alice', 'MANAGER'),
        ('bob',   'MANAGER'),
        ('oscar', 'MANAGER'),
        ('carol', 'ADMIN'),
        ('dave',  'RESOURCE_OWNER'),
        ('erin',  'PLANNER')) AS r(username, role)
JOIN member ON member.username = r.username;

-- Each Team's Manager, mirrored from member_role.
UPDATE team
SET manager_member_id = member_role.member_id
FROM member_role
WHERE member_role.team_id = team.id AND member_role.role = 'MANAGER';
