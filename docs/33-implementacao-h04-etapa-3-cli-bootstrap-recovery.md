# Etapa 3 — H-04 | CLI offline de Bootstrap e Recovery

**Status:** concluída e validada em PostgreSQL 16 descartável.

**Escopo:** comandos CLI offline para bootstrap do primeiro ADMIN e
recuperação administrativa, reutilizando a infraestrutura da migration V3 e a
migration incremental V3.1. Não foram criados endpoints REST, DTOs, V4/V5,
frontend nem alterações no fluxo de cadastro normal.

## 1. Resumo

Esta etapa entregou:

- `AdminCliApplication`: aplicação Spring Boot não-web (`WebApplicationType.NONE`)
  que não inicializa servidor HTTP, scheduling, HttpSecurity, JWT, OpenAPI,
  DataSource da aplicação, JPA nem Flyway.
- `CliEnvironment`: carrega exclusivamente variáveis `OFFLINE_POSTGRES_*` e
  `OFFLINE_OPERATOR_ID`, validando que o usuário é `arena_offline_operator`.
- `CliRunner`: orquestra a leitura interativa (prompts ocultos para senha),
  validações locais e chamada às funções SECURITY DEFINER da V3/V3.1.
- `AdminLifecycleCliService`: invoca `h04_bootstrap_first_admin` e
  `h04_recover_admin` pela role `arena_offline_operator`.
- `V3_1__admin_recovery_audit_context.sql`: migration incremental que adiciona
  `actor_ref`, `reason` e `change_ticket` às funções de bootstrap/recovery,
  registrando a identidade do operador, justificativa e autorização no evento
  de auditoria do banco.
- Testes de integração executados e aprovados em PostgreSQL 16 descartável.

## 2. Arquivos criados/alterados

### 2.1 CLI

- `src/main/java/br/com/arena/cli/AdminCliApplication.java`
- `src/main/java/br/com/arena/cli/CliEnvironment.java`
- `src/main/java/br/com/arena/cli/CliRunner.java`
- `src/main/java/br/com/arena/cli/CliAbortException.java`
- `src/main/java/br/com/arena/cli/AdminLifecycleCliService.java`
- `src/main/java/br/com/arena/cli/input/CliInputReader.java`
- `src/main/java/br/com/arena/cli/input/ConsoleInputReader.java`
- `src/main/resources/application-cli.properties`

### 2.2 Banco

- `src/main/resources/db/migration/V3_1__admin_recovery_audit_context.sql`

### 2.3 Build

- `pom.xml`: configuração do Spring Boot Maven Plugin para gerar o JAR
  `target/arena-0.0.1-SNAPSHOT-cli.jar` com `Start-Class` apontando para
  `br.com.arena.cli.AdminCliApplication`.

### 2.4 Testes

- `src/test/java/br/com/arena/cli/StaticInputReader.java`
- `src/test/java/br/com/arena/cli/AdminCliSecurityTest.java`
- `src/test/java/br/com/arena/cli/AdminCliBootstrapIntegrationTest.java`
- `src/test/java/br/com/arena/cli/AdminCliRecoveryIntegrationTest.java`

### 2.5 Suporte

- `src/test/java/br/com/arena/testsupport/TestPostgresSettings.java`: tornados
  `public` o record e o método `from` para permitir uso pelos testes do CLI.
- `src/test/java/br/com/arena/testsupport/IsolatedPostgresIntegrationTest.java`:
  passou a apontar explicitamente para `ArenaApplication.class`, garantindo que
  testes de integração do CLI e da API compartilhem o mesmo contexto Spring/Flyway.
- `src/main/java/br/com/arena/ArenaApplication.java`: adicionado `@ComponentScan`
  com filtro regex para excluir o pacote `br.com.arena.cli.*`, evitando que a
  aplicação web carregue os beans do CLI.

## 3. Revisão de segurança da V3/V3.1

| Item | Avaliação |
|---|---|
| Funções SECURITY DEFINER com `search_path` explícito | **OK** — todas têm `SET search_path = public`. |
| Permissões EXECUTE limitadas | **OK** — `REVOKE FROM PUBLIC` e `GRANT` apenas para `arena_offline_operator`. |
| `arena_runtime` sem operações administrativas | **OK** — runtime não tem EXECUTE em bootstrap/recovery/reconcile. |
| `arena_offline_operator` com permissões mínimas | **OK** — apenas EXECUTE nas funções de bootstrap/recovery/reconcile. |
| `admin_audit_event` impede UPDATE/DELETE | **OK** — `REVOKE ALL ... FROM PUBLIC, arena_runtime`; offline_operator não tem DML direto. |
| Estados e invariantes respeitados | **OK** — validações cobrem PENDING, RECOVERY_REQUIRED, CONSUMED e fail closed. |
| Auditoria registra operador, justificativa e ticket | **OK** — V3.1 inclui `actor_ref`, `reason` e `change_ticket` no `admin_audit_event`. |

