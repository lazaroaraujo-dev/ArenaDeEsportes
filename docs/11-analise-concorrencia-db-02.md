# Análise técnica DB-02 — Concorrência de agendamentos

**Status:** proposta para aprovação. Esta análise não implementa alterações, não executa migrações e não marca DB-02 como resolvido.

## 1. Causa e operações envolvidas

`AgendamentoService.salvar` consulta reservas da quadra/data em `AgendamentoRepository.findByQuadraAndData`, verifica intervalos em memória e depois chama `save` (`AgendamentoService.java:81–106`). `atualizar` faz uma verificação equivalente e grava depois (`:125–148`). Nenhum dos métodos está anotado com `@Transactional`; a transação padrão de cada chamada do repositório não abrange a sequência consulta → decisão → gravação.

Duas transações podem, portanto, observar o mesmo estado anterior à gravação e ambas aprovar intervalos que se sobrepõem. A regra Java é adequada para dar feedback em chamadas sequenciais, mas não é uma invariante concorrente.

As operações encontradas que afetam reservas são:

| Operação | Efeito sobre o horário |
|---|---|
| `POST /agendamentos` → `salvar` | Cria reserva `PENDENTE`; verifica disponibilidade antes de salvar. |
| `PUT /agendamentos/{id}` → `atualizar` | Pode alterar quadra, data e horas; DB-01 valida sequencialmente antes da mutação. |
| `PATCH /agendamentos/{id}/proprietario` | Altera apenas o proprietário; não altera intervalo, quadra ou data. |
| `DELETE /agendamentos/{id}` → `deletar` | Remove fisicamente a linha; após commit, o horário deixa de existir. |
| Transição de status | Não há operação de API atual que confirme/cancele; `StatusAgendamento` contém `PENDENTE`, `CONFIRMADO`, `CANCELADO`. |
| SQL/importação/escritor externo | Pode escrever diretamente nas tabelas; não é limitado pelas validações do serviço. |

Evidências principais: `AgendamentoService.java:63–108, 111–168, 170–176`; `AgendamentoRepository.java:11–16`; `Agendamento.java:18–41`; `StatusAgendamento.java:3–7`.

## 2. Modelagem atual e implicações

`Agendamento` guarda `data: LocalDate`, `horaInicio: LocalTime`, `horaFim: LocalTime`, `quadra` e `status`. O banco terá colunas separadas (por exemplo, `data`, `hora_inicio`, `hora_fim`, `quadra_id`, `status`), mas não há migration/schema versionado para confirmar os nomes físicos atuais.

A validação existente exige `horaFim > horaInicio`; portanto, o domínio atual não permite reserva que atravesse a meia-noite. A regra de sobreposição é:

```text
inicioSolicitado < fimExistente
e
fimSolicitado > inicioExistente
```

Isso equivale a intervalos semiabertos `[início, fim)`: reservas adjacentes, em que o fim de uma é igual ao início da outra, são permitidas. A modelagem local `LocalDate` + `LocalTime` não inclui fuso horário nem distingue instantes ambíguos em transições de horário de verão.

## 3. Alternativas avaliadas

| Alternativa | Garante ausência de sobreposição? | Vantagens | Limitações |
|---|---|---|---|
| Consulta Java seguida de `save` | **Não** | Simples e dá mensagem amigável em chamadas sequenciais. | É o comportamento atual; duas consultas podem aprovar antes de qualquer gravação. |
| Lock pessimista em reservas existentes | **Não, sozinho** | Pode proteger alterações das linhas já encontradas. | PostgreSQL não bloqueia uma “lacuna” quando ainda não há linha correspondente; duas criações podem não bloquear nada em comum. |
| Lock pessimista na linha estável da `Quadra` | Sim para escritores que seguem o protocolo | `SELECT ... FOR UPDATE` na quadra, dentro da transação, serializa operações para aquele recurso, inclusive quando não há reserva ainda. | Serializa também horários/dias independentes da mesma quadra; qualquer caminho que não adquira esse lock pode contornar a regra. Ainda é recomendável uma garantia no banco. |
| Lock advisory por quadra/data | Sim para escritores que seguem o protocolo | Pode serializar apenas o par recurso/dia sem criar uma linha de slot. | SQL específico, hash/ordem de locks e protocolo precisam ser uniformes; escritores externos podem ignorá-lo. Não substitui constraint. |
| Isolamento `SERIALIZABLE` | Pode abortar uma das transações concorrentes | Mantém serializabilidade se todos os participantes usarem a transação corretamente. | Pode produzir `40001` e exige retry seguro; maior custo/complexidade e ainda não é uma regra declarativa aplicada a escritores com outro isolamento. |
| `UNIQUE(quadra_id, data, hora_inicio, hora_fim)` | **Não** | Simples para duplicatas exatas. | Não detecta intervalos parcialmente sobrepostos ou intervalos contidos. |
| PostgreSQL `EXCLUDE` com range temporal | **Sim, como invariante do banco** | Protege INSERT/UPDATE concorrentes e escritores fora da aplicação; expressa diretamente “mesma quadra e intervalos não se sobrepõem”. | Requer GiST e `btree_gist` para igualdade de `bigint`; migração e tratamento explícito da violação; precisa decidir quais status participam. |

