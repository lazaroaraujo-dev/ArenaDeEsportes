-- H-04/V4.2: exclusão administrativa segura de usuários.
-- Move a exclusão para uma função SECURITY DEFINER autocontida, executada
-- exclusivamente por arena_admin_writer, e remove o DELETE direto de
-- arena_runtime em public.usuario.
--
-- A função reúne, em uma única transação PostgreSQL:
--   advisory lock -> revalidação do ator -> validação do estado singleton ->
--   bloqueio do alvo -> checagem de agendamentos -> proteção do último ADMIN ->
--   DELETE -> auditoria.
--
-- Limite de confiança: o banco confia que a aplicação informa p_actor_user_id
-- a partir da identidade autenticada (SecurityContext), nunca a partir de
-- input do cliente. Sob o advisory lock a função revalida que o ator ainda
-- existe e continua com perfil ADMIN.
--
-- Contrato de retorno (mapeado pela aplicação):
--   SUCCESS           -> exclusão e auditoria SUCCEEDED
--   TARGET_NOT_FOUND  -> alvo inexistente, sem mutação e sem auditoria
--   LAST_ADMIN        -> rejeição auditada REJECTED/LAST_ADMIN
--   HAS_AGENDAMENTOS  -> rejeição auditada REJECTED/HAS_AGENDAMENTOS
--
-- Erros técnicos e de autorização lançam exceção e revertem a transação:
--   42501 (insufficient_privilege) -> ator inexistente ou não ADMIN
--   55000 (object_not_in_prerequisite_state) -> estado administrativo inconsistente
--
-- Owner: arena_migrator (Flyway). Executa como uma única transação.

-- ---------------------------------------------------------------------------
-- 1. A auditoria passa a distinguir exclusão de CLIENTE de exclusão de ADMIN.
-- O DROP é resolvido pelo catálogo para não depender do nome autogerado pela
-- constraint inline criada na V3.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_constraint_name TEXT;
BEGIN
    SELECT con.conname
      INTO v_constraint_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
      JOIN pg_attribute att
        ON att.attrelid = rel.oid
       AND att.attnum = ANY (con.conkey)
     WHERE nsp.nspname = 'public'
       AND rel.relname = 'admin_audit_event'
       AND con.contype = 'c'
       AND att.attname = 'operation'
     LIMIT 1;

    IF v_constraint_name IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE public.admin_audit_event DROP CONSTRAINT %I',
            v_constraint_name
        );
    END IF;
END $$;

ALTER TABLE public.admin_audit_event
    ADD CONSTRAINT admin_audit_event_operation_check
    CHECK (operation IN (
        'BOOTSTRAP', 'RECOVERY', 'PROMOTE', 'DEMOTE',
        'ADMIN_DELETE', 'CLIENT_DELETE',
        'PASSWORD_RESET', 'PASSWORD_CHANGE', 'RECONCILE'
    ));

