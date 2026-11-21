# 04 — Auditoria de banco de dados

## Modelo encontrado

| Entidade | Campos relevantes | Relacionamentos |
|---|---|---|
| `Usuario` | id, nome, e-mail único, telefone, senha, perfil | `@OneToMany` para agendamentos, `mappedBy="usuario"` |
| `Quadra` | id, nome, tipo, ativa | `@OneToMany` para agendamentos, `mappedBy="quadra"` |
| `Agendamento` | id, data, hora início/fim, status | `@ManyToOne` obrigatório para usuário e quadra |

Enums são persistidos como texto (`EnumType.STRING`), evitando dependência de ordinal. `usuario.email` tem `unique = true`; campos principais e FKs declaram `nullable = false`. Não foram encontrados scripts de schema ou ferramenta de migração. O README sugere `spring.jpa.hibernate.ddl-auto=update`, mas esse valor não está configurado em arquivo versionado.

## Achados

### DB-01 — Atualização de agendamento ignora conflito e estado ativo da quadra

- **Prioridade:** ALTA
- **Situação:** comprovado
- **Arquivo/classe:** `src/main/java/br/com/arena/service/AgendamentoService.java`, método `atualizar` (107–126).
- **Descrição técnica:** o método atualiza data, horários, usuário e quadra sem chamar a validação de sobreposição usada por `salvar`, sem excluir o próprio agendamento da busca de conflitos e sem verificar `quadra.isAtiva()`.
- **Consequência prática:** um cliente ou administrador pode editar uma reserva para um horário já ocupado ou para uma quadra desativada, contrariando a regra de negócio anunciada.
- **Solução recomendada:** centralizar validação aplicável a criação/edição, ignorando o registro atual na edição; validar quadra ativa e estado permitido. Cobrir mudança de dia/quadra/horário e atualização do mesmo slot.

### DB-02 — Verificação de conflito não é atômica sob concorrência

- **Prioridade:** ALTA
- **Situação:** risco confirmado pela sequência de código; ocorrência depende de requisições concorrentes
- **Arquivo/classe:** `service/AgendamentoService.java`, `salvar` (77–102); `repository/AgendamentoRepository.java`; entidades `Agendamento`.
- **Descrição técnica:** a aplicação consulta agendamentos para uma quadra/data, verifica intervalos em memória e só então insere. Não há `@Transactional` no caso de uso, lock ou restrição de exclusão no banco que impeça intervalos sobrepostos.
- **Consequência prática:** duas requisições simultâneas podem ler conjunto sem conflito e ambas gravar horários que se sobrepõem. A regra atual não garante ausência de overbooking em carga concorrente.
- **Solução recomendada:** definir uma estratégia transacional de serialização por quadra/data e, preferencialmente, uma invariante no PostgreSQL (por exemplo, restrição de exclusão sobre intervalo temporal, com tipos/extensão adequados). Tratar conflito de integridade como HTTP 409. Um `@Transactional` sem lock/constraint apropriado não elimina sozinho todas as corridas.

### DB-03 — Busca de conflito não filtra estado do agendamento

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** `AgendamentoRepository.findByQuadraAndData`; `AgendamentoService.salvar`; enums `StatusAgendamento`.
- **Descrição técnica:** a busca retorna todas as reservas da quadra e dia, e o loop considera qualquer estado como bloqueador. Existe `CANCELADO`, mas não há endpoint/serviço que aplique esse estado; a exclusão atual é física.
- **Consequência prática:** se reservas canceladas forem criadas por outro mecanismo, elas continuarão impedindo a reutilização do horário. O ciclo de estados não está definido no contrato.
- **Solução recomendada:** formalizar estados que ocupam disponibilidade e filtrar apenas estados bloqueadores. Implementar transições autorizadas de cancelamento, com política de prazo/auditoria apropriada ao produto.

### DB-04 — Relacionamentos podem gerar consultas LAZY/EAGER ineficientes

- **Prioridade:** MÉDIA
- **Situação:** comportamento de carregamento comprovado; N+1 é risco a medir
- **Arquivo/classe:** `model/Agendamento.java` (`@ManyToOne` sem estratégia, portanto EAGER por padrão JPA); `model/Usuario.java` e `model/Quadra.java` (`@OneToMany`, LAZY por padrão); `AgendamentoMapper.toDTO`.
- **Descrição técnica:** o mapeador de agendamento acessa nome do usuário e da quadra. Listagens usam `findAll()` e não declaram `EntityGraph`, projeção ou consulta com fetch planejado. EAGER não significa necessariamente join único; Hibernate pode executar consultas secundárias. Coleções LAZY são usadas em verificações de exclusão.
- **Consequência prática:** potencial de N+1 nas listas de reservas e dependência de sessão aberta para acessar coleções; o volume ainda não foi medido.
- **Solução recomendada:** medir SQL em teste de integração; para listagens, projetar DTO diretamente ou usar fetch planejado (`EntityGraph`/query apropriada). Manter relações explicitamente LAZY quando possível e carregar apenas o necessário no caso de uso.

