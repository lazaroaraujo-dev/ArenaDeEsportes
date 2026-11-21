# Revisão técnica final — DB-02

**Escopo:** revisão independente dos documentos 10–12, atualizada pelas decisões aprovadas em 2026-11-21. É um plano, não uma implementação: não houve alteração de código/configuração, limpeza de dados, migration nem operação de contêiner. SEG-01 e DB-01 permanecem preservados; DB-02 continua pendente.

## 1. Resumo executivo

A constraint PostgreSQL `EXCLUDE USING gist` com `btree_gist`, `quadra_id` e `tsrange(..., '[)')` é tecnicamente adequada ao modelo atual e ao PostgreSQL 16, desde que o schema físico e os privilégios da extensão sejam confirmados. O predicado parcial aprovado deve incluir apenas `PENDENTE` e `CONFIRMADO`. Essa constraint é a defesa contra corridas entre INSERT/UPDATE; a pré-consulta Java e `@Transactional` não a substituem.

As decisões novas fecham os pontos de negócio que faltavam no documento 12: ADMIN confirma; regras de reagendamento são definidas; o DELETE físico será removido; e os agendamentos de desenvolvimento podem ser limpos de forma controlada. Ainda há pré-condições operacionais: identificar o banco correto antes da limpeza, tratar os registros antigos sem inventar timestamps e validar schema/privilégios antes da migration. O scheduler de um minuto precisa ser combinado com pré-expiração no fluxo de reserva para que um ciclo ainda não executado não mantenha slots vencidos ocupados.

**Recomendação geral:** a arquitetura constraint + transações + transições condicionais está consistente e pronta para implementação após diagnóstico read-only do banco de desenvolvimento. A etapa de limpeza só pode ocorrer depois de confirmar explicitamente a conexão-alvo; esta revisão não executa essa limpeza.

## 2. Decisões de negócio consolidadas

| Regra aprovada | Consequência técnica |
|---|---|
| Novos agendamentos são `PENDENTE`; pendentes bloqueiam por 12 horas desde criação. | Persistir instante de criação; não reiniciar o prazo ao editar horário. |
| Pendentes vencidos passam a `EXPIRADO`. | Job periódico e condição `status=PENDENTE` + deadline vencido. |
| `PENDENTE` e `CONFIRMADO` bloqueiam; `CANCELADO` e `EXPIRADO` liberam. | Constraint parcial apenas para os dois estados bloqueadores; todas as pré-consultas usam a mesma regra. |
| Cancelar preserva linha; proprietário ou ADMIN pode cancelar `PENDENTE`/`CONFIRMADO`; não pode cancelar `CANCELADO`/`EXPIRADO`. | PATCH de ação explícita, mantendo validação de proprietário/ADMIN de SEG-01. |
| Adjacência é permitida; atravessar meia-noite é proibido. | Range `[)` e validação `horaFim > horaInicio` no serviço; idealmente também CHECK no banco. |
| Histórico do agendamento fica no banco; não haverá tabela de eventos de status nesta etapa. | Mudança de status preserva a linha, mas não mantém trilha de cada estado anterior. |
| Confirmação | Somente ADMIN, antes do limite de 12 horas. |
| Reagendamento PENDENTE | Proprietário ou ADMIN, antes do deadline original. |
| Reagendamento CONFIRMADO | Somente ADMIN. |
| Reagendamento CANCELADO/EXPIRADO | Proibido. |
| Exclusão física pela API | O DELETE atual será removido; cancelamento é PATCH e preserva a linha. |

O prazo aprovado é contado do instante de criação; atualização de data/horário não renova as 12 horas. O intervalo de até aproximadamente um ciclo do scheduler é uma tolerância operacional aprovada, mas a pré-expiração no caminho de criação/edição continua necessária para evitar que uma linha vencida bloqueie uma tentativa de reutilizar o horário.

### Matriz de permissões

| Operação/estado | ADMIN | Proprietário autenticado | Visitante/cliente sem conta (futuro) |
|---|---|---|---|
| Consultar quadras/disponibilidade | Permitido | Permitido | Público no produto futuro |
| Criar reserva | Permitido conforme o fluxo administrativo atual | Permitido; identidade vem do principal | Público no produto futuro; vínculo de contato ainda será projetado |
| Confirmar PENDENTE antes do limite | Permitido | Proibido | Proibido |
| Reagendar PENDENTE antes do limite | Permitido | Permitido | Proibido |
| Reagendar CONFIRMADO | Permitido | Proibido | Proibido |
| Reagendar CANCELADO/EXPIRADO | Proibido | Proibido | Proibido |
| Cancelar PENDENTE/CONFIRMADO | Permitido | Permitido | Proibido |
| Cancelar CANCELADO/EXPIRADO | Proibido | Proibido | Proibido |
| Reatribuir proprietário | Permitido pela rota ADMIN explícita de SEG-01 | Proibido | Proibido |
| Excluir fisicamente pela API | Rota removida | Rota removida | Rota removida |

