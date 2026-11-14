-- Bumps (SRS Exclusive.Bump.Effect, issue #9): a Required line Bumped by a Priority line waits at the
-- head of its waitlist. The mark stays on the line, so the line keeps its place when it leaves the
-- waitlist and joins it again (a waitlist fill that undoes its own Holds).
ALTER TABLE request_line ADD COLUMN bumped boolean NOT NULL DEFAULT false;
