# Etapa 8 — H-04 | Implementação 2: Migration V3 (estado singleton, triggers e proteção do último ADMIN)

**Status:** implementação da etapa 2 concluída e validada em PostgreSQL 16.15
descartável.

**Escopo:** apenas a migration `V3__admin_lifecycle_state_and_triggers.sql` e seus
testes de integração. Não foram criados CLI bootstrap/recovery, endpoints de
perfil, DTOs, V4/V5 nem frontend.

## 1. Resumo

Esta etapa entregou a infraestrutura de banco de dados do H-04:

- Tabela singleton `public.admin_lifecycle_state` com `admin_count`,
  `bootstrap_consumed`, `recovery_required` e timestamps.
- Tabela append-only `public.admin_audit_event` para auditoria administrativa.
- Triggers em `public.usuario` que mantêm `admin_count` transacionalmente e
  impedem que o último ADMIN seja demitido/excluído.
- Funções SECURITY DEFINER para bootstrap, recovery, reconcile e append de
  auditoria, todas sob o mesmo advisory transaction lock.
- Inicialização segura da V3 que distingue instalação nova, legado sem ADMIN e
  legado com ADMIN.
- Testes de integração em PostgreSQL 16 descartável cobrindo os três estados
  iniciais, contagem, proteção do último ADMIN, concorrência e ACLs.

## 2. Arquivos criados/alterados

### 2.1 Código

- `src/main/resources/db/migration/V3__admin_lifecycle_state_and_triggers.sql` —
  migration Flyway com tabelas, triggers, funções e grants.
- `src/main/resources/application.properties` — placeholder
  `spring.flyway.placeholders.h04InitialInstall` com default `false`.

### 2.2 Suporte a testes

- `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java` — campo
  `h04InitialInstall` e validação `TEST_H04_INITIAL_INSTALL` (`true`/`false`).
- `src/test/java/br/com/arena/testsupport/TestPostgresContextInitializer.java` —
  repassa o placeholder para `spring.flyway.placeholders.h04InitialInstall`.
- `src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java` —
  testes para `TEST_H04_INITIAL_INSTALL`.

### 2.3 Testes de integração

- `src/test/java/br/com/arena/testsupport/AdminLifecycleStateIntegrationTest.java` —
  15 testes para o cenário legado vazio (RECOVERY_REQUIRED) e operações
  subsequentes.
- `src/test/java/br/com/arena/testsupport/AdminLifecycleStateFreshInstallIntegrationTest.java` —
  3 testes para instalação nova (PENDING) e bootstrap.

## 3. Decisões aplicadas

### 3.1 Owner e ACL

- `arena_migrator` continua como owner SECURITY DEFINER de todas as funções e
  tabelas criadas na V3.
- Não foi criado `arena_admin_fn_owner` nem memberships adicionais.
- `arena_runtime` não tem acesso de leitura/escrita às tabelas internas
  `admin_lifecycle_state` e `admin_audit_event`.
- `arena_admin_writer` pode executar as funções de trigger em `usuario`.
- `arena_offline_operator` pode executar `h04_bootstrap_first_admin`,
  `h04_recover_admin` e `h04_reconcile_admin_state`.

### 3.2 Estados e invariantes

| Estado               | `bootstrap_consumed` | `recovery_required` | `admin_count` | Condição real de `usuario.perfil='ADMIN'` |
|----------------------|----------------------|---------------------|---------------|--------------------------------------------|
| PENDING              | false                | false               | 0             | 0                                          |
| RECOVERY_REQUIRED    | true                 | true                | 0             | 0                                          |
| CONSUMED/ativo       | true                 | false               | ≥ 1           | ≥ 1 e igual a `admin_count`                |

A constraint trigger `trg_h04_validate_admin_lifecycle_state` (DEFERRABLE
INITIALLY DEFERRED) rejeita qualquer commit que deixe combinação inválida ou
`admin_count` divergente da contagem real.

### 3.3 Inicialização V3

A migration usa o placeholder `${h04InitialInstall}`:

- `true` (apenas quando o pipeline puder afirmar que é instalação nova e vazia):
  insere estado **PENDING**.
- `false` com `usuario` vazio: insere **RECOVERY_REQUIRED**.
- `false` com pelo menos um ADMIN: insere **CONSUMED** com `admin_count` real.
- `true` com `usuario` não vazio: falha de forma segura (`RAISE EXCEPTION`).