No futuro, “proprietário” poderá ser uma identidade de reserva/contato, não necessariamente uma linha `Usuario`. Isso não altera a chave de concorrência, que continua sendo quadra + intervalo, e não deve levar à separação Usuario/Cliente nesta implementação.

## 3. Revisão crítica do documento 12

### Aspectos tecnicamente consistentes

- O diagnóstico de que a consulta seguida de `save` não é atômica está correto (`AgendamentoService.salvar`, `:81–106`; `atualizar`, `:125–148`).
- A constraint de exclusão GiST é a garantia adequada para sobreposição arbitrária; `UNIQUE` em início/fim não seria suficiente.
- `tsrange(data + hora_inicio, data + hora_fim, '[)')` representa o modelo atual de timestamps locais sem fuso. A faixa semiaberta conserva a permissão de adjacência e o serviço já proíbe `horaFim <= horaInicio`.
- A inclusão de `PENDENTE` e `CONFIRMADO`, excluindo `CANCELADO`/`EXPIRADO`, corresponde às regras agora aprovadas.
- O aviso de que `@Transactional` isolado não evita write skew está correto. O `23P01` precisa de tratamento distinto de outras violações de integridade.
- A necessidade de lock/condição atômica na corrida confirmação–expiração e a necessidade de PostgreSQL real nos testes foram corretamente identificadas.

### Lacunas e correções que permanecem

1. **[OBRIGATÓRIA] DELETE físico:** hoje `DELETE /agendamentos/{id}` chama `repository.delete` (`AgendamentoService.java:170–176`). A decisão atual é remover essa rota, não manter uma janela de exclusão física deprecated; o cancelamento oficial será PATCH e preservará a linha.
2. **[OBRIGATÓRIA] Diagnóstico de dados antigos:** `Agendamento` não tem `criadoEm`. Como os dados atuais são de desenvolvimento, podem ser limpos, mas apenas após consulta read-only e confirmação explícita de que a conexão é o banco de desenvolvimento correto. A limpeza não deve incluir usuários nem quadras e não será executada nesta revisão.
3. **[OBRIGATÓRIA] Reagendamento por estado:** PENDENTE pode ser reagendado por proprietário/ADMIN antes do deadline original; CONFIRMADO somente por ADMIN; CANCELADO/EXPIRADO nunca. PUT não renova `criadoEm`.
4. **[OBRIGATÓRIA] Erro durante commit:** a violação de exclusão pode surgir no flush/commit, não necessariamente na linha de `save`. A tradução a 409 precisa acontecer no limite que observa a falha; não basta um `try/catch` em torno de `save()` se o flush só ocorrer depois do retorno do método.
5. **[RECOMENDADA] `statement_timestamp()` versus lock:** usar relógio do banco. Confirmação/cancelamento devem obter lock e consultar o relógio depois do lock; `CURRENT_TIMESTAMP` é fixo no início da transação e pode estar desatualizado após espera.
6. **[RECOMENDADA] Schema real:** a migration do documento 12 é ilustrativa. Confirmar nomes/tipos físicos, schema e se `status` é VARCHAR ou enum PostgreSQL antes de executar DDL.

## 4. Problemas encontrados e correções necessárias

### DB2-01 — DELETE físico precisa ser removido

- **Gravidade:** ALTA, conflito direto com regra aprovada.
- **Evidência:** `AgendamentoController.java:61–64` expõe DELETE e `AgendamentoService.java:170–176` faz exclusão física.
- **Correção necessária [OBRIGATÓRIA]:** remover DELETE do controller/service e adicionar PATCH de cancelamento que preserva a linha, validando proprietário/ADMIN. A API não está em produção; não há necessidade de alias físico ou janela de compatibilidade.

### DB2-02 — Timestamp não existe; limpeza de desenvolvimento exige salvaguarda

