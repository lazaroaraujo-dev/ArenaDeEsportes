# Etapa 7 — separação de usuários PostgreSQL (H-02)

**Resultado:** implementação concluída e suíte completa aprovada em PostgreSQL 16
descartável. A migration V1 foi preservada sem alterações.

## 1. Problema e objetivo

Antes desta etapa, a aplicação usava uma única configuração
`spring.datasource.*`. Como `spring.flyway.url/user/password` não estavam
definidos, Flyway usava as credenciais da datasource principal. Assim, a conta
que atendia requisições também precisava criar e alterar o schema.

O objetivo H-02 é separar:

1. a identidade de runtime, limitada a operações de aplicação;
2. a identidade usada pelo Flyway para migrations;
3. o administrador/provisionador usado somente na preparação explícita de um
   banco novo.

O runtime não deve possuir `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `CREATE` no
database ou schema, nem acesso ao histórico Flyway. A configuração Hibernate
`ddl-auto=validate` e o Flyway habilitado foram mantidos.

## 2. Arquitetura anterior e nova

| Aspecto | Anterior | Nova |
|---|---|---|
| Conexão da aplicação | `spring.datasource.*` | `spring.datasource.*` com `arena_runtime` |
| Conexão de migration | Herdava a datasource da aplicação | `spring.flyway.*` explícito com `arena_migrator` |
| DDL | A conta usada pelo runtime também podia migrar | DDL somente pelo migrator/Flyway |
| Provisionamento | Não estava separado do uso da aplicação | Script manual para database novo e administrador separado |
| Testes PostgreSQL | Um usuário/senha de teste para ambas as conexões | Logins e senhas de runtime e migrator distintos |

Flyway e Hibernate usam conexões diferentes, ainda que apontem para o mesmo
database. As migrations são aplicadas pelo migrator antes de o Hibernate
validar o schema.

## 3. Papéis e privilégios

| Identidade | Uso | Privilégios principais |
|---|---|---|
| `arena_runtime` | Requisições da aplicação e datasource JPA | `LOGIN`, `CONNECT`, `USAGE` no schema `public`, `SELECT/INSERT/UPDATE/DELETE` nas tabelas `usuario`, `quadra` e `agendamento`; `USAGE/SELECT` nas sequências. Sem atributos administrativos, sem membership em outros roles, sem `CREATE` no database/schema ou `TEMP`, e sem acesso ao histórico Flyway. |
| `arena_migrator` | Conexão exclusiva do Flyway | `LOGIN`, `CONNECT`, `USAGE/CREATE` no schema `public` e ownership dos objetos criados pelas migrations. Sem `SUPERUSER`, `CREATEDB`, `CREATEROLE`, `REPLICATION`, `BYPASSRLS` ou membership em outros roles. Não mantém `CREATE` no database após o provisionamento. |
| Provisionador | Preparação administrativa de uma instalação nova | Identidade administrativa externa à aplicação e aos testes. Cria os dois roles e grants. Precisa poder criar roles, conceder privilégios e assumir o role recém-criado para instalar a extensão; o procedimento testado usou o superuser bootstrap do PostgreSQL. Não é configurada no Spring nem no initializer de testes. |

`CREATE ON DATABASE` é um privilégio de objeto, diferente do atributo
`CREATEDB`. Durante o provisionamento, o script concede esse privilégio ao
migrator, instala `btree_gist` sob `SET ROLE arena_migrator` e revoga o grant
antes do `COMMIT`. A extensão é trusted no PostgreSQL 16. A V1 encontra a
extensão instalada por meio de `CREATE EXTENSION IF NOT EXISTS`.

O provisionamento também revoga `CONNECT`, `CREATE` e `TEMPORARY` de `PUBLIC`
no database novo, revoga `CREATE` de `PUBLIC` no schema `public` e concede
`CONNECT`/`USAGE` explicitamente aos logins necessários. Isso evita que o
runtime herde permissões públicas de DDL ou de criação de tabelas temporárias.

## 4. Spring Boot e variáveis de ambiente

`src/main/resources/application.properties` mantém Flyway habilitado e
Hibernate em `validate`, com credenciais independentes:

| Propriedade | Variável | Role |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `arena_runtime` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | segredo de runtime |
| `spring.flyway.url` | `SPRING_FLYWAY_URL` | |
| `spring.flyway.user` | `SPRING_FLYWAY_USER` | `arena_migrator` |
| `spring.flyway.password` | `SPRING_FLYWAY_PASSWORD` | segredo do migrator |

Normalmente as duas URLs apontam para o mesmo database, mas os usuários e as
senhas não são compartilhados. O provisionador não aparece nessas propriedades.

`.env.example` contém somente placeholders. `POSTGRES_*` são variáveis de
bootstrap da imagem PostgreSQL e se aplicam à inicialização de um diretório de
dados novo; não alteram roles ou databases em um diretório existente.
`ARENA_DATABASE`, `ARENA_PROVISION_NEW_DATABASE`,
`ARENA_RUNTIME_PASSWORD` e `ARENA_MIGRATOR_PASSWORD` são entradas do script
administrativo. O exemplo deixa a confirmação em `NO`; ela só deve ser mudada
para `YES` para um alvo novo, vazio e aprovado.

Os testes usam `TEST_POSTGRES_URL`,
`TEST_POSTGRES_RUNTIME_USERNAME/PASSWORD`,
`TEST_POSTGRES_MIGRATOR_USERNAME/PASSWORD`,
`TEST_JWT_SECRET` e `TEST_JWT_EXPIRATION_MS`. O guard H-03 continua exigindo
exatamente `127.0.0.1:<porta>/arena_flyway_test`, sem fallback para variáveis
`SPRING_*`. Também exige os logins dedicados e senhas distintas. O initializer
injeta as credenciais runtime na datasource e as credenciais migrator no
Flyway antes do refresh do contexto.

## 5. Migrations

### V1 — preservada

`src/main/resources/db/migration/V1__create_arena_schema.sql` não foi alterada.
Ela cria `btree_gist`, as tabelas, sequências, constraints e índices. No
provisionamento novo, a extensão já existe; o `IF NOT EXISTS` da V1 não tenta
instalá-la novamente.

### V2 — privilégios do runtime

`src/main/resources/db/migration/V2__grant_runtime_privileges.sql`:

- concede somente `SELECT`, `INSERT`, `UPDATE` e `DELETE` nas três tabelas da
  aplicação;
- concede `USAGE` e `SELECT` nas sequências existentes;
- configura `ALTER DEFAULT PRIVILEGES FOR ROLE arena_migrator` no schema
  `public` para as tabelas e sequências criadas por migrations futuras;
- revoga todos os privilégios do runtime sobre
  `public.flyway_schema_history`.

O `USAGE` do schema e os privilégios iniciais de database são preparados pelo
script de provisionamento. A V2 é executada depois da criação do histórico
Flyway, e não concede acesso do runtime a esse histórico.

## 6. Provisionamento de uma instalação nova

O script `scripts/postgres/provision-arena-roles.sql` **não cria o database**:
um administrador deve primeiro criar um database vazio e conectar nele. Ele
não é montado em `docker-entrypoint-initdb.d`, não executa automaticamente e
não é um procedimento de conversão de uma instalação existente.

Procedimento para uma instalação nova:

1. Usar um cluster/serviço PostgreSQL novo ou um database novo, vazio e
   aprovado. Definir o owner e a conexão administrativa por um mecanismo seguro
   da plataforma. A conta administrativa precisa poder assumir o role migrator
   criado pelo script; no procedimento validado ela era superuser. Não reutilizar
   nem recriar um volume persistente existente.
2. Fornecer ao processo `psql`, por secret manager ou mecanismo equivalente,
   `ARENA_DATABASE`, `ARENA_PROVISION_NEW_DATABASE=YES`,
   `ARENA_RUNTIME_PASSWORD` e `ARENA_MIGRATOR_PASSWORD`. As duas senhas devem
   ser fortes, não vazias e diferentes. Não ativar `set -x` nem registrar os
   valores.
3. Conectar como administrador ao database recém-criado e executar:

   ```bash
   psql --host <host-aprovado> \
     --username arena_provisioner \
     --dbname "$ARENA_DATABASE" \
     --file scripts/postgres/provision-arena-roles.sql
   ```

   A autenticação administrativa deve vir de `.pgpass`, secret manager ou
   mecanismo aprovado; não colocar senha na linha de comando. O script compara
   `current_database()` com `ARENA_DATABASE` e exige confirmação explícita,
   senhas não vazias e distintas. Ele cria os roles, instala a extensão e
   revoga o `CREATE ON DATABASE` temporário em uma transação.
4. Configurar no ambiente da aplicação `SPRING_DATASOURCE_*` com o login e
   senha runtime e `SPRING_FLYWAY_*` com o login e senha migrator. Iniciar a
   aplicação permite ao Flyway aplicar V1 e V2. Nunca fornecer a credencial do
   provisionador à aplicação.
5. Restringir e proteger a credencial administrativa conforme a política
   operacional do ambiente. A conta provisionadora continua separada dos dois
   logins da aplicação.

O script falha se os roles já existirem; não é idempotente. Portanto, não deve
ser executado novamente nem apontado a um database existente para “ajustar”
privilégios. A conversão de instalações existentes requer plano e aprovação
administrativa próprios.

## 7. Arquivos criados e modificados

**Criados**

- `scripts/postgres/provision-arena-roles.sql` — provisionamento explícito de
  roles e grants para database novo.
- `src/main/resources/db/migration/V2__grant_runtime_privileges.sql` — grants
  runtime e privilégios padrão futuros.
- `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`
  — testes PostgreSQL de roles, DML, DDL, histórico Flyway e defaults.
- `docs/24-separacao-usuarios-postgresql-h02.md` — este relatório.

**Modificados**

- `.env.example` — placeholders de provisionador, runtime, migrator e Spring;
  nenhuma credencial real foi adicionada.
- `src/main/resources/application.properties` — datasource runtime e
  propriedades Flyway explícitas.
- `src/test/java/br/com/arena/testsupport/TestPostgresContextInitializer.java`
  — datasource e Flyway recebem credenciais distintas.
- `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java` —
  validação dos dois logins/senhas e manutenção do destino estrito H-03.
- `src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java` —
  testes para credenciais distintas e guard atualizado.
- `src/test/java/br/com/arena/model/AgendamentoCreatedAtIntegrationTest.java`
  — inspeção de migrations via API Flyway, sem ler o histórico como runtime.
- `src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java`
  — o teste de dois inserts diretos concorrentes também trata SQLSTATE `40P01`
  como uma transação abortada pela disputa na constraint `EXCLUDE`.

`V1__create_arena_schema.sql` e `docker-compose.yml` não foram modificados.
Foi observado um diff não relacionado em
`src/main/java/br/com/arena/service/PostgresClock.java`; ele foi preservado e
não faz parte da implementação H-02.

## 8. Testes executados e resultados

Os testes PostgreSQL foram executados exclusivamente contra um container
`postgres:16` descartável:

- PostgreSQL reportado pelo driver: **16.15**;
- database: `arena_flyway_test`;
- na execução final aprovada, porta host aleatória `127.0.0.1:49580`;
- publicação somente no loopback;
- `/var/lib/postgresql/data` em `tmpfs`;
- opção Docker `--rm`; nenhum volume nomeado ou bind mount;
- variáveis de datasource/Flyway/JWT de produção explicitamente removidas do
  processo Maven;
- container parado e removido após os testes.

O container foi iniciado com o padrão abaixo; senhas descartáveis foram
fornecidas somente ao processo/container e não estão reproduzidas neste
relatório:

```bash
docker run --rm --detach --name <container-descartavel> \
  --env POSTGRES_DB=arena_flyway_test \
  --env POSTGRES_USER=arena_provisioner \
  --env POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --publish 127.0.0.1::5432 \
  --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=1g \
  --shm-size=256m postgres:16

