-- V5.8.1: versão de credencial para invalidação de JWT.
--
-- Usuários existentes recebem versão 0. A versão é lida pelo Hibernate e
-- incluída como claim nos JWTs; uma futura alteração de senha deverá
-- incrementá-la na mesma transação, sem UPDATE direto do runtime (V4.4).
--
-- Nenhum privilégio adicional é concedido: arena_runtime já possui SELECT na
-- tabela e não recebe UPDATE nesta coluna.

ALTER TABLE public.usuario
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
