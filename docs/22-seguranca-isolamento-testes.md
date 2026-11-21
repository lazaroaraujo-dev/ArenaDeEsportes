# Etapa 6 — segurança e isolamento dos testes de integração

## 1. Diagnóstico encontrado

Antes das alterações, havia quatro classes que inicializavam o contexto:

| Classe | Configuração anterior |
|---|---|
| `ArenaApplicationTests` | `@SpringBootTest` sem datasource de teste, guarda de URL ou perfil isolado. |
| `AgendamentoCreatedAtIntegrationTest` | `@SpringBootTest` e `@DynamicPropertySource` com validação local própria de `TEST_POSTGRES_URL`. |
| `AgendamentoLifecycleIntegrationTest` | `@SpringBootTest`/MockMvc e outra validação própria de `TEST_POSTGRES_URL`. |
| `AgendamentoConcurrencyIntegrationTest` | `@SpringBootTest`/MockMvc e terceira validação própria de `TEST_POSTGRES_URL`. |

Não foram encontrados `@DataJpaTest`, `@WebMvcTest` ou outras classes que iniciem contextos Spring com banco. Os testes standalone de DB-01/SEG-01 usam controllers/serviços/repositórios simulados e não conectam ao banco.

`application.properties` contém placeholders `SPRING_DATASOURCE_*`, `spring.flyway.enabled=true` e `ddl-auto=validate`. Portanto, o `ArenaApplicationTests` anterior podia herdar uma datasource externa e iniciar Flyway. As três integrações usavam TEST_*, mas a proteção era repetida por classe e não abrangia todas as inicializações.

## 2. Estado do Git e preservação

O estado Git foi registrado antes da implementação. Já havia diversas alterações rastreadas e não rastreadas das etapas anteriores, incluindo `pom.xml`, fontes de segurança/agendamentos/quadras, `src/main/resources/`, `docs/` e testes. Foram preservadas; não houve reset, limpeza ou commit.

Nesta etapa foram alterados somente os quatro testes de contexto existentes e adicionados arquivos de suporte em `src/test/java/br/com/arena/testsupport/`, mais este relatório. Nenhum arquivo de produção, configuração, migration, dependência ou `.env` foi alterado.

## 3. Estratégia centralizada implementada

Foi criada a anotação composta `@IsolatedPostgresIntegrationTest`, que combina `@SpringBootTest` com `TestPostgresContextInitializer`. Ela substitui o uso direto de `@SpringBootTest` nas quatro classes que inicializam o backend.

O initializer executa durante a preparação do contexto, **antes do refresh e antes da criação do DataSource/Flyway**. Ele chama `TestPostgresSettings` usando exclusivamente `System.getenv()` para as variáveis:

- `TEST_POSTGRES_URL`
- `TEST_POSTGRES_USERNAME`
- `TEST_POSTGRES_PASSWORD`
- `TEST_JWT_SECRET`
- `TEST_JWT_EXPIRATION_MS`

Não há fallback para `SPRING_DATASOURCE_*`, `JWT_SECRET` ou `JWT_EXPIRATION_MS`. Depois de validar todas as variáveis, o initializer adiciona uma `MapPropertySource` de maior precedência com:

- datasource de teste;
- Flyway habilitado;
- Hibernate `ddl-auto=validate`;
- propriedades JWT exclusivas do teste.

Assim, a configuração normal da aplicação não fornece datasource aos contextos de teste. Ausência ou valor inválido gera exceção de configuração antes do refresh; Flyway não chega a ser inicializado.

### Política de URL

Somente é aceita a forma exata:

```text
jdbc:postgresql://127.0.0.1:<porta>/arena_flyway_test
```

A porta precisa estar no intervalo 1–65535. A validação rejeita `localhost`, hosts externos, bancos `arena`/`arena_dev`, porta ausente/inválida, userinfo, query parameters e sufixos que possam mudar o destino. A mensagem de erro identifica a variável inválida, sem imprimir URL, usuário, senha ou token.

JWT de teste também é obrigatório e validado para tamanho mínimo compatível com JJWT; a expiração precisa ser inteira e positiva. Mensagens não incluem valores.

## 4. Testes de segurança adicionados

`TestPostgresSettingsTest` é unitário e não inicializa aplicação nem banco. Ele verifica:

1. Aceitação da URL local exata de `arena_flyway_test`.
2. Falha quando `TEST_POSTGRES_URL` está ausente.
3. `SPRING_DATASOURCE_URL` presente não serve como fallback.
4. Rejeição de `/arena`, `/arena_dev`, host externo, `localhost`, query parameter e portas inválidas.
5. Falha para credencial de teste ausente e JWT inválido, sem incluir os valores na mensagem.
6. A URL de teste sobrepõe qualquer datasource normal no ambiente de configuração.
7. Todo teste atual que usa `@SpringBootTest`/`@ContextConfiguration` está marcado com a anotação central.