docker port <container-descartavel> 5432/tcp

docker exec -i \
  --env ARENA_DATABASE=arena_flyway_test \
  --env ARENA_PROVISION_NEW_DATABASE=YES \
  --env ARENA_RUNTIME_PASSWORD="$ARENA_RUNTIME_PASSWORD" \
  --env ARENA_MIGRATOR_PASSWORD="$ARENA_MIGRATOR_PASSWORD" \
  --env PGPASSWORD="$POSTGRES_PASSWORD" \
  <container-descartavel> \
  psql --no-psqlrc -U arena_provisioner -d arena_flyway_test \
  < scripts/postgres/provision-arena-roles.sql

# Definir TEST_PORT com a porta impressa por "docker port"; fornecer os demais
# TEST_* por secret manager. SPRING_*/JWT de produção ficam removidos do Maven.
TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:${TEST_PORT}/arena_flyway_test" \
  TEST_POSTGRES_RUNTIME_USERNAME=arena_runtime \
  TEST_POSTGRES_RUNTIME_PASSWORD="$TEST_POSTGRES_RUNTIME_PASSWORD" \
  TEST_POSTGRES_MIGRATOR_USERNAME=arena_migrator \
  TEST_POSTGRES_MIGRATOR_PASSWORD="$TEST_POSTGRES_MIGRATOR_PASSWORD" \
  TEST_JWT_SECRET="$TEST_JWT_SECRET" \
  TEST_JWT_EXPIRATION_MS=3600000 \
  env -u SPRING_DATASOURCE_URL \
      -u SPRING_DATASOURCE_USERNAME \
      -u SPRING_DATASOURCE_PASSWORD \
      -u SPRING_FLYWAY_URL \
      -u SPRING_FLYWAY_USER \
      -u SPRING_FLYWAY_PASSWORD \
      -u JWT_SECRET \
      -u JWT_EXPIRATION_MS \
      ./mvnw test

