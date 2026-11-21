# DB-02 — Etapa 1: configuração PostgreSQL e Flyway

## 1. Escopo e resultado

Esta etapa adicionou configuração de datasource por ambiente, Flyway e uma migration inicial do schema DB-02. A migration foi executada **somente** em um PostgreSQL 16 descartável, isolado por `tmpfs`; o banco de desenvolvimento `arena` não foi migrado nem alterado.

O build e os 11 testes existentes passaram. O startup de teste aplicou V1 e Hibernate validou o schema. Um INSERT no banco descartável confirmou o default `criado_em`; a entidade JPA atual ainda não mapeia esse campo, portanto a leitura do timestamp foi verificada por SQL `RETURNING`, não através da entidade.

## 2. Arquivos alterados

| Arquivo | Alteração |
|---|---|
| `pom.xml` | Adicionados `spring-boot-starter-flyway` e `flyway-database-postgresql`, sem versões explícitas; versões geridas pelo parent Boot 4.0.7. O driver PostgreSQL existente foi mantido sem duplicação. |
| `src/main/resources/application.properties` | Adicionada configuração de datasource e JWT usando placeholders de variáveis de ambiente; `ddl-auto=validate`; Flyway habilitado. Nenhum segredo está no arquivo. |
| `src/main/resources/db/migration/V1__create_arena_schema.sql` | Criadas tabelas, constraints, FKs, índices, timestamp, `btree_gist` e EXCLUDE parcial para horários bloqueantes. |
| `docs/16-implementacao-inicial-flyway-db-02.md` | Registro da implementação e das validações. |

Não foram alterados entidades, serviços, repositórios, controllers, DTOs, Docker Compose, `.env` ou o volume `arena_postgres_data`.

## 3. Dependências Flyway e compatibilidade

O projeto usa Spring Boot 4.0.7 e Java 21. O parent/BOM Boot 4.0.7 gerencia Flyway 11.14.1:

- `spring-boot-starter-flyway` 4.0.7 fornece a integração Spring Boot/Flyway e Flyway Core.
- `flyway-database-postgresql` 11.14.1 é o módulo específico necessário para PostgreSQL.
- O driver `org.postgresql:postgresql` já estava declarado em runtime.