-- ---------------------------------------------------------------------------
-- 2. Função autocontida de exclusão administrativa.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.h04_admin_excluir_usuario(
    p_actor_user_id BIGINT,
    p_target_user_id BIGINT,
    p_actor_ref VARCHAR(255),
    p_reason TEXT,
    p_change_ticket VARCHAR(128),
    p_request_id VARCHAR(128)
)
RETURNS TABLE (
    status VARCHAR(32),
    profile_before VARCHAR(16),
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
    v_has_agendamentos BOOLEAN;
BEGIN
    IF p_actor_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 actor user id is required'
            USING ERRCODE = '42501';
    END IF;

    IF p_target_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 target user id is required'
            USING ERRCODE = '22023';
    END IF;

    -- Serializa com bootstrap, recovery, reconcile, promoção, rebaixamento e
    -- demais exclusões. O trigger BEFORE DELETE em usuario usa a mesma chave.
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

    -- Bloqueia o alvo. O FOR UPDATE conflita com o FOR KEY SHARE adquirido
    -- por inserções de agendamento, fechando a corrida entre checagem e DELETE.
    SELECT u.perfil, u.email
      INTO v_profile_before, v_target_email
      FROM public.usuario u
     WHERE u.id = p_target_user_id
       FOR UPDATE;

    IF NOT FOUND THEN
        -- Preserva o contrato atual: 404 sem evento de auditoria.
        RETURN QUERY
            SELECT 'TARGET_NOT_FOUND'::VARCHAR(32),
                   NULL::VARCHAR(16),
                   NULL::VARCHAR(255);
        RETURN;
    END IF;

    IF v_profile_before NOT IN ('ADMIN', 'CLIENTE') THEN
        RAISE EXCEPTION 'H-04 unexpected target profile: %', v_profile_before
            USING ERRCODE = '55000';
    END IF;

    v_operation := CASE
        WHEN v_profile_before = 'ADMIN' THEN 'ADMIN_DELETE'
        ELSE 'CLIENT_DELETE'
    END;

    -- Exclusão física só é permitida sem agendamentos (FK ON DELETE NO ACTION).
    SELECT EXISTS (
        SELECT 1
          FROM public.agendamento a
         WHERE a.usuario_id = p_target_user_id
    ) INTO v_has_agendamentos;

    IF v_has_agendamentos THEN
        -- Rejeição previsível: audita e retorna sem lançar exceção, para que a
        -- aplicação possa comitar o evento antes de traduzir para HTTP 400.
        PERFORM public.h04_append_audit_event(
            v_operation, 'REJECTED', 'HTTP', 'USER',
            p_actor_user_id, v_actor_ref,
            p_target_user_id, v_target_email, v_profile_before, NULL,
            COALESCE(p_reason, 'Tentativa de exclusão de usuário com agendamentos'),
            p_change_ticket, p_request_id, 'HAS_AGENDAMENTOS'
        );

        RETURN QUERY
            SELECT 'HAS_AGENDAMENTOS'::VARCHAR(32),
                   v_profile_before,
                   v_target_email;
        RETURN;
    END IF;

    -- Proteção do último ADMIN na própria transação. Os triggers de contagem e
    -- o constraint trigger diferido continuam sendo o backstop do banco.
    IF v_profile_before = 'ADMIN' AND v_state.admin_count <= 1 THEN
        PERFORM public.h04_append_audit_event(
            v_operation, 'REJECTED', 'HTTP', 'USER',
            p_actor_user_id, v_actor_ref,
            p_target_user_id, v_target_email, v_profile_before, NULL,
            COALESCE(p_reason, 'Tentativa de exclusão do último administrador'),
            p_change_ticket, p_request_id, 'LAST_ADMIN'
        );

        RETURN QUERY
            SELECT 'LAST_ADMIN'::VARCHAR(32),
                   v_profile_before,
                   v_target_email;
        RETURN;
    END IF;

    DELETE FROM public.usuario u
     WHERE u.id = p_target_user_id;

    -- Mesma transação do DELETE: se a auditoria falhar, a exclusão é revertida.
    PERFORM public.h04_append_audit_event(
        v_operation, 'SUCCEEDED', 'HTTP', 'USER',
        p_actor_user_id, v_actor_ref,
        p_target_user_id, v_target_email, v_profile_before, NULL,
        p_reason, p_change_ticket, p_request_id, NULL
    );

    RETURN QUERY
        SELECT 'SUCCESS'::VARCHAR(32),
               v_profile_before,
               v_target_email;
END;
$$;

REVOKE ALL ON FUNCTION public.h04_admin_excluir_usuario(
    BIGINT, BIGINT, VARCHAR(255), TEXT, VARCHAR(128), VARCHAR(128)
) FROM PUBLIC, arena_runtime, arena_offline_operator;

GRANT EXECUTE ON FUNCTION public.h04_admin_excluir_usuario(
    BIGINT, BIGINT, VARCHAR(255), TEXT, VARCHAR(128), VARCHAR(128)
) TO arena_admin_writer;

-- ---------------------------------------------------------------------------
-- 3. arena_runtime deixa de poder excluir usuários diretamente.
-- ---------------------------------------------------------------------------
REVOKE DELETE ON TABLE public.usuario FROM arena_runtime;

-- ---------------------------------------------------------------------------
-- 4. Endurecimento do writer: a função é autocontida e acessa estado/auditoria
--    como arena_migrator. O writer não precisa de EXECUTE nas funções internas.
-- ---------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state()
    FROM arena_admin_writer;

REVOKE EXECUTE ON FUNCTION public.h04_append_audit_event(
    VARCHAR(32), VARCHAR(16), VARCHAR(32), VARCHAR(16), BIGINT, VARCHAR(255),
    BIGINT, VARCHAR(255), VARCHAR(16), VARCHAR(16), TEXT, VARCHAR(128),
    VARCHAR(128), VARCHAR(64)
) FROM arena_admin_writer;