# Após a suíte:
docker stop <container-descartavel>
```

Comandos Maven e resultados:

| Comando | Resultado |
|---|---|
| `./mvnw -DskipTests test-compile` | `BUILD SUCCESS` |
| `./mvnw -Dtest=TestPostgresSettingsTest test` | 7 testes, 0 falhas, 0 erros, 0 ignorados |
| `./mvnw test` com `TEST_*` do container e variáveis `SPRING_*` de produção removidas | **60 testes, 0 falhas, 0 erros, 0 ignorados; `BUILD SUCCESS`** na execução final |

Na execução completa, Flyway validou e aplicou V1 e V2 usando `arena_migrator`;
Hibernate iniciou em `validate` usando `arena_runtime`.

| Classe | Testes |
|---|---:|
| `ArenaApplicationTests` | 1 |
| `AgendamentoConcurrencyIntegrationTest` | 24 |
| `AgendamentoControllerDb01Test` | 5 |
| `AgendamentoControllerSecurityTest` | 5 |
| `AgendamentoLifecycleIntegrationTest` | 9 |
| `AgendamentoCreatedAtIntegrationTest` | 5 |
| `PostgresRolePrivilegesIntegrationTest` | 4 |
| `TestPostgresSettingsTest` | 7 |
| **Total** | **60** |

Uma repetição intermediária, antes do ajuste do teste de concorrência, teve
1 erro em `exclusionPostgresContinuaSendoAutoridadeEmDoisInsertsConcorrentes`:
o PostgreSQL abortou um dos inserts com deadlock `40P01`, em vez da violação de
exclusão `23P01` que o helper aceitava originalmente. O helper do teste passou
a reconhecer ambos como resultado de uma das transações não confirmar; nenhuma
regra de produção ou proteção H-03 foi alterada. Após esse ajuste, a suíte
completa foi executada em um container novo e terminou com os 60 testes
aprovados.

O teste de lifecycle existente registra no log uma violação de chave estrangeira
que é provocada intencionalmente para validar o tratamento de erro; o caso
passou e não gerou falha ou erro na suíte.

## 9. Verificações de segurança e recusas

`PostgresRolePrivilegesIntegrationTest` confirmou por conexão e catálogos que:

- a conexão JDBC da aplicação usa `current_user = arena_runtime`;
- a conexão configurada no Flyway usa `current_user = arena_migrator`;
- ambos os roles não têm atributos administrativos nem memberships;
- runtime não tem `CREATE` ou `TEMP` no database nem `CREATE` no schema;
- migrator não tem `CREATE` no database após o provisionamento, mas tem
  `USAGE/CREATE` no schema;
- as três tabelas da V1 pertencem ao migrator;
- runtime tem DML nas tabelas e `USAGE/SELECT`, mas não `UPDATE`, nas
  sequências;
- runtime não possui privilégios de tabela sobre
  `flyway_schema_history`;
- uma tentativa real de leitura do histórico falha para runtime;
- `CREATE TABLE`, `CREATE TEMP TABLE` e `CREATE SCHEMA` falham para runtime;
- DML runtime funciona e um objeto de teste criado pelo migrator comprova os
  defaults de tabela e sequência para objetos futuros.

Separadamente, o script de provisionamento foi executado com confirmação `NO`
e interrompeu antes de criar roles; com `YES`, o provisionamento no database
descartável terminou com sucesso.

## 10. Recursos explicitamente intocados

Durante esta etapa:

- nenhuma conexão ou consulta foi feita ao database `arena` ou à conta
  `arena_dev`; portanto, nenhum comando desta implementação alterou esses
  recursos;
- o `.env` real não foi lido, editado ou incluído em comandos;
- `arena_postgres_data` não foi montado, removido ou recriado; o Compose não foi
  iniciado nem alterado;
- os únicos comandos Docker PostgreSQL referiram-se aos containers
  descartáveis desta validação, com `tmpfs`, porta loopback aleatória e sem
  volume nomeado;
- não foi feito commit nem push.

Essa confirmação descreve as ações desta etapa; não foi feita uma consulta ao
ambiente existente para inspecionar estado, justamente para mantê-lo intocado.

## 11. Limitações e próximos passos

- O procedimento cobre **instalações novas**. Não há migração operacional
  automática de `arena_dev` ou de databases existentes; planejar isso com DBA,
  aprovação e backup próprios.
- A criação do database e do usuário provisionador ocorre fora do script. O
  operador deve garantir que o alvo esteja vazio e sob controle antes de
  fornecer `ARENA_PROVISION_NEW_DATABASE=YES`.
- Roles PostgreSQL são globais ao cluster. Ambientes distintos no mesmo cluster
  podem exigir nomes de roles separados; o script atual assume um par dedicado
  (`arena_runtime`/`arena_migrator`) e falha se os nomes já existirem.
- Os privilégios padrão cobrem objetos no schema `public` criados exatamente
  pelo role `arena_migrator`. Migrations futuras que usem outro owner/schema
  exigem revisão explícita dos defaults e grants.
- A disputa concorrente direta na constraint `EXCLUDE` pode abortar a transação
  perdedora por `23P01` ou `40P01`; o teste agora cobre ambos. A aplicação já
  classifica deadlock como erro transitório, não como conflito de domínio.
- O procedimento da extensão foi validado em PostgreSQL 16, onde `btree_gist`
  é trusted. Outras versões/distribuições devem validar esse requisito antes
  do provisionamento.
- O bootstrap seguro do primeiro usuário administrador da aplicação (H-04)
  continua fora do escopo desta etapa.

## 12. Esclarecimentos após revisão

### 12.1 Diff em `PostgresClock.java`

O diff local atual de `src/main/java/br/com/arena/service/PostgresClock.java`
remove o Javadoc:

```java
/**
 * Deve ser chamado dentro da transação e somente depois dos locks necessários.
 */
