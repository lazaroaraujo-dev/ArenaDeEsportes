# Etapa 4 (Parte 4) — H-04 | Gerenciamento Administrativo de Perfis e Proteção do Último ADMIN

**Status:** concluída e validada em PostgreSQL 16 descartável.

**Escopo:** implementação do gerenciamento administrativo de perfis (promoção, rebaixamento e exclusão administrativa), proteção estrita do último ADMIN contra rebaixamento e exclusão, criação da migration incremental `V3_2__admin_lifecycle_state_reader.sql` com função `SECURITY DEFINER` protegida, tratamento de auditoria na mesma transação sem `REQUIRES_NEW`, advisory lock transacional `7409395520021` com validação fail-closed, e testes de concorrência e integridade.

---

## 1. Resumo da Implementação

Esta entrega complementa o fluxo de usuários da API Arena De Esportes:
1. **Separação de DTOs e Contratos (Parte 3)**:
   - `UsuarioCadastroDTO`: exclusivo para cadastro público (`POST /usuarios`), fixando o perfil como `CLIENTE`.
   - `UsuarioUpdateDTO`: exclusivo para atualização de dados pessoais cadastrais (`PUT /usuarios/{id}`), sem campos de `perfil` ou `senha`.
   - `UsuarioPerfilDTO`: exclusivo para alteração administrativa de perfil (`PATCH /usuarios/{id}/perfil`), validado com `@NotNull` para enum `Perfil`.
2. **Endpoint Administrativo de Perfil (`PATCH /usuarios/{id}/perfil`)**:
   - Acessível exclusivamente por usuários com `ROLE_ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`).
   - Suporta promoção (`CLIENTE -> ADMIN`) e rebaixamento (`ADMIN -> CLIENTE`).
   - Serializado sob o advisory lock transacional `7409395520021`.
3. **Exclusão Administrativa de Usuários (`DELETE /usuarios/{id}`)**:
   - Acessível exclusivamente por `ROLE_ADMIN`.
   - Serializado sob o advisory lock transacional `7409395520021`.
   - Respeita integridade referencial: bloqueia exclusão com HTTP 400 Bad Request caso o usuário possua agendamentos vinculados.
4. **Proteção do Último Administrador (HTTP 409 Conflict)**:
   - Impede rebaixamento ou exclusão do último `ADMIN` do sistema quando `admin_count <= 1`.
   - Auditoria de rejeição previsível (`LAST_ADMIN`) persistida na mesma transação com status `REJECTED`, confirmada antes de retornar HTTP 409.
5. **Segurança de Banco e Isolamento de Privilégios (Migration V3.2)**:
   - Migration `V3_2__admin_lifecycle_state_reader.sql` cria a função `h04_get_admin_lifecycle_state()`.
   - A função roda como `SECURITY DEFINER` (pertencente a `arena_migrator`) com `SET search_path = pg_catalog, public`.
   - Permite que a role `arena_runtime` consulte os campos necessários (`admin_count`, `bootstrap_consumed`, `recovery_required`) sem concessão de `SELECT` direto sobre a tabela `admin_lifecycle_state`.
   - Valida existência do registro singleton e falha com erro explícito (`fail-closed`) se o estado estiver ausente.

---

## 2. Arquivos Criados e Modificados

### 2.1 Banco de Dados (Flyway Migration)
- `src/main/resources/db/migration/V3_2__admin_lifecycle_state_reader.sql`:
  - Cria função `public.h04_get_admin_lifecycle_state()`.
  - Concede `EXECUTE` estritamente a `arena_runtime` e `arena_admin_writer`.
  - Revoga `EXECUTE` de `PUBLIC`.

### 2.2 DTOs e Objetos de Retorno
- `src/main/java/br/com/arena/dto/usuario/UsuarioPerfilDTO.java`: DTO para requisição de alteração de perfil com validação `@NotNull`.
- `src/main/java/br/com/arena/dto/usuario/AdminOperationStatus.java`: enum de status funcional (`SUCCESS`, `LAST_ADMIN`, `TARGET_NOT_FOUND`, `NO_OP`).
- `src/main/java/br/com/arena/dto/usuario/AdminOperationResult.java`: record contendo status funcional, `UsuarioResponseDTO` e mensagem explicativa.
- `src/main/java/br/com/arena/dto/usuario/UsuarioCadastroDTO.java` (Parte 3): cadastro sem campo `perfil`.
- `src/main/java/br/com/arena/dto/usuario/UsuarioUpdateDTO.java` (Parte 3): atualização sem `perfil` ou `senha`.