- **Gravidade:** ALTA, pré-condição operacional para limpar e migrar.
- **Evidência:** `Agendamento.java:18–33` só contém data/hora/status; nenhum `criadoEm`/`created_at` foi encontrado. O status não permite deduzir a idade.
- **Correção necessária [OBRIGATÓRIA]:** primeiro diagnosticar quantidade/status e verificar database/host/porta; a conexão deve ser explicitamente confirmada como desenvolvimento antes de qualquer limpeza. Após confirmação, limpar somente as linhas de agendamento, preservando usuários e quadras. Não executar essa etapa nesta revisão.

### DB2-03 — Atualização de status precisa ser atômica

- **Gravidade:** ALTA.
- **Evidência:** atualmente só criação atribui status (`AgendamentoService.java:104`); não há confirmação, cancelamento por status ou scheduler. `StatusAgendamento` não contém `EXPIRADO`.
- **Correção necessária [OBRIGATÓRIA]:** adicionar `EXPIRADO`; confirmação, cancelamento e expiração usam transação e condição de estado. Somente ADMIN confirma e apenas antes do prazo. A expiração só atualiza linha ainda `PENDENTE` e vencida. Confirmação obtém lock da linha e verifica prazo após o lock. Cancelamento só aceita `PENDENTE`/`CONFIRMADO`, sem sobrescrever `CANCELADO`/`EXPIRADO`.

### DB2-04 — Regras de PUT por estado precisam substituir o comportamento irrestrito atual

- **Gravidade:** ALTA, risco de reagendar estado que deve liberar o horário.
- **Evidência:** DB-01 valida horário e quadra, mas não restringe estado (`AgendamentoService.java:111–150`). Após os novos estados, o método ainda poderia ser chamado para linhas canceladas/expiradas se não houver guarda.
- **Correção necessária [OBRIGATÓRIA]:** PENDENTE é editável pelo proprietário/ADMIN antes do prazo original; CONFIRMADO, somente por ADMIN; CANCELADO/EXPIRADO não são editáveis. PUT não renova `criadoEm`; PENDENTE vencido deve transicionar para EXPIRADO e o reagendamento ser recusado.

### DB2-05 — Dependência do schema físico e rollout da constraint

- **Gravidade:** ALTA para produção.
- **Evidência:** não há migrations/schema no repositório; apenas `@Enumerated(EnumType.STRING)` (`Agendamento.java:31–33`) e JPA. A constraint ainda não existe.
- **Correção necessária [OBRIGATÓRIA]:** confirmar tipo/nome das colunas, reconciliar conflitos preexistentes, verificar privilégio `btree_gist` e coordenar deploy para que versões antigas não convertam `23P01` em 500.

## 5. Arquitetura técnica definitiva recomendada

**[OBRIGATÓRIA]** Manter a arquitetura em camadas atual: controllers apenas expõem comandos explícitos, `AgendamentoService` coordena validações/transações e PostgreSQL mantém a invariante final. Separar a rotina de expiração em um componente de scheduling que chama um serviço transacional; não concentrar a job no controller nem adicionar um framework de workflow.

**[OBRIGATÓRIA]** Preservar as proteções SEG-01/DB-01: PUT sem `usuarioId`, autorização de proprietário/ADMIN, quadra ativa e pré-consulta excluindo o próprio ID. A constraint complementa essas verificações; não substitui autorização nem validação de estado.

**[OBRIGATÓRIA]** Usar um único timestamp de criação imutável do banco. O campo de deadline pode ser derivado (`criadoEm + 12h`); não manter duas fontes que possam divergir.

## 6. Estratégia definitiva de concorrência PostgreSQL

### Regra temporal no PostgreSQL

Para PostgreSQL 16, a estratégia é compatível: GiST oferece `&&` para ranges e `btree_gist` fornece igualdade GiST para `quadra_id` numérico. A constraint de desenho é:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE agendamento
    ADD CONSTRAINT ck_agendamento_horario_valido
    CHECK (hora_fim > hora_inicio);

ALTER TABLE agendamento
    ADD CONSTRAINT ex_agendamento_quadra_periodo_bloqueante
    EXCLUDE USING gist (
        quadra_id WITH =,
        (tsrange(data + hora_inicio, data + hora_fim, '[)')) WITH &&
    )
    WHERE (status IN ('PENDENTE', 'CONFIRMADO'));
```

**[OBRIGATÓRIA]** Constraint parcial apenas em `PENDENTE`/`CONFIRMADO`; [)` permite adjacência. INSERT concorrente, UPDATE de quadra/data/hora e SQL externo ficam sujeitos à mesma garantia. Quando o status muda para `CANCELADO` ou `EXPIRADO`, a linha sai do predicado no commit e libera o range. A constraint não decide expiração por idade; ela só observa status.

