# DB-02 — Regras aprovadas e plano de implementação

**Status:** planejamento técnico para aprovação. DB-02 permanece pendente. Nenhum código, configuração, banco ou contêiner foi alterado nesta etapa.

## 1. Decisões de negócio consolidadas

As regras abaixo são tratadas como aprovadas:

| Estado/regra | Efeito sobre disponibilidade |
|---|---|
| Novo agendamento | Inicia em `PENDENTE`. |
| `PENDENTE` | Bloqueia por 12 horas desde a criação; depois deve transicionar para `EXPIRADO`. |
| `CONFIRMADO` | Continua bloqueando o intervalo. |
| `CANCELADO` | Mantém o registro e libera o intervalo. |
| `EXPIRADO` | Mantém o registro e libera o intervalo. |
| Intervalos adjacentes | Permitidos; `[09:00,10:00)` e `[10:00,11:00)` não conflitam. |
| Virada de dia | Não permitida. |
| Histórico | O agendamento não deve ser removido fisicamente ao cancelar/expirar. |

Algumas decisões de implementação ainda são necessárias, mas não alteram essas regras:

- Quem pode confirmar uma reserva (a API atual não tem operação de confirmação).
- Se o cancelamento manual também pode partir de `CONFIRMADO`, e se `EXPIRADO` pode ser cancelado.
- Como tratar reservas `PENDENTE` preexistentes sem timestamp de criação.
- Se “preservar histórico” significa conservar o registro atual ou também auditar cada transição de status.
- Qual tolerância operacional é aceitável entre completar 12 horas e a rotina agendada efetivar `EXPIRADO`.

Recomendação para essas decisões auxiliares: confirmação apenas antes do limite; `CANCELADO` e `EXPIRADO` terminais; cancelamento autorizado para proprietário/ADMIN em `PENDENTE` e `CONFIRMADO`; histórico de transições separado somente se for requisito de auditoria. A origem da confirmação e a política de registros antigos precisam de aprovação explícita.

## 2. Diagnóstico do código atual

### O que existe

- `Agendamento` (`src/main/java/br/com/arena/model/Agendamento.java:18–41`) tem data, horas, status, usuário e quadra. **Não tem** timestamp de criação ou expiração.
- `StatusAgendamento` (`src/main/java/br/com/arena/enumerate/StatusAgendamento.java:3–7`) contém somente `PENDENTE`, `CONFIRMADO` e `CANCELADO`; `EXPIRADO` não existe.
- `AgendamentoService.salvar` define `PENDENTE`, consulta os agendamentos da quadra/data e salva (`AgendamentoService.java:63–108`).
- `AgendamentoService.atualizar` aplica DB-01, consulta conflitos ignorando o próprio ID e salva (`AgendamentoService.java:111–150`).
- `AgendamentoRepository` tem apenas `findByQuadraAndData` (`AgendamentoRepository.java:11–16`).
- `AgendamentoService.deletar` remove a linha (`AgendamentoService.java:170–176`). Não há serviço de confirmação, cancelamento por estado ou expiração automática.
- Não foram encontrados `@Scheduled`, `@EnableScheduling`, campos de criação nem migrations no código. `ArenaApplication` é apenas o bootstrap Spring (`ArenaApplication.java:6–11`).
- Não há `@Transactional` nos serviços. O `pom.xml` não declara ferramenta de migration nem Testcontainers.

### Efeito da ausência de timestamp

Sem `criadoEm`/`created_at`, não é possível determinar com exatidão a idade das reservas `PENDENTE` existentes. A migration não deve inventar essa idade. Deve haver reconciliação dos registros existentes; opções estão em “Riscos de migração”.

## 3. Estratégia de expiração automática

### Timestamp e cálculo das 12 horas

Adicionar um timestamp imutável de criação, recomendado como `Instant` na entidade e `TIMESTAMPTZ` no PostgreSQL (`criado_em`). Para evitar divergência de relógio entre instâncias, recomenda-se que a criação venha do relógio do banco (por exemplo, default `statement_timestamp()`) e que a rotina compare usando o mesmo relógio:

```sql
criado_em <= statement_timestamp() - INTERVAL '12 hours'
```

O ORM precisa obter o valor gerado no INSERT (`@Generated`/refresh ou mecanismo equivalente); não se deve combinar um default do banco com um valor local concorrente como fonte de verdade. Alternativamente, um `Clock` UTC injetado na aplicação é testável, mas requer sincronização de relógios entre instâncias. A estratégia do banco é preferida para múltiplas instâncias.

