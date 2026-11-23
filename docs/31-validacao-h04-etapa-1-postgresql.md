# H-04 — Validação real da Etapa 1 com PostgreSQL 16

**Status:** validação executada com sucesso em PostgreSQL 16 descartável.

## 1. Verificações iniciais

### Docker

```text
$ docker --version
Docker version 29.8.1, build 4a63305

$ docker info --format '{{ .ServerVersion }}'
29.8.1
```

### Contêineres existentes

```text
$ docker ps
CONTAINER ID   IMAGE         COMMAND                  CREATED        STATUS                   PORTS                      NAMES
124405e64726   postgres:16   "docker-entrypoint.s…"   36 hours ago   Up 2 minutes (healthy)   127.0.0.1:5432->5432/tcp   arenadeesportes-postgres-1
```

O contêiner `arenadeesportes-postgres-1` (banco `arena`, porta 5432) foi
identificado e **não foi alterado**. Um novo contêiner descartável foi criado
em porta aleatória para a validação.

## 2. Ambiente isolado

Contêiner criado:

```bash
docker run --rm --detach --name arena_h04_validate_1 \
  --env POSTGRES_DB=arena_flyway_test \
  --env POSTGRES_USER=arena_provisioner \
  --env POSTGRES_PASSWORD=<temp> \
  --publish 127.0.0.1::5432 \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=1g \
  --shm-size=256m postgres:16
```

Resultado:

```text
127.0.0.1:52054
Up 7 seconds
```

- Imagem: `postgres:16`
- Porta publicada: `127.0.0.1:52054` (aleatória)
- Armazenamento: `tmpfs`
- Database: `arena_flyway_test`
- Nenhum volume nomeado.

## 3. Provisionamento

Script executado com credenciais temporárias distintas para os quatro papéis:

```bash
docker exec -i \
  --env ARENA_DATABASE=arena_flyway_test \
  --env ARENA_PROVISION_NEW_DATABASE=YES \
  --env ARENA_RUNTIME_PASSWORD=<temp> \
  --env ARENA_MIGRATOR_PASSWORD=<temp> \
  --env ARENA_ADMIN_WRITER_PASSWORD=<temp> \
  --env ARENA_OFFLINE_OPERATOR_PASSWORD=<temp> \
  --env PGPASSWORD=<temp> \
  arena_h04_validate_1 \
  psql --no-psqlrc -U arena_provisioner -d arena_flyway_test \
  < scripts/postgres/provision-arena-roles.sql
```

Resultado:

```text
BEGIN
CREATE ROLE
CREATE ROLE
CREATE ROLE
CREATE ROLE
GRANT
REVOKE
REVOKE
GRANT
GRANT
GRANT
GRANT
SET
CREATE EXTENSION
RESET
REVOKE
COMMIT
```

**Provisionamento bem-sucedido.**

## 4. Evidências dos privilégios dos quatro papéis

### Atributos

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolreplication, rolbypassrls, rolinherit
FROM pg_roles
WHERE rolname LIKE 'arena_%'
ORDER BY rolname;
```

Resultado:

```text
        rolname         | rolcanlogin | rolsuper | rolcreatedb | rolcreaterole | rolreplication | rolbypassrls | rolinherit
------------------------+-------------+----------+-------------+---------------+----------------+--------------+------------
 arena_admin_writer     | t           | f        | f           | f             | f              | f            | f
 arena_migrator         | t           | f        | f           | f             | f              | f            | f
 arena_offline_operator | t           | f        | f           | f             | f              | f            | f
 arena_runtime          | t           | f        | f           | f             | f              | f            | f
```

Observação: `arena_provisioner` (superuser) também aparece na consulta porque
seu nome coincide com `LIKE 'arena_%'`; ele não faz parte dos papéis de
aplicação.

### Memberships

```sql
SELECT COUNT(*) AS membership_count
FROM pg_auth_members
WHERE member::regrole::text IN (
    'arena_runtime', 'arena_migrator',
    'arena_admin_writer', 'arena_offline_operator'
);
```

Resultado:

```text
 membership_count
------------------
                0
```

### Privilégios de tabela para writer/offline

```sql
SELECT grantee, table_schema, table_name, privilege_type
FROM information_schema.table_privileges
WHERE grantee IN ('arena_admin_writer', 'arena_offline_operator')
ORDER BY grantee, table_schema, table_name;
```

Resultado:

```text
 grantee | table_schema | table_name | privilege_type