### 2.3 Camada de Serviços e Controle
- `src/main/java/br/com/arena/service/AdminLifecycleDbService.java`:
  - Encapsula chamadas ao banco sob JDBC: `pg_advisory_xact_lock(7409395520021)`, leitura via `h04_get_admin_lifecycle_state()`, contagem real de admins, validação fail-closed e gravação de auditoria via `h04_append_audit_event()`.
- `src/main/java/br/com/arena/service/UsuarioService.java`:
  - Implementa `alterarPerfil(Long targetUserId, UsuarioPerfilDTO dto)` e `deletarAdministrativo(Long targetUserId)`.
  - Transacional (`@Transactional`), executando validação, auditoria e mutação sob o mesmo lock advisory.
- `src/main/java/br/com/arena/controller/UsuarioController.java`:
  - Endpoint `PATCH /usuarios/{id}/perfil`.
  - Endpoint `DELETE /usuarios/{id}` adaptado para traduzir `AdminOperationStatus.LAST_ADMIN` em `ConflictException` (HTTP 409).
- `src/main/java/br/com/arena/mapper/UsuarioMapper.java`: mappers correspondentes.

### 2.4 Testes Automatizados
- `src/test/java/br/com/arena/controller/UsuarioPerfilAdminIntegrationTest.java`:
  - 12 testes de integração cobrindo promoção, rebaixamento, auto-rebaixamento autorizado, bloqueio do último admin, exclusão de admin, bloqueio de exclusão do último admin, autorização RBAC (cliente recebe 403), integridade com agendamentos (400), concorrência entre admins e fail-closed de consistência.
- `src/test/java/br/com/arena/controller/UsuarioCadastroAtualizacaoIntegrationTest.java`: 9 testes de integração validando os contratos de cadastro e atualização (Parte 3).

---

## 3. Detalhamento da Migration V3.2

### 3.1 Problema Identificado
Na migration V3 (`V3__admin_lifecycle_state_and_triggers.sql`), todos os privilégios sobre a tabela `admin_lifecycle_state` foram revogados de `PUBLIC` e de `arena_runtime` (`REVOKE ALL ON public.admin_lifecycle_state FROM arena_runtime`).
Ao mesmo tempo, as decisões arquiteturais do documento 29 estabeleceram que:
1. As operações administrativas devem validar o estado do singleton `admin_lifecycle_state` sob advisory lock antes de qualquer alteração.
2. A aplicação na Etapa 4 continua utilizando o datasource `arena_runtime`, postergando o datasource segregado `arena_admin_writer` para a Etapa 5.
3. Não era permitido conceder privilégios excessivos (como `SELECT` direto) a `arena_runtime` sobre `admin_lifecycle_state`.

### 3.2 Solução: Função SECURITY DEFINER Controlada
A migration incremental `V3_2__admin_lifecycle_state_reader.sql` resolve a questão de forma elegante:
```sql
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
BEGIN
    SELECT admin_count, bootstrap_consumed, recovery_required
    INTO p_admin_count, p_bootstrap_consumed, p_recovery_required
    FROM public.admin_lifecycle_state
    WHERE singleton_id = 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'H-04 admin lifecycle state singleton is missing';
    END IF;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state() TO arena_runtime;
GRANT EXECUTE ON FUNCTION public.h04_get_admin_lifecycle_state() TO arena_admin_writer;
```

Propriedades de segurança:
- Pertence a `arena_migrator`.
- Executa com `SECURITY DEFINER` e `search_path` protegido (`pg_catalog, public`).
- Apenas expõe os 3 campos estritamente necessários para validação do ciclo de vida.
- Falha explicitamente se o singleton estiver ausente (`RAISE EXCEPTION`).
- `arena_runtime` ganha apenas `EXECUTE` nesta função de leitura, sem privilégios de DML ou de leitura de outros campos/tabelas sensíveis.

---

## 4. Validação sob Advisory Lock e Double Verification

