# Etapa 5 — concorrência na ativação/desativação de quadras

## 1. Diagnóstico inicial e preservação do Git

Antes de editar arquivos, `git status --short` registrou alterações preexistentes não commitadas, incluindo configurações/Flyway, segurança, entidades/serviços de agendamento, DTOs e testes das etapas anteriores. Elas foram preservadas; não houve reset, limpeza ou substituição do trabalho anterior. O snapshot inicial completo consta no histórico da tarefa.

No snapshot, já apareciam como modificados `.gitignore`, `pom.xml`, `ArenaApplication.java`, `AgendamentoController.java`, `AgendamentoResponseDTO.java`, `StatusAgendamento.java`, `GlobalExceptionHandler.java`, `AgendamentoMapper.java`, `Agendamento.java`, `AgendamentoRepository.java`, `JwtAuthenticationFilter.java` e `AgendamentoService.java`. Já apareciam como não rastreados `.env.example`, `docker-compose.yml`, `docs/`, os DTOs de atualização/reatribuição, `ConflictException`, scheduler/serviço de expiração, `src/main/resources/` e testes de controller/model.

### Fluxos antes desta etapa

- `POST /quadras` (ADMIN) criava quadra a partir do DTO, que contém `ativa`.
- `PUT /quadras/{id}` (ADMIN) carregava a quadra sem lock e podia alterar `ativa`, nome e tipo.
- `PATCH /quadras/{id}/status?ativa=...` (ADMIN) carregava a quadra sem lock e alterava `ativa`.
- `DELETE /quadras/{id}` (ADMIN) verificava agendamentos e excluía fisicamente a quadra, sem lock transacional.
- POST/PUT de agendamento verificavam `Quadra.ativa` com uma leitura comum. A Etapa 4 já bloqueava os registros de agendamento e preservava a EXCLUDE, mas a leitura de `ativa` ainda podia ficar obsoleta em relação a PATCH/PUT concorrente da quadra.

## 2. Protocolo de lock e ordem global

`QuadraRepository` agora oferece busca por ID com `PESSIMISTIC_WRITE`. `QuadraService.buscarParaAtualizacao` usa essa busca dentro da transação.

### Ordem adotada

1. Escritas que dependem da disponibilidade da quadra obtêm primeiro o lock da **quadra destino**.
2. Depois do lock, leem `ativa` da entidade obtida pela consulta bloqueante.
3. Somente se ativa, POST/PUT adquirem locks de agendamento. Quando há várias linhas, a consulta alvo + bloqueantes ordena por ID, preservando a ordem da Etapa 4.

PUT bloqueia a quadra de destino, não a quadra de origem: a regra de atividade só se aplica ao recurso que receberá o agendamento. Não é necessário bloquear quadras distintas na mesma operação.

PUT/PATCH de quadra e DELETE administrativo bloqueiam a própria quadra antes da alteração/verificação. Essas operações não adquirem locks de agendamento. Confirmação, cancelamento e reatribuição da Etapa 4 bloqueiam agendamento(s), mas não solicitam quadra depois. O scheduler continua usando `SKIP LOCKED`, portanto não espera por essas linhas.

O protocolo global é **Quadra → Agendamento(s) em ordem crescente de ID**. Fluxos que usam somente um tipo de linha não adquirem o outro tipo depois. Isso evita a inversão Quadra/Agendamento entre reserva, edição e desativação. O lock na quadra serializa reservas da mesma quadra, mesmo quando ainda não há agendamento; a EXCLUDE continua obrigatória para escritores que não seguem esse protocolo e para a integridade final de intervalos.

## 3. Comportamento implementado

