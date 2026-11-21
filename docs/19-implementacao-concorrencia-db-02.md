# DB-02 — Etapa 4: concorrência, locks e integridade transacional

## 1. Diagnóstico inicial e preservação do Git

Antes de editar arquivos, `git status --short` mostrou alterações preexistentes não commitadas. Elas foram preservadas:

- **Rastreados já modificados:** `.gitignore`, `pom.xml`, `ArenaApplication.java`, `AgendamentoController.java`, `AgendamentoResponseDTO.java`, `StatusAgendamento.java`, `GlobalExceptionHandler.java`, `AgendamentoMapper.java`, `Agendamento.java`, `AgendamentoRepository.java`, `JwtAuthenticationFilter.java` e `AgendamentoService.java`.
- **Não rastreados já presentes:** `.env.example`, `docker-compose.yml`, `docs/` (incluindo as referências das etapas anteriores), DTOs de atualização/reatribuição, `ConflictException`, scheduler/serviço de expiração, `src/main/resources/` e os testes de controller/model.

Esses arquivos não foram restaurados, limpos ou sobrescritos. As alterações desta etapa se limitaram ao serviço/repositório de agendamentos, tratamento de exceções, dois testes preexistentes e novos componentes/testes/documentação listados na seção 7.

### Riscos de concorrência identificados

- POST fazia pré-consulta seguida de INSERT; a pré-consulta não protege o intervalo vazio entre SELECT e INSERT.
- PUT lia e mutava uma entidade sem lock. Confirmação, cancelamento, reatribuição e expiração individual também não serializavam modificações sobre a mesma linha.
- O scheduler atualizava pendências em lote sem bloquear/revalidar cada linha em relação a transições de usuário.
- `@Transactional` delimita a operação, mas não serializa escritores nem substitui a EXCLUDE.
- Não havia `@Version`. A constraint EXCLUDE parcial da V1 era a única proteção final contra sobreposição.

## 2. Estratégia de locks e relógio

### Linhas de agendamento

O repository agora usa `@Lock(PESSIMISTIC_WRITE)` nas leituras que antecedem escrita:

- confirmação, cancelamento e reatribuição bloqueiam a linha por ID antes de ler status;
- POST bloqueia as reservas bloqueadoras existentes da quadra/data;
- PUT bloqueia o agendamento-alvo e as reservas bloqueadoras do destino numa consulta ordenada por ID.

Depois da consulta de lock, o serviço decide usando as entidades obtidas nessa leitura bloqueante, sem carregar primeiro o alvo por uma leitura sem lock. O PUT mantém a identidade da entidade bloqueada e não atualiza proprietário ou `criadoEm`.

O conjunto de locks de POST/PUT usa uma ordem determinística crescente por ID. Confirmação, cancelamento e reatribuição bloqueiam somente uma linha. O scheduler não espera por linhas ocupadas: usa `FOR UPDATE SKIP LOCKED`, em lotes ordenados por ID. Isso evita o ciclo de espera entre job e requests. Não há locks de quadra nem tabela de slots.

### Disponibilidade

A EXCLUDE `ex_agendamento_quadra_periodo_bloqueante` permanece inalterada e é a autoridade para inserções/updates concorrentes, inclusive quando ainda não existe linha de agendamento para bloquear. Os locks dos registros existentes serializam transições relevantes e expiram pendências antigas do destino; eles **não** substituem nem enfraquecem a constraint.

A pré-consulta mantém PENDENTE/CONFIRMADO como bloqueadores e intervalos `[)`. A adjacência é permitida; o CHECK da V1 e a validação de serviço continuam proibindo atravessar meia-noite.

### Relógio PostgreSQL após o lock

`PostgresClock` consulta `SELECT clock_timestamp()` com `JdbcTemplate` dentro da transação. A chamada ocorre depois que todos os locks requeridos pela operação foram obtidos; a decisão compara esse instante com `criadoEm + 12h`. A confirmação/cancelamento vencidos atualizam para EXPIRADO, retornam um resultado de conflito e deixam a transação commitar. O serviço de borda converte o resultado em HTTP 409 **depois do commit**, sem `noRollbackFor`.

No scheduler, o CTE seleciona e bloqueia candidatos com `SKIP LOCKED`; o UPDATE externo revalida `status = 'PENDENTE'` e compara novamente `criado_em` com `clock_timestamp() - INTERVAL '12 hours'`. Cada lote de até 500 linhas tem sua própria transação.

## 3. Resultado das corridas de estado

As transições de aplicação sobre a mesma linha ficam serializadas:

- Se confirmação adquirir o lock antes do prazo, grava CONFIRMADO. O scheduler ignora o status depois do commit.
- Se a linha já estiver vencida quando a operação obtiver o lock, a operação grava EXPIRADO; uma tentativa de confirmação/cancelamento/reagendamento retorna 409 depois do commit.
- Se o scheduler obtiver o lock e expirar primeiro, a operação que aguarda lê o estado atualizado e não o reverte.
- Se cancelamento obtiver o lock enquanto PENDENTE ainda está no prazo, grava CANCELADO; o scheduler revalida o status e não expira a linha.
- CANCELADO e EXPIRADO não são reativados. Confirmação não é permitida para estados diferentes de PENDENTE.

O teste que segura o lock, inicia a confirmação em outra thread, observa uma sessão PostgreSQL esperando lock e só então avança `criado_em` antes de liberar a transação demonstrou que a decisão usa o prazo/estado após a espera.

## 4. Deadlocks, falhas transitórias e retries

- A ordem global para conjuntos de linhas de agendamento é crescente por ID; transições unitárias bloqueiam somente o alvo.
- O scheduler usa `SKIP LOCKED` e nunca espera por uma linha bloqueada por request.
- A constraint de faixa continua sendo o árbitro de POST/PUT concorrentes. SQLSTATE `23P01` só vira HTTP 409 quando o nome `ex_agendamento_quadra_periodo_bloqueante` também é reconhecido.
- SQLSTATE `40P01` (deadlock) e `40001` (falha de serialização) são mapeados para **503 Service Unavailable** com `Retry-After: 1`; não são mascarados como conflito de domínio.
- Não foi implementado retry automático. Não houve justificativa observada nos testes para reexecutar operações; retry genérico poderia duplicar efeitos e esconder a origem do erro.
- Nenhum deadlock ou erro de serialização ocorreu nos cenários concorrentes executados. O mapeamento transitório foi verificado no handler com causas SQLSTATE explícitas.

## 5. Testes concorrentes e evidência de conexões independentes

Os testes novos ficam em `AgendamentoConcurrencyIntegrationTest`, usam PostgreSQL 16 real e não usam H2 nem `Thread.sleep()`.

- Chamadas HTTP simultâneas usam `ExecutorService`, `CyclicBarrier` e a cadeia completa Spring Security/JWT.
- O teste de espera por lock executa a confirmação em thread distinta da transação que segura o lock. Uma conexão observadora consulta `pg_stat_activity` e só libera a transação após detectar `wait_event_type = 'Lock'` em outra sessão.
- Os INSERTs diretos concorrentes usam duas tarefas com `TransactionTemplate` e barreira antes do INSERT; cada transação mantém sua própria conexão enquanto aguarda a outra.
- Nenhum teste compartilha uma transação externa de teste. Fixtures são criadas antes das operações simultâneas e as chamadas concorrentes fazem commit/rollback em suas próprias transações.

| Cenário | Resultado |
|---|---|
| Confirmação bloqueada; deadline ultrapassa enquanto espera | HTTP 409; estado persistido EXPIRADO. |
| Confirmação antes, no limite e depois de 12 horas | Antes: CONFIRMADO; limite/depois: 409 e EXPIRADO. |
| Confirmação versus scheduler após o deadline | Final EXPIRADO, sem retorno a PENDENTE/CONFIRMADO. |
| Cancelamento versus scheduler após o deadline | 409 e final EXPIRADO; não há sobrescrita do estado. |
| Confirmação versus cancelamento | Operações são serializadas; resultado final CANCELADO; confirmação retorna sucesso somente se preceder o cancelamento. |
| Dois PUTs no mesmo ID | Ambos executam em ordem serial; o estado final corresponde integralmente a um PUT, mantendo dono, status e `criadoEm`. |
| Reatribuição ADMIN versus PUT | Ambas são serializadas; data/horas e novo proprietário permanecem no estado final. |
| PUTs contrapostos sobre o mesmo conjunto de reservas | Ambos recebem 409 sem deadlock e sem mutação parcial; as linhas mantêm os horários originais. |
| Dois POSTs HTTP com faixas sobrepostas | Um 201, um 409 e somente uma faixa bloqueadora persistida. |
| Dois INSERTs JDBC concorrentes na mesma faixa | Um commit; o outro recebe SQLSTATE `23P01` da EXCLUDE. |
| Intervalos adjacentes concorrentes | Ambos recebem 201. |
| `40P01`/`40001` | Handler devolve 503 com `Retry-After: 1`. |

## 6. Suíte completa

Executado `mvn test` apontando toda a suíte para `arena_flyway_test`, em PostgreSQL **16.15** descartável:

| Classe | Testes | Resultado |
|---|---:|---|
| `ArenaApplicationTests` | 1 | Passou |
| `AgendamentoConcurrencyIntegrationTest` | 12 | Passou |
| `AgendamentoControllerDb01Test` | 5 | Passou |
| `AgendamentoControllerSecurityTest` | 5 | Passou |
| `AgendamentoLifecycleIntegrationTest` | 9 | Passou |
| `AgendamentoCreatedAtIntegrationTest` | 5 | Passou |
| **Total** | **37** | **0 falhas, 0 erros; BUILD SUCCESS** |

As regressões mantêm SEG-01, DB-01, persistência de `criadoEm`, regras de ciclo de vida, estados, EXCLUDE e execução pela cadeia real do Spring Security.

## 7. Arquivos alterados nesta etapa

| Arquivo | Mudança |
|---|---|
| `src/main/java/br/com/arena/repository/AgendamentoRepository.java` | Locks pessimistas e consultas ordenadas; CTE do scheduler com `FOR UPDATE SKIP LOCKED`, revalidação de status e relógio atual. |
| `src/main/java/br/com/arena/service/AgendamentoWriteService.java` | Novo coordenador transacional de gravação; locks antes das decisões; revalidação de estado e expiração com horário DB pós-lock. |
| `src/main/java/br/com/arena/service/AgendamentoOperationResult.java` | Novo resultado interno para commitar expiração antes de converter a recusa em 409. |
| `src/main/java/br/com/arena/service/PostgresClock.java` | Nova consulta `clock_timestamp()` na conexão transacional atual. |
| `src/main/java/br/com/arena/service/AgendamentoService.java` | Fachada converte resultados pós-commit em conflitos HTTP e delega escritas ao serviço transacional. |
| `src/main/java/br/com/arena/exception/GlobalExceptionHandler.java` | `40P01`/`40001` identificados como falhas transitórias 503; mantém tratamento específico da EXCLUDE. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerDb01Test.java` | Adapta as verificações de controller ao coordenador transacional. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerSecurityTest.java` | Adapta mocks de lock e valida novamente SEG-01. |
| `src/test/java/br/com/arena/controller/AgendamentoConcurrencyIntegrationTest.java` | Novos testes concorrentes PostgreSQL/JWT, com threads e conexões independentes. |
| `docs/19-implementacao-concorrencia-db-02.md` | Este relatório. |

Não foram alterados `pom.xml`, V1, entidades, DTOs, controller, configuração de ambiente, `.env` ou dependências.

## 8. Ambiente e proteção do banco

- PostgreSQL 16.15 em contêiner temporário, porta aleatória ligada a `127.0.0.1`, `tmpfs` e sem volume nomeado. O contêiner de teste foi removido ao terminar; nenhum volume foi removido.
- V1 foi validada/aplicada somente no banco descartável da suíte.
- Verificação read-only posterior no container de desenvolvimento retornou `current_database() = arena`, nenhuma tabela no schema consultado e nenhuma `flyway_schema_history`. `arena_postgres_data` permanece presente; `arenadeesportes-postgres-1` continuou saudável.
- Não houve escrita, limpeza ou migration no banco `arena`; nenhum segredo ou token de teste foi registrado.

## 9. Limitações remanescentes

- O protocolo de lock depende de todos os escritores da aplicação seguirem as mesmas consultas bloqueantes. Escritores SQL externos podem ignorar os locks de aplicação; a EXCLUDE ainda os protege contra sobreposição, mas não coordena transições de status fora da aplicação.
- A verificação de `Quadra.ativa` não bloqueia uma alteração simultânea da própria quadra. A etapa não adicionou lock de quadra, pois isso serializaria todas as reservas do recurso e exigiria uma ordem global adicional; a EXCLUDE cobre a concorrência de intervalo, não a ativação/desativação.
- PUTs simultâneos são serializados, e a última operação na ordem dos locks prevalece. A API não possui `@Version`, ETag ou token de versão para rejeitar um PUT baseado em uma tela antiga; a proteção implementada impede gravação concorrente de entidade obsoleta dentro da transação, mas não detecta intenção desatualizada do cliente.
- O scheduler tem granularidade de aproximadamente um minuto; linhas vencidas podem permanecer PENDENTE por até esse intervalo quando não há operação de usuário que as expire antes.
- Nenhum retry automático é realizado para deadlock/serialização; o cliente recebe 503 e `Retry-After`.

Não foram observados deadlocks nos cenários testados. A integridade de sobreposição é protegida pela EXCLUDE; a serialização dos estados da aplicação foi exercitada nos testes concorrentes descritos acima.
