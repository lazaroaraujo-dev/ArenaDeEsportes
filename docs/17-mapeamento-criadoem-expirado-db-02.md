# DB-02 — Etapa 2: mapeamento de `criadoEm` e status `EXPIRADO`

## 1. Resumo

Esta etapa mapeia o `criado_em` já definido em V1 como valor gerado pelo PostgreSQL e acrescenta `EXPIRADO` ao enum Java. A migration V1 não foi modificada. A suíte completa passou em PostgreSQL 16.15 descartável, incluindo inicialização Flyway, validação Hibernate e testes de persistência.

O banco de desenvolvimento `arena` não recebeu migration nem dados de teste. Foi confirmado após os testes que continua sem tabelas de aplicação e sem `flyway_schema_history`.

## 2. Arquivos alterados

| Arquivo | Alteração |
|---|---|
| `src/main/java/br/com/arena/model/Agendamento.java` | Campo `Instant criadoEm`, gerado no INSERT pelo banco, recuperado pelo Hibernate e não gravável em INSERT/UPDATE pela aplicação. |
| `src/main/java/br/com/arena/enumerate/StatusAgendamento.java` | Acrescentado `EXPIRADO`; valores existentes preservados. |
| `src/test/java/br/com/arena/model/AgendamentoCreatedAtIntegrationTest.java` | Cinco testes de integração com PostgreSQL real, Flyway e Hibernate. A URL de teste é validada para aceitar somente `127.0.0.1:<porta>/arena_flyway_test`. |
| `docs/17-mapeamento-criadoem-expirado-db-02.md` | Registro da estratégia, validações, resultados e pendências. |

Não foram alterados nesta etapa `pom.xml`, V1, `application.properties`, DTOs, serviços, repositórios, controllers, `Usuario` ou `Quadra`. As alterações de configuração/Flyway do estágio anterior foram preservadas.

## 3. Estratégia de mapeamento de `criadoEm`

Em `Agendamento`, o campo foi mapeado assim:

- Tipo Java `Instant`, compatível com `TIMESTAMPTZ`.
- `@Column(name = "criado_em", nullable = false, insertable = false, updatable = false)`.
- `@Generated(event = EventType.INSERT)`, da API Hibernate 7, solicita a leitura do valor gerado pelo banco após o INSERT.
- `@Setter(AccessLevel.NONE)` impede que o código de aplicação use o setter gerado por Lombok.

Não foi utilizado `@CreationTimestamp`: a fonte de tempo continua sendo o default PostgreSQL `statement_timestamp()` da migration V1. Hibernate omite a coluna no INSERT e recupera o valor gerado, usando o mecanismo suportado pelo dialeto/driver. A coluna também não entra em UPDATE.

O teste confirmou que o valor de `criadoEm` devolvido pelo Hibernate é igual ao valor lido diretamente do PostgreSQL. A leitura após `EntityManager.clear()` confirmou que o timestamp persistido continua igual.

## 4. Status `EXPIRADO`

`EXPIRADO` foi acrescentado ao final do enum `StatusAgendamento`, preservando `PENDENTE`, `CONFIRMADO` e `CANCELADO`. A V1 já contém `EXPIRADO` no CHECK, portanto não foi criada migration adicional.

Um teste persistiu um registro `EXPIRADO` no PostgreSQL e confirmou que o Hibernate o converteu de volta para `StatusAgendamento.EXPIRADO`. Também foi verificado que um registro expirado pode coexistir no mesmo intervalo com um `PENDENTE`, conforme o predicado parcial da constraint.

Os serviços ainda não realizam expiração. O enum agora permite representar o estado, mas scheduler, confirmação, cancelamento e regras de transição continuam pendentes para as próximas etapas DB-02.

## 5. Ambiente e isolamento dos testes

- PostgreSQL descartável: imagem `postgres:16`, servidor reportado pelos testes como **16.15**.
- Database de teste: `arena_flyway_test`.
- A porta do host foi atribuída dinamicamente (`127.0.0.1:63683` nesta execução).
- `/var/lib/postgresql/data` usou `tmpfs`; `docker inspect` confirmou `Mounts=[]`. O volume `arena_postgres_data` não foi montado nem modificado.
- As credenciais e a chave JWT foram temporárias para o processo de teste e não foram registradas neste documento.
- O contêiner temporário não aparece no estado final de `docker ps -a`; o banco de desenvolvimento `arenadeesportes-postgres-1` permaneceu ativo e saudável.

## 6. Testes executados

Com variáveis temporárias apontando para `arena_flyway_test`:

```text
# Os valores de senha/chave são temporários e foram omitidos deste relatório.
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:<porta-temporaria>/arena_flyway_test
SPRING_DATASOURCE_USERNAME=<usuario-descartavel>
SPRING_DATASOURCE_PASSWORD=<segredo-descartavel>
JWT_SECRET=<chave-descartavel>
JWT_EXPIRATION_MS=3600000
TEST_POSTGRES_URL=$SPRING_DATASOURCE_URL
TEST_POSTGRES_USERNAME=$SPRING_DATASOURCE_USERNAME
TEST_POSTGRES_PASSWORD=$SPRING_DATASOURCE_PASSWORD
TEST_JWT_SECRET=$JWT_SECRET
TEST_JWT_EXPIRATION_MS=$JWT_EXPIRATION_MS
mvn test
```

`AgendamentoCreatedAtIntegrationTest` valida que `TEST_POSTGRES_URL` aponta exatamente para `127.0.0.1:<porta>/arena_flyway_test`, evitando executar Flyway no database `arena`.

Ao executar a suíte completa, todas as `SPRING_DATASOURCE_*` também precisam apontar ao mesmo database descartável: o teste legado `ArenaApplicationTests` carrega a aplicação e executa Flyway sem o guard `TEST_POSTGRES_URL` da classe nova. Não executar `mvn test` com as variáveis de uma sessão IntelliJ que apontem a `arena`.

**Resultado: BUILD SUCCESS — 16 testes, 0 falhas, 0 erros.**

| Teste | Verificação |
|---|---|
| Geração de timestamp | Persistiu sem preencher `criadoEm`; PostgreSQL aplicou `statement_timestamp()`, e Hibernate retornou valor não nulo igual ao SELECT da coluna. O default da coluna também foi inspecionado. |
| Persistência | Após limpar o persistence context e recarregar por ID, `criadoEm` permaneceu igual. |
| Atualização | Alterar data/horas e salvar não modificou o timestamp original. |
| Enum `EXPIRADO` | Persistência e leitura retornaram `StatusAgendamento.EXPIRADO`; status expirado coexistiu com PENDENTE no mesmo intervalo. |
| V1 e schema | Flyway validou e aplicou V1; Hibernate `ddl-auto=validate` inicializou; `flyway_schema_history` registrou V1 com sucesso e a constraint EXCLUDE estava presente. |
| Invariantes SQL | INSERT sobreposto foi rejeitado com SQLSTATE `23P01`; horário adjacente foi aceito; horário atravessando meia-noite foi rejeitado pelo CHECK. |
| Regressões | Os testes existentes de SEG-01 e DB-01 também passaram. |

## 7. Verificação do banco `arena`

Depois da suíte, consultas read-only ao container de desenvolvimento confirmaram:

- `current_database() = arena`, `current_user = arena_dev`.
- Nenhuma tabela de aplicação no schema consultado.
- Nenhuma tabela `flyway_schema_history`.
- O container de desenvolvimento continuou `Up (healthy)` com o volume `arena_postgres_data`.

V1 foi executada somente no PostgreSQL descartável `arena_flyway_test`. Não houve escrita, migration, limpeza ou alteração no banco `arena`.

## 8. Problemas e limitações

- Permanecem avisos de inicialização preexistentes e não bloqueantes sobre `spring.jpa.open-in-view` e configuração explícita de `AuthenticationProvider`; não foram alterados nesta etapa.
- O timestamp é gerado e recuperado pelo JPA, mas nenhum serviço/DTO ainda o utiliza.
- O enum pode armazenar `EXPIRADO`, mas `AgendamentoService` ainda não implementa as transições ou a expiração automática.
- A validação de integração usa um PostgreSQL externo descartável, fornecido por variáveis `TEST_POSTGRES_*`; não foi adicionada dependência Testcontainers.
- Os testes `standaloneSetup` anteriores continuam não exercitando o proxy real de `@PreAuthorize`; essa lacuna não foi ampliada por esta etapa.

## 9. Pendências antes da próxima etapa

1. Obter autorização para implementar as regras de serviço/scheduler e, se necessário, ajustar a entidade/DTOs de transição.
2. Implementar expiração condicional após 12 horas, confirmação exclusiva ADMIN, cancelamento PATCH com retenção e reagendamento conforme status, mantendo SEG-01/DB-01.
3. Acrescentar testes de concorrência com conexões PostgreSQL separadas e conversão de `23P01` para HTTP 409.
4. Não aplicar V1 ao banco `arena` até a autorização explícita prevista para essa operação.