Este SQL não é pronto para execução: confirmar tabela/colunas/tipos, versão e privilégio de extensão. `date + time` é compatível com o modelo local atual e com a proibição de virar o dia. Para vários fusos ou hora de parede ambígua por DST, seria uma evolução futura para `timestamptz`/`tstzrange`, não requisito atual.

**[OBRIGATÓRIA]** O `CHECK (hora_fim > hora_inicio)` mantém no PostgreSQL a regra atual contra duração zero/negativa e intervalos que tentem atravessar a meia-noite; a validação Java continua para retornar erro de entrada legível.

### Camada de serviço e erro

- **[OBRIGATÓRIA]** `@Transactional` na criação e edição, envolvendo pré-expiração, pré-consulta e gravação.
- **[OBRIGATÓRIA]** Pré-consulta com somente status bloqueadores e intervalo, excluindo o próprio ID no PUT. Ela dá resposta amigável, mas não garante concorrência.
- **[OBRIGATÓRIA]** Constraint como fonte final de integridade e tradução da violação específica `23P01`/nome da constraint para 409.
- **[OBRIGATÓRIA]** Não traduzir toda `DataIntegrityViolationException` para conflito; FK, NOT NULL e outras falhas precisam de tratamento distinto.
- **[RECOMENDADA]** Query `exists` de sobreposição em vez de carregar todos os agendamentos da quadra/dia, preservando a comparação de DB-01.
- **[RECOMENDADA]** Lock pessimista da linha da reserva ao confirmar/cancelar e ao decidir seu estado perto do deadline. Lock da linha da quadra só é necessário se também se optar por serializar todas as reservas da mesma quadra; não é substituto da exclusion constraint.

## 7. Estratégia definitiva de expiração e cancelamento

### Timestamp

**[OBRIGATÓRIA]** Persistir `criadoEm` como `Instant`/`TIMESTAMPTZ`, gerado pelo banco para novos registros (default `statement_timestamp()` ou mecanismo equivalente) e recuperado pelo ORM. O deadline é `criadoEm + 12h`; não reiniciar em PUT. O scheduler e confirmações usam relógio do banco, evitando skew entre JVMs.

### Scheduler

- **[OBRIGATÓRIA]** Habilitar scheduling (atualmente `ArenaApplication` só possui `@SpringBootApplication`; não há `@EnableScheduling` nem `@Scheduled`).
- **[OBRIGATÓRIA]** A job atualiza somente `PENDENTE` vencido (`criado_em <= statement_timestamp() - interval '12 hours'`) e é idempotente.
- **[OBRIGATÓRIA]** Executar no startup e periodicamente, aproximadamente a cada minuto, em transação. Depois de indisponibilidade, processar backlog usando o timestamp persistido.
- **[OBRIGATÓRIA]** Criação/edição expira pendentes vencidos relevantes da quadra/data dentro da própria transação antes da pré-consulta, para que um ciclo ainda não executado não retenha o horário.
- **[RECOMENDADA]** Índice parcial `criado_em WHERE status='PENDENTE'`; começar com lote simples se volume baixo e adotar batch/`SKIP LOCKED` se medições justificarem.
- **[RECOMENDADA]** Atualização condicional no banco é idempotente para múltiplas instâncias; não introduzir lock distribuído sem necessidade demonstrada.
- **[FUTURA]** Métricas/alertas de quantidade de vencidos, duração da job e atrasos, além de histórico de transição caso auditoria passe a exigir.

A tolerância de até aproximadamente um ciclo é aceita. Para o evento confirm/expire, obter lock, consultar o relógio DB após o lock e decidir uma única transição. No limite exato, confirmação só antes de `criadoEm + 12h`; expiração em ou depois do limite.

**[OBRIGATÓRIA]** Cancelamento e PUT também devem resolver o status temporal da linha sob lock. Se uma reserva ainda estiver gravada como `PENDENTE`, mas já tiver vencido, a transação primeiro a torna `EXPIRADO` e não a cancela nem a reagenda como ativa. A transição para `EXPIRADO` precisa ser commitada mesmo quando a operação solicitada retorna 409; não lançar uma exceção que reverta a mudança.

### Cancelamento e confirmação

