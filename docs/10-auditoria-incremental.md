# 10 — Auditoria incremental

**Escopo:** conferência do estado atual do código e dos arquivos de infraestrutura contra as auditorias 01–09. Não houve alteração de código/configuração, execução de migrações ou operação de início/parada/remoção de contêineres. Os testes selecionados usam MockMvc standalone e repositórios simulados.

## A. Estado atual

O projeto evoluiu desde a auditoria inicial: SEG-01 e DB-01 estão implementados, há dez testes de controller/service cobrindo esses fluxos e foi adicionada uma configuração Compose somente para PostgreSQL. O Compose passa a validação e define imagem oficial, banco `arena`, volume nomeado, healthcheck e porta local.

Ainda é um MVP sem garantia contra concorrência de reservas, sem fluxo de cancelamento por estado, migrações ou transações de serviço. A aplicação Spring continua sem configuração versionada de datasource/JWT; o teste legado de contexto não inicia em ambiente limpo.

## B. Correções confirmadas

### SEG-01 — Propriedade de agendamento corrigida

- **Prioridade original:** ALTA — **status atual: corrigido**
- **Evidência:** `AgendamentoController.java:44–59` usa `AgendamentoAtualizacaoRequestDTO` no `PUT`; `AgendamentoAtualizacaoRequestDTO.java:9–24` não contém `usuarioId`. `AgendamentoService.java:111–150` valida proprietário/ADMIN e atualiza data, horas e quadra sem trocar `agendamento.usuario`. A reatribuição está separada em `PATCH /{id}/proprietario`, `AgendamentoController.java:52–59`, com `@PreAuthorize` e checagem ADMIN adicional em `AgendamentoService.java:153–168`.
- **Testes:** `AgendamentoControllerSecurityTest.java:99–179` cobre edição própria, envio de `usuarioId` no PUT sem alteração de proprietário, tentativa de editar reserva alheia, recusa de cliente à operação administrativa e reatribuição por ADMIN.
- **Resultado atual:** esses cinco testes passaram no comando combinado registrado em G.
- **Limitação:** MockMvc usa `standaloneSetup`; não carrega a cadeia real nem o proxy de `@PreAuthorize`. A verificação de papel do serviço é testada, mas a anotação em si não é exercitada nesse teste.

### DB-01 — Atualização valida disponibilidade

- **Prioridade original:** ALTA — **status atual: corrigido**
- **Evidência:** `AgendamentoService.java:111–148` mantém validações de hora/data, valida propriedade, rejeita quadra inativa, consulta por quadra/data, ignora o próprio ID (`Objects.equals`) e testa sobreposição antes de alterar a entidade.
- **Testes:** `AgendamentoControllerDb01Test.java:104–184` cobre horário livre, ocupado, manter o mesmo horário, quadra inativa e preservação dos dados originais após conflito.
- **Resultado atual:** os cinco testes passaram no comando combinado registrado em G.
- **Limitação:** teste usa repository mockado, não PostgreSQL; não comprova concorrência nem comportamento real da consulta JPA.

### Infraestrutura PostgreSQL

- **Status:** configuração presente e validada, não iniciada.
- **Evidência:** `docker-compose.yml:1–22` usa `postgres:16`, declara `POSTGRES_DB` com padrão `arena`, exige usuário/senha, publica somente em `127.0.0.1:5432`, monta `arena_postgres_data` e verifica prontidão com `pg_isready`. `.env.example:1–3` tem valores de exemplo; `.gitignore:35–38` ignora `.env` e preserva `.env.example`.
- **Validação:** `docker compose config --quiet` passou usando o `.env` local atual. `docker ps` e `ss` foram usados apenas para leitura; não há contêiner ativo publicando 5432, a porta está sem listener e o volume `arena_postgres_data` não aparece entre os volumes existentes.
- **Documentação:** `docs/09-postgresql-docker.md` corresponde à configuração e alerta que `POSTGRES_*` é para Compose, não configuração automática do Spring.

## C. Problemas ainda pendentes

### DB-02 — Corrida entre verificação e gravação de reserva