`@Version`/optimistic locking pode detectar duas atualizações concorrentes do **mesmo registro**, mas não duas reservas diferentes que se sobrepõem. Não é solução para DB-02.

## 4. Estratégia recomendada

Recomenda-se usar a constraint PostgreSQL de exclusão como **autoridade final**, com transações nos casos de uso e uma pré-consulta apenas para feedback antecipado:

1. `salvar` e `atualizar` executam a operação inteira em uma transação.
2. A aplicação mantém uma consulta de sobreposição para responder rapidamente em casos sequenciais. No update, exclui o próprio ID. A consulta deve filtrar apenas os estados que, após decisão de negócio, ocupam a quadra.
3. O PostgreSQL impõe uma `EXCLUDE USING gist` para a chave da quadra e o range derivado de data/horas. A constraint cobre criação e mudanças de quadra/data/horário e arbitrará concorrência entre transações.
4. Uma violação concorrente da constraint é traduzida para conflito de domínio e HTTP **409 Conflict**. A verificação antecipada não substitui essa tradução.
5. A operação de gravação deve forçar/observar a constraint dentro do limite transacional (por exemplo, `saveAndFlush`, se esse for o mecanismo escolhido) ou mapear a falha de commit no limite apropriado. Converter somente violações da constraint de sobreposição — não qualquer erro de integridade — para 409.

O isolamento padrão PostgreSQL `READ COMMITTED` é suficiente para preservar a invariante quando a constraint de exclusão existe; uma transação que concorre com outra terá de esperar/ser rejeitada pela constraint. `SERIALIZABLE` e locks de quadra podem ser avaliados depois por necessidade de UX/throughput, mas não são necessários para tornar a regra do banco verdadeira.

### Constraint ilustrativa

Para o modelo atual, uma expressão `tsrange` é possível porque `data + hora_inicio` e `data + hora_fim` formam timestamps locais:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE agendamento
    ADD CONSTRAINT ex_agendamento_quadra_periodo_ativo
    EXCLUDE USING gist (
        quadra_id WITH =,
        (tsrange(data + hora_inicio, data + hora_fim, '[)')) WITH &&
    )
    WHERE (status IN ('PENDENTE', 'CONFIRMADO'));