O BOM gerencia as versões; não foram fixadas versões diretamente nas duas novas dependências. `mvn test` resolveu os artefatos com sucesso. Referências: [Spring Boot 4.x — Database Initialization/Flyway](https://docs.spring.io/spring-boot/4.0/how-to/data-initialization.html) e [BOM Boot 4.0.7](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.7/spring-boot-dependencies-4.0.7.pom).

## 4. Configuração datasource e Hibernate

`application.properties` não contém valores de conexão ou segredos:

```properties
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
spring.flyway.enabled=true
spring.jpa.hibernate.ddl-auto=validate
jwt.secret=${JWT_SECRET}
jwt.expiration=${JWT_EXPIRATION_MS}
```

Para IntelliJ no Windows e para Maven no WSL, definir no ambiente de execução:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arena
SPRING_DATASOURCE_USERNAME=arena_dev
SPRING_DATASOURCE_PASSWORD=<valor local não versionado>
JWT_SECRET=<segredo local forte não versionado>
JWT_EXPIRATION_MS=<duração em milissegundos>
```

O bind Docker `127.0.0.1:5432` foi confirmado no host; por isso, `localhost:5432` é o endereço para aplicação no Windows/WSL com o forwarding ativo. Uma aplicação em contêiner na rede Compose usaria o nome do serviço, `postgres:5432`. O `.env` com `POSTGRES_*` é carregado pelo Compose, não automaticamente pelo processo Spring/IntelliJ.

`ddl-auto=validate` impede Hibernate de criar, atualizar ou excluir tabelas. Flyway é o único responsável pelo DDL. `JWT_SECRET` e `JWT_EXPIRATION_MS` permanecem obrigatórios porque `JwtService` consome `jwt.secret` e `jwt.expiration`; usar chave de teste somente no ambiente de teste e nunca versionar uma chave real.

## 5. Migration V1 e modelo físico

`V1__create_arena_schema.sql` cria:

- `usuario`: IDENTITY/BIGINT, campos String VARCHAR(255), e-mail UNIQUE, perfil e CHECK com `ADMIN`/`CLIENTE`.
- `quadra`: IDENTITY/BIGINT, nome, tipo, `ativa BOOLEAN NOT NULL` e CHECK dos tipos de quadra.
- `agendamento`: IDENTITY/BIGINT, `data DATE`, horas `TIME WITHOUT TIME ZONE`, status String com default `PENDENTE`, `criado_em TIMESTAMPTZ DEFAULT statement_timestamp()`, FKs obrigatórias e CHECK `hora_fim > hora_inicio`.
- FKs com `ON DELETE NO ACTION`/`ON UPDATE NO ACTION`, sem cascade.
- CHECK com `PENDENTE`, `CONFIRMADO`, `CANCELADO`, `EXPIRADO`.
- Extensão `btree_gist` e constraint `EXCLUDE USING gist` para `(quadra_id, tsrange(..., '[)'))`, parcial para `PENDENTE` e `CONFIRMADO`.
- Índices para `usuario_id`, `(quadra_id, data)` e busca de pendentes por `criado_em`; a EXCLUDE cria o índice GiST composto.

Os nomes seguem o naming strategy físico padrão esperado (`Agendamento` → `agendamento`, `horaInicio` → `hora_inicio`). Hibernate validou essa migration no teste. `criado_em` ainda não existe na entidade Java; a coluna extra é aceita pelo schema `validate`, e seu valor foi testado por `INSERT ... RETURNING`. O mapeamento JPA para ler `criadoEm` será necessário na etapa de scheduler e deve ser autorizado/implementado nessa etapa, não foi antecipado aqui. O enum Java também ainda não contém `EXPIRADO`; até essa mudança de código, a aplicação não pode ler esse valor de uma linha.

## 6. Verificação de permissões PostgreSQL

Consultas read-only no banco de desenvolvimento confirmaram:

- `current_database() = arena`, `current_user = arena_dev`.
- O papel tem `rolsuper=true`, `rolcreatedb=true` e privilégio `CREATE` no database.
- `btree_gist` está disponível como extensão confiável na versão 1.7, mas **não foi instalado em `arena`**.

No banco descartável, V1 instalou `btree_gist` com sucesso. Nenhuma extensão foi instalada no banco de desenvolvimento.

## 7. Ambiente descartável e isolamento

Foi usado um contêiner `postgres:16` chamado `arena-flyway-validation`, database `arena_flyway_test`, credenciais fictícias temporárias e porta aleatória `127.0.0.1:53480`. O diretório de dados foi `tmpfs`; `docker inspect` confirmou `HostConfig.Tmpfs` e `Mounts=[]`, portanto não usou volume nomeado.

O contêiner de desenvolvimento `arenadeesportes-postgres-1` permaneceu `Up (healthy)` em `127.0.0.1:5432`, montando `arena_postgres_data`. O teste Maven conectou explicitamente a `arena_flyway_test` na porta temporária, não a `arena`. Ao final, o contêiner temporário não constava mais em `docker ps -a`; o comando `docker stop` retornou “No such container” e não afetou o contêiner de desenvolvimento. Nenhum volume foi criado/removido por essa validação.

## 8. Testes executados e resultados

### Flyway, Hibernate e suíte Maven

Com variáveis de teste temporárias (valores omitidos), foi executado `mvn test` apontando para `jdbc:postgresql://127.0.0.1:53480/arena_flyway_test`:

- Flyway 11.14.1 validou uma migration e aplicou `V1` com sucesso.
- `flyway_schema_history` registrou versão `1`, descrição `create arena schema`, `success=true`.
- Spring Boot 4.0.7 iniciou com PostgreSQL 16.15.
- Hibernate 7.2.19.Final inicializou `EntityManagerFactory` com `ddl-auto=validate`; não houve erro de schema.
- Resultado: **11 testes, 0 falhas, 0 erros; BUILD SUCCESS**.

### Inspeção do schema descartável

Consultas read-only após a migration encontraram `usuario`, `quadra`, `agendamento` e `flyway_schema_history`. A tabela de agendamentos contém PK, CHECKs de horário/status, FKs, EXCLUDE e índices previstos; `btree_gist` está instalado no banco descartável.

### INSERT e invariantes

Em `arena_flyway_test`, um bloco de teste:

- Inseriu usuário/quadra temporários e um agendamento omitindo `criado_em` e `status`.
- `INSERT ... RETURNING criado_em, status` retornou timestamp não nulo e `PENDENTE`.
- Um intervalo sobreposto foi rejeitado pela constraint com SQLSTATE `23P01`.
- Um intervalo adjacente foi aceito.
- Um intervalo atravessando a meia-noite foi rejeitado pelo CHECK.

Esses INSERTs ocorreram exclusivamente no contêiner descartável em `tmpfs`.

### Banco de desenvolvimento depois dos testes

Consulta read-only posterior confirmou `current_database() = arena`, `current_user = arena_dev`, nenhuma tabela de aplicação no schema e nenhuma `flyway_schema_history`. Portanto V1 **não** foi aplicada ao banco de desenvolvimento; `arena_postgres_data` permaneceu presente e inalterado.

## 9. Problemas e limitações identificados

1. **`criado_em` não está mapeado na entidade `Agendamento`.** A geração e leitura SQL pelo PostgreSQL foram verificadas, mas não a recuperação do valor pelo Hibernate. É necessária uma futura alteração autorizada em `Agendamento` (campo `Instant` gerado) antes de implementar expiração no serviço.
2. **`EXPIRADO` não está no enum Java.** O CHECK/migration já prevê esse estado; a etapa de serviço/status deverá adicionar o enum antes de gravar ou ler linhas expiradas.
3. **Flyway não foi aplicado a `arena`.** Esse banco segue sem tabelas; a migration foi testada somente no banco isolado.
4. **Sem teste de fluxo JPA que insere `Agendamento`:** o teste existente `contextLoads` valida startup/migration/schema, e o `RETURNING` foi um teste SQL. A recuperação de `criadoEm` por entidade permanece explicitamente pendente.
5. **Teste temporário usa usuário privilegiado:** no PostgreSQL oficial de teste, `POSTGRES_USER` é superuser. O usuário local `arena_dev` também foi confirmado como superuser; em ambientes não locais, separar privilégios de migration e runtime é recomendação de segurança.
6. Logs de startup mantiveram avisos não bloqueantes sobre Open Session in View e configuração explícita de `AuthenticationProvider`; não foram alterados por estarem fora do escopo.

## 10. Pendências antes de aplicar V1 em `arena`

- Revisar a migration no controle de versão e aprovar o schema/tipos/nomes.
- Implementar, com autorização, o mapeamento de `criadoEm` e o enum `EXPIRADO`, preservando SEG-01/DB-01.
- Revisar a separação entre usuário de migration (DDL/`btree_gist`) e usuário de runtime para outros ambientes.
- Definir as próximas etapas DB-02 (serviços transacionais, expiração, confirmação/cancelamento e HTTP 409).
- Aplicar V1 ao banco `arena` somente em etapa autorizada e após confirmar novamente `current_database()`, host/porta, volume e o alvo. Não usar `ddl-auto=update`; manter Hibernate em `validate`.

Nenhuma migration, alteração no banco `arena` ou edição de dados foi realizada nesta etapa.
