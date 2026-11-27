-- H-04/V4.1: cadastro público seguro via SECURITY DEFINER.
-- Cria exclusivamente CLIENTE e mantém a decisão de perfil no banco.

CREATE OR REPLACE FUNCTION public.h04_registrar_cliente(
    p_nome VARCHAR(255),
    p_email VARCHAR(255),
    p_telefone VARCHAR(255),
    p_senha_hash VARCHAR(255)
)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id BIGINT;
BEGIN
    IF p_nome IS NULL OR btrim(p_nome) = '' THEN
        RAISE EXCEPTION 'H-04 nome is required';
    END IF;

    IF p_email IS NULL OR btrim(p_email) = '' THEN
        RAISE EXCEPTION 'H-04 email is required';
    END IF;

    IF p_telefone IS NULL OR btrim(p_telefone) = '' THEN
        RAISE EXCEPTION 'H-04 telefone is required';
    END IF;

    IF p_senha_hash IS NULL OR btrim(p_senha_hash) = '' THEN
        RAISE EXCEPTION 'H-04 password hash is required';
    END IF;

    INSERT INTO public.usuario
        (nome, email, telefone, senha, perfil)
    VALUES
        (btrim(p_nome), btrim(p_email), btrim(p_telefone), p_senha_hash, 'CLIENTE')
    ON CONFLICT (email) DO NOTHING
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$;

REVOKE ALL ON FUNCTION public.h04_registrar_cliente(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.h04_registrar_cliente(
    VARCHAR(255), VARCHAR(255), VARCHAR(255), VARCHAR(255)
) TO arena_runtime;

REVOKE INSERT ON TABLE public.usuario FROM arena_runtime;