---------+--------------+------------+----------------
(0 rows)
```

Nenhum privilégio de tabela foi concedido a `arena_admin_writer` nem a
`arena_offline_operator`.

## 5. Execução dos testes

### Testes unitários do guard H-03

```bash
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:52054/arena_flyway_test \
TEST_POSTGRES_RUNTIME_USERNAME=arena_runtime \
TEST_POSTGRES_RUNTIME_PASSWORD=<temp> \
TEST_POSTGRES_MIGRATOR_USERNAME=arena_migrator \
TEST_POSTGRES_MIGRATOR_PASSWORD=<temp> \
TEST_POSTGRES_WRITER_USERNAME=arena_admin_writer \
TEST_POSTGRES_WRITER_PASSWORD=<temp> \
TEST_POSTGRES_OFFLINE_USERNAME=arena_offline_operator \
TEST_POSTGRES_OFFLINE_PASSWORD=<temp> \
TEST_JWT_SECRET=<temp> \
TEST_JWT_EXPIRATION_MS=3600000 \
env -u SPRING_DATASOURCE_URL \
    -u SPRING_DATASOURCE_USERNAME \
    -u SPRING_DATASOURCE_PASSWORD \
    -u SPRING_FLYWAY_URL \
    -u SPRING_FLYWAY_USER \
    -u SPRING_FLYWAY_PASSWORD \
    -u JWT_SECRET \
    -u JWT_EXPIRATION_MS \
    ./mvnw -Dtest=TestPostgresSettingsTest test
```

Resultado:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Teste de integração PostgreSQL

```bash
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:52054/arena_flyway_test \
TEST_POSTGRES_RUNTIME_USERNAME=arena_runtime \
TEST_POSTGRES_RUNTIME_PASSWORD=<temp> \
TEST_POSTGRES_MIGRATOR_USERNAME=arena_migrator \
TEST_POSTGRES_MIGRATOR_PASSWORD=<temp> \
TEST_POSTGRES_WRITER_USERNAME=arena_admin_writer \
TEST_POSTGRES_WRITER_PASSWORD=<temp> \
TEST_POSTGRES_OFFLINE_USERNAME=arena_offline_operator \
TEST_POSTGRES_OFFLINE_PASSWORD=<temp> \
TEST_JWT_SECRET=<temp> \
TEST_JWT_EXPIRATION_MS=3600000 \
env -u SPRING_DATASOURCE_URL \
    -u SPRING_DATASOURCE_USERNAME \
    -u SPRING_DATASOURCE_PASSWORD \
    -u SPRING_FLYWAY_URL \
    -u SPRING_FLYWAY_USER \
    -u SPRING_FLYWAY_PASSWORD \
    -u JWT_SECRET \
    -u JWT_EXPIRATION_MS \
    ./mvnw -Dtest=PostgresRolePrivilegesIntegrationTest test
```

Resultado:

```text
Database: jdbc:postgresql://127.0.0.1:52054/arena_flyway_test (PostgreSQL 16.15)
Successfully applied 2 migrations to schema "public", now at version v2
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Todos os testes da Etapa 1 passaram.**

## 6. Validação da natureza one-time do script

O script foi executado uma segunda vez no mesmo banco descartável:

```text
BEGIN
ERROR:  role "arena_runtime" already exists
exit code: 3
```

Comportamento esperado confirmado: o script falha com código de saída 3 quando
uma das roles já existe, preservando a natureza one-time e não idempotente.

## 7. Limpeza do ambiente descartável

```bash
docker stop arena_h04_validate_1
```

Resultado:

```text
arena_h04_validate_1
```

Confirmação de remoção:

```text
$ docker ps -a --filter name=arena_h04_validate_1
CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES
```

O contêiner `arena_h04_validate_1` foi removido. O contêiner
`arenadeesportes-postgres-1` e os demais contêineres/volumes preexistentes
permanecem inalterados.

## 8. Confirmação de preservação dos recursos existentes

- `arenadeesportes-postgres-1` (banco `arena`, porta 5432): **não alterado**.
- Banco `arena_dev`: **não acessado**.
- Volume `arena_postgres_data`: **não montado, modificado nem removido**.
- `.env` real: **não lido nem alterado**.
- Migrations V3, V4 ou V5: **não criadas nem executadas**.
- Nenhum commit ou push realizado.

## 9. Estado Git final

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
?? docs/31-validacao-h04-etapa-1-postgresql.md
?? scripts/
?? src/main/resources/db/migration/V2__grant_runtime_privileges.sql
?? src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java
```

Todas as alterações anteriores foram preservadas. Nenhuma entrada foi
descartada ou revertida.

## 10. Arquivos alterados nesta validação

- `docs/31-validacao-h04-etapa-1-postgresql.md` — atualizado com resultados
  reais.
- `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`
  — ajuste estático anterior mantido (nomes de tabela qualificados com
  `public.` para writer/offline); não houve outra alteração nesta execução.

## 11. Conclusão

A Etapa 1 (provisionamento das roles H-04) foi validada com sucesso em
PostgreSQL 16.15 descartável:

- As quatro roles foram criadas com os atributos corretos.
- Nenhuma role possui atributos administrativos.
- Não há memberships entre as roles de aplicação/operador.
- `arena_admin_writer` e `arena_offline_operator` não têm privilégios de
  tabela/sequência nem DDL direto.
- A separação entre `arena_runtime` e `arena_migrator` permanece conforme H-02.
- O script de provisionamento falha de forma segura em segunda execução.
- `TestPostgresSettingsTest` (9 testes) e
  `PostgresRolePrivilegesIntegrationTest` (6 testes) passaram.

A Etapa 1 está aprovada para prosseguir. **Não avancei para a Migration V3.**