- **[OBRIGATÓRIA]** `PATCH /agendamentos/{id}/cancelamento` como ação explícita, sem status livre no DTO. Autorizar proprietário/ADMIN conforme SEG-01; aceitar somente `PENDENTE` e `CONFIRMADO`; manter a linha como `CANCELADO`.
- **[OBRIGATÓRIA]** Remover o DELETE físico atual. Como a API não está em produção, não é necessária uma rota de compatibilidade; atualizar OpenAPI e documentação para apresentar o PATCH como cancelamento oficial.
- **[OBRIGATÓRIA]** Job não expira `CONFIRMADO`/`CANCELADO`; cancelamento não altera uma linha já `EXPIRADO` ou `CANCELADO`.
- **[OBRIGATÓRIA]** Somente ADMIN pode confirmar, e apenas antes do deadline. Não aceitar `CONFIRMADO` ou `EXPIRADO` por campo de DTO genérico.
- **[OBRIGATÓRIA]** PUT em `PENDENTE` é autorizado a proprietário/ADMIN antes do prazo original; PUT em `CONFIRMADO` somente a ADMIN; `CANCELADO`/`EXPIRADO` são imutáveis. Nunca reiniciar `criadoEm`.

## 8. Fronteiras transacionais

| Operação | Unidade transacional e proteção |
|---|---|
| Criar — **OBRIGATÓRIA** | Lock/validar quadra ativa, expirar pendentes vencidos do destino, consultar status bloqueadores, inserir; rollback em 23P01. |
| Atualizar — **OBRIGATÓRIA** | Carregar e validar dono/ADMIN (SEG-01), verificar estado editável, validar quadra ativa, expirar vencidos no destino, conferir excluindo ID, atualizar; constraint protege concorrência. |
| Confirmar — **OBRIGATÓRIA** | Somente ADMIN; transação, lock da linha, status `PENDENTE`, leitura do relógio DB pós-lock, prazo ainda aberto; atualizar para `CONFIRMADO`. |
| Cancelar — **OBRIGATÓRIA** | Transação e lock/compare-and-set; proprietário/ADMIN; somente PENDENTE/CONFIRMADO; atualizar para `CANCELADO`, sem delete. |
| Expirar — **OBRIGATÓRIA** | Uma transação por lote; `UPDATE` condicional em `PENDENTE` vencido; não sobrescrever status mais novo; persistence context deve ser limpo/atualizado se DML bulk for usado. |

`@Transactional` não impede sozinho duas reservas em linhas diferentes; essa garantia é da constraint. Condições de status e locks evitam lost transition entre confirmação, cancelamento e expiração.

## 9. Estratégia de migrations

### Flyway versus Liquibase

- **Recomendação [RECOMENDADA]: Flyway com migrations SQL.** A alteração é PostgreSQL-específica (`btree_gist`, `EXCLUDE`, range e predicado parcial); SQL versionado deixa explícitos DDL e preflight. O projeto não tem ferramenta nem dependência hoje.
- **Alternativa:** Liquibase se o projeto já tivesse necessidade de changelogs declarativos, múltiplos formatos ou gestão padronizada de rollback. Para este conjunto pequeno de DDL PostgreSQL adicionaria mais estrutura sem benefício claro.
- **Dependências:** Flyway e, conforme a versão adotada, módulo PostgreSQL do Flyway; confirmar compatibilidade com Spring Boot 4.0.7 antes de adicionar. `@Scheduled` não exige dependência adicional além do contexto Spring já presente. Testcontainers requer dependência de teste se escolhido.

### Ordem segura

1. **[OBRIGATÓRIA] Diagnóstico read-only:** verificar `current_database()`, endereço/porta e ambiente conectado; coletar contagens por status, linhas de agendamento, intervalos inválidos e overlaps ativos. Não escrever nem excluir dados.
2. **[OBRIGATÓRIA] Confirmação do alvo:** antes de limpar, confirmar explicitamente que a conexão aponta ao banco de desenvolvimento correto. Registrar contagens de agendamentos, usuários e quadras.
3. **[OBRIGATÓRIA] Limpeza controlada:** após confirmação do alvo, limpar somente linhas de agendamento; preservar usuários/quadras e conferir contagens após a operação. Esta revisão não executa limpeza nem fornece autorização para executá-la.
4. **[OBRIGATÓRIA] Schema versionado:** adicionar migration para `criado_em` gerado pelo banco, `EXPIRADO`, CHECK de horário, índice da expiração, extensão e constraint parcial. Com agendamentos de desenvolvimento limpos, não há necessidade de inventar timestamps históricos.
5. **[OBRIGATÓRIA] Preflight/rollout:** verificar schema físico, ausência de overlaps ativos e privilégio `btree_gist`; coordenar a versão que entende `EXPIRADO`/`23P01` antes de ativar a constraint.
6. **[OBRIGATÓRIA]** Não usar `ddl-auto=update` para substituir migration; não aplicar migration no volume de desenvolvimento sem alvo confirmado e procedimento aprovado.