- POST e PUT de agendamento chamam `buscarParaAtualizacao` no destino e só então validam `ativa`.
- PUT/PATCH de quadra e DELETE também operam sob transação e lock pessimista.
- Desativar apenas altera `ativa`; não exclui a quadra nem cancela, expira ou altera o status de agendamentos existentes.
- Reativar usa o mesmo lock antes de permitir novas reservas.
- Uma tentativa de reservar/reagendar para quadra inativa continua retornando **HTTP 400**, mantendo a convenção existente de `BusinessException` para esta validação.
- O DELETE físico de quadra permanece operação administrativa separada. Sob o lock da quadra, a verificação de filhos e a exclusão não correm junto com POST/PUT que usem o protocolo.
- V1, EXCLUDE, intervalo semiaberto e regras de status não foram alterados.

### Serialização das corridas

- Se POST/PUT obtém o lock da quadra primeiro, valida a quadra ativa e conclui sua transação; a desativação aguarda e ocorre depois. O agendamento aceito permanece registrado.
- Se a desativação obtém o lock primeiro, POST/PUT aguarda; depois do commit, a consulta bloqueante lê `ativa = false` e recusa a operação com 400.
- Reativação e reservas seguem a mesma ordem.
- Em PUT entre quadras, somente a quadra destino é bloqueada antes dos agendamentos. Updates que envolvem o mesmo conjunto de reservas ainda ordenam os locks de Agendamento por ID; teste contraposto não observou deadlock.

## 4. Testes PostgreSQL

Os testes novos foram adicionados a `AgendamentoConcurrencyIntegrationTest`. Usaram PostgreSQL 16 real e descartável, `@SpringBootTest`, MockMvc com Spring Security/JWT e conexões independentes:

- Corridas HTTP usam `ExecutorService` e `CyclicBarrier`.
- Os testes determinísticos de espera seguram a quadra numa transação, iniciam o POST/PUT em outra thread e detectam `wait_event_type = 'Lock'` por uma conexão observadora em `pg_stat_activity`. A quadra é então marcada inativa antes de liberar o lock; a requisição bloqueada retorna 400 e não cria/move a reserva.
- Não há `Thread.sleep()`. Os casos mantêm os locks/transações independentes e não compartilham uma transação externa de teste.

| Cenário | Resultado |
|---|---|
| POST em quadra ativa/inativa | 201 em ativa; 400 em inativa. |
| PUT para quadra ativa/inativa | 200 em ativa; 400 em inativa, sem alteração parcial. |
| Desativar por PATCH e por PUT de quadra | A quadra permanece no banco; PENDENTE/CONFIRMADO e suas contagens permanecem inalterados. |
| Reativar e reservar | A reativação permite novo POST conforme as demais regras. |
| POST simultâneo à desativação | Ordem serial observada; ou POST 201 antes da desativação, ou POST 400 depois dela. Estado final sempre inativo e sem reserva aceita após a desativação. |
| POST bloqueado enquanto a quadra é desativada | Ao obter o lock, lê `ativa = false`, retorna 400 e nenhuma linha é criada. |
| PUT bloqueado enquanto o destino é desativado | Retorna 400 e mantém quadra/data/horas originais. |
| PUT concorrente entre duas quadras | Ambos recebem 409 por faixa ocupada, sem deadlock nem mutação parcial. |

## 5. Suíte completa

Executado `mvn test` em banco PostgreSQL **16.15** descartável:

| Classe | Testes | Resultado |
|---|---:|---|
| `ArenaApplicationTests` | 1 | Passou |
| `AgendamentoConcurrencyIntegrationTest` | 24 | Passou |
| `AgendamentoControllerDb01Test` | 5 | Passou |
| `AgendamentoControllerSecurityTest` | 5 | Passou |
| `AgendamentoLifecycleIntegrationTest` | 9 | Passou |
| `AgendamentoCreatedAtIntegrationTest` | 5 | Passou |
| **Total** | **49** | **0 falhas, 0 erros; BUILD SUCCESS** |

Foram executadas as regressões SEG-01, DB-01, DB-02 Etapas 1–4, além dos testes de quadra desta etapa. Nenhum deadlock foi observado.

## 6. Arquivos alterados nesta etapa