O default em `application.properties` é `false`, garantindo que nunca se infira
novidade em ambiente real.

### 3.4 Serialização e locks

- Todas as operações de perfil (INSERT/UPDATE/DELETE em `usuario`) disparam um
  trigger BEFORE STATEMENT que adquire `pg_advisory_xact_lock` com a chave
  fixa `7409395520021`.
- As funções `h04_bootstrap_first_admin`, `h04_recover_admin` e
  `h04_reconcile_admin_state` também adquirem o mesmo lock.
- O lock é transacional (`xact`), portanto liberado automaticamente no
  commit/rollback.

### 3.5 Proteção do último ADMIN

- Trigger AFTER ROW em `usuario` aplica o delta real em `admin_count`.
- Decremento só é permitido quando o resultado mantém pelo menos 1 ADMIN.
- Se uma instrução multi-row tentar reduzir para zero, toda a instrução é
  rejeitada (a trigger FOR EACH ROW lança exceção e a transação faz rollback).

### 3.6 Bootstrap e recovery

- `h04_bootstrap_first_admin` só funciona em estado **PENDING**.
- `h04_recover_admin` só funciona em estado **RECOVERY_REQUIRED**.
- Ambos consomem/atualizam o marcador apropriado e inserem evento em
  `admin_audit_event`.
- `h04_reconcile_admin_state` é uma ferramenta offline para corrigir
  inconsistências sem criar/apagar usuários.

## 4. Validação em PostgreSQL 16 descartável

Ambiente:

```text
Imagem:    postgres:16
Database:  arena_flyway_test
Porta:     aleatória em 127.0.0.1
Storage:   tmpfs (sem volume nomeado)
Provision: scripts/postgres/provision-arena-roles.sql
```

### 4.1 Cenário legado vazio (RECOVERY_REQUIRED)

Classe: `AdminLifecycleStateIntegrationTest`
Variável: `TEST_H04_INITIAL_INSTALL=false`

```text
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
```

Cobertura:

- Estado inicial RECOVERY_REQUIRED.
- Bootstrap rejeitado enquanto recovery é necessário.
- Recovery cria ADMIN e limpa `recovery_required`.
- INSERT de CLIENTE não altera contador.
- INSERT de ADMIN incrementa contador.
- PROMOTE (CLIENTE → ADMIN) incrementa contador.
- DEMOTE/DELETE de ADMIN decrementam contador quando há mais de um.
- DEMOTE do último ADMIN é rejeitado.
- DELETE do último ADMIN é rejeitado.
- UPDATE multi-row que deixaria zero admins é rejeitado em bloco.
- Concorrência: duas demoções simultâneas não reduzem abaixo de 1.
- Reconcile: de CONSUMED para RECOVERY_REQUIRED quando não há admins.
- Reconcile: mantém estado consistente inalterado.
- `arena_runtime` não lê/altera `admin_lifecycle_state` nem `admin_audit_event`.

### 4.2 Instalação nova (PENDING)

Classe: `AdminLifecycleStateFreshInstallIntegrationTest`
Variável: `TEST_H04_INITIAL_INSTALL=true`

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Cobertura:

- Estado inicial PENDING.
- Bootstrap cria o primeiro ADMIN e consome o marcador.
- Segundo bootstrap falha com "bootstrap already consumed".

### 4.3 Guard de configuração

Classe: `TestPostgresSettingsTest`

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

### 4.4 Privilégios das roles (regressão da Etapa 1)

Classe: `PostgresRolePrivilegesIntegrationTest`

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

## 5. Notas e restrições

- A V3 **não** cria CLI, endpoints, DTOs, V4/V5 nem frontend. Esses itens
  permanecem para etapas futuras.
- A reconciliação de estado inconsistente é possível apenas via função
  `h04_reconcile_admin_state`, que deve ser invocada offline por operador
  autorizado (`arena_offline_operator`).
- Nenhum commit/push foi realizado. O container `arenadeesportes-postgres-1`
  (porta 5432, banco `arena`) não foi alterado.
- Não foi lido nem alterado `.env` real; apenas `.env.example` e variáveis
  `TEST_*` foram usadas.

## 6. Próximos passos sugeridos

- Etapa 3: implementar comandos CLI offline para bootstrap e recovery.
- Etapa 4: criar endpoints/fluxos de administração de perfil usando
  `arena_admin_writer` e as funções de banco.
- Etapa 5: aplicar V4/V5 conforme decisões arquiteturais do H-04.
