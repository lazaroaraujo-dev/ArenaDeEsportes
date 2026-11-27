-- H-04/V4.5: remoção dos EXECUTE residuais das funções auxiliares.
--
-- h04_append_audit_event(...) e h04_get_admin_lifecycle_state() são funções
-- SECURITY DEFINER de propriedade de arena_migrator. Após V4.3/V4.4, nenhum
-- fluxo de produção chama essas funções diretamente pelo datasource de
-- arena_runtime:
--   - alteração de perfil e exclusão administrativa são funções autocontidas,
--     executadas por arena_admin_writer, que gravam auditoria como definer;
--   - bootstrap, recovery e reconcile gravam auditoria internamente como definer
--     em nome de arena_offline_operator;
--   - a leitura do estado administrativo pela aplicação não é mais necessária.
--
-- As chamadas internas (PERFORM) continuam funcionando porque são executadas
-- pelo próprio dono das funções. Não há mudança em DML, em funções
-- administrativas autorizadas, em default privileges ou em associação de roles.

REVOKE EXECUTE ON FUNCTION public.h04_append_audit_event(
    VARCHAR(32), VARCHAR(16), VARCHAR(32), VARCHAR(16), BIGINT, VARCHAR(255),
    BIGINT, VARCHAR(255), VARCHAR(16), VARCHAR(16), TEXT, VARCHAR(128),
    VARCHAR(128), VARCHAR(64)
) FROM arena_runtime, arena_offline_operator;

REVOKE EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state()
    FROM arena_runtime;