- **Gravidade:** ALTA — risco comprovado pelo fluxo; ocorrência exige requisições concorrentes.
- **Arquivo/linhas:** `AgendamentoService.java:81–106` (criação) e `125–148` (edição); `AgendamentoRepository.java:11–16`.
- **Evidência:** cada operação consulta horários e depois chama `save`; não há `@Transactional`, lock ou restrição do banco que torne a checagem de intervalo exclusiva. O repositório busca quadra/data, mas a regra é decidida em Java.
- **Impacto:** duas criações ou uma criação e uma edição simultâneas podem validar contra o mesmo estado e persistir horários sobrepostos. DB-01 corrige o caso sequencial, não a concorrência.
- **Recomendação:** definir uma estratégia transacional e uma garantia PostgreSQL apropriada para intervalos, como lock por recurso/dia ou constraint de exclusão, e testar duas transações concorrentes. `@Transactional` isoladamente não fecha a corrida.

### DB-03 — Estados de reserva não têm ciclo de cancelamento

- **Gravidade:** MÉDIA; a colisão com cancelados é condicional à existência dessas linhas.
- **Arquivo/linhas:** `StatusAgendamento.java:3–6`; `AgendamentoService.java:81–97, 125–141, 170–175`; `AgendamentoRepository.java:11–16`.
- **Evidência:** criação atribui `PENDENTE`; nenhum fluxo encontrado define `CANCELADO` ou `CONFIRMADO`. `DELETE` remove fisicamente a linha. As consultas de disponibilidade não filtram estado.
- **Impacto:** hoje a API não expõe cancelamento por estado. Se linhas `CANCELADO` forem inseridas por SQL/importação ou outro caminho, continuarão bloqueando horários.
- **Recomendação:** definir quais estados bloqueiam disponibilidade e oferecer uma transição autorizada de cancelamento, preservando histórico se isso for requisito.

### CFG-01 — Datasource e propriedades JWT não são reproduzíveis pelo repositório

