GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE public.usuario, public.quadra, public.agendamento
    TO arena_runtime;

GRANT USAGE, SELECT
    ON ALL SEQUENCES IN SCHEMA public
    TO arena_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE arena_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO arena_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE arena_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO arena_runtime;

-- Flyway's history table is created before V2. Keep its metadata migrator-only.
REVOKE ALL PRIVILEGES
    ON TABLE public.flyway_schema_history
    FROM arena_runtime;
