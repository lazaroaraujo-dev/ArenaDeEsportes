-- H-04: adiciona contexto de auditoria (identidade do operador, justificativa
-- e ticket) aos comandos bootstrap e recovery. Migration incremental sobre V3.
-- Owner: arena_migrator (Flyway).

-- ---------------------------------------------------------------------------
-- Bootstrap: agora recebe identidade do operador e registra no audit.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.h04_bootstrap_first_admin(
    p_nome VARCHAR(255),
    p_email VARCHAR(255),
    p_telefone VARCHAR(255),
    p_senha_hash VARCHAR(255),
    p_actor_ref VARCHAR(255)
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_state public.admin_lifecycle_state%ROWTYPE;
    v_user_id BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(public.h04_admin_lifecycle_lock_key());

    SELECT * INTO v_state
    FROM public.admin_lifecycle_state
    WHERE singleton_id = 1
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 admin lifecycle state is missing';
    END IF;

    IF v_state.recovery_required THEN
        RAISE EXCEPTION 'H-04 cannot bootstrap while recovery is required';
    END IF;

    IF v_state.bootstrap_consumed THEN
        RAISE EXCEPTION 'H-04 bootstrap already consumed';
    END IF;

    IF v_state.admin_count <> 0 THEN
        RAISE EXCEPTION 'H-04 bootstrap requires admin_count=0';
    END IF;

    IF EXISTS (SELECT 1 FROM public.usuario WHERE perfil = 'ADMIN') THEN
        RAISE EXCEPTION 'H-04 bootstrap requires zero real admins';
    END IF;

    INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
    VALUES (p_nome, p_email, p_telefone, p_senha_hash, 'ADMIN')
    RETURNING id INTO v_user_id;

    PERFORM public.h04_consume_bootstrap_marker();

    PERFORM public.h04_append_audit_event(
        'BOOTSTRAP', 'SUCCEEDED', 'BOOTSTRAP_CLI',
        'OPERATOR', NULL, COALESCE(p_actor_ref, 'bootstrap-cli'),
        v_user_id, p_email, NULL, 'ADMIN',
        NULL, NULL, NULL, NULL
    );

    RETURN v_user_id;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.h04_bootstrap_first_admin(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.h04_bootstrap_first_admin(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) TO arena_offline_operator;

-- ---------------------------------------------------------------------------
-- Recovery: agora recebe identidade do operador, justificativa e ticket.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.h04_recover_admin(
    p_user_id BIGINT,
    p_nome VARCHAR(255),
    p_email VARCHAR(255),
    p_telefone VARCHAR(255),
    p_senha_hash VARCHAR(255),
    p_actor_ref VARCHAR(255),
    p_reason TEXT,
    p_change_ticket VARCHAR(128)
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_state public.admin_lifecycle_state%ROWTYPE;
    v_existing_profile VARCHAR(16);
    v_target_id BIGINT;
BEGIN
    PERFORM pg_advisory_xact_lock(public.h04_admin_lifecycle_lock_key());

    SELECT * INTO v_state
    FROM public.admin_lifecycle_state
    WHERE singleton_id = 1
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 admin lifecycle state is missing';
    END IF;

    IF NOT v_state.bootstrap_consumed THEN
        RAISE EXCEPTION 'H-04 recovery requires bootstrap consumed; use bootstrap';
    END IF;

    IF NOT v_state.recovery_required THEN
        RAISE EXCEPTION 'H-04 recovery requires recovery_required=true; use reconcile';
    END IF;

    IF v_state.admin_count <> 0 THEN
        RAISE EXCEPTION 'H-04 recovery requires admin_count=0';
    END IF;

    IF EXISTS (SELECT 1 FROM public.usuario WHERE perfil = 'ADMIN') THEN
        RAISE EXCEPTION 'H-04 recovery requires zero real admins';
    END IF;

    IF p_user_id IS NOT NULL THEN
        SELECT perfil INTO v_existing_profile
        FROM public.usuario
        WHERE id = p_user_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'H-04 recovery target user % not found', p_user_id;
        END IF;

        UPDATE public.usuario
        SET perfil = 'ADMIN'
        WHERE id = p_user_id;

        v_target_id := p_user_id;

        PERFORM public.h04_append_audit_event(
            'RECOVERY', 'SUCCEEDED', 'RECOVERY_CLI',
            'OPERATOR', NULL, COALESCE(p_actor_ref, 'recovery-cli'),
            v_target_id, p_email, v_existing_profile, 'ADMIN',
            p_reason, p_change_ticket, NULL, NULL
        );
    ELSE
        INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
        VALUES (p_nome, p_email, p_telefone, p_senha_hash, 'ADMIN')
        RETURNING id INTO v_target_id;

        PERFORM public.h04_append_audit_event(
            'RECOVERY', 'SUCCEEDED', 'RECOVERY_CLI',
            'OPERATOR', NULL, COALESCE(p_actor_ref, 'recovery-cli'),
            v_target_id, p_email, NULL, 'ADMIN',
            p_reason, p_change_ticket, NULL, NULL
        );
    END IF;

    UPDATE public.admin_lifecycle_state
    SET recovery_required = false,
        last_recovery_at = statement_timestamp(),
        updated_at = statement_timestamp()
    WHERE singleton_id = 1;

    RETURN v_target_id;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.h04_recover_admin(
    BIGINT, VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255),
    VARCHAR(255), TEXT, VARCHAR(128)
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.h04_recover_admin(
    BIGINT, VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255),
    VARCHAR(255), TEXT, VARCHAR(128)
) TO arena_offline_operator;