A criação da constraint constrói GiST e pode bloquear escrita; medir em cópia de tamanho representativo. Conflitos históricos devem interromper a migration até decisão; nada de DELETE/correção automática.

## 10. Endpoints e segurança

- **POST /agendamentos — OBRIGATÓRIA:** preservar identidade derivada do principal para CLIENTE; `usuarioId` do cliente não define seu proprietário.
- **PUT /agendamentos/{id} — OBRIGATÓRIA:** manter DTO sem `usuarioId`, proprietário preservado, autorização SEG-01 e validações DB-01. Não renovar `criadoEm`.
- **PATCH /agendamentos/{id}/cancelamento — OBRIGATÓRIA:** ação fixa, sem status arbitrário, autorização proprietário/ADMIN e transição somente a partir de PENDENTE/CONFIRMADO. Tentativas para CANCELADO/EXPIRADO retornam conflito sem nova alteração.
- **PATCH /agendamentos/{id}/confirmacao — OBRIGATÓRIA:** somente ADMIN confirma PENDENTE antes do deadline; nunca aceitar status arbitrário via DTO.
- **DELETE /agendamentos/{id} — OBRIGATÓRIA:** remover controller/service de exclusão física. A API não está em produção, logo não requer compatibilidade de clientes; documentar a nova rota PATCH.
- **409 — OBRIGATÓRIA:** conflito de faixa ou estado incompatível usa 409; dados/formato inválidos continuam 400, recurso inexistente 404, não autenticado 401 e não autorizado 403.
- **Swagger/OpenAPI — RECOMENDADA:** refletir estados/ações e não aplicar bearer como requisito global às operações públicas; é ajuste de contrato, não mecanismo de concorrência.

## 11. Plano de implementação em etapas

1. **Diagnóstico seguro — OBRIGATÓRIA:** consulta read-only de banco/alvo e contagens de `agendamento`, `usuario` e `quadra`; não limpar até confirmar explicitamente o banco de desenvolvimento.
2. **Limpeza controlada — OBRIGATÓRIA:** com conexão verificada e confirmação explícita, remover apenas agendamentos de desenvolvimento e comprovar que usuários/quadras permanecem. Não incorporar limpeza automática à inicialização nem a uma migration repetível.
3. **Migration SQL — OBRIGATÓRIA:** escolher versão compatível do Flyway; após a tabela de agendamentos estar vazia, criar `criado_em TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()`, índice parcial, status `EXPIRADO`, CHECK, `btree_gist` e exclusion constraint; validar em PostgreSQL 16.
4. **Status/timestamp — OBRIGATÓRIA:** mapear `Instant` gerado pelo banco, acrescentar `EXPIRADO`, não expor campos de controle para escrita em DTO genérico.
5. **Concorrência — OBRIGATÓRIA:** transações de criação/edição, pré-expiração no alvo, pré-consulta por status bloqueador, constraint final e mapeamento específico 23P01→409.
6. **Transições — OBRIGATÓRIA:** confirmação exclusiva ADMIN antes de 12h; cancelamento PATCH de PENDENTE/CONFIRMADO por proprietário/ADMIN; remover DELETE físico; bloquear edição dos estados terminais.
7. **Scheduler — OBRIGATÓRIA:** habilitar `@EnableScheduling`, executar imediatamente e em intervalo configurável (~1 minuto), com update idempotente/condicional e proteção contra confirmação concorrente.
8. **Testes PostgreSQL — OBRIGATÓRIA:** integração e corridas com conexões distintas em banco descartável PostgreSQL 16; Testcontainers é opção, não dependência já existente.
9. **Cliente sem conta — FUTURA:** projetar identidade/contato de reserva desacoplada de `Usuario` sem alterar agora o modelo de cliente; aplicar requisitos de abuso/privacidade para endpoint público.
10. **OpenAPI/operabilidade — RECOMENDADA:** documentar 409, cancelamento, confirmação ADMIN, remoção de DELETE e métricas da job.
11. **Histórico de transições — FUTURA:** tabela/eventos só se requisito de auditoria for ampliado; nesta etapa o status atual no registro é suficiente.