```

Este SQL é **ilustrativo**, não foi executado. Confirmar nomes/tipos físicos da tabela antes de preparar migration. `btree_gist` fornece a classe GiST de igualdade para `quadra_id` (`bigint`); sua criação pode exigir privilégio/allowlist do serviço PostgreSQL. O predicado de status deve refletir a decisão de negócio abaixo. Sem predicado parcial, todos os estados, inclusive `CANCELADO`, continuariam ocupando o intervalo.

O limite `[)` preserva a semântica atual: `[09:00,10:00)` e `[10:00,11:00)` não se sobrepõem; igualdade integral, contenção e sobreposição parcial são rejeitadas. A constraint compara `quadra_id`, então horários iguais em quadras distintas são permitidos. Em update, a própria linha não conflita com ela mesma; a pré-consulta da aplicação ainda deve ignorar o ID atual.

## 5. Mudanças que seriam necessárias após aprovação

### Banco e migrações

- Adotar migrations versionadas; hoje não há Flyway/Liquibase nem scripts SQL no projeto. Uma migration SQL versionada (Flyway seria uma opção adequada ao DDL PostgreSQL específico) pode criar a extensão e a exclusion constraint.
- Antes de adicioná-la, auditar dados existentes para intervalos inválidos e pares sobrepostos nos status escolhidos. Migration deve falhar e interromper a implantação se houver duplicidade; resolução de dados precisa de decisão explícita, sem apagar reservas automaticamente.
- A criação da exclusion constraint precisa construir índice GiST e pode bloquear escrita/ter custo em tabela grande. Planejar janela e validar comportamento na versão PostgreSQL-alvo; `EXCLUDE` não tem o fluxo simples de “adicionar constraint não validada” disponível para algumas outras constraints.
- Decidir se também será adicionada `CHECK (hora_fim > hora_inicio)` no banco. A validação Java permanece para mensagem amigável, mas CHECK protege escritas externas contra intervalo invertido/zero.

### Entidade JPA

- **Nenhuma mudança de entidade é obrigatória** se a constraint usar expressão SQL sobre as colunas existentes. Não é necessário mapear um tipo PostgreSQL `tsrange` para que o banco impeça a sobreposição.
- Se uma coluna `periodo` gerada/materializada for preferida para consulta, ela exigirá avaliação de compatibilidade/mapeamento Hibernate e não deve ser introduzida apenas para duplicar os campos já existentes.
- `@Version` é uma proteção opcional para lost updates no mesmo agendamento, mas não substitui a exclusion constraint.

### Repositório e serviço

- Repositório pode substituir a leitura de todas as reservas do dia por uma consulta `exists` de sobreposição que filtre status e aceite ID opcional a excluir. Isso reduz dados carregados, mas é somente pré-validação; o banco permanece autoridade.
- Colocar a fronteira de `@Transactional` nos casos de uso `salvar` e `atualizar`, incluindo leitura, pré-validação e gravação. Não confiar em lock de linhas de `Agendamento` existentes para proteger lacunas.
- Manter a validação de quadra ativa de DB-01. A exclusão temporal não representa `Quadra.ativa`.
- Tratar a violação de constraint pelo nome/SQLSTATE PostgreSQL `23P01` (exclusion violation) e devolvê-la como 409. Não transformar `DataIntegrityViolationException` indiscriminadamente em conflito de horário, pois pode representar FK, NOT NULL ou outra falha.
- `PATCH /agendamentos/{id}/proprietario` não altera o intervalo e deve continuar com a autorização ADMIN de SEG-01; alterações posteriores de data/horário continuam passando pelo mesmo caso de uso protegido pela constraint.

## 6. Decisões de negócio necessárias

Estas escolhas não devem ser inferidas apenas do enum:

1. **Estados que ocupam horário:** recomendação inicial é `PENDENTE` e `CONFIRMADO` bloquearem, e `CANCELADO` liberar. A aplicação atualmente só cria `PENDENTE`, e a busca atual considera todas as linhas. Confirmar se uma reserva pendente segura o horário e por quanto tempo.
2. **Expiração de `PENDENTE`:** se houver prazo, definir quem/transação atualiza o estado e o que acontece quando o prazo expira. Sem isso, pending pode bloquear indefinidamente.
3. **Cancelamento:** decidir se cancelamento libera imediatamente, se há prazo/multa e se o histórico deve ser mantido. Hoje `DELETE` remove fisicamente.
4. **Intervalos adjacentes:** a regra Java atual permite; recomenda-se preservar `[)`, mas confirmar que não há intervalo obrigatório de limpeza entre reservas.
5. **Virada de dia:** o serviço atual rejeita `horaFim <= horaInicio`; confirmar que reservas noturnas não fazem parte do domínio.
6. **Fuso horário:** `tsrange` representa horário local sem fuso, adequado apenas se a arena operar num fuso bem definido e a regra usar hora de parede. Múltiplas localidades ou precisão de instante/DST podem exigir `timestamptz`/`tstzrange` e modelagem de fuso.
7. **Quadra desativada durante reserva concorrente:** DB-01 rejeita quadra inativa no update/create sequencial. Se também for obrigatório impedir reserva quando a desativação ocorre simultaneamente, definir sincronização entre o update de `Quadra.ativa` e reservas (por exemplo, lock da linha da quadra); a constraint temporal não cobre esse estado.

## 7. Migração proposta

1. Aprovar os status bloqueadores, semântica de adjacência, timezone e regra de overnight.
2. Confirmar schema físico e versão PostgreSQL do ambiente; criar migration SQL versionada e revisar privilégios para `btree_gist`.
3. Executar consulta de diagnóstico prévia com o mesmo range e predicado de status da futura constraint. Exemplo conceitual:

   ```sql
   WITH ativos AS (
       SELECT id, quadra_id,
              tsrange(data + hora_inicio, data + hora_fim, '[)') AS periodo
       FROM agendamento
       WHERE status IN ('PENDENTE', 'CONFIRMADO')
   )
   SELECT a.id AS agendamento_a, b.id AS agendamento_b, a.quadra_id
   FROM ativos a
   JOIN ativos b
     ON a.quadra_id = b.quadra_id
    AND a.id < b.id
    AND a.periodo && b.periodo;
   ```

4. Corrigir cada conflito histórico por decisão de negócio registrada; não remover nem reatribuir linhas automaticamente.
5. Aplicar migration em ambiente de teste com cópia/fixture isolada, medir duração/lock e testar escrita concorrente.
6. Planejar rollout: instalar primeiro o tratamento de 409 compatível ou coordenar janela para que versões antigas não devolvam 500 ao receber a nova violação; então aplicar constraint e liberar escritores.
7. Em produção, executar migration conforme procedimento aprovado, verificar constraint instalada e monitorar violações. Não usar o volume de desenvolvimento nem `ddl-auto=update` como migration.

## 8. Estratégia de testes com PostgreSQL real

Os testes atuais de DB-01 usam MockMvc standalone e repository mockado; não demonstram constraint nem concorrência real. Após aprovação, usar PostgreSQL da mesma major version em ambiente descartável (por exemplo, Testcontainers, caso a dependência seja aprovada) ou banco de CI isolado. Nunca apontar teste a dados locais/produção. Cada concorrente deve usar conexão/transação independente.

1. **Invariante direta no banco:** duas inserções sobrepostas na mesma quadra; uma confirma, a outra recebe a constraint de exclusão/SQLSTATE `23P01`; existe exatamente uma linha ativa.
2. **Criação concorrente pela aplicação:** sincronizar duas transações após a pré-consulta e antes da gravação; validar um sucesso e um 409 e confirmar uma única reserva.
3. **Update concorrente:** duas reservas existentes tentam mover para intervalos conflitantes; uma alteração confirma, a outra falha e os campos originais da transação rejeitada continuam intactos.
4. **Criação versus update:** duas operações concorrentes disputam o mesmo intervalo; resultado continua sendo no máximo uma reserva bloqueadora.
5. **Sem falso conflito:** mesmo horário em quadras/datas diferentes; intervalos adjacentes `[09:00,10:00)` e `[10:00,11:00)`.
6. **Conflitos reais:** mesmo intervalo, sobreposição parcial, contenção e troca de quadra/data.
7. **Estados:** confirmar os casos `PENDENTE`, `CONFIRMADO` e `CANCELADO` segundo a decisão aprovada, incluindo transição cancelado → ativo e liberação/reutilização.
8. **Contrato HTTP:** mapear apenas a violação nomeada de sobreposição para 409; validar que erros de FK/NOT NULL não viram 409.
9. **Regressões:** preservar testes SEG-01 de proprietário/ADMIN e DB-01 de quadra ativa, slot próprio e não mutação em conflito.
10. **Migration:** aplicá-la em schema vazio e em fixture com conflito preexistente; no segundo caso, deve falhar sem apagar/corrigir dados silenciosamente.

## 9. Impacto sobre SEG-01 e DB-01

- **SEG-01:** permanece inalterado. O `PUT` continua sem `usuarioId` e não altera o proprietário. A constraint usa quadra e intervalo, não proprietário. A reatribuição ADMIN não muda disponibilidade.
- **DB-01:** permanece como validação de aplicação e feedback sequencial (quadra ativa, pré-consulta, exclusão do próprio ID e validação antes da mutação). A exclusion constraint complementaria essa validação para concorrência; não a substitui quanto a quadra ativa.
- **DB-02:** permanece **pendente** até migration, tratamento 409 e testes PostgreSQL concorrentes aprovados. Nenhuma etapa deste documento marca a correção como concluída.

## 10. Recomendação final

Adotar como base a **exclusion constraint GiST sobre `quadra_id` e `tsrange(..., '[)')`**, com `btree_gist`, transações nos serviços de criação/edição, pré-consulta para feedback e tradução específica de `23P01` para 409. Manter lock pessimista na linha de `Quadra` como opção de otimização/serialização das requisições da API, não como única garantia. Aprovar primeiro as decisões de status/fuso/overnight e a estratégia de migração dos dados existentes.
