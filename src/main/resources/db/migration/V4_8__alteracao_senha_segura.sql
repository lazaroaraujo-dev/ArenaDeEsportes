-- H-04/V5.8.2: alteração autenticada da própria senha.
--
-- Atualiza o hash e incrementa credential_version em uma única transação,
-- executada como arena_migrator. O runtime não recebe UPDATE direto em senha
-- nem em credential_version (V4.4), apenas EXECUTE nesta função.
--
-- A identidade do ator é informada pelo backend a partir do principal
-- autenticado; a função revalida a existência do registro e bloqueia a linha.
-- A auditoria registra somente metadados — nunca senha, hash ou token.

CREATE OR REPLACE FUNCTION public.h04_alterar_senha(
    p_actor_user_id BIGINT,
    p_senha_hash VARCHAR(255)
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_email VARCHAR(255);
    v_nova_versao BIGINT;
BEGIN
    IF p_actor_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 actor user id is required'
            USING ERRCODE = '42501';
    END IF;

    IF p_senha_hash IS NULL OR btrim(p_senha_hash) = '' THEN
        RAISE EXCEPTION 'H-04 password hash is required'
            USING ERRCODE = '22023';
    END IF;

    -- Lock de linha: serializa alterações concorrentes da mesma conta.
    SELECT u.email
      INTO v_email
      FROM public.usuario u
     WHERE u.id = p_actor_user_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 actor % not found', p_actor_user_id
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.usuario u
       SET senha = p_senha_hash,
           credential_version = u.credential_version + 1
     WHERE u.id = p_actor_user_id
    RETURNING u.credential_version INTO v_nova_versao;

    PERFORM public.h04_append_audit_event(
        'PASSWORD_CHANGE', 'SUCCEEDED', 'HTTP', 'USER',
        p_actor_user_id, v_email,
        p_actor_user_id, v_email,
        NULL, NULL, NULL, NULL, NULL, NULL
    );

    RETURN v_nova_versao;
END;
$$;

REVOKE ALL ON FUNCTION public.h04_alterar_senha(BIGINT, VARCHAR(255))
    FROM PUBLIC, arena_admin_writer, arena_offline_operator;

GRANT EXECUTE ON FUNCTION public.h04_alterar_senha(BIGINT, VARCHAR(255))
    TO arena_runtime;