## 12. Plano de testes

- **Unitários [OBRIGATÓRIA]:** deadlines 11h59m59s, exatamente 12h e após 12h; só PENDENTE expira; CANCELADO/CONFIRMADO não são alterados pela job; deadline não reinicia no PUT.
- **Repository/JPA [OBRIGATÓRIA]:** query de pendentes vencidos, status bloqueadores, ID excluído em update e exclusion constraint no PostgreSQL.
- **Expiração [OBRIGATÓRIA]:** invocar job com rows antigas no banco descartável; simular backlog pós-indisponibilidade; executar duas chamadas concorrentes da job e confirmar idempotência.
- **Confirmação versus expiração [OBRIGATÓRIA]:** duas conexões/transações sincronizadas no deadline; apenas estado permitido vence, sem CONFIRMADO ser revertido nem EXPIRADO ser confirmado.
- **Concorrência de reserva [OBRIGATÓRIA]:** duas criações, dois updates, create-versus-update para mesmo range; exatamente uma reserva bloqueadora, outra falha traduzida em 409.
- **Range [OBRIGATÓRIA]:** adjacente permitido; igual, parcial, contido e contenção rejeitados; mesma hora em outra quadra/data permitida; cruzar meia-noite rejeitado.
- **Cancelamento [OBRIGATÓRIA]:** PENDENTE e CONFIRMADO podem cancelar pelo proprietário/ADMIN; CANCELADO e EXPIRADO rejeitados; linha fica no banco e intervalo pode ser reservado depois do commit; cliente não cancela reserva de terceiro.
- **Migration [OBRIGATÓRIA]:** schema limpo passa; fixture de teste com overlap faz preflight/migration parar sem apagar registros; novos inserts recebem `criadoEm` válido do banco.
- **Permissões e PUT [OBRIGATÓRIA]:** confirmar somente ADMIN; PENDENTE reagendável por proprietário/ADMIN antes do prazo; CONFIRMADO só por ADMIN; CANCELADO/EXPIRADO não reagendáveis.
- **API legado [OBRIGATÓRIA]:** PATCH cancela sem deletar; rota DELETE não está mais mapeada; cliente não consegue enviar CONFIRMADO/EXPIRADO em DTO genérico.
- **Limpeza [OBRIGATÓRIA]:** executar apenas em ambiente isolado após verificação do alvo; conferir que somente contagem de agendamentos muda e usuários/quadras são preservados.
- **Regressões [OBRIGATÓRIA]:** manter testes SEG-01 e DB-01 e acrescentar execução com method security real, além de teste de persistência — os atuais usam `standaloneSetup`/mocks.
- **Isolamento [OBRIGATÓRIA]:** testes concorrentes usam conexões independentes e não podem compartilhar uma transação de teste externa que esconda commits/locks.

## 13. Riscos de implantação

- **Dados de desenvolvimento:** a limpeza aprovada é destrutiva para agendamentos; conexão/host/database incorretos podem apagar dados indevidos. Exigir verificação explícita read-only do alvo antes e conferência de usuários/quadras após.
- **Timestamp legado:** com os agendamentos de desenvolvimento limpos, não é necessário inventar idade histórica; se aparecerem dados de outro ambiente, parar e não aplicar limpeza/backfill automaticamente.
- **DELETE:** a rota deve ser removida, não apenas marcada deprecated; se ficar mapeada, ainda permitiria remoção física e violaria a regra de histórico.
- **Constraint GiST:** `btree_gist` pode não estar habilitado no serviço gerenciado; criação da constraint exige índice/lock e pode ser longa.
- **Rolling deploy:** versões antigas não entendem `EXPIRADO` e não tratam 23P01; evitar coexistência de writers incompatíveis.
- **Atraso do scheduler:** o status pode ficar PENDENTE até um ciclo; pré-expiração no alvo de create/update protege disponibilidade nesse intervalo.
- **Bulk update:** pode deixar entidades em persistence context stale e não aciona callbacks; limpar contexto e testar em transação real.
- **Múltiplas instâncias:** sem idempotência/condições, jobs duplicadas podem concorrer. Não usar lock distribuído sem necessidade, mas testar múltiplas chamadas simultâneas.
- **Contrato:** DELETE/PATCH e status de transição exigem documentação/versionamento; não trocar silenciosamente comportamento de clientes.
- **Tipo de status/schema:** confirmar se a coluna física é VARCHAR ou enum/check antes de adicionar `EXPIRADO`.
- **Clientes futuros:** qualquer pessoa deverá consultar quadras/disponibilidade e reservar sem conta; cadastro será opcional com e-mail, senha e telefone verificado por OTP; WhatsApp servirá para notificações e a confirmação inicial continuará ADMIN. Hoje `SecurityConfig` exige autenticação por padrão, não existe endpoint de disponibilidade, `AgendamentoRequestDTO.usuarioId` é obrigatório (`:21–25`) e `Agendamento.usuario` é FK obrigatória (`nullable=false`). Esses contratos exigirão evolução posterior de exposição/identidade/contato. Não separar `Usuario`/`Cliente`, alterar a FK nem adicionar OTP/WhatsApp nesta correção DB-02.
- **Recuperação de reserva sem conta — FUTURA:** definir como visitante prova vínculo para consultar, reagendar ou cancelar sem expor reservas de terceiros. A constraint permanece baseada apenas em quadra/intervalo/status.

