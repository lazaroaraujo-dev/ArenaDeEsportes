-- H-04/B1: revoga EXECUTE das assinaturas legadas de bootstrap e recovery.
-- As assinaturas novas continuam sendo as únicas autorizadas para
-- arena_offline_operator e exigem contexto completo de auditoria.

REVOKE ALL ON FUNCTION public.h04_bootstrap_first_admin(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) FROM PUBLIC, arena_offline_operator;

REVOKE ALL ON FUNCTION public.h04_recover_admin(
    BIGINT, VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) FROM PUBLIC, arena_offline_operator;

-- Preserva explicitamente os grants das assinaturas novas.
GRANT EXECUTE ON FUNCTION public.h04_bootstrap_first_admin(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) TO arena_offline_operator;

GRANT EXECUTE ON FUNCTION public.h04_recover_admin(
    BIGINT, VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255),
    VARCHAR(255), TEXT, VARCHAR(128)
) TO arena_offline_operator;
