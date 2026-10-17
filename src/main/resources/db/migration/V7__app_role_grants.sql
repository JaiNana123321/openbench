-- Privileges of the application's database role (design Section 6.2, "The append-only Ledger").
-- Flyway migrates as the schema owner; the application connects as openbench_app, which owns nothing.
-- The role itself is created by the database's init script (docker/postgres-init), so no password
-- lives in a migration.

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO openbench_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO openbench_app;

-- Tables that later migrations create get the same privileges; an append-only one revokes as below.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO openbench_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO openbench_app;

-- Append-only by grant, not by convention: Transactions, Request history, and the setting change log.
REVOKE UPDATE, DELETE ON transaction, request_event, setting_change FROM openbench_app;

-- Flyway's history belongs to the migration role alone.
REVOKE ALL ON flyway_schema_history FROM openbench_app;
