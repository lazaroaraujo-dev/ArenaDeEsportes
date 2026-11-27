-- H-04/V4.6: fechamento dos default privileges de arena_migrator em public.
--
-- V2 configurou, para todos os objetos futuros criados por arena_migrator:
--   - TABLES: SELECT, INSERT, UPDATE, DELETE para arena_runtime;
--   - SEQUENCES: USAGE, SELECT para arena_runtime.
--
-- Isso fazia com que qualquer tabela nova herdasse DML direto irrestrito ao
-- runtime, contornando as restrições conquistadas em V4.1 (INSERT em usuario),
-- V4.2 (DELETE em usuario) e V4.4 (UPDATE por coluna em usuario).
--
-- Esta migration altera apenas o template de ACL para objetos FUTUROS. Não
-- modifica o ACL de tabelas/sequences já existentes, que continuam com seus
-- grants explícitos de V2/V3/V4.
--
-- Cada migration futura deve conceder explicitamente somente o necessário.
-- Não há concessão genérica para substituir a que está sendo removida.

ALTER DEFAULT PRIVILEGES FOR ROLE arena_migrator IN SCHEMA public
    REVOKE ALL ON TABLES FROM arena_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE arena_migrator IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM arena_runtime;
