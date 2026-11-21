# Correção DB-01 — Validação da atualização de agendamentos

## Problema identificado

A auditoria DB-01 apontou que `PUT /agendamentos/{id}` podia gravar uma reserva em uma quadra inativa ou em um intervalo já ocupado. O fluxo validava a ordem das horas, a data futura e a permissão do solicitante, mas não repetia as validações de disponibilidade feitas na criação.

## Causa no código anterior

Em `AgendamentoService.atualizar`, o código anterior carregava o agendamento existente, verificava sua propriedade, buscava a quadra informada e atualizava data, horas e quadra diretamente. Não consultava `AgendamentoRepository.findByQuadraAndData` e não verificava `quadra.isAtiva()` antes de salvar.

Na edição é necessário também distinguir o próprio registro dos outros resultados da busca. Como a consulta pode retornar a reserva que está sendo editada, comparar o intervalo solicitado consigo mesmo produziria um falso conflito se o cliente mantivesse o horário atual.

## Solução implementada

Antes de alterar os campos da entidade, `AgendamentoService.atualizar` agora:

1. Mantém as validações existentes de hora final posterior à inicial e início no futuro.
2. Carrega o agendamento e executa a autorização existente (`validarPermissao`).
3. Carrega a quadra solicitada e rejeita a operação se estiver inativa.
4. Consulta os agendamentos da mesma quadra e data.
5. Ignora na verificação o registro cujo ID é o do agendamento atual.
6. Para cada outro registro, aplica a regra de sobreposição: início solicitado antes do fim existente **e** fim solicitado depois do início existente.
7. Só depois de todas as validações, altera data, horas e quadra e chama `save`.

Conflito e quadra inativa geram `BusinessException`, que o tratamento HTTP atual converte em **400 Bad Request**. A convenção de 409 para conflito permanece uma melhoria separada da auditoria de API.

## SEG-01 preservado

A correção anterior continua intacta:

- O `PUT` recebe `AgendamentoAtualizacaoRequestDTO`, que não possui `usuarioId`.
- `atualizar` não substitui o proprietário do agendamento; conserva a associação carregada da entidade existente.
- A autorização por proprietário ou ADMIN continua sendo verificada antes de qualquer alteração.
- A reatribuição de proprietário permanece disponível apenas em `PATCH /agendamentos/{id}/proprietario`, protegida por `@PreAuthorize("hasRole('ADMIN')")` e por verificação adicional no serviço.
- Os testes existentes de SEG-01 foram executados novamente junto com os testes DB-01.

## Arquivos modificados e responsabilidades

| Arquivo | Responsabilidade nesta correção |
|---|---|
| `src/main/java/br/com/arena/service/AgendamentoService.java` | Verifica estado ativo e conflito durante update, exclui o próprio ID da checagem e preserva as validações de autorização, data e horário. A propriedade continua preservada pelo código de SEG-01. |
| `src/test/java/br/com/arena/controller/AgendamentoControllerDb01Test.java` | Testes HTTP do endpoint com serviço real, `GlobalExceptionHandler` e repositórios simulados; verifica também que falhas não chamam `save`. |
| `docs/07-roadmap-melhorias.md` | Marca somente DB-01 como concluído e DB-02 como pendente; mantém os demais itens. |
| `docs/08-correcao-db-01.md` | Registra causa, solução, testes, resultados e limitações desta etapa. |

Não foram alterados controller, DTO, entidade, repositório, configuração ou dependências nesta etapa. A alteração de contrato e autorização descrita para SEG-01 foi feita na etapa anterior.

## Explicação didática

Uma atualização não deve gravar os novos valores assim que recebe o JSON. Primeiro carrega-se a reserva atual e confirma-se que o usuário pode alterá-la. Depois, com a quadra e o dia de destino conhecidos, consulta-se a lista daquele recurso e dia.

O próprio agendamento aparece potencialmente nessa lista. O código compara IDs e pula apenas esse registro; os demais continuam sujeitos à regra de sobreposição. Assim, manter o próprio horário não é tratado como colisão, enquanto uma reserva diferente no intervalo solicitado impede a edição. A entidade só é modificada após passar por essas verificações, evitando deixar alterações parciais em memória quando a regra falha.

Esta checagem é uma validação de negócio no serviço. Ela **não** serializa duas requisições simultâneas nem estabelece uma restrição no PostgreSQL; essa garantia é DB-02 e permanece fora do escopo.

## Testes criados e cenários

`AgendamentoControllerDb01Test` executa o `PUT` via MockMvc standalone. O `AgendamentoService` é real; os repositórios e serviços de busca relacionados são simulados. O handler global transforma as exceções em respostas HTTP.

| Cenário | Resultado verificado |
|---|---|
| Atualização para horário disponível | HTTP 200; novo intervalo é aplicado. |
| Atualização para horário ocupado por outro agendamento | HTTP 400; não grava. |
| Atualização mantendo o próprio horário | HTTP 200; o registro atual é excluído da detecção de conflito. |
| Atualização para quadra inativa | HTTP 400; não consulta disponibilidade nem grava. |
| Conflito com alteração de data/horário | HTTP 400; data, horas e quadra originais permanecem iguais e `save` não é chamado. |

As verificações de estado usam o objeto que representa o registro carregado e a expectativa de que o repositório não receba `save`. Não são testes de integração com PostgreSQL.

## Comandos executados e resultados

| Comando | Resultado |
|---|---|
| `mvn -Dtest=AgendamentoControllerDb01Test test` | **5 testes, 0 falhas, 0 erros. BUILD SUCCESS.** |
| `mvn -Dtest=AgendamentoControllerDb01Test,AgendamentoControllerSecurityTest test` | **10 testes, 0 falhas, 0 erros. BUILD SUCCESS.** Os cinco testes de SEG-01 passaram junto com os cinco de DB-01. |
| `mvn -q test` | **11 testes: 0 falhas, 1 erro.** Os testes direcionados passaram; o teste legado `ArenaApplicationTests.contextLoads` não inicia porque não há URL de datasource configurada nem banco embutido no projeto. |

Nenhuma configuração foi alterada para contornar a falha do teste de contexto.

## Limitações que permanecem

- **DB-02 continua pendente:** a consulta seguida de `save` não é atômica contra atualizações concorrentes; não foram adicionados locks, `@Transactional` ou constraint PostgreSQL.
- O teste usa repositório mockado e não valida consulta/constraint real no PostgreSQL.
- A busca continua considerando os estados atuais como bloqueadores; a política de estados/cancelamento da auditoria DB-03 não foi alterada.
- Conflito ainda retorna 400 pelo `GlobalExceptionHandler`, não 409.
- A suíte completa permanece com o erro preexistente de configuração ausente do datasource em `contextLoads`.
- Outras regras fora de DB-01 — como a disponibilidade na criação sob concorrência, cancelamento e duração operacional — não foram alteradas.