- **Gravidade:** ALTA para execução em ambiente limpo; dependente de configuração externa.
- **Arquivo/linhas:** ausência de `src/main/resources`; `JwtService.java:17–24`; `docker-compose.yml:5–8`; `.env.example:1–3`.
- **Evidência:** JWT requer `jwt.secret` e `jwt.expiration`; Compose define somente variáveis `POSTGRES_*`, que não configuram automaticamente o datasource do Spring. Não há `SPRING_DATASOURCE_*` nem properties versionados. O `.env` local atual contém as chaves `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, mas seus valores não foram lidos nem incluídos neste relatório.
- **Impacto:** uma execução local do Spring precisa de configuração adicional de datasource e JWT. A suíte completa falha antes de subir o contexto por não encontrar URL/driver de datasource.
- **Recomendação:** documentar uma configuração local segura para IntelliJ/testes, com segredos fora do Git e perfil de testes isolado; validar também as propriedades JWT no startup.

### TEST-03 — Testes de regra passam, mas não há integração com Spring Security real ou PostgreSQL

- **Gravidade:** MÉDIA.
- **Arquivo/linhas:** `AgendamentoControllerSecurityTest.java:89–91`; `AgendamentoControllerDb01Test.java:80–88`; `ArenaApplicationTests.java:6–11`.
- **Evidência:** testes de segurança e DB-01 constroem MockMvc standalone, usam `SecurityContextHolder` manual e mocks para repositories/services. O teste de contexto existente requer datasource, e não há teste de integração com banco encontrado.
- **Impacto:** testes atuais não verificam filtros JWT, proxy de `@PreAuthorize`, mapping JPA, constraints ou a corrida DB-02.
- **Recomendação:** manter os testes rápidos e acrescentar, quando houver ambiente de teste configurado, testes de integração com Spring Security e PostgreSQL para autorização, constraints e concorrência.

### API-01 — Conflitos seguem retornando 400 em vez de 409

- **Gravidade:** MÉDIA.
- **Arquivo/linhas:** `GlobalExceptionHandler.java:34–45`; conflitos são lançados em `AgendamentoService.java:93–96, 136–139`.
- **Evidência:** toda `BusinessException`, incluindo horário ocupado, é mapeada a `BAD_REQUEST`.
- **Impacto:** consumidores não distinguem conflito de estado (`409`) de dados inválidos (`400`) sem interpretar mensagem.
- **Recomendação:** criar tratamento específico para conflito e documentar o contrato; manter validações de formato/regra de entrada em 400.

### API-06 — Coleções sem paginação ou ordenação

- **Gravidade:** MÉDIA.
- **Arquivo/linhas:** `AgendamentoService.java:40–45`; `QuadraService.java:24–30`; `UsuarioService.java:29–35`; controllers devolvem `List`.
- **Evidência:** listagens usam `findAll()` e não aceitam `Pageable`, filtros ou ordenação.
- **Impacto:** respostas e custo de consulta crescem com o volume; consumidores não têm navegação incremental.
- **Recomendação:** introduzir paginação/ordenação com limites máximos para coleções que possam crescer, começando por usuários e agendamentos.

### API-07 — OpenAPI anuncia bearer como requisito global, inclusive nas rotas públicas

- **Gravidade:** BAIXA (divergência documental).
- **Arquivo/linhas:** `OpenApiConfig.java:45–54`; rotas públicas em `SecurityConfig.java:74–83`.
- **Evidência:** a especificação adiciona um `SecurityRequirement` global; a política HTTP libera `/auth/**` e `POST /usuarios`, sem exceções por operação no bean OpenAPI.
- **Impacto:** Swagger/consumidores podem acreditar que login e cadastro exigem token, contrariando a política efetiva.
- **Recomendação:** declarar segurança por operação e marcar explicitamente as operações públicas.

### API-08 — DTO de criação ainda exige `usuarioId` de clientes

- **Gravidade:** BAIXA (contrato inconsistente; não é falha de identidade no fluxo atual).
- **Arquivo/linhas:** `AgendamentoRequestDTO.java:21–25`; `AgendamentoService.java:68–74`.
- **Evidência:** o campo é obrigatório no DTO compartilhado de criação; para `CLIENTE`, o serviço ignora o ID informado e usa o principal. Para ADMIN, o ID enviado é utilizado.
- **Impacto:** clientes precisam enviar dado sem autoridade/efeito e o esquema não expressa claramente o campo administrativo. A identidade do cliente é, no entanto, derivada do token.
- **Recomendação:** considerar DTOs de criação por papel ou contrato que deixe explícita a atribuição administrativa; preservar a derivação do usuário pelo principal para cliente.

### DB-05 — Não há migrações versionadas

- **Gravidade:** MÉDIA antes de persistência compartilhada/produção.
- **Arquivo/linhas:** raiz do projeto e `pom.xml:32–98`; README sugere `ddl-auto=update` em 89–90.
- **Evidência:** não foram encontrados scripts SQL, diretórios Flyway/Liquibase nem dependências correspondentes; também não há índice explícito para a busca por quadra/data. O `ddl-auto` do README não está em configuração versionada.
- **Impacto:** evolução do schema não fica reproduzível/revisável por versão; o contêiner cria apenas o banco inicial, não uma história de schema. A busca pode exigir varredura crescente, a confirmar com plano/volume reais.
- **Recomendação:** adotar migrações versionadas antes de ambientes com dados persistentes; medir a consulta e criar índice só se justificado.

### SEC-ERR-01 — Respostas de erro da cadeia de segurança podem divergir do contrato MVC

- **Gravidade:** MÉDIA.
- **Arquivo/linhas:** `JwtAuthenticationFilter.java:43–78`; `SecurityConfig.java:59–96`; `GlobalExceptionHandler.java:67–103`.
- **Evidência:** não há `AuthenticationEntryPoint`/`AccessDeniedHandler` configurados. O advice MVC trata exceções específicas, mas rejeições ocorridas na cadeia de filtros podem não passar por ele. O filtro captura `Exception` ampla e segue sem autenticar.
- **Impacto:** corpos/status de falhas de autenticação podem variar, e falhas operacionais durante carregamento do usuário podem ser tratadas como token inválido.
- **Recomendação:** padronizar entry point/denied handler e distinguir token inválido de erro operacional sem expor detalhes internos.

## D. Novos problemas e regressões verificados

### INC-01 — Regra de sobreposição duplicada entre criação e atualização

- **Gravidade:** BAIXA (manutenibilidade; não é falha funcional confirmada).
- **Arquivo/linhas:** `AgendamentoService.java:87–97` e `128–141`.
- **Evidência:** a expressão de sobreposição e a exceção de conflito aparecem separadamente nos dois fluxos.
- **Impacto:** futuras mudanças de regra podem ser aplicadas em criação e esquecidas na edição. Os testes atuais cobrem ambos os caminhos sequenciais.
- **Recomendação:** se a regra evoluir, centralizar comparação/validação sem alterar a política atual. Não é bloqueador da correção DB-01.

### INC-02 — Permissões declarativas do novo endpoint não são exercitadas pelos testes atuais

- **Gravidade:** BAIXA como lacuna de teste; não foi encontrada falha de autorização no código.
- **Arquivo/linhas:** `AgendamentoController.java:52–59`; `AgendamentoControllerSecurityTest.java:132–178`.
- **Evidência:** o endpoint declara `@PreAuthorize("hasRole('ADMIN')")` e o serviço também verifica ADMIN; os testes standalone chamam o serviço/handler sem carregar o proxy de method security.
- **Impacto:** a defesa do serviço é testada, mas um erro futuro na configuração/wiring de `@EnableMethodSecurity` não seria detectado por essa classe.
- **Recomendação:** incluir teste de slice/integrado com a configuração real de segurança quando datasource/perfil de teste estiver disponível.

### SEC-LOCAL-01 — Permissões efetivas do `.env` não estão confirmadas

- **Gravidade:** MÉDIA, risco condicionado ao ACL do Windows.
- **Arquivo/linhas:** `.env` local (ignorado por `.gitignore:36–38`); caminho do workspace está em `/mnt/c`.
- **Evidência:** o arquivo contém chaves `POSTGRES_*`; `git check-ignore` e `git status --ignored` o marcam como ignorado e `git ls-files` não o lista. `stat` no WSL reporta modo `0777`; em DrvFS isso pode refletir a montagem padrão, e as permissões efetivas do Windows não foram verificadas. Os valores não foram lidos.
- **Impacto:** se o ACL efetivo permitir leitura por outros usuários do host, a senha local do banco pode ser exposta.
- **Recomendação:** revisar o ACL/permissões do arquivo no Windows/OneDrive e restringir o acesso ao usuário local. Não armazenar credenciais reais no repositório.

Não foi encontrada regressão de propriedade: o `PUT` não recebe proprietário no DTO nem troca `agendamento.usuario`; a troca usa rota administrativa explícita. A comparação DB-01 ocorre antes de mutar a entidade.

## E. Divergências documentais

| Documento | Estado documentado | Estado atual |
|---|---|---|
| `docs/01-auditoria-geral.md:13,42–46` | SEG-01/DB-01 pendentes e apenas `contextLoads` | Histórico desatualizado: ambos corrigidos; há dez testes direcionados, mas sem cobertura real de banco. |
| `docs/03-auditoria-seguranca.md:20–27,93,109` | Cliente pode transferir reserva | Desatualizado: DTO de update não tem `usuarioId`; transferência está no PATCH ADMIN. |
| `docs/04-auditoria-banco-de-dados.md:15–22` | Update não valida quadra/conflito | Desatualizado: DB-01 foi corrigido e tem cinco testes direcionados. DB-02/DB-03 continuam relevantes com a ressalva desta auditoria. |
| `docs/05-auditoria-api-rest.md:26,42–49` | Update usa `AgendamentoRequestDTO` e atribuição não separada | Desatualizado: update usa DTO sem proprietário e existe `PATCH /agendamentos/{id}/proprietario`; criação ainda usa DTO com `usuarioId` obrigatório, embora cliente tenha identidade derivada do token. A especificação OpenAPI ainda aplica bearer globalmente, inclusive a rotas públicas. |
| `docs/06-auditoria-testes.md:5–25` | Só existe `contextLoads` | Desatualizado: existem `AgendamentoControllerDb01Test` e `AgendamentoControllerSecurityTest`; ainda faltam integração JPA/PostgreSQL e execução com cadeia de segurança real. |
| `docs/07-roadmap-melhorias.md:10,20` | DB-01 marcado concluído, DB-02 pendente | Confirmado pelo código e testes direcionados; não marque DB-02 como concluído. |
| `docs/08-correcao-db-01.md` | DB-01 implementado e suite completa bloqueada por datasource | A correção continua presente; os testes direcionados passaram novamente. |
| `docs/09-postgresql-docker.md` | Compose somente para banco, config validada, sem `up` | Arquivo Compose corresponde; nesta conferência também passou `docker compose config --quiet`. Nenhum contêiner foi alterado. |
| `README.md:6–7,25–28,45–50,82–90,115–123` | Boot 3.x, Auth0, ModelMapper/MapStruct, cadastro em `/auth/register`, quadras ativas; Docker ainda listado genericamente | Código declara Boot 4.0.7/JJWT e mapeadores manuais; cadastro é `/usuarios`; lista inclui inativas; `DELETE` remove fisicamente. A configuração de `application.properties` é instrução, não arquivo existente. Compose atual é apenas PostgreSQL, não conteineriza a API. |

## F. Roadmap atualizado

Este quadro atualiza prioridades para a próxima etapa sem editar/apagar os documentos históricos. Os estados de SEG-01 e DB-01 foram conferidos no código e em testes; DB-02 permanece pendente.

| Ordem | Prioridade | Estado | Próximo trabalho |
|---|---|---|---|
| 1 | **ALTA** | Pendente | Definir configuração local/teste reproduzível de datasource e JWT; limitar ACL de `.env`; permitir teste de contexto sem dados reais. |
| 2 | **ALTA** | Pendente — DB-02 | Proteger criação e edição contra concorrência com estratégia PostgreSQL apropriada e testes concorrentes; não confiar apenas em checagem seguida de `save`. |
| 3 | **MÉDIA** | Pendente — DB-03 | Definir ciclo de estados; implementar cancelamento autorizado e decidir como estados cancelados afetam disponibilidade/histórico. |
| 4 | **MÉDIA** | Pendente | Cobrir a cadeia Spring Security real e persistência PostgreSQL; tratar conflitos como 409 e padronizar 401/403. |
| 5 | **MÉDIA** | Pendente | Adotar migrações versionadas e paginação/ordenação nas coleções. |
| 6 | **MÉDIA/BAIXA** | Pendente | Alinhar README/OpenAPI/documentos 01–06 com as rotas e correções atuais; avaliar consolidação da regra duplicada. |
| — | — | **Concluído e confirmado** | SEG-01 e DB-01. PostgreSQL Compose foi criado e validado; banco não foi iniciado nesta auditoria. |

## G. Avaliação final

O projeto evoluiu: a transferência de proprietário no PUT foi removida, DB-01 aplica validações antes da mutação, há uma operação administrativa explícita para reatribuição e o ambiente PostgreSQL local está descrito/configurado sem colocar a API em contêiner. Os dez testes direcionados passaram sem instalar dependências.

Comandos e resultados desta auditoria:

- `mvn -o -Dtest=AgendamentoControllerDb01Test,AgendamentoControllerSecurityTest test` — 10 testes, 0 falhas/erros, `BUILD SUCCESS`.
- `mvn -o -q test` — 11 testes, 0 falhas e 1 erro no teste legado `contextLoads`.
- `docker compose config --quiet` — validou a configuração sem iniciar contêineres.

Ainda não há evidência de integridade concorrente nem de integração com PostgreSQL. A suíte completa executou 11 testes, sem falhas de assertion, mas teve um erro em `ArenaApplicationTests.contextLoads`: sem `spring.datasource.url`/driver configurado no ambiente Maven, o contexto não sobe. Próximos passos prioritários: configuração de teste/local reprodutível, solução DB-02 com teste concorrente, e definição do comportamento de cancelamento DB-03. Nenhum contêiner foi iniciado, parado ou removido.
