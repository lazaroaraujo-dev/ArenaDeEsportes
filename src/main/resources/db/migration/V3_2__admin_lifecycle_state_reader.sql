-- Migration V3.2: Funcao SECURITY DEFINER de leitura do estado administrativo.
-- Permite que a aplicacao web (conectada como arena_runtime) consulte de forma segura
-- a consistencia do estado singleton e a contagem de ADMINs, sem necessitar de
-- permissao direta de SELECT sobre a tabela admin_lifecycle_state.
-- Esta funcao nao adquire advisory lock por conta propria. A aquisicao do
-- advisory lock e responsabilidade dos chamadores, e os chamadores de producao
-- devem adquirir o lock antes de chamar esta funcao.
-- Owner: arena_migrator (Flyway)

CREATE OR REPLACE FUNCTION public.h04_get_admin_lifecycle_state(
    OUT p_admin_count BIGINT,
    OUT p_bootstrap_consumed BOOLEAN,
    OUT p_recovery_required BOOLEAN
)
RETURNS RECORD
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_state public.admin_lifecycle_state%ROWTYPE;
BEGIN
    SELECT * INTO v_state
    FROM public.admin_lifecycle_state
    WHERE singleton_id = 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 admin lifecycle state is missing';
    END IF;

    p_admin_count := v_state.admin_count;
    p_bootstrap_consumed := v_state.bootstrap_consumed;
    p_recovery_required := v_state.recovery_required;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state() FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state()
    TO arena_runtime, arena_admin_writer;