## 14. Decisões ainda dependentes de aprovação

Não restam decisões de negócio abertas sobre os estados, prazo, confirmação, reagendamento, cancelamento, adjacência, meia-noite ou retenção do registro atual. Foram aprovados: confirmação exclusiva ADMIN antes de 12h; PENDENTE editável por proprietário/ADMIN no prazo original; CONFIRMADO editável somente por ADMIN; CANCELADO/EXPIRADO terminais; cancelamento por proprietário/ADMIN de PENDENTE/CONFIRMADO; remoção do DELETE físico.

### Gate operacional antes da implementação destrutiva

- **[OBRIGATÓRIA]** Antes da limpeza inicial, executar somente diagnóstico read-only e confirmar explicitamente `current_database()`, servidor/porta e que o alvo é o banco de desenvolvimento correto.
- **[OBRIGATÓRIA]** Depois da confirmação, executar uma etapa de limpeza controlada que remova somente linhas de agendamento e valide que usuários e quadras foram preservados. Essa limpeza não ocorreu nesta revisão.
- **[OBRIGATÓRIA]** Não incluir exclusão automática de agendamentos no startup nem na migration repetível.

### Evolução futura de clientes — fora do escopo DB-02

Reserva sem conta, cadastro opcional, e-mail/senha/telefone verificado por OTP e notificações WhatsApp são requisitos futuros aprovados. Eles não impedem DB-02 se a constraint continuar baseada apenas em quadra/intervalo/status. A FK obrigatória `Agendamento.usuario` e a regra atual de autenticação poderão precisar de evolução posterior; não separar `Usuario`/`Cliente` nem adicionar dependências de OTP/WhatsApp nesta correção. O canal público futuro exigirá decisões próprias de abuso/rate limiting e privacidade.

**Pendência de execução, não de negócio:** escolher/validar versão compatível da ferramenta de migration e confirmar disponibilidade de `btree_gist` no ambiente PostgreSQL alvo.

## 15. Critérios de conclusão de DB-02

- Migration aplicada em PostgreSQL 16 de teste, com `btree_gist` e constraint parcial ativa para `PENDENTE`/`CONFIRMADO`.
- Diagnóstico do ambiente de desenvolvimento feito antes de qualquer limpeza; limpeza confirmada remove somente agendamentos, preserva usuários/quadras; depois da migration, todo novo registro recebe timestamp do banco sem backfill fictício.
- INSERT/UPDATE concorrentes reais não produzem mais de uma reserva bloqueadora no mesmo intervalo.
- Expiração usa timestamp DB, muda apenas PENDENTE vencido e funciona após downtime e com múltiplas instâncias.
- Confirmação/expiração concorrentes não sobrescrevem estados; cancelamento mantém a linha e libera o intervalo.
- Adjacent slots passam; overlaps, contenção e igualdade falham; data/quadra distintas não conflitam.
- Violação específica `23P01` retorna 409; demais falhas de integridade mantêm tratamento distinto.
- Testes SEG-01 e DB-01 continuam passando; cliente não altera proprietário e DB-01 continua validando quadra ativa/self-exclusion.
- Confirmação ADMIN antes de 12h; matriz de reagendamento/cancelamento aplicada; PATCH mantém a linha; DELETE físico removido; cliente não define status por DTO genérico.
- Contratos/OpenAPI documentam estados, ações e 409; a futura reserva pública não incorpora prematuramente uma entidade Cliente nesta entrega.

DB-02 só poderá ser marcado resolvido após todos os critérios acima passarem. Esta revisão não marca o item como concluído.