| Arquivo | Alteração |
|---|---|
| `src/main/java/br/com/arena/repository/QuadraRepository.java` | Busca `PESSIMISTIC_WRITE` por ID. |
| `src/main/java/br/com/arena/service/QuadraService.java` | Busca bloqueante; transações e locks para PUT, PATCH de status e DELETE. |
| `src/main/java/br/com/arena/service/AgendamentoWriteService.java` | POST/PUT bloqueiam e validam a quadra destino antes dos locks de agendamento. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerDb01Test.java` | Adapta stubs à leitura bloqueante da quadra, preservando os testes DB-01. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerSecurityTest.java` | Adapta stubs à leitura bloqueante, preservando SEG-01. |
| `src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java` | Testes de ativação/desativação e suas corridas em PostgreSQL. |
| `docs/20-concorrencia-quadras-e-decisoes-arquiteturais.md` | Este relatório e decisões operacionais. |

Não foram alterados `QuadraController`, V1, dependências, DTOs, `.env`, propriedades de datasource ou frontend.

## 7. Decisões arquiteturais sobre banco e administração

Decisões registradas para a arquitetura/implantação:

- No funcionamento normal, **Spring Boot é o único escritor** do banco. Funcionários, gerentes e proprietários usam a API e não recebem credenciais PostgreSQL.
- O `Perfil.ADMIN` da aplicação autoriza chamadas da API; não é uma conta administrativa nem concede papel administrativo no PostgreSQL.
- O primeiro ADMIN deve ser provisionado por bootstrap seguro, controlado e auditável. O cadastro público atual força `CLIENTE`; não foi criado um bootstrap nesta etapa.
- Após existir um ADMIN confiável, a gestão de perfis administrativos ocorre pela API protegida (`PUT /usuarios/{id}`, restrita a ADMIN). A aplicação deve evitar expor esse fluxo a usuários comuns.
- O usuário técnico de runtime deve ter privilégio mínimo: conexão, uso do schema, DML necessário nas tabelas e uso das sequences; sem superuser, criação de database, DDL ou gestão de extensões.
- O usuário Flyway deve ser distinto e provisionado para migrations/DDL, incluindo os privilégios de extensão requeridos no ambiente alvo. A separação deve ocorrer por processo/pipeline de migração e credenciais geridas em cofre; não por compartilhamento da senha de runtime.

Essas decisões são planejamento. Esta etapa **não** criou usuários/roles PostgreSQL, não separou datasources/credenciais e não alterou configuração ou `.env`.

## 8. Melhorias futuras e limitações

- Adicionar controle otimista (`@Version` ou ETag) para detectar PUTs de clientes baseados em uma versão antiga; definir a política de conflito/atualização da interface antes de mudar o contrato.
- Implementar a separação operacional entre credenciais runtime e Flyway, com privilégios mínimos, bootstrap seguro do primeiro ADMIN e procedimento de rotação.
- Todos os escritores da aplicação devem seguir o lock da quadra antes dos locks de agendamento. Escritores SQL externos não participam do protocolo; a EXCLUDE continua protegendo apenas a sobreposição de faixas.
- A consistência é garantida para as rotas de aplicação testadas. A granularidade do scheduler de expiração continua aproximadamente um minuto, conforme a etapa anterior.

## 9. Preservação do banco de desenvolvimento

- A suíte usou contêiner PostgreSQL 16 descartável, porta aleatória em `127.0.0.1`, armazenamento `tmpfs` e nenhum volume nomeado.
- V1 foi aplicada somente nesse banco descartável.
- Consulta read-only posterior ao container de desenvolvimento confirmou `current_database() = arena`, nenhum objeto no schema consultado e zero `flyway_schema_history`.
- `arena_postgres_data` continua presente e o container `arenadeesportes-postgres-1` permaneceu saudável. Não houve escrita, limpeza ou migration em `arena`; nenhum volume foi removido.
- Nenhuma credencial, chave JWT ou token foi incluído neste relatório.