```

A implementação de `agora()`, incluindo a consulta
`SELECT clock_timestamp()`, não mudou; o diff também deixa uma linha em branco
adicional. O `HEAD` rastreado contém o comentário e o histórico aponta para o
commit `8d5fce5 feat(booking): implement booking lifecycle, concurrency control
and PostgresClock`. O Git não registra autor/origem de uma modificação ainda
não commitada, portanto não é possível atribuir com segurança quem removeu o
comentário. O diff foi identificado como não relacionado a H-02 e foi
preservado; não foi descartado nem revertido.

O registro da inspeção inicial da Etapa 7 apontava worktree limpo; na primeira
verificação de status posterior ao início da implementação, esse diff já estava
presente. Nenhum patch desta etapa foi direcionado a `PostgresClock.java`.
Assim, é possível delimitar que a alteração apareceu nesse intervalo, mas não
identificar sua autoria ou causa exata.

### 12.2 Conta administrativa `arena_provisioner`

`scripts/postgres/provision-arena-roles.sql` **não cria**
`arena_provisioner`; cria apenas `arena_runtime` e `arena_migrator`. O database
alvo também precisa existir antes da execução. O script deve ser conectado
diretamente ao database novo e vazio.

Na imagem oficial PostgreSQL, configurar `POSTGRES_USER=arena_provisioner`
cria essa conta bootstrap com privilégio administrativo somente quando a imagem
inicializa um diretório de dados novo. Esse foi o modelo usado no teste
descartável. Em uma instalação nova, essa conta bootstrap — ou uma conta DBA /
superuser aprovada com poderes equivalentes — executa o script. Ela precisa
poder criar roles e grants e executar `SET ROLE arena_migrator` para instalar
`btree_gist`; no teste, a conta bootstrap era superuser.

Se o ambiente já tiver um administrador de cluster, pode-se usar essa conta
aprovada; ela não precisa se chamar `arena_provisioner`. Se não existir uma
conta bootstrap/provisionadora, sua criação pertence ao processo administrativo
de criação do cluster e ocorre **fora** deste script. Em nenhum caso se deve
alterar uma instalação, database, `.env` ou volume existente para criar essa
conta como parte de H-02.

### 12.3 Snapshot do Git

Estado observado após a implementação, em **22/11/2026**:

- branch: `develop`, acompanhando `origin/develop`; a saída de status não mostra
  indicação `ahead` ou `behind`;
- arquivos rastreados modificados, todos sem staging:
  - `.env.example`
  - `src/main/java/br/com/arena/service/PostgresClock.java`
  - `src/main/resources/application.properties`
  - `src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java`
  - `src/test/java/br/com/arena/model/AgendamentoCreatedAtIntegrationTest.java`
  - `src/test/java/br/com/arena/testsupport/TestPostgresContextInitializer.java`
  - `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java`
  - `src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java`
- arquivos não rastreados:
  - `docs/24-separacao-usuarios-postgresql-h02.md`
  - `scripts/postgres/provision-arena-roles.sql`
  - `src/main/resources/db/migration/V2__grant_runtime_privileges.sql`
  - `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`

Não foram feitos commits nem push durante a Etapa 7. O diff em
`PostgresClock.java` permanece intacto conforme esclarecido acima.

### 12.4 Verificação local de divergência Git

Em verificação local posterior, sem `fetch`, `pull`, commit ou push, foram
obtidos estes resultados:

```text
$ git status -sb
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
?? scripts/
?? src/main/resources/db/migration/V2__grant_runtime_privileges.sql
?? src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java

$ git rev-list --left-right --count HEAD...origin/develop
0       0

$ git log -1 --format='%H%n%s' HEAD
194a322d76dc8772c53af26b09d1fff1c2ebc94f
docs(git): add commit organization report (doc 23)
```

Nesse snapshot, `develop` e a referência local `origin/develop` apontam para o
mesmo commit (0 à frente, 0 atrás). Como não foi feito `fetch`, isso compara
somente com a referência remota armazenada localmente e não confirma o estado
atual do servidor remoto. O resultado difere do registro anterior de 12 commits
à frente; esta verificação não determina quando nem por qual operação a
referência local mudou.

Todos os arquivos modificados e não rastreados listados acima continuaram
presentes. Nenhuma entrada foi descartada ou revertida. A única alteração
realizada para atender a esta revisão foi acrescentar este registro ao próprio
relatório; não houve operação de escrita no índice ou no histórico Git.