Não é necessário persistir também `expira_em`: o deadline é `criado_em + 12 horas`. Se consumidores precisarem exibi-lo, pode ser derivado no DTO como `expiraEm`; isso é uma decisão de contrato/API.

### Rotina Spring

Não existe agendamento Spring atualmente. A implementação precisará habilitar scheduling (`@EnableScheduling`) e adicionar um componente dedicado para a expiração, com operação transacional e idempotente.

Recomendação inicial:

- Executar imediatamente no startup e depois em intervalo curto e configurável, por exemplo, a cada 1 minuto (`fixedDelay`). O atraso máximo de efetivação do status será aproximadamente o intervalo mais o tempo de processamento.
- Atualizar somente linhas ainda `PENDENTE` cujo `criado_em` já atingiu o cutoff. Uma atualização condicional é essencial; não carregar status, processar em memória e sobrescrever sem revalidar.
- Para o volume inicial, uma atualização SQL em lote pode bastar; adicionar processamento em lotes com `FOR UPDATE SKIP LOCKED` se volume/tempo de lock justificar.
- Criar índice para a varredura, por exemplo, B-tree parcial sobre `criado_em WHERE status = 'PENDENTE'`.
- Se a aplicação ficar desligada, a primeira execução após reiniciar seleciona todos os pendentes vencidos pelo cutoff do banco. A job não depende de ter rodado exatamente no instante do vencimento.

Um `@Scheduled` roda em cada instância. Isso não exige por si só uma dependência de lock distribuído: uma operação SQL condicional (`status = 'PENDENTE'` e cutoff) é idempotente, e o PostgreSQL serializa atualizações concorrentes na mesma linha. Se houver processamento em lotes, `SKIP LOCKED` permite que instâncias dividam linhas sem duplicar trabalho. Lock distribuído só deve ser adicionado se houver requisito operacional que a atualização condicional não resolva.

### Confirmação concorrente com expiração

Confirmação ainda não existe e deve ser um caso de uso transacional. Um desenho seguro é:

1. Bloquear a linha do agendamento (`SELECT ... FOR UPDATE`/lock pessimista do repositório).
2. Ler o estado e comparar `criado_em + 12h` com o relógio do banco **depois de obter o lock**.
3. Se ainda estiver dentro do prazo e o estado continuar `PENDENTE`, mudar para `CONFIRMADO`.
4. Se o limite foi atingido, mudar para `EXPIRADO` e devolver resultado de prazo encerrado.
5. A job também atualiza apenas registros ainda `PENDENTE` e vencidos.

Assim, se a confirmação obtiver o lock primeiro e for válida, a job reavalia o estado e não sobrescreve `CONFIRMADO`. Se a expiração obtiver o lock primeiro, a confirmação encontra `EXPIRADO` e não o reverte. No limite exato, definir o deadline como exclusivo para confirmação: confirmação válida se `agora < criadoEm + 12h`; expiração elegível se `agora >= criadoEm + 12h`.

Se o caminho de confirmação lançar uma exceção de runtime depois de definir `EXPIRADO` na mesma transação, a transação pode fazer rollback e desfazer a expiração. O serviço deve retornar um resultado que permita commit da mudança de estado e então responder 409, ou separar cuidadosamente as transações; não lançar uma exceção que reverta a transição.

## 4. Status, cancelamento e histórico

### Estados que entram em conflito

A futura pré-consulta da aplicação e a constraint PostgreSQL devem usar a mesma regra:

- Bloqueiam: `PENDENTE`, `CONFIRMADO`.
- Liberam: `CANCELADO`, `EXPIRADO`.

Adicionar `EXPIRADO` em `StatusAgendamento`. Atualizar uso do enum no serviço, consulta do repositório, resposta DTO/OpenAPI e testes. A entidade já persiste enum como `STRING`; verificar o tipo/constraints reais da coluna antes da migration. Se houver `CHECK` no banco que limite os nomes, ele também precisará ser atualizado.

### Cancelamento sem apagar

O endpoint atual `DELETE /agendamentos/{id}` chama `delete` e elimina o registro. Para representar uma transição preservando o recurso/histórico, recomenda-se um endpoint explícito `PATCH /agendamentos/{id}/cancelamento` (ou `PATCH /{id}/status` com operação controlada), que não aceite status arbitrário no corpo. A resposta pode retornar o agendamento atualizado com `CANCELADO`.

