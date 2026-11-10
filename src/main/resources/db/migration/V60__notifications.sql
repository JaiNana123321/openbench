-- Notifications module: in-app notifications (design Sections 4.4 and 6). A notice is written in the
-- transaction of the action that raised it; in Release 1 the row is the delivery.

-- created_at is when the action happened, by the application's Clock; read_at is set at most once.
-- kind is a NotificationKind name; it has no CHECK, so a new kind needs no migration.
CREATE TABLE notification (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id  bigint      NOT NULL REFERENCES member,
    kind       text        NOT NULL,
    text       text        NOT NULL,
    link       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz,
    created_by bigint      REFERENCES member
);

-- Every screen asks for the signed-in Member's unread notices.
CREATE INDEX notification_unread ON notification (member_id) WHERE read_at IS NULL;

-- No notice is deleted (design Section 4.4, Notifications invariants); marking one read is an update.
REVOKE DELETE ON notification FROM openbench_app;
