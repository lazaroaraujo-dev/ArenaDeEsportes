-- H-04/V4.3: alteração administrativa de perfil executada apenas por
-- arena_admin_writer, através de uma função SECURITY DEFINER autocontida.
--
-- A função concentra, em uma única transação PostgreSQL:
--   advisory lock -> revalidação do ator -> validação do estado singleton ->
--   bloqueio do alvo -> decisão da transição -> proteção do último ADMIN ->
--   UPDATE -> auditoria.
--
-- O contador administrativo NÃO é manipulado aqui: os triggers existentes
-- (V3) continuam sendo a única autoridade para h04_update_admin_count e para
-- a validação diferida no commit.
--
-- Limite de confiança: p_actor_user_id é informado pela aplicação a partir do
-- principal autenticado do Spring Security. A função revalida o ator sob o lock.
--
-- Contrato de retorno:
--   SUCCESS          -> perfil alterado e auditoria SUCCEEDED
--   NO_OP            -> perfil já era o solicitado, sem mutação e sem auditoria
--   TARGET_NOT_FOUND -> alvo inexistente, sem mutação e sem auditoria
--   LAST_ADMIN       -> rejeição auditada REJECTED/LAST_ADMIN, sem exceção
--
-- Erros técnicos:
--   42501 -> ator ausente ou sem perfil ADMIN
--   22023 -> parâmetro obrigatório ausente/inválido
--   55000 -> estado administrativo inconsistente ou transição inesperada
--
-- Owner: arena_migrator (Flyway).

