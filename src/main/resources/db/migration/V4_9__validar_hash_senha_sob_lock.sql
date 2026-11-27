-- V5.8.2.1: validação do hash atual sob lock na alteração de senha.
--
-- Corrige a janela em que duas requisições validavam BCrypt contra o mesmo
-- hash antigo antes do commit da primeira. O backend informa o hash esperado
-- (lido do principal autenticado); a função bloqueia a linha, compara o hash
-- armazenado com o esperado e rejeita a operação se ele mudou.
--
-- Nenhuma senha em texto puro é enviada ao banco; a comparação é de hashes.
-- Não há auditoria de sucesso para operação rejeitada.

DROP FUNCTION IF EXISTS public.h04_alterar_senha(BIGINT, VARCHAR(255));

CREATE OR REPLACE FUNCTION public.h04_alterar_senha(
    p_actor_user_id BIGINT,
    p_hash_atual_esperado VARCHAR(255),
    p_novo_senha_hash VARCHAR(255)
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_email VARCHAR(255);
    v_hash_atual VARCHAR(255);
    v_nova_versao BIGINT;
BEGIN
    IF p_actor_user_id IS NULL THEN
        RAISE EXCEPTION 'H-04 actor user id is required'
            USING ERRCODE = '42501';
    END IF;

    IF p_hash_atual_esperado IS NULL OR btrim(p_hash_atual_esperado) = '' THEN
        RAISE EXCEPTION 'H-04 expected password hash is required'
            USING ERRCODE = '22023';
    END IF;

    IF p_novo_senha_hash IS NULL OR btrim(p_novo_senha_hash) = '' THEN
        RAISE EXCEPTION 'H-04 new password hash is required'
            USING ERRCODE = '22023';
    END IF;

    SELECT u.email, u.senha
      INTO v_email, v_hash_atual
      FROM public.usuario u
     WHERE u.id = p_actor_user_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 actor % not found', p_actor_user_id
            USING ERRCODE = '42501';
    END IF;

    IF v_hash_atual IS DISTINCT FROM p_hash_atual_esperado THEN
        -- Concorrência: outra alteração já trocou a senha sob o lock.
        -- 40001 é mapeado pela aplicação como retry-after (503).
        RAISE EXCEPTION 'H-04 password changed concurrently'
            USING ERRCODE = '40001';
    END IF;

    UPDATE public.usuario u
       SET senha = p_novo_senha_hash,
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

REVOKE ALL ON FUNCTION public.h04_alterar_senha(BIGINT, VARCHAR(255), VARCHAR(255))
    FROM PUBLIC, arena_admin_writer, arena_offline_operator;

GRANT EXECUTE ON FUNCTION public.h04_alterar_senha(BIGINT, VARCHAR(255), VARCHAR(255))
    TO arena_runtime;
