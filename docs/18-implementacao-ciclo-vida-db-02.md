# DB-02 — Etapa 3: ciclo de vida dos agendamentos

## 1. Resultado e escopo

Etapa 3 implementada e validada com PostgreSQL 16 descartável. Foram adicionadas expiração automática, confirmação por ADMIN, cancelamento lógico, regras de reagendamento e integração com a API. A execução completa de `mvn test` passou com **25 testes, 0 falhas e 0 erros**.

O banco de desenvolvimento `arena` não recebeu migration nem escrita. A V1 não foi alterada nem aplicada a esse banco. O teste usou um contêiner PostgreSQL descartável, com diretório de dados em `tmpfs` e sem volume nomeado.

Esta entrega **não conclui a etapa posterior de concorrência**. As transições e o processamento são transacionais/condicionais onde descrito abaixo, mas não foram adicionados locks de linha nem testes simultâneos.

## 2. Arquivos alterados

| Arquivo | Alteração nesta etapa |
|---|---|
| `src/main/java/br/com/arena/ArenaApplication.java` | Habilita `@EnableScheduling`. |
| `src/main/java/br/com/arena/service/AgendamentoExpirationScheduler.java` | Novo componente: execução imediata no startup e depois a cada 60 segundos. |
| `src/main/java/br/com/arena/service/AgendamentoExpirationService.java` | Novo serviço transacional: executa um lote de expiração por transação. |
| `src/main/java/br/com/arena/repository/AgendamentoRepository.java` | Updates SQL condicionais para expirar por ID, quadra/data e em lotes de até 500. |
| `src/main/java/br/com/arena/service/AgendamentoService.java` | Transações, expiração prévia relevante, validações de estado/permissão, confirmação, cancelamento lógico e reagendamento. |
| `src/main/java/br/com/arena/controller/AgendamentoController.java` | Adiciona PATCH de confirmação/cancelamento, documenta operações e remove DELETE físico. |
| `src/main/java/br/com/arena/exception/ConflictException.java` | Nova exceção de conflito de estado/horário. |
| `src/main/java/br/com/arena/exception/GlobalExceptionHandler.java` | Responde 409 a conflitos e somente à EXCLUDE identificada por SQLSTATE `23P01` e nome; erros de integridade distintos não são convertidos em 409. DELETE removido responde 405. |
| `src/main/java/br/com/arena/dto/agendamento/AgendamentoResponseDTO.java` | Expõe `criadoEm` como campo somente de resposta na documentação OpenAPI. |
| `src/main/java/br/com/arena/mapper/AgendamentoMapper.java` | Mapeia `criadoEm` nas respostas. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerDb01Test.java` | Atualiza a expectativa de conflito de horário de 400 para 409. |
| `src/test/java/br/com/arena/controller/AgendamentoLifecycleIntegrationTest.java` | Novos testes PostgreSQL e MockMvc com cadeia real de segurança/JWT. |

`StatusAgendamento.EXPIRADO`, `Agendamento.criadoEm`, DTOs de requisição, V1, `pom.xml` e configurações de ambiente são das etapas anteriores e não foram alterados nesta etapa.

## 3. Regras e operações

- Todo novo agendamento continua sendo criado como `PENDENTE`; status e `criadoEm` não são campos dos DTOs de criação/atualização. O proprietário do PUT é preservado. Campos extras `usuarioId`, `status` e `criadoEm` enviados pelo teste não alteraram esses valores.
- PENDENTE bloqueia até atingir 12 horas desde o `criado_em` gerado pelo PostgreSQL. No limite ou depois dele passa para `EXPIRADO`; a linha permanece e deixa de participar da constraint parcial de horários.
- CONFIRMADO continua bloqueando. A job não altera CONFIRMADO, CANCELADO ou EXPIRADO.
- A pré-consulta Java considera apenas PENDENTE/CONFIRMADO, usa intervalos semiabertos `[)` e mantém a validação `horaFim > horaInicio`.
- PENDENTE pode ser reagendado por proprietário ou ADMIN antes do prazo original; CONFIRMADO, apenas por ADMIN. CANCELADO/EXPIRADO não podem ser reagendados. PUT não altera nem reinicia `criadoEm`.
- Proprietário ou ADMIN cancela PENDENTE/CONFIRMADO; CANCELADO/EXPIRADO não podem ser cancelados novamente. O cancelamento atualiza o status, sem excluir a linha.
- Somente ADMIN confirma PENDENTE ainda dentro do prazo. Um PENDENTE vencido é marcado EXPIRADO e a confirmação retorna 409.
- A reatribuição continua sendo uma operação separada, protegida para ADMIN.

### Endpoints

| Método e rota | Comportamento |
|---|---|
| `POST /agendamentos` | Cria PENDENTE; a identidade de CLIENTE vem do principal autenticado. |
| `PUT /agendamentos/{id}` | Reagenda segundo status, proprietário/perfil e deadline original. |
| `PATCH /agendamentos/{id}/confirmacao` | Confirmação exclusiva de ADMIN; retorna o recurso atualizado. |
| `PATCH /agendamentos/{id}/cancelamento` | Cancelamento por proprietário ou ADMIN; preserva o registro. |
| `PATCH /agendamentos/{id}/proprietario` | Reatribuição administrativa preexistente, mantida. |
| `DELETE /agendamentos/{id}` | Removido; não há compatibilidade para exclusão física. |

Conflito de horário/estado retorna 409; entrada inválida permanece 400, falta de permissão 403 e recurso inexistente 404. A resposta de sucesso dos PATCHs é 200.

## 4. Expiração e relógio

O scheduler chama o serviço transacional imediatamente após o startup e em `fixedDelay` de 60.000 ms. Assim, após um período desligada, a aplicação processa o backlog pela idade persistida, sem depender de eventos em memória.

O repository seleciona somente PENDENTES com `criado_em <= statement_timestamp() - INTERVAL '12 hours'`, ordena por timestamp/ID e atualiza lotes de até 500. O scheduler repete até não haver linhas; cada lote é executado numa transação própria. O UPDATE revalida `status = 'PENDENTE'`, é idempotente e não carrega o histórico inteiro para a JVM.

Confirmação, cancelamento e PUT também verificam/efetivam a expiração do registro antes da transição. POST/PUT expiram PENDENTES vencidos da quadra/data de destino antes da checagem de disponibilidade, evitando que um ciclo ainda não executado mantenha a faixa ocupada. A decisão temporal usa SQL e o relógio PostgreSQL (`statement_timestamp()`), não o relógio JVM.

Quando uma operação precisa gravar `EXPIRADO` e ser recusada, ela lança `ConflictException` configurada como `noRollbackFor`; a transição é commitada e então o handler produz 409.

## 5. Segurança, erros e documentação da API

- `@PreAuthorize` restringe confirmação e reatribuição a ADMIN; o serviço também valida o perfil da confirmação.
- Cancelamento e edição verificam o principal no backend. CLIENTE não pode agir sobre reserva de terceiro; CLIENTE não edita CONFIRMADO.
- DTOs continuam sem status/criadoEm editáveis e o PUT não possui `usuarioId`.
- `criadoEm` está presente nas respostas e marcado read-only no schema OpenAPI. As descrições OpenAPI do PUT e dos PATCHs registram permissões, prazo e conflitos.
- A violação PostgreSQL só é traduzida para 409 se forem reconhecidos simultaneamente SQLSTATE `23P01` e a constraint `ex_agendamento_quadra_periodo_bloqueante`. Teste PostgreSQL confirmou que erro de FK não recebe o mesmo mapeamento.

## 6. Testes executados

`mvn test` — **BUILD SUCCESS; 25 testes, 0 falhas, 0 erros**.

| Classe | Testes | Resultado |
|---|---:|---|
| `ArenaApplicationTests` | 1 | Passou |
| `AgendamentoControllerDb01Test` | 5 | Passou; regressão DB-01 |
| `AgendamentoControllerSecurityTest` | 5 | Passou; regressão SEG-01 |
| `AgendamentoLifecycleIntegrationTest` | 9 | Passou em PostgreSQL real e cadeia Spring Security |
| `AgendamentoCreatedAtIntegrationTest` | 5 | Passou; regressão de mapeamento/constraint |

Os testes novos cobrem:

- Antes, exatamente no limite e depois de 12 horas; status não elegíveis; idempotência; backlog representando indisponibilidade; e lote com 501 linhas.
- Confirmação ADMIN, 403 para CLIENTE, estados incompatíveis e expiração efetivamente gravada apesar do 409.
- Cancelamento de PENDENTE/CONFIRMADO por proprietário/ADMIN, recusa de terceiro/estados terminais e retenção da linha.
- Permissões de PUT por status/perfil, terminalidade, proprietário/timestamp imutáveis, deadline não reiniciado e campos de controle ignorados.
- Estados bloqueadores, adjacência, liberação após CANCELADO/EXPIRADO, remoção de DELETE e mapeamento específico de `23P01`.
- JWT e cadeia real do Spring Security via `@SpringBootTest` + `@AutoConfigureMockMvc`, além dos testes standalone preexistentes.

O teste de backlog cria timestamps antigos no PostgreSQL descartável e invoca o mesmo scheduler da aplicação; não aguarda 12 horas nem reinicia um processo real.

## 7. Ambiente e preservação do banco

- PostgreSQL **16.15** em contêiner descartável, database `arena_flyway_test`, porta loopback aleatória e `tmpfs`; sem volume nomeado. O contêiner foi removido ao fim dos testes; nenhum volume Docker foi removido.
- Segredos e tokens de teste foram temporários e não estão registrados neste documento.
- Verificação read-only posterior no container de desenvolvimento: `current_database() = arena`, nenhuma tabela no schema consultado e nenhuma `flyway_schema_history`. `arena_postgres_data` continua presente e o container de desenvolvimento permaneceu saudável.
- V1 foi validada/aplicada apenas no banco descartável para a suíte. Não houve migration, limpeza ou escrita no banco `arena`.

## 8. Concorrência ainda pendente

Não foram implementados locks pessimistas, compare-and-set completo para todas as transições, retries ou testes simultâneos com conexões independentes. A consulta prévia de disponibilidade e `@Transactional` **não** impedem, sozinhos, duas operações concorrentes de disputar estado. A EXCLUDE existente continua sendo a autoridade final para sobreposição; uma corrida de INSERT/UPDATE ainda depende da constraint e da tradução específica `23P01`.

Há também uma janela possível entre a verificação do prazo e a gravação de confirmação/cancelamento/PUT: sem lock da linha e nova leitura do relógio após adquirir esse lock, operações concorrentes próximas do deadline podem disputar a transição. Esta etapa não afirma resolver integralmente a corrida scheduler–confirmação/cancelamento. `statement_timestamp()` é usado no SQL desta etapa; a etapa de concorrência deverá decidir o protocolo de lock e consultar o horário do banco depois de obter o lock, sem reutilizar o timestamp de início da transação.

## 9. Próxima etapa recomendada

Implementar a etapa específica de concorrência: locks/atualizações condicionais para serializar transições no mesmo agendamento, decisão do relógio PostgreSQL após lock, testes concorrentes de confirmação–expiração–cancelamento e de criação/PUT em conexões independentes, validando 409 da EXCLUDE. Manter todos esses testes em PostgreSQL descartável; não aplicar V1 ao banco `arena` sem autorização operacional separada.