### DB-05 — Sem índices explícitos para a busca de disponibilidade ou migrações

- **Prioridade:** MÉDIA
- **Situação:** ausência comprovada nos arquivos versionados
- **Arquivo/classe:** `repository/AgendamentoRepository.java`, `model/Agendamento.java`, raiz do projeto.
- **Descrição técnica:** a consulta por quadra/data filtra por `quadra_id` e `data`; não há índice declarado para esses campos. Só há unicidade de e-mail explicitamente indicada. Não há scripts/migrações versionados. PostgreSQL cria índice para constraint unique, mas não cria automaticamente índice para cada FK.
- **Consequência prática:** consulta de disponibilidade pode degradar à medida que reservas crescem; alterações de schema ficam implícitas e difíceis de reproduzir/reverter.
- **Solução recomendada:** validar plano de execução com volume representativo e então criar índice composto apropriado; adotar migrações versionadas antes de ambiente persistente. Não adicionar índices sem medir consultas e cardinalidade.

### DB-06 — Regras de intervalo existem apenas na aplicação

- **Prioridade:** ALTA
- **Situação:** comprovado
- **Arquivo/classe:** `AgendamentoService.validarHorarios` e entidade `Agendamento`.
- **Descrição técnica:** `horaFim > horaInicio` e início no futuro são verificações Java; não há `CHECK` constraint ou mecanismo equivalente encontrado no schema. `LocalDate`/`LocalTime` não definem timezone ou intervalo operacional.
- **Consequência prática:** escrita fora da API pode persistir intervalos inválidos; mudanças em mais de um caminho podem escapar da validação. Horários locais sem fuso exigem política explícita se a operação tiver múltiplos fusos ou integração futura.
- **Solução recomendada:** manter validação amigável na API e reforçar invariantes persistentes no banco onde apropriado; documentar fuso da arena e política de horário de verão antes de múltiplos locais.

### DB-07 — `@Version` e política de exclusão/auditoria ausentes

- **Prioridade:** MÉDIA (conforme volume e requisitos de histórico)
- **Situação:** ausência comprovada
- **Arquivo/classe:** `model/Agendamento.java`, `model/Quadra.java`, `model/Usuario.java`; `AgendamentoService.deletar`.
- **Descrição técnica:** não há controle otimista de versão. O `DELETE` de agendamento remove a linha fisicamente e não registra motivo/ator. A entidade enumera `CANCELADO`, mas não há fluxo que a utilize.
- **Consequência prática:** edições concorrentes podem sobrescrever dados sem detecção; cancelamentos não deixam histórico de negócio.
- **Solução recomendada:** avaliar `@Version` para concorrência de edição e transição de cancelamento/auditoria em vez de exclusão física, conforme requisitos legais e operacionais. Não é necessário adotar soft delete genérico sem política de retenção.

## Exclusões e integridade referencial

`QuadraService.deletar` e `UsuarioService.deletar` bloqueiam exclusão se a coleção de agendamentos não estiver vazia, e as FKs de `Agendamento` são não nulas. Isso é uma proteção básica. A checagem em coleção pode carregar muitos registros e, sem transação/constraint apropriada, não é uma garantia robusta contra gravação concorrente. As FKs continuam sendo a última defesa; falhas de constraint não têm tratamento específico documentado.

## Consistência de usuário e perfil

O e-mail tem unicidade declarada no banco e há checagem prévia no serviço. A checagem prévia melhora mensagem, mas duas criações concorrentes ainda podem colidir na constraint; `DataIntegrityViolationException` não tem handler específico e pode resultar em 500 genérico. `UsuarioService.atualizar` aceita `perfil` nulo, embora a coluna seja não nula; a validação do DTO não exige `perfil`, podendo resultar em erro de persistência se ADMIN omitir o campo.

## Configuração de schema e banco

O README sugere `ddl-auto=update`, mas não há arquivo de configuração no repositório para confirmar o valor efetivo. Em ambiente de desenvolvimento isso pode facilitar inicialização; em produção, atualização automática de schema não substitui migração revisada e pode tornar mudanças não reproduzíveis. Datasource, credenciais e política de schema precisam ser fornecidos externamente, sem incluir segredos no Git.
