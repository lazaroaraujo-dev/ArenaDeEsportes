-- One-time provisioning for a newly created database.
-- Execute only with an approved provisioning administrator against a new target.
-- This file is never mounted into docker-entrypoint-initdb.d and is not automatic.
\set ON_ERROR_STOP on

\getenv arena_database ARENA_DATABASE
\getenv runtime_password ARENA_RUNTIME_PASSWORD
\getenv migrator_password ARENA_MIGRATOR_PASSWORD
\getenv admin_writer_password ARENA_ADMIN_WRITER_PASSWORD
\getenv offline_operator_password ARENA_OFFLINE_OPERATOR_PASSWORD
\getenv provision_confirmation ARENA_PROVISION_NEW_DATABASE

SELECT current_database() = :'arena_database' AS target_matches \gset
\if :target_matches
\else
    \echo 'Provisioning stopped: connection does not match ARENA_DATABASE.'
    \quit 3
\endif

SELECT :'provision_confirmation' = 'YES'
   AND :'runtime_password' <> ''
   AND :'migrator_password' <> ''
   AND :'admin_writer_password' <> ''
   AND :'offline_operator_password' <> ''
   AND :'runtime_password' <> :'migrator_password'
   AND :'runtime_password' <> :'admin_writer_password'
   AND :'runtime_password' <> :'offline_operator_password'
   AND :'migrator_password' <> :'admin_writer_password'
   AND :'migrator_password' <> :'offline_operator_password'
   AND :'admin_writer_password' <> :'offline_operator_password'
    AS provisioning_inputs_valid \gset
\if :provisioning_inputs_valid
\else
    \echo 'Provisioning stopped: explicit confirmation and four distinct non-empty passwords are required.'
    \quit 3
\endif

BEGIN;

CREATE ROLE arena_runtime
    LOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'runtime_password';

CREATE ROLE arena_migrator
    LOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'migrator_password';

CREATE ROLE arena_admin_writer
    LOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'admin_writer_password';

CREATE ROLE arena_offline_operator
    LOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    PASSWORD :'offline_operator_password';

GRANT CONNECT ON DATABASE :"arena_database"
    TO arena_runtime, arena_migrator, arena_admin_writer, arena_offline_operator;

REVOKE CONNECT, CREATE, TEMPORARY
    ON DATABASE :"arena_database"
    FROM PUBLIC;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT CREATE ON DATABASE :"arena_database" TO arena_migrator;
GRANT USAGE ON SCHEMA public TO arena_runtime;
GRANT USAGE, CREATE ON SCHEMA public TO arena_migrator;
GRANT USAGE ON SCHEMA public TO arena_admin_writer, arena_offline_operator;

-- btree_gist is trusted in PostgreSQL 16. Install under the migrator's
-- effective identity, then immediately revoke its database-level CREATE.
SET ROLE arena_migrator;
CREATE EXTENSION IF NOT EXISTS btree_gist;
RESET ROLE;

REVOKE CREATE ON DATABASE :"arena_database" FROM arena_migrator;

-- No table, sequence or function grants are given to writer/offline here.
-- V3/V4 will grant EXECUTE only on the explicitly allowlisted functions.
-- This script remains one-time and non-idempotent; it fails if any role exists.

COMMIT;