A execução negativa de `ArenaApplicationTests` com todas as variáveis TEST_* e SPRING_DATASOURCE_* removidas falhou como esperado com a mensagem de variável de teste ausente. O initializer foi executado antes do refresh; não houve tentativa de conectar ou executar Flyway.

## 5. Arquivos alterados/criados

| Arquivo | Alteração |
|---|---|
| `src/test/java/br/com/arena/ArenaApplicationTests.java` | Passa a usar `@IsolatedPostgresIntegrationTest`; não herda datasource normal sem validação. |
| `src/test/java/br/com/arena/model/AgendamentoCreatedAtIntegrationTest.java` | Usa a configuração central; removida a guarda duplicada local. |
| `src/test/java/br/com/arena/controller/AgendamentoLifecycleIntegrationTest.java` | Usa a configuração central; removida a guarda duplicada local. |
| `src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java` | Usa a configuração central; removida a guarda duplicada local. |
| `src/test/java/br/com/arena/testsupport/IsolatedPostgresIntegrationTest.java` | Nova anotação composta para contextos de teste com banco. |
| `src/test/java/br/com/arena/testsupport/TestPostgresContextInitializer.java` | Novo initializer, aplicado antes do refresh/Flyway. |
| `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java` | Validação central de variáveis, URL e parâmetros de teste. |
| `src/test/java/br/com/arena/testsupport/TestPostgresSettingsTest.java` | Testes unitários do guard e de sua aplicação centralizada. |
| `docs/22-seguranca-isolamento-testes.md` | Este relatório. |

Não foi modificada a configuração de produção `application.properties`; nela, Flyway continua habilitado e Hibernate permanece em `validate`.

## 6. Execução da suíte completa

Foi iniciado um PostgreSQL 16 descartável com:

- database `arena_flyway_test`;
- porta aleatória publicada somente em `127.0.0.1`;
- armazenamento `tmpfs`;
- nenhum volume nomeado.

Para executar `mvn test`, foram definidos os cinco TEST_* obrigatórios. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET` e `JWT_EXPIRATION_MS` foram explicitamente removidos do ambiente do processo.

**Resultado:** `mvn test` — **BUILD SUCCESS, 55 testes, 0 falhas, 0 erros, 0 ignorados**.

| Classe | Testes |
|---|---:|
| `ArenaApplicationTests` | 1 |
| `AgendamentoConcurrencyIntegrationTest` | 24 |
| `AgendamentoControllerDb01Test` | 5 |
| `AgendamentoControllerSecurityTest` | 5 |
| `AgendamentoLifecycleIntegrationTest` | 9 |
| `AgendamentoCreatedAtIntegrationTest` | 5 |
| `TestPostgresSettingsTest` | 6 |
| **Total** | **55** |

Flyway registrou e aplicou V1 no endereço `127.0.0.1:<porta-aleatória>/arena_flyway_test`; os contextos subsequentes encontraram o schema de teste atualizado. `AgendamentoCreatedAtIntegrationTest` também verifica `current_database() == arena_flyway_test`, a versão 1 e a EXCLUDE. A URL passa pelo guard central antes que Flyway possa conectar.

## 7. Banco `arena` e volumes

Após a suíte, foi feita uma consulta explicitamente read-only no container de desenvolvimento, com `default_transaction_read_only=on` e transação `READ ONLY`:

- `current_database() = arena`, `current_user = arena_dev`, PostgreSQL 16.15;
- papel atual: `rolsuper=true`, `rolcreatedb=true`;
- nenhuma tabela no schema consultado e nenhuma `flyway_schema_history`;
- `btree_gist` disponível, versão 1.7, mas não instalada.

O container descartável foi removido pelo próprio `--rm`. O container de desenvolvimento permaneceu saudável e `arena_postgres_data` continua presente. Não houve escrita, limpeza ou migration em `arena`; nenhum volume foi removido.

Nenhuma senha, token ou JWT secret foi registrado. O `.env` real não foi lido.

## 8. Limitações e pendências

- O guard valida rigorosamente host, porta, nome do database e parâmetros da URL; **não consegue provar por si só que o diretório de dados do PostgreSQL está em `tmpfs`**. Essa garantia é operacional: o comando usado para a suíte criou container descartável sem volume. Execuções futuras devem usar o mesmo procedimento.
- Novos testes que inicializem Spring com banco devem usar `@IsolatedPostgresIntegrationTest`. O teste de descoberta falhará se uma nova classe atual usar `@SpringBootTest`/`@ContextConfiguration` sem a anotação.
- As decisões H-02/H-04 da auditoria anterior — separar o usuário de runtime do usuário Flyway e bootstrap seguro do primeiro ADMIN — não fazem parte desta etapa e continuam pendentes.
- O funcionamento de testes que não inicializam Spring não exige PostgreSQL; eles não podem acessar um banco por esse guard, portanto devem continuar sem chamadas a datasource externo.

**Conclusão H-03:** corrigido para todos os contextos Spring encontrados, incluindo `ArenaApplicationTests`. A suíte completa foi executada exclusivamente no PostgreSQL descartável e passou.
