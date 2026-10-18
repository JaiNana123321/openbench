-- Runs once when the PostgreSQL container initializes its data directory (compose.yaml and the
-- Testcontainers base class PostgresTest). Creates the login role the application uses in
-- production; migration V7 grants it its privileges. This password is for local use only.
CREATE ROLE openbench_app LOGIN PASSWORD 'openbench_app';