O novo caso de uso deve chamar a validação de propriedade existente: proprietário ou ADMIN, sem alterar SEG-01. É preferível preservar `DELETE` apenas como rota legada/depreciada ou descontinuá-la em uma versão documentada; não mudar silenciosamente sua semântica sem avaliar clientes existentes.

Quando `CANCELADO` for gravado, a linha permanece no banco e sai da exclusion constraint parcial no commit. `EXPIRADO` tem o mesmo efeito de liberação. Se “histórico preservado” também exigir histórico de cada transição, o campo `status` atual não basta: será necessária uma tabela de histórico (agendamento, status anterior/novo, instante e ator), decisão que deve ser confirmada antes de implementação.

## 5. Constraint PostgreSQL para DB-02

### Regra recomendada

Com os status aprovados, a exclusion constraint deve incluir apenas `PENDENTE` e `CONFIRMADO`; `CANCELADO` e `EXPIRADO` não participam. Intervalos semiabertos mantêm reservas consecutivas permitidas:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE agendamento
    ADD CONSTRAINT ex_agendamento_quadra_periodo_bloqueante
    EXCLUDE USING gist (
        quadra_id WITH =,
        (tsrange(data + hora_inicio, data + hora_fim, '[)')) WITH &&
    )
    WHERE (status IN ('PENDENTE', 'CONFIRMADO'));