CREATE OR REPLACE FUNCTION public.h04_admin_alterar_perfil(
    p_actor_user_id BIGINT,
    p_target_user_id BIGINT,
    p_novo_perfil VARCHAR(16),
    p_actor_ref VARCHAR(255),
    p_reason TEXT,
    p_change_ticket VARCHAR(128),
    p_request_id VARCHAR(128)
)
RETURNS TABLE (
    status VARCHAR(32),
    profile_before VARCHAR(16),
    profile_after VARCHAR(16),
    target_email VARCHAR(255)
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_actor_profile VARCHAR(16);
    v_actor_email VARCHAR(255);
    v_actor_ref VARCHAR(255);
    v_state public.admin_lifecycle_state%ROWTYPE;
    v_real_admin_count BIGINT;
    v_profile_before VARCHAR(16);
    v_target_email VARCHAR(255);
    v_operation VARCHAR(32);
BEGIN
    IF p_actor_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 actor user id is required'
            USING ERRCODE = '42501';
    END IF;

    IF p_target_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 target user id is required'
            USING ERRCODE = '22023';
    END IF;

    IF p_novo_perfil IS NULL OR p_novo_perfil NOT IN ('ADMIN', 'CLIENTE') THEN
        RAISE EXCEPTION 'H-04 invalid target profile: %', p_novo_perfil
            USING ERRCODE = '22023';
    END IF;

    -- Mesma chave usada por bootstrap, recovery, reconcile, exclusão e pelos
    -- triggers de usuario. A reentrância ocorre na mesma sessão/transação.
    PERFORM pg_advisory_xact_lock(public.h04_admin_lifecycle_lock_key());

    -- Revalidação do ator sob o lock (proteção contra TOCTOU).
    SELECT u.perfil, u.email
      INTO v_actor_profile, v_actor_email
      FROM public.usuario u
     WHERE u.id = p_actor_user_id;

    IF NOT FOUND OR v_actor_profile <> 'ADMIN' THEN
        RAISE EXCEPTION 'H-04 actor % is not an ADMIN', p_actor_user_id
            USING ERRCODE = '42501';
    END IF;

    v_actor_ref := COALESCE(NULLIF(btrim(p_actor_ref), ''), v_actor_email);

    -- Validação fail-closed do estado singleton, sob o mesmo lock.
    SELECT s.*
      INTO v_state
      FROM public.admin_lifecycle_state s
     WHERE s.singleton_id = 1
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 admin lifecycle state is missing'
            USING ERRCODE = '55000';
    END IF;

    SELECT COUNT(*)
      INTO v_real_admin_count
      FROM public.usuario u
     WHERE u.perfil = 'ADMIN';

    IF v_state.admin_count <> v_real_admin_count THEN
        RAISE EXCEPTION
            'H-04 admin_count (%) diverges from real admin count (%)',
            v_state.admin_count, v_real_admin_count
            USING ERRCODE = '55000';
    END IF;

    IF v_state.bootstrap_consumed = false OR v_state.recovery_required = true THEN
        RAISE EXCEPTION
            'H-04 inconsistent lifecycle state: bootstrap_consumed=%, recovery_required=%',
            v_state.bootstrap_consumed, v_state.recovery_required
            USING ERRCODE = '55000';
    END IF;

    -- Bloqueia o alvo antes de decidir a transição.
    SELECT u.perfil, u.email
      INTO v_profile_before, v_target_email
      FROM public.usuario u
     WHERE u.id = p_target_user_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RETURN QUERY
            SELECT 'TARGET_NOT_FOUND'::VARCHAR(32),
                   NULL::VARCHAR(16),
                   NULL::VARCHAR(16),
                   NULL::VARCHAR(255);
        RETURN;
    END IF;

    IF v_profile_before NOT IN ('ADMIN', 'CLIENTE') THEN
        RAISE EXCEPTION 'H-04 unexpected target profile: %', v_profile_before
            USING ERRCODE = '55000';
    END IF;

    IF v_profile_before = p_novo_perfil THEN
        -- NO_OP não altera dados e não grava auditoria.
        RETURN QUERY
            SELECT 'NO_OP'::VARCHAR(32),
                   v_profile_before,
                   v_profile_before,
                   v_target_email;
        RETURN;
    END IF;

    v_operation := CASE
        WHEN v_profile_before = 'CLIENTE' AND p_novo_perfil = 'ADMIN' THEN 'PROMOTE'
        WHEN v_profile_before = 'ADMIN' AND p_novo_perfil = 'CLIENTE' THEN 'DEMOTE'
        ELSE NULL
    END;

    IF v_operation IS NULL THEN
        RAISE EXCEPTION
            'H-04 unsupported profile transition: % -> %',
            v_profile_before, p_novo_perfil
            USING ERRCODE = '55000';
    END IF;

    IF v_operation = 'DEMOTE' AND v_state.admin_count <= 1 THEN
        -- Rejeição previsível: audita e retorna sem lançar exceção, para que a
        -- aplicação possa comitar o evento antes de traduzir para HTTP 409.
        PERFORM public.h04_append_audit_event(
            v_operation, 'REJECTED', 'HTTP', 'USER',
            p_actor_user_id, v_actor_ref,
            p_target_user_id, v_target_email,
            v_profile_before, p_novo_perfil,
            COALESCE(p_reason, 'Tentativa de rebaixamento do último administrador'),
            p_change_ticket, p_request_id, 'LAST_ADMIN'
        );

        RETURN QUERY
            SELECT 'LAST_ADMIN'::VARCHAR(32),
                   v_profile_before,
                   v_profile_before,
                   v_target_email;
        RETURN;
    END IF;

    -- Os triggers de V3 atualizam e validam o contador; não duplicar essa lógica.
    UPDATE public.usuario u
       SET perfil = p_novo_perfil
     WHERE u.id = p_target_user_id;

    -- Mesma transação do UPDATE: se a auditoria falhar, a alteração é revertida.
    PERFORM public.h04_append_audit_event(
        v_operation, 'SUCCEEDED', 'HTTP', 'USER',
        p_actor_user_id, v_actor_ref,
        p_target_user_id, v_target_email,
        v_profile_before, p_novo_perfil,
        p_reason, p_change_ticket, p_request_id, NULL
    );

    RETURN QUERY
        SELECT 'SUCCESS'::VARCHAR(32),
               v_profile_before,
               p_novo_perfil,
               v_target_email;
END;
$$;

REVOKE ALL ON FUNCTION public.h04_admin_alterar_perfil(
    BIGINT, BIGINT, VARCHAR(16), VARCHAR(255), TEXT, VARCHAR(128), VARCHAR(128)
) FROM PUBLIC, arena_runtime, arena_offline_operator;

GRANT EXECUTE ON FUNCTION public.h04_admin_alterar_perfil(
    BIGINT, BIGINT, VARCHAR(16), VARCHAR(255), TEXT, VARCHAR(128), VARCHAR(128)
) TO arena_admin_writer;
