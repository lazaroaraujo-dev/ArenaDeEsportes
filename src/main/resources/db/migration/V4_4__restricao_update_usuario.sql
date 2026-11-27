-- H-04/V4.4: restrição de UPDATE em public.usuario para arena_runtime.
--
-- A aplicação normal só deve atualizar dados pessoais (nome, email, telefone).
-- Perfil e senha não podem ser alterados diretamente pelo runtime:
--   - alteração de perfil é feita por h04_admin_alterar_perfil(...), executada
--     por arena_admin_writer (V4.3);
--   - cadastro público é feito por h04_registrar_cliente(...) (V4.1).
--
-- Não há mudança em INSERT, DELETE ou SELECT. Não há mudança em quadra,
-- agendamento ou nas funções SECURITY DEFINER. Default privileges de V2 não
-- são alterados aqui (pertencem à V4.6).
--
-- Owner: arena_migrator (Flyway).

REVOKE UPDATE ON TABLE public.usuario FROM arena_runtime;

GRANT UPDATE (nome, email, telefone)
    ON TABLE public.usuario
    TO arena_runtime;