```

Este é um **exemplo de desenho**, não uma migration pronta ou executada. Confirmar tabela, nomes físicos, tipos, schema e disponibilidade/privilégio de `btree_gist` no PostgreSQL de destino.

`date + time` produz timestamp local sem fuso, compatível com o modelo atual de uma arena em fuso único. A regra Java continua proibindo `horaFim <= horaInicio`, portanto não há intervalo atravessando meia-noite. Se o produto vier a operar em vários fusos ou precisar distinguir o horário repetido de uma transição DST, será necessário rever `LocalDate`/`LocalTime` e avaliar `tstzrange`; não presumir isso agora.

### Interação com expiração e cancelamento

Predicados de índices/constraints não podem depender de `now()` para remover uma linha automaticamente ao completar 12 horas. Além de não representar uma transição de estado, o predicado seria dependente do tempo e não serve como condição dinâmica de uma exclusion index. A liberação ocorre quando uma transação altera `PENDENTE` para `EXPIRADO` ou para `CANCELADO`; no commit, a linha deixa o predicado parcial.

Enquanto a job ainda não atualizou um `PENDENTE` vencido, ele permanece na constraint. Para não reter o intervalo até o próximo ciclo do scheduler, criação/atualização deve expirar pendentes vencidos do par quadra/data (ou do conjunto de candidatos) dentro da mesma transação antes da pré-consulta/gravação. A job global continua necessária para efetivar status sem tráfego na API. Ambas as operações usam condição `status = 'PENDENTE'` e cutoff de 12h.

A constraint é a autoridade final para criação e update concorrentes. O serviço ainda faz pré-consulta para resposta antecipada. A corrida entre pré-consulta e gravação resulta em violação PostgreSQL `23P01`; somente essa constraint deve ser traduzida para **409 Conflict**, sem converter indiscriminadamente erros de FK/NOT NULL em 409.

## 6. Fronteiras transacionais

| Caso de uso | Fronteira necessária |
|---|---|
| Criar | `@Transactional`: obter/validar quadra, expirar pendentes vencidos relevantes, verificar conflitos e inserir. A exclusion constraint cobre a concorrência que escapar da pré-consulta. |
| Atualizar | `@Transactional`: carregar a reserva, validar proprietário/ADMIN (SEG-01), validar quadra ativa, expirar pendentes vencidos no destino, consultar excluindo o próprio ID e atualizar. A constraint também valida a troca de quadra/data/horário. |
| Confirmar | `@Transactional`: bloquear linha, consultar estado e hora atual do banco após o lock, permitir somente `PENDENTE` antes do limite e mudar para `CONFIRMADO`. |
| Cancelar | `@Transactional`: bloquear/atualizar condicionalmente apenas estados canceláveis, preservar linha, validar proprietário/ADMIN e mudar para `CANCELADO`. |
| Expirar | `@Transactional` por execução/lote: atualização condicional de pendentes vencidos; não sobrescrever `CONFIRMADO` ou `CANCELADO`. Em volume maior, limitar lote e usar `SKIP LOCKED`. |

`@Transactional` sozinho não evita sobreposição entre linhas distintas. A constraint é necessária para DB-02. As transições de estado precisam ser condicionais/serializadas para que expiração não sobrescreva confirmação ou cancelamento.

## 7. Arquivos e componentes afetados futuramente

Nada abaixo foi modificado nesta análise.

| Área/arquivo provável | Alteração prevista |
|---|---|
| `model/Agendamento.java` | Campo imutável `criadoEm` (`Instant`) mapeado para timestamp com fuso. |
| `enumerate/StatusAgendamento.java` | Acrescentar `EXPIRADO`. |
| `repository/AgendamentoRepository.java` | Consulta de sobreposição por intervalo/status e ID excluído; consulta/update atômico de pendentes vencidos; lock de linha para confirmação/cancelamento conforme desenho. |
| `service/AgendamentoService.java` | Transações; pré-expiração do alvo; filtragem pelos estados bloqueadores; confirmação/cancelamento; tradução controlada de violação concorrente. Preservar proprietário e validações DB-01. |
| `controller/AgendamentoController.java` | Endpoints explícitos de confirmar/cancelar, se aprovados; manter ADMIN restrito onde definido e autorização por proprietário em ações do cliente. |
| DTOs de agendamento | Expor status atualizado; opcionalmente `criadoEm`/`expiraEm` na resposta; não permitir que o cliente escolha `EXPIRADO` ou `CONFIRMADO` por DTO genérico. |
| Componente/configuração de scheduling | Habilitar `@EnableScheduling`, configurar frequência e executar serviço de expiração; `ArenaApplication` hoje não habilita scheduling. |
| `GlobalExceptionHandler` e exceções | Traduzir apenas a constraint de sobreposição/estado incompatível para 409 com resposta estável. |
| `src/main/resources/db/migration/` | Migrations versionadas para timestamp, índice de vencimento, extension/constraint e possíveis CHECKs. Diretório/ferramenta não existem hoje. |
| `pom.xml` | Nenhuma dependência necessária para `@Scheduled` além do contexto Spring já presente. Uma ferramenta de migration (ex.: Flyway) e Testcontainers para testes reais exigiriam dependências ainda não declaradas e aprovação/planejamento. |
| Swagger/README e docs | Documentar prazo, status, operações de confirmação/cancelamento, 409 e política de estados. |

## 8. Plano de implementação por etapas

1. **Fechar contrato de status e confirmação:** definir ator autorizado a confirmar, estados dos quais se pode cancelar e se o histórico precisa de tabela de transições.
2. **Preparar schema versionado:** escolher ferramenta de migration; inventariar conflitos existentes e pendentes antigos sem timestamp; aprovar backfill; criar migration de `criado_em`, índice de expiração e exclusion constraint.
3. **Adicionar estado e timestamp:** mapear `criadoEm` de origem única do banco e `EXPIRADO`; manter respostas compatíveis ou versionar/documentar DTO.
4. **Centralizar a política de disponibilidade:** criar consulta rápida de conflito considerando somente `PENDENTE`/`CONFIRMADO`; update continua excluindo o próprio ID. Pré-expirar pendentes vencidos no destino dentro da transação.
5. **Adicionar transações e tradução de conflito:** envolver create/update; forçar observação da constraint em ponto controlado; identificar constraint/SQLSTATE `23P01` e retornar 409.
6. **Implementar transições de estado:** confirmação com lock e prazo; cancelamento preservando a linha e autorização SEG-01; expiração condicional e idempotente.
7. **Habilitar scheduler:** habilitar scheduling, configurar intervalo curto e observável, executar backlog no startup e manter update seguro em múltiplas instâncias.
8. **Atualizar contratos/documentação:** OpenAPI, DTO de resposta, API cancellation/confirmation e instruções operacionais; alinhar README sem afirmar proteção concorrente antes dos testes.

As etapas 2–6 precisam de coordenação de deploy: a constraint pode fazer uma versão antiga receber exceção não tratada (500). Preparar tradução de `23P01` antes/na mesma janela de ativação da constraint e não permitir writers incompatíveis durante a migração.

## 9. Plano de testes

Executar em PostgreSQL real da mesma major version de produção, em banco/schema descartável. O projeto atual não tem Testcontainers; se aprovado, pode ser adicionado como dependência de teste. Alternativamente, usar serviço PostgreSQL de CI isolado, sem apontar para `.env`/volume de desenvolvimento. Não usar testes com mocks como prova da constraint.

- **Unidade:** cálculo de deadline; estados permitidos/bloqueantes; limite exato de 12h; idempotência de expiração; validação de horário e regra semiaberta.
- **Integração de status:** PENDENTE dentro do prazo confirma; após o limite transiciona a EXPIRADO e não confirma; CANCELADO não é expirado; CONFIRMADO nunca é expirado pela job; expirado/cancelado libera o intervalo.
- **Scheduler:** invocar serviço de expiração com relógio controlado/SQL fixtures em vez de aguardar 12h real; testar backlog depois de indisponibilidade e job sem linhas elegíveis.
- **Concorrência com conexões separadas:** duas criações sobrepostas — uma vence e outra recebe 409/SQLSTATE `23P01`; testar também dois updates, create versus update e duas operações em quadras distintas.
- **Intervalos:** igualdade, sobreposição parcial, contenção, adjacência, data diferente e quadra diferente; garantir que virada de dia continue rejeitada.
- **Corrida confirmação/expiração:** usar duas transações sincronizadas perto do deadline e afirmar um único estado final válido (`CONFIRMADO` ou `EXPIRADO`), nunca regressão para `PENDENTE` nem sobrescrita.
- **Cancelamento:** dono e ADMIN podem cancelar segundo as permissões aprovadas; cliente não cancela reserva de terceiro; linha permanece; novo agendamento pode ocupar o intervalo depois do commit.
- **Constraint/migration:** inserir via JDBC para comprovar que a regra não depende do serviço; migration em base limpa; preflight detecta overlaps antigos e não apaga dados.
- **Regressões:** manter testes de SEG-01 e DB-01; incluir ao menos um teste com configuração real de method security, não somente `standaloneSetup`.

## 10. Riscos e cuidados

1. **Pendentes históricos sem idade conhecida:** não há `criado_em` atual. Backfill para “agora” dá mais 12h de bloqueio a todos os pendentes antigos; backfill para passado pode expirará-los em massa. Auditar e aprovar política antes da migration.
2. **Migração de exclusão:** conflitos existentes fazem a constraint falhar. Não corrigir com DELETE automático; reconciliar cada reserva com o responsável.
3. **Privilégio de extensão:** ambiente gerenciado pode não permitir `CREATE EXTENSION btree_gist`; confirmar antes do rollout.
4. **Lock/índice/volume:** criação da constraint pode bloquear escrita enquanto o GiST é construído. Avaliar volume, duração e janela.
5. **Atraso do scheduler:** execução a cada minuto implica até aproximadamente um ciclo de atraso no status. Pré-expiração no caminho da reserva evita que um `PENDENTE` vencido impeça reutilização quando houver uma nova tentativa. Monitorar duração, quantidade expiradas e falhas.
6. **Múltiplas instâncias:** cada réplica executa `@Scheduled`. A atualização precisa ser idempotente/condicional e transacional; processamento em lote pode usar `SKIP LOCKED`. Lock distribuído não deve ser pressuposto.
7. **Relógios:** calcular criação e vencimento com horário do banco (`TIMESTAMPTZ`/`statement_timestamp` ou relógio DB após lock), evitando diferenças entre JVMs.
8. **Transição de status em lote:** DML bulk pode ignorar callbacks e deixar entidades gerenciadas desatualizadas; limpar/atualizar persistence context e registrar métricas. Se surgir `@Version`, bulk update deve considerá-lo.
9. **Histórico:** alterar `status` preserva o agendamento, mas não a sequência de status. Uma trilha auditável exige mecanismo adicional.
10. **Segurança e compatibilidade:** confirmação não deve aceitar status genérico enviado pelo cliente; cancelamento conserva as validações de proprietário/ADMIN de SEG-01. Não remover `PUT`/`DELETE` nem alterar seus contratos silenciosamente.

## 11. Critérios objetivos para DB-02 ser considerado resolvido

- Existe constraint PostgreSQL instalada que impede sobreposição concorrente para a mesma quadra entre os status bloqueantes aprovados.
- Criação e atualização estão transacionais e continuam aplicando a validação DB-01; o update ignora o próprio ID na pré-consulta.
- Violação da constraint específica é traduzida para HTTP 409; outros erros de integridade não são mascarados como conflito de horário.
- Testes com PostgreSQL real demonstram que, em corridas de INSERT, UPDATE e create-versus-update, no máximo uma reserva bloqueante ocupa cada intervalo.
- Adjacência, mudança de quadra/data e status cancelado/expirado seguem as regras aprovadas.
- SEG-01 continua coberto: cliente não troca proprietário; reatribuição permanece só pela operação ADMIN explícita.
- DB-02 só pode ser marcado concluído após migration aplicada em ambiente de teste e todos esses testes passarem. Este documento não o marca como resolvido.
