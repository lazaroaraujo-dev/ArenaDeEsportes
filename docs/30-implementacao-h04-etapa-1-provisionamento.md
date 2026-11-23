# Etapa 8 — H-04 | Implementação 1: provisionamento das roles

**Status:** implementação da etapa 1 concluída. Testes de integração PostgreSQL
não puderam ser executados neste ambiente por indisponibilidade de motor de
contêiner/PostgreSQL; a compilação dos testes e os testes unitários do guard
H-03 passaram.

## 1. Resumo

Esta etapa preparou o provisionamento das duas novas roles H-04:

- `arena_admin_writer` — login usado posteriormente pela API web para executar
  funções administrativas via `EXECUTE`;
- `arena_offline_operator` — login usado exclusivamente pelos comandos CLI
  offline (bootstrap/recovery/reconcile).

As roles existentes `arena_runtime`, `arena_migrator` e o provisionador foram
preservados. Nenhuma tabela, trigger, função SECURITY DEFINER, endpoint ou
política de senha foi implementada — esses itens ficam para as próximas etapas.

## 2. Decisões aplicadas

- `arena_admin_writer` e `arena_offline_operator` são logins `NOINHERIT`, sem
  `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `REPLICATION` nem `BYPASSRLS`.
- Nenhuma membership foi concedida.
- Nenhum privilégio de DML/DDL direto foi concedido; apenas `CONNECT` no
  database e `USAGE` no schema `public`.
- `EXECUTE` em funções será concedido somente quando as funções forem criadas
  nas migrations V3/V4.
- O script de provisionamento continua one-time e não idempotente; falha se
  qualquer role já existir.
- A infraestrutura de testes H-03 foi estendida para exigir e validar
  credenciais `TEST_*` distintas para runtime, migrator, writer e offline.

## 3. Arquivos criados e modificados

**Modificados**

- `scripts/postgres/provision-arena-roles.sql` — adiciona criação de
  `arena_admin_writer` e `arena_offline_operator`, validação de quatro senhas
  distintas e grants mínimos (`CONNECT`/`USAGE`).
- `.env.example` — adiciona placeholders `ARENA_ADMIN_WRITER_PASSWORD` e
  `ARENA_OFFLINE_OPERATOR_PASSWORD`.
- `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java` — estende
  o record com writer/offline; valida nomes fixos e senhas distintas entre os
  quatro roles.
- `src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java` —
  atualiza `validTestEnvironment`, adiciona testes de existência/distinção das
  novas credenciais e teste de rejeição de variáveis de produção como fallback.
- `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`
  — adiciona verificações de atributos, ausência de membership, ausência de
  DML/DDL direto e conexão isolada para writer/offline.

**Criados**

- `docs/30-implementacao-h04-etapa-1-provisionamento.md` — este relatório.

## 4. Roles e privilégios configurados

### Atributos comuns a todas as roles de aplicação/operador

| Atributo | Valor |
|---|---|
| LOGIN | sim |
| INHERIT | não (`NOINHERIT`) |
| SUPERUSER | não |
| CREATEDB | não |
| CREATEROLE | não |
| REPLICATION | não |
| BYPASSRLS | não |
| Membership | nenhuma |

### Privilégios por role após o script

| Role | Database | Schema public | Tabelas/sequências | Funções |
|---|---|---|---|---|
| `arena_runtime` | CONNECT | USAGE | DML via V2 (mantido) | nenhuma ainda |
| `arena_migrator` | CONNECT | USAGE, CREATE | ownership V1/V2 | nenhuma ainda |
| `arena_admin_writer` | CONNECT | USAGE | nenhum | nenhuma ainda (V3/V4) |
| `arena_offline_operator` | CONNECT | USAGE | nenhum | nenhuma ainda (V3/V4) |

O script **não** concede `CREATE ON DATABASE`, `TEMPORARY`, `TRUNCATE` ou
privilégios de tabela/sequência a writer/offline. O `CREATE ON SCHEMA public`
permanece apenas com `arena_migrator` e é revogado de `PUBLIC`.

## 5. Testes executados e resultados

### Compilação

```bash
./mvnw -DskipTests test-compile
```

**Resultado:** `BUILD SUCCESS` — 11 arquivos-fonte de teste recompilados sem
erros.

### Testes unitários do guard H-03

```bash
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:54329/arena_flyway_test \
TEST_POSTGRES_RUNTIME_USERNAME=arena_runtime \
TEST_POSTGRES_RUNTIME_PASSWORD=<32 caracteres> \
TEST_POSTGRES_MIGRATOR_USERNAME=arena_migrator \
TEST_POSTGRES_MIGRATOR_PASSWORD=<32 caracteres> \
TEST_POSTGRES_WRITER_USERNAME=arena_admin_writer \
TEST_POSTGRES_WRITER_PASSWORD=<32 caracteres> \
TEST_POSTGRES_OFFLINE_USERNAME=arena_offline_operator \
TEST_POSTGRES_OFFLINE_PASSWORD=<32 caracteres> \
TEST_JWT_SECRET=<32 caracteres> \
TEST_JWT_EXPIRATION_MS=3600000 \
./mvnw -Dtest=TestPostgresSettingsTest test
```

**Resultado:** 9 testes, 0 falhas, 0 erros, 0 ignorados; `BUILD SUCCESS`.

### Testes de integração PostgreSQL

**Não executados.** Este ambiente WSL 2 não possui motor de contêiner
acessível (`docker`/`podman`/`nerdctl` indisponíveis) nem cliente/servidor
PostgreSQL instalados. Portanto, não foi possível provisionar um container
PostgreSQL 16 descartável para validar o script SQL e o
`PostgresRolePrivilegesIntegrationTest`.

A suíte de integração está pronta para rodar assim que houver um ambiente com
PostgreSQL 16 descartável (loopback/porta aleatória/`tmpfs`, sem volume
nomeado) e as variáveis `TEST_*` correspondentes.

## 6. Evidências de isolamento H-03

- O guard `TestPostgresSettings` continua exigindo
  `jdbc:postgresql://127.0.0.1:<port>/arena_flyway_test`.