Todas as operações de escrita administrativa seguem o fluxo:
1. `pg_advisory_xact_lock(7409395520021)`: serializa operações concorrentes no nível do banco.
2. `validarConsistenciaOuFalhar()`:
   - Consulta `h04_get_admin_lifecycle_state()`.
   - Consulta `SELECT count(*) FROM public.usuario WHERE perfil = 'ADMIN'`.
   - **Verificação Dupla**: `state.adminCount() == realCount`.
   - **Fail-closed**: Se houver divergência, ou se `!bootstrap_consumed || recovery_required`, a operação lança `IllegalStateException` imediatamente, abortando a transação sem aplicar alterações.

---

## 5. Auditoria de Rejeições Previsíveis sem REQUIRES_NEW

Conforme aprovado na revisão arquitetural:
- O rebaixamento ou exclusão do último ADMIN não deve lançar exceção transacional dentro do `UsuarioService`, pois isso realizaria rollback do evento de auditoria.
- A rejeição `LAST_ADMIN` é tratada como **resultado funcional**:
  1. `adminLifecycleDbService.registrarAuditoria(...)` grava o evento com `outcome = 'REJECTED'` e `error_code = 'LAST_ADMIN'`.
  2. O serviço retorna `new AdminOperationResult(AdminOperationStatus.LAST_ADMIN, ...)` e a transação do banco é comitada com sucesso, garantindo a rastreabilidade.
  3. Na camada HTTP (`UsuarioController`), o status `LAST_ADMIN` é traduzido em `ConflictException`, resultando no retorno `HTTP 409 Conflict` com mensagem descritiva no corpo JSON.
- Essa abordagem elimina qualquer dependência de `Propagation.REQUIRES_NEW`, mantém o advisory lock comitado junto com o evento e preserva integridade transacional simples.

---

## 6. Resultados dos Testes de Integração e Concorrência

Os testes foram executados com sucesso em container PostgreSQL 16 descartável (`tmpfs`, porta dinâmica):
- `UsuarioPerfilAdminIntegrationTest`: 12 testes, **0 falhas**.
  - `adminPromoveClienteParaAdminComSucessoEAuditoria`: 200 OK, auditoria `PROMOTE SUCCEEDED`.
  - `adminRebaixaOutroAdminQuandoExistemOutrosComSucesso`: 200 OK, auditoria `DEMOTE SUCCEEDED`.
  - `adminNaoConsegueRebaixarUltimoAdminRetorna409EGravaAuditoria`: 409 Conflict, auditoria `DEMOTE REJECTED LAST_ADMIN`.
  - `adminNaoConsegueExcluirUltimoAdminRetorna409EGravaAuditoria`: 409 Conflict, auditoria `ADMIN_DELETE REJECTED LAST_ADMIN`.
  - `adminExcluiOutroAdminQuandoExistemOutrosComSucesso`: 204 No Content, auditoria `ADMIN_DELETE SUCCEEDED`.
  - `adminPodeRebaixarASiMesmoSeHouverOutroAdmin`: 200 OK.
  - `clienteNaoConsegueAlterarPerfilRetorna403`: 403 Forbidden.
  - `clienteNaoConsegueExcluirUsuarioRetorna403`: 403 Forbidden.
  - `exclusaoDeUsuarioComAgendamentosBloqueiaPorIntegridade`: 400 Bad Request.
  - `operacoesConcorrentesNaoConseguemDeixarOSistemaSemAdmin`: concorrência serializada sob o lock; no máximo 1 sucesso; contagem final de admins `>= 1`.
  - `operacoesConcorrentesRejeitamRebaixamentoDoUltimoAdminCom409`: duas threads simultâneas tentam rebaixar o único admin; ambas recebem 409 Conflict.
  - `estadoInconsistenteProvocaFalhaSeguraFailClosed`: divergência forçada aborta via fail-closed.
- `UsuarioCadastroAtualizacaoIntegrationTest`: 9 testes, **0 falhas**.
- `AdminCliSecurityTest`: 4 testes, **0 falhas**.
- `TestPostgresSettingsTest`: 10 testes, **0 falhas**.
- **Total validado**: **35 testes executados, 100% de sucesso**.

---

## 7. Próximos Passos (Parte 5)

Aguardar autorização formal para iniciar a **Parte 5**:
1. Implementação da Migration V4 (criação e concessão dos privilégios administrativos estritos a `arena_admin_writer`).
2. Configuração do datasource secundário/administrativo na aplicação Spring Boot.
3. Roteamento das operações administrativas para o pool do `arena_admin_writer`.