**Observação:** o documento 29 prevê um gate adicional de transições de perfil
no PostgreSQL (validação de `session_user` para impedir que `arena_runtime`
altere perfil ou delete usuário). Esse gate ainda **não está implementado na
V3/V3.1**; está previsto para a V4. A V3/V3.1 atual protege a contagem e o
último ADMIN através de triggers; a proteção de perfil por role de sessão será
entregue na próxima etapa.

## 4. Arquitetura

```text
Operador autorizado
        |
        v
java -jar arena-0.0.1-SNAPSHOT-cli.jar <bootstrap|recovery>
        |
        v
AdminCliApplication (WebApplicationType.NONE, profile=cli)
        |
        +---> CliEnvironment (OFFLINE_POSTGRES_*, OFFLINE_OPERATOR_ID)
        |
        +---> CliRunner
        |         |
        |         +---> CliInputReader (console sem eco; fallback para pipe)
        |         +---> BCryptPasswordEncoder
        |         +---> AdminLifecycleCliService
        |                   |
        |                   +---> DataSource arena_offline_operator
        |                   +---> SELECT public.h04_bootstrap_first_admin(...)
        |                   +---> SELECT public.h04_recover_admin(...)
        |
        +---> PostgreSQL V3/V3.1 (funções SECURITY DEFINER)
```

Princípios:

- Nenhuma regra de negócio duplicada em Java: validações de estado e
  transições permanecem nas funções SQL.
- Senha nunca trafega como argumento de linha de comando; lida via console sem
  eco ou, em pipe, sem eco também.
- Hash (nunca senha) é enviado à função SQL.
- Identidade do operador, justificativa e ticket vão direto para o evento de
  auditoria do banco, não apenas para o terminal.
- Nenhuma credencial versionada; tudo vem de variáveis de ambiente.

## 5. Comandos disponíveis

### 5.1 Bootstrap

```bash
export OFFLINE_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/arena
export OFFLINE_POSTGRES_USERNAME=arena_offline_operator
export OFFLINE_POSTGRES_PASSWORD=<senha_forte_16+_chars>
export OFFLINE_OPERATOR_ID=<identidade do operador>
export OFFLINE_OPERATOR_NAME=<nome legível do operador>

java -jar target/arena-0.0.1-SNAPSHOT-cli.jar bootstrap
```

Interação:

```text
H-04 bootstrap — first ADMIN creation
Operator: Nome do Operador (operador-id)
Nome: Admin Principal
E-mail: admin@exemplo.invalid
Telefone: 0000000000
Senha (mínimo 12 caracteres):
Confirme a senha:
Bootstrap concluído. ADMIN id=1
```

### 5.2 Recovery

```bash
java -jar target/arena-0.0.1-SNAPSHOT-cli.jar recovery
```

Interação:

```text
H-04 recovery — administrative recovery
Operator: Nome do Operador (operador-id)
Justificativa operacional: Perda do último ADMIN por desligamento
Ticket/autorização: TICKET-12345
Criar novo ADMIN (deixe ID vazio) ou promover usuário existente?
ID do usuário existente (ENTER para novo):
Nome: Admin Recuperado
E-mail: recuperado@exemplo.invalid
Telefone: 0000000000
Senha (mínimo 12 caracteres):
Confirme a senha:
Recovery concluído. ADMIN id=2
```

## 6. Variáveis de ambiente

| Variável | Obrigatória | Descrição |
|---|---|---|
| `OFFLINE_POSTGRES_URL` | Sim | JDBC URL do PostgreSQL (ex: `jdbc:postgresql://127.0.0.1:5432/arena`). |
| `OFFLINE_POSTGRES_USERNAME` | Sim | Deve ser `arena_offline_operator`. |
| `OFFLINE_POSTGRES_PASSWORD` | Sim | Mínimo 16 caracteres. |
| `OFFLINE_OPERATOR_ID` | Sim | Identidade verificável do operador. Não pode ser vazia. |
| `OFFLINE_OPERATOR_NAME` | Não | Nome legível do operador. Default igual a `OFFLINE_OPERATOR_ID`. |

Não há fallback para `SPRING_DATASOURCE_*`, `SPRING_FLYWAY_*` nem `.env`.

## 7. Validações locais

- Senha mínima de 12 caracteres.
- Confirmação de senha deve coincidir.
- E-mail deve conter `@` e domínio.
- Campos obrigatórios não podem ser vazios.
- Recovery exige justificativa e ticket/autorização.

## 8. Tratamento de erros

Códigos de saída:

| Código | Significado |
|---|---|
| 0 | Sucesso. |
| 2 | Erro de validação (dados inválidos, senha curta, etc.). |
| 3 | Erro de negócio (bootstrap/recovery rejeitado pelo banco). |
| 4 | Erro técnico (console indisponível, etc.). |
| 5 | V3/V3.1 não aplicada (funções não encontradas). |

Mensagens de erro não contêm senha, hash nem credenciais.

## 9. Segurança das credenciais

- Conexão exclusiva com `arena_offline_operator`.
- `arena_runtime` é rejeitado em `CliEnvironment`.
- Senha lida sem eco via `Console.readPassword()` em terminal real.
- Em pipe/CI, `ConsoleInputReader` faz fallback para leitura linha a linha sem
  vazar a senha em logs (o programa nunca a imprime).
- Apenas hash BCrypt sai da memória para a função SQL.
- Nenhuma senha em argv, log ou audit.
- `AdminCliApplication` exclui autoconfigurações de DataSource, JPA e Flyway
  (pacotes corretos do Spring Boot 4); não inicializa servidor web.

## 10. Migrations

### V3 (existente)

- Cria tabelas de estado e auditoria.
- Cria triggers e funções de bootstrap/recovery.
- Aplica permissões mínimas.

### V3.1 — incremental

- `src/main/resources/db/migration/V3_1__admin_recovery_audit_context.sql`
- Altera as assinaturas de `h04_bootstrap_first_admin` e `h04_recover_admin`
  para receber `actor_ref`, `reason` e `change_ticket`.
- Atualiza os eventos de auditoria inseridos por essas funções.
- Mantém `SECURITY DEFINER`, `search_path = public` e permissões.
- Não reescreve histórico Flyway; depende da V3 já aplicada.

## 11. Testes

Todos os testes abaixo foram executados em PostgreSQL 16 descartável
(container temporário, tmpfs, porta aleatória em 127.0.0.1, sem volume nomeado).

### 11.1 CLI

`AdminCliSecurityTest`:

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Cobertura:

- `CliEnvironment` rejeita `arena_runtime`.
- `CliEnvironment` rejeita senha curta.
- Senha em texto claro não aparece no output do CLI.
- `AdminCliApplication` exclui autoconfigurações DataSource/JPA/Flyway.

`AdminCliBootstrapIntegrationTest`:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

Cobertura:

- bootstrap bem-sucedido em PENDING;
- segundo bootstrap rejeitado;
- bootstrap rejeitado em RECOVERY_REQUIRED;
- senha curta rejeitada;
- senhas divergentes rejeitadas;
- auditoria registra `actor_ref` do operador.

`AdminCliRecoveryIntegrationTest`:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

Cobertura:

- recovery bem-sucedido em RECOVERY_REQUIRED;
- recovery rejeitado em PENDING;
- recovery rejeitado quando já existe ADMIN;
- recovery rejeitado em estado inconsistente;
- justificativa vazia rejeitada;
- auditoria registra `actor_ref`, `reason` e `change_ticket`.

### 11.2 Regressão V3

`AdminLifecycleStateIntegrationTest`:

```text
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
```

`AdminLifecycleStateFreshInstallIntegrationTest`:

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

`PostgresRolePrivilegesIntegrationTest`:

```text
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

`TestPostgresSettingsTest`:

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

Total da suíte executada nesta etapa: **48 testes, 0 falhas, 0 erros**.

### 11.3 Teste manual do JAR executável

Comandos executados com sucesso em PostgreSQL 16 descartável:

```bash
java -jar target/arena-0.0.1-SNAPSHOT-cli.jar bootstrap < input.txt
java -jar target/arena-0.0.1-SNAPSHOT-cli.jar recovery < input.txt
```

Resultado: bootstrap e recovery concluídos; auditoria persistiu no banco com
`actor_ref`, `reason` e `change_ticket` preenchidos.

## 12. Limitações conhecidas

- A limpeza de senha da memória é melhor esforço (`String` imutável em Java).
  Recomenda-se execução em ambiente controlado e sem swap/leitura de memória.
- O CLI não implementa reconciliação; essa função permanece disponível apenas
  via função SQL `h04_reconcile_admin_state` para operador autorizado.

## 13. Notas de operação

- Antes de executar o CLI, confirme que as migrations V3 e V3.1 foram aplicadas
  (a CLI verifica a existência das funções e retorna código 5 caso contrário).
- Execute o CLI em ambiente controlado, com acesso restrito ao operador
  autorizado.
- Para uso interativo, execute em terminal real para leitura oculta de senha.
  Para automação (CI/scripts), redirecione STDIN; a senha nunca será ecoada
  pelo programa, mas o terminal pode exibir caracteres digitados se houver TTY.
- Nenhum commit/push foi realizado. O banco `arena`, container
  `arenadeesportes-postgres-1` e volume `arena_postgres_data` permanecem
  intactos.