- Nenhum fallback para `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*`, `JWT_SECRET` ou
  outra variável de produção foi introduzido; o novo teste
  `rejectsProductionJwtAndSpringVariablesAsFallback` rejeita explicitamente um
  ambiente que só contenha essas variáveis.
- As credenciais de teste writer/offline são obrigatórias, distintas entre si e
  distintas de runtime/migrator.
- Nenhum volume nomeado, bind mount, database `arena`/`arena_dev` ou
  `arena_postgres_data` foi utilizado nesta etapa.

## 7. Confirmação de recursos intocados

- Nenhuma conexão foi feita aos bancos `arena` ou `arena_dev`.
- `arena_postgres_data` não foi montado, removido ou alterado.
- O `.env` real não foi lido, editado ou impresso.
- Nenhuma migration V3, V4 ou V5 foi criada ou executada.
- Nenhuma tabela, trigger, função, endpoint ou DTO de H-04 foi implementado.
- Não foi realizado commit nem push.

## 8. Problemas encontrados e decisões pendentes

1. **Indisponibilidade de motor de contêiner/PostgreSQL:** impede a execução do
   `PostgresRolePrivilegesIntegrationTest` e da validação real do script SQL.
   Deve ser executado em ambiente com Docker/Podman antes de avançar.
2. **Owner SECURITY DEFINER:** mantém-se `arena_migrator`, conforme decisão
   fechada do doc29. Nenhuma membership nova foi criada.
3. **EXECUTE em funções:** será concedido apenas nas migrations V3/V4, quando
   as funções forem criadas.
4. **Datasources writer/offline no Spring:** não foram adicionados em
   `application.properties` nesta etapa, pois ainda não há funções/endpoints
   que os utilizem. Serão introduzidos nas etapas de API/CLI.
5. **Decisões operacionais pendentes:** identidade verificável do operador,
   classificação de instalação nova, retenção de auditoria, política Unicode/
   BCrypt, janela de maintenance e autorização de reconcile permanecem fora do
   escopo técnico desta etapa.

## 9. Próximos passos

1. Executar `PostgresRolePrivilegesIntegrationTest` em PostgreSQL 16
   descartável para confirmar atributos, ACLs e ausência de memberships.
2. Implementar a migration V3: tabelas `admin_lifecycle_state` e
   `admin_audit_event`, triggers, funções SECURITY DEFINER e grants.
3. Implementar a CLI offline de bootstrap/recovery/reconcile.
4. Implementar a API de alteração de perfil/exclusão/reset com datasource
   writer.
5. Implementar V4/V5: ACL final e política de senha.

## 10. Git status final

```text
## develop...origin/develop
 M .env.example
 M src/main/java/br/com/arena/service/PostgresClock.java
 M src/main/resources/application.properties
 M src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java
 M src/test/java/br/com/arena/model/AgendamentoCreatedAtIntegrationTest.java
 M src/test/java/br/com/arena/testsupport/TestPostgresContextInitializer.java
 M src/test/java/br/com/arena/testsupport/TestPostgresSettings.java
 M src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java
?? docs/24-separacao-usuarios-postgresql-h02.md
?? docs/25-diagnostico-bootstrap-primeiro-admin.md
?? docs/26-projeto-tecnico-h04-bootstrap-admin.md
?? docs/27-revisao-final-projeto-tecnico-h04.md
?? docs/28-revisao-final-projeto-tecnico-h04.md
?? docs/29-plano-tecnico-h04-consolidado.md
?? docs/30-implementacao-h04-etapa-1-provisionamento.md
?? scripts/
?? src/main/resources/db/migration/V2__grant_runtime_privileges.sql
?? src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java
```

**Correção:** em relação ao snapshot anterior, `PostgresRolePrivilegesIntegrationTest.java` consta apenas como não rastreado (`??`), não como modificado. Ele foi criado durante a Etapa 7/H-02 e ainda não foi commitado, portanto não há versão anterior rastreada no `HEAD`. Todas as alterações anteriores foram preservadas e nenhuma entrada foi descartada ou revertida.
