# 21 — Auditoria final do backend e preparação para implantação

## 1. Resumo executivo

O backend tem uma base funcional de camadas, autenticação JWT, papéis ADMIN/CLIENTE, DTOs, Flyway e controles de ciclo de vida/concorrência para agendamentos. A implementação atual contém a proteção PostgreSQL EXCLUDE contra sobreposição e locks para transições de agendamento e ativação de quadras.

**Conclusão:** não recomendo implantar a partir do estado atual do workspace. Há quatro bloqueadores de implantação/operabilidade: artefatos necessários ainda não estão versionados/commitados; aplicação e Flyway compartilham credenciais, e a conta observada no banco `arena` é superuser; o teste de contexto sem guarda pode executar Flyway contra qualquer datasource configurado; e não há bootstrap implementado para o primeiro ADMIN. Além disso, há pendências de segurança, validação e contrato que devem ser resolvidas ou aceitas formalmente antes de exposição pública.

**Achados:** 0 críticos, 4 altos, 7 médios, 0 baixos e 2 melhorias futuras.

**Frontend:** é possível começar protótipos e telas administrativas com os contratos existentes, após congelar a especificação. O fluxo completo de cliente ainda não está pronto para integração: falta listar as reservas próprias e consultar disponibilidade, a listagem de quadras inclui inativas, o DTO de criação exige `usuarioId` mesmo quando o serviço ignora esse valor para CLIENTE, e CORS não está configurado para uma origem de frontend separada.

**Migração V1:** a consulta read-only desta auditoria encontrou o database `arena` sem tabelas no schema consultado e sem `flyway_schema_history`; `btree_gist` está disponível, mas não instalado. Assim, a precondição de schema vazio para uma migration inicial parece satisfeita. A V1 **não foi aplicada**. Isso não é autorização de migração nem aprovação para usar o papel superuser de runtime: destino, credenciais separadas, backup e procedimento precisam ser aprovados antes.

## 2. Escopo e metodologia

- Inspecionados diretamente os documentos 01–20 presentes, todos os arquivos Java de `src/main/java`, testes, `pom.xml`, `application.properties`, V1, Docker Compose, `.env.example`, `.gitignore` e README.
- Conferidas rotas, anotações de segurança, DTOs, mapeadores, relacionamentos JPA, queries/locks e testes no código atual. Relatórios anteriores foram tratados como histórico, não como substituto dessa inspeção.
- O estado do Git foi registrado antes da auditoria. As alterações existentes foram preservadas.
- Foi feita uma consulta PostgreSQL **somente leitura**, com transação read-only, para identificar o estado atual de `arena`, tabelas, histórico Flyway, extensão e papel conectado. Nenhum valor de `.env`, senha, token ou JWT secret foi lido.
- **Não** foram executados Maven, build, testes, migrations, operações Docker de escrita, ou operações de escrita no banco. Os resultados de testes citados abaixo são os registrados anteriormente em `docs/18`–`docs/20`, não resultados reproduzidos nesta auditoria.
- O repositório não contém frontend, Dockerfile da aplicação ou workflows `.github` encontrados na inspeção.

### Documentos revisados

Encontrados e lidos: `docs/01-auditoria-geral.md` a `docs/20-concorrencia-quadras-e-decisoes-arquiteturais.md`, incluindo todos os documentos listados na solicitação. Nenhum dos documentos 01–20 estava ausente. Os documentos 01–07 e 11–17 descrevem estados históricos/planos das etapas anteriores; as conclusões sobre o estado atual foram conferidas no código e nos documentos finais 18–20.

## 3. Estado do Git observado

No início da auditoria, antes de criar este relatório:

- **Modificados e rastreados:** `.gitignore`, `pom.xml`, `ArenaApplication.java`, `AgendamentoController.java`, `AgendamentoResponseDTO.java`, `StatusAgendamento.java`, `GlobalExceptionHandler.java`, `AgendamentoMapper.java`, `Agendamento.java`, `AgendamentoRepository.java`, `QuadraRepository.java`, `JwtAuthenticationFilter.java`, `AgendamentoService.java` e `QuadraService.java`.
- **Não rastreados:** `.env.example`, `docker-compose.yml`, `docs/`, DTOs de agendamento, exceções e serviços novos, `src/main/resources/` e testes de controller/model.

`git ls-files src/main/resources docs src/test/java` listou somente `src/test/java/br/com/arena/ArenaApplicationTests.java`. Portanto, configuração, V1, relatórios e a maioria dos testes estão no workspace, mas não estavam rastreados no início. O `pom.xml` e diversas alterações de produção também aparecem modificados e não commitados.

**Consequência:** um checkout limpo do commit atual não contém o mesmo backend que foi inspecionado. Antes de um release, é necessário revisar e versionar explicitamente os arquivos pretendidos; esta auditoria não os adicionou ao índice nem fez commit.

## 4. Arquitetura e organização

### Aspectos adequados

- A organização por `controller`, `service`, `repository`, `model`, `dto`, `mapper`, `security`, `auth`, `config` e `exception` é simples e compatível com o tamanho do MVP.
- Controllers são predominantemente adaptadores HTTP; as regras de agendamento estão em serviços e persistência em Spring Data.
- Entidades não são serializadas diretamente; mapeadores geram DTOs de resposta.
- `AgendamentoWriteService` delimita transações e `AgendamentoService` traduz resultados de domínio após o commit, evitando desfazer EXPIRADO quando a API responde 409.
- A separação de estados de aplicação e integridade da faixa no PostgreSQL está clara: locks serializam escritores da aplicação e EXCLUDE protege a sobreposição final.

### Observações arquiteturais

- `AgendamentoService` e `AgendamentoWriteService` formam uma fachada e um coordenador transacional. A separação tem justificativa concreta no commit de expiração prévio à resposta 409; não é, por si só, duplicação problemática.
- `SecurityUtils` consulta o `SecurityContextHolder` estático e `Usuario` implementa `UserDetails`. Isso acopla domínio/serviços ao Spring Security e torna alguns testes unitários dependentes de estado global; é melhoria de isolamento, não defeito funcional comprovado.
- `@Data` é aplicado às entidades com relacionamentos bidirecionais. O `toString`/`equals` gerado pode percorrer associações, recursar ou carregar dados lazy. Não encontrei uso explícito de `toString()` de entidades em logs; a exposição de senha/hash por essa via é risco potencial, não vazamento observado.
- Acesso a coleções lazy em exclusões de usuário/quadra depende de contexto de persistência. QuadraService agora tem transação para DELETE; UsuarioService.delete não declara `@Transactional` e consulta `usuario.getAgendamentos()`, dependendo atualmente do Open Session in View no fluxo MVC.

## 5. Segurança e autenticação

### Controles confirmados no código

- O cadastro público passa por `UsuarioService.salvar`, que sempre define `Perfil.CLIENTE`, ignorando `perfil` recebido.
- `PUT /usuarios/me` não altera perfil.
- `/usuarios/{id}` para atualização e exclusão exige ADMIN. `/quadras` de escrita exige ADMIN.
- Ações de reserva verificam ADMIN ou proprietário; confirmação exige ADMIN; criação de CLIENTE deriva o proprietário do principal.
- Senhas são codificadas por `BCryptPasswordEncoder`; `UsuarioResponseDTO` não contém senha.
- A cadeia é stateless e CSRF está desativado. Com o contrato atual, que autentica pelo header Bearer e não por cookie, essa escolha é coerente.
- JWT assinado é validado por JJWT e o usuário/autoridades são recarregados do banco a cada request autenticada.

### Achados de segurança

- O filtro JWT captura `Exception` ampla, limpa o `SecurityContext` e continua sem registrar a causa. Falha de parsing e indisponibilidade/erro ao carregar usuário podem parecer um token inválido, dificultando diagnóstico.
- Não há `AuthenticationEntryPoint` ou `AccessDeniedHandler` próprios. O advice MVC define ErrorResponse para exceções MVC, mas respostas produzidas pela cadeia de filtros podem ter corpo/formato diferente. Os testes anteriores verificam status em alguns cenários, não uniformidade de todos os corpos 401/403.
- Senha pública aceita mínimo de seis caracteres; não foi encontrado rate limiting, bloqueio progressivo ou outra proteção contra tentativa repetida no login. A suficiência da política depende da exposição e do perímetro, mas é insuficiente como hardening para serviço público.
- `jwt.secret` e `jwt.expiration` são obrigatórios, sem configuração versionada por ambiente nem validação declarativa de tamanho/valor positivo no startup. `Keys.hmacShaKeyFor` rejeita chave inadequada ao assinar/validar, mas isso pode aparecer no uso em runtime. Revogação de JWT após troca de senha não existe; o token permanece válido até expirar.
- Swagger/OpenAPI é público pela configuração de segurança. Isso pode ser intencional em desenvolvimento; a publicação em produção deve ser decisão explícita.
- Não foi encontrada configuração CORS. Se o frontend for servido de outra origem, chamadas de browser/preflight podem ser bloqueadas. TLS/proxy e políticas de rede não podem ser avaliados pelo repositório.

**Sem evidência de escalonamento público para ADMIN:** o cadastro força CLIENTE e a alteração administrativa de perfil exige um ADMIN existente. Entretanto, não há bootstrap seguro implementado para criar o primeiro ADMIN, nem proteção contra demover/excluir o último ADMIN.

## 6. API REST e contratos

| Método e rota | Permissão | Entrada → resposta | Observações/códigos principais |
|---|---|---|---|
| `POST /auth/login` | Público | `LoginRequestDTO` → `LoginResponseDTO` | 200; credenciais inválidas tratadas como 401. |
| `POST /usuarios` | Público | `UsuarioRequestDTO` → `UsuarioResponseDTO` | 201; serviço força CLIENTE. |
| `GET /usuarios` | ADMIN | — → lista | 200; sem paginação. |
| `GET /usuarios/me` | Autenticado | — → `UsuarioResponseDTO` | 200. |
| `PUT /usuarios/me` | Autenticado | `UsuarioRequestDTO` → `UsuarioResponseDTO` | 200; perfil não é alterado. |
| `GET /usuarios/{id}` | ADMIN | — → `UsuarioResponseDTO` | 200/404. |
| `PUT /usuarios/{id}` | ADMIN | `UsuarioRequestDTO` → `UsuarioResponseDTO` | 200; permite administração de perfil, mas `perfil` pode ser omitido. |
| `DELETE /usuarios/{id}` | ADMIN | — | 204 se não houver agendamentos; regra de coleção lazy. |
| `GET /quadras` | Autenticado | — → lista | 200; inclui quadras inativas, apesar do README prometer ativas; sem paginação. |
| `GET /quadras/{id}` | Autenticado | — → `QuadraResponseDTO` | 200/404. |
| `POST /quadras` | ADMIN | `QuadraRequestDTO` → `QuadraResponseDTO` | 201. |
| `PUT /quadras/{id}` | ADMIN | `QuadraRequestDTO` → `QuadraResponseDTO` | 200; `ativa` pode ser atualizada sob lock. |
| `PATCH /quadras/{id}/status?ativa=...` | ADMIN | parâmetro boolean → `QuadraResponseDTO` | 200; operação sob lock. |
| `DELETE /quadras/{id}` | ADMIN | — | 204 se vazia; 400 se tiver reservas. |
| `GET /agendamentos` | ADMIN | — → lista | 200; sem paginação. |
| `GET /agendamentos/{id}` | Proprietário ou ADMIN | — → `AgendamentoResponseDTO` | 200/403/404. |
| `POST /agendamentos` | Autenticado | `AgendamentoRequestDTO` → resposta | 201/400/409; `usuarioId` continua obrigatório no DTO, mas CLIENTE é derivado do principal. |
| `PUT /agendamentos/{id}` | Proprietário ou ADMIN, conforme status | DTO de atualização → resposta | 200/400/403/404/409/503. |
| `PATCH /agendamentos/{id}/confirmacao` | ADMIN | sem DTO | 200/403/404/409. |
| `PATCH /agendamentos/{id}/cancelamento` | Proprietário ou ADMIN | sem DTO | 200/403/404/409; preserva a linha. |
| `PATCH /agendamentos/{id}/proprietario` | ADMIN | `AgendamentoProprietarioRequestDTO` | 200; reatribuição explícita. |
| `DELETE /agendamentos/{id}` | Não mapeado | — | Removido; 405 pela API atual. |

### Contratos e documentação

- Não existe endpoint de lista de agendamentos próprios nem consulta de disponibilidade. O cliente só consulta uma reserva se já conhecer seu ID; listagem geral é ADMIN.
- `AgendamentoRequestDTO.usuarioId` é obrigatório mesmo para CLIENTE, embora o serviço ignore esse valor nesse perfil.
- `QuadraRequestDTO.tipo` não tem `@NotNull`. `UsuarioRequestDTO.perfil` também é opcional; no `PUT /usuarios/{id}`, o serviço atribui diretamente `dto.getPerfil()`. Omissão pode terminar em violação NOT NULL/500, em vez de erro de validação 400.
- O nome/e-mail duplicado é pré-checado, mas a unicidade também é imposta pelo banco. Uma corrida na criação pode gerar exceção de integridade genérica (500); não há tradução específica para `uq_usuario_email`.
- `OpenApiConfig` aplica bearer como requisito global, inclusive a login/cadastro públicos. Descrições explícitas foram acrescentadas a algumas ações de agendamento, mas não há contrato OpenAPI completo/consistente de respostas e permissões para todos os controllers.
- README afirma Spring Boot 3.x, Auth0 JWT, ModelMapper/MapStruct, `POST /auth/register`, lista somente quadras ativas e orienta `ddl-auto=update`. O código atual usa Boot 4.0.7, JJWT, mapeadores manuais, `/usuarios`, inclui inativas e configura `ddl-auto=validate` com Flyway.

## 7. Banco de dados, Flyway e consistência

### Compatibilidade observada

Por inspeção, a V1 corresponde aos mappings principais: `IDENTITY/BIGINT`, `DATE`, `TIME WITHOUT TIME ZONE`, enums como texto, `criado_em TIMESTAMPTZ`, FKs e CHECKs de perfil/tipo/status/horário. A entidade mapeia `criadoEm` como valor gerado pelo banco, não inserível/atualizável pela aplicação. A EXCLUDE parcial usa `[)` e PENDENTE/CONFIRMADO, alinhada aos estados bloqueadores. A configuração presente usa `spring.jpa.hibernate.ddl-auto=validate`.

Os documentos 16–20 registram validação anterior de Flyway/Hibernate e testes PostgreSQL. Esta auditoria não executou Hibernate validate nem migration.

### Banco `arena`: inspeção atual somente leitura

A consulta foi executada dentro de `BEGIN READ ONLY`, com `default_transaction_read_only=on`, pelo serviço PostgreSQL local:

- `current_database() = arena`, `current_user = arena_dev`, server version `16.15`.
- O papel atual tem `rolsuper = true` e `rolcreatedb = true`.
- Não foram encontradas tabelas no schema consultado nem `public.flyway_schema_history`.
- `btree_gist` está disponível na versão 1.7, mas não instalada.
- Não foram consultados nem alterados dados de negócio; não foi lido `.env`.

**V1:** a ausência de tabelas/histórico sugere que a precondição de schema vazio para inicialização está satisfeita. A extensão está disponível e o papel observado é privilegiado o suficiente para criá-la. Isso indica viabilidade técnica para uma migration inicial no database de desenvolvimento, não autorização para aplicá-la. Antes de qualquer aplicação, confirmar novamente o alvo/URL usado pelo Spring, garantir backup/rollback operacional e definir a conta Flyway separada. A instância `arena_dev` superuser não deve ser adotada como conta de runtime de produção.

### Riscos de dados e transações

- A EXCLUDE protege sobreposição para PENDENTE/CONFIRMADO; a transição de expiração/cancelamento tira a linha do predicado, preservando o histórico da reserva.
- Não há tabela de histórico de transições; somente o estado atual é persistido.
- Datas/horários são locais sem timezone (`LocalDate`/`LocalTime`/`tsrange`). Isso assume uma arena e política de hora local; múltiplos fusos/DST requerem decisão de produto.
- Operações de busca e listagem usam `findAll` e `Agendamento` tem `@ManyToOne` com fetch padrão EAGER. O mapper lê nome do usuário e quadra, podendo provocar consultas secundárias/N+1; não foi feito profiling ou medição.
- Não foi encontrado `@Version`; a concorrência de reserva/status usa os locks pessimistas da Etapa 4/5 e a EXCLUDE. O controle otimista para requests baseadas em snapshots antigos continua futuro.

## 8. Agendamentos, locks e transações

Inspeção do código confirma:

- POST bloqueia Quadra destino, valida `ativa`, depois bloqueia bloqueantes de agendamento ordenados por ID e faz flush/EXCLUDE.
- PUT segue Quadra destino → alvo e bloqueantes de agendamento por ID; a identidade vem do registro carregado sob lock, o proprietário e `criadoEm` não são atualizados.
- Quadra PUT/PATCH/DELETE bloqueiam a linha da quadra em transação. A desativação altera somente `ativa`.
- Confirmação/cancelamento/reatribuição bloqueiam o agendamento; não adquirem quadra depois. O scheduler usa CTE `FOR UPDATE SKIP LOCKED`, revalida PENDENTE e o prazo com `clock_timestamp`.
- `PostgresClock` consulta o relógio do banco após locks. Resultados de expiração são retornados da transação e só então convertidos em 409.
- `40P01`/`40001` têm handler 503 + `Retry-After: 1`, sem retry automático. A EXCLUDE `23P01` só é convertida em 409 quando SQLSTATE e nome são reconhecidos.

Isso está alinhado com os documentos 19/20. A proteção de `Quadra.ativa` depende de todos os escritores da aplicação seguirem o protocolo; SQL externo pode contorná-la. A EXCLUDE continua protegendo sobreposição, mas não consegue impor uma regra cross-table “quadra ativa”.

## 9. Testes e confiabilidade

### Resultados anteriores documentados

`docs/18`, `docs/19` e `docs/20` registram, respectivamente, 25, 37 e 49 testes aprovados em PostgreSQL descartável. O relatório mais recente registra **49 testes, 0 falhas, 0 erros** depois da Etapa 5. As fontes atuais contêm as classes de ciclo de vida, concorrência, DB-01, SEG-01, `criadoEm` e contexto descritas nesses relatórios.

**Esta auditoria não executou os testes.** Portanto, 49/0/0 é resultado previamente documentado, não uma validação realizada agora.

### Cobertura inspecionada

- Há integração com PostgreSQL real, guardas `TEST_POSTGRES_URL` limitadas a `127.0.0.1:<porta>/arena_flyway_test`, DTO/API e cadeia real Spring Security via `@SpringBootTest`, MockMvc e JWT.
- Testes concorrentes usam executor/barreiras/latches e sessões independentes; há observação de espera de lock via `pg_stat_activity`. A classe de concorrência concentra muitos cenários e helpers, mas o desenho é adequado ao objetivo.
- `AgendamentoControllerDb01Test` e `AgendamentoControllerSecurityTest` usam MockMvc standalone, mocks e `SecurityContextHolder` manual; eles são regressões unitárias, não demonstram filtro/JWT/method security. As classes de integração compensam parcialmente essa limitação.
- Não há testes de login/registro, encoding da senha, expiração JWT, perfil nulo no admin update, duplicidade de e-mail concorrente, formato uniforme de 401/403 ou início do primeiro ADMIN.
- Não encontrei configuração de JaCoCo/cobertura ou workflow CI no repositório.

### Bloqueio de segurança dos testes

`ArenaApplicationTests` é um `@SpringBootTest` sem `@DynamicPropertySource`, perfil de teste ou guarda de URL. A `application.properties` usa `SPRING_DATASOURCE_URL` e habilita Flyway. Assim, `mvn test` herda o datasource do ambiente e pode executar Flyway no banco apontado por `SPRING_DATASOURCE_URL`; os guardas dos outros testes não protegem essa classe. Os relatórios anteriores já alertam para executar a suíte completa somente com **todas** as variáveis Spring apontando ao PostgreSQL descartável.

Isso é um risco concreto de teste acidentalmente migrar um banco de desenvolvimento/externo; não executei Maven nesta auditoria.

## 10. Configuração, implantação e gestão de acesso ao banco

- `application.properties` exige datasource e JWT por variáveis, usa `ddl-auto=validate` e habilita Flyway. Não há perfis de desenvolvimento/produção ou configuração separada de Flyway.
- O Compose configura somente PostgreSQL, publica a porta no loopback e usa volume persistente. Não há Dockerfile da aplicação, serviço Spring, healthcheck da API nem deployment manifest encontrado.
- `.env.example` contém placeholder e `POSTGRES_*`; não fornece as variáveis `SPRING_DATASOURCE_*`/JWT necessárias à aplicação. O `.env` real foi ignorado deliberadamente na auditoria.
- `.gitignore` ignora `.env` e arquivos `.env.*`, mantendo `.env.example`; não encontrei segredo real nos arquivos inspecionados.
- No funcionamento do container, `POSTGRES_USER` é o papel inicial privilegiado da imagem. A aplicação/Flyway usam a mesma datasource configurada, sem `spring.flyway.user` separado. A consulta read-only confirmou que o papel conectado a `arena` é superuser/createdb.
- TLS, proxy, firewall e secret manager de produção não são configurados nem verificáveis aqui.

O relatório 20 registra as decisões de arquitetura: Spring Boot como escritor normal, equipe sem credenciais PostgreSQL, ADMIN da aplicação distinto de administrador do banco, primeiro ADMIN por bootstrap controlado, runtime com DML mínimo e usuário de Flyway separado. Essas decisões **não estão implementadas**; esse documento as registra como plano.

## 11. Documentação e frontend

README está desatualizado em tecnologia, registro, quadras ativas, cancelamento e DDL. As auditorias 01–07 também descrevem o estado inicial — por exemplo, afirmam falta de scheduler/locks/DTO de atualização — e devem ser lidas como histórico, com docs 08–20 atualizando as etapas. A revisão 21 é a consolidação atual.

Para frontend, é possível iniciar telas administrativas/autenticação em desenvolvimento após congelar o contrato. Para uma experiência completa de cliente, faltam ou precisam de decisão:

1. endpoint de reservas próprias;
2. consulta de horários disponíveis, ou contrato explícito de tentativa/409;
3. tratamento/filtragem de quadras inativas na listagem;
4. CORS se frontend e API estiverem em origens distintas;
5. alinhamento de OpenAPI/README, erros de autenticação e campos obrigatórios.

Não há frontend no repositório; nenhum contrato foi alterado nesta auditoria.

## 12. Achados priorizados

### H-01 — Artefatos necessários não estão no Git

- **Severidade:** ALTO
- **Localização:** `git status --short`; `src/main/resources/`, `docs/` e novos serviços/testes aparecem não rastreados; `pom.xml` e várias classes aparecem modificados.
- **Problema observado:** o workspace contém V1, configuração e código de etapas anteriores que não fazem parte do commit atual. `git ls-files src/main/resources docs src/test/java` retornou somente `ArenaApplicationTests.java`.
- **Consequência prática:** um checkout limpo não reproduz o backend testado; pode faltar configuração, migration e classes de produção.
- **Evidência:** estado Git observado nesta auditoria e inventário de arquivos.
- **Correção recomendada:** revisar, selecionar e versionar em commit de release todos os arquivos intencionais; validar build em checkout limpo.
- **Impede implantação:** **Sim**, a partir do commit atual.

### H-02 — Papel superuser observado e credenciais runtime/Flyway compartilhadas

- **Severidade:** ALTO
- **Localização:** `src/main/resources/application.properties:2–8`; `docker-compose.yml:5–12`; `.env.example:1–3`; V1.
- **Problema observado:** Flyway usa a mesma datasource do runtime; não há credenciais/datasource Flyway separados. O Compose usa `POSTGRES_USER` para inicializar o papel do PostgreSQL; o exemplo é `arena_dev`.
- **Consequência prática:** se a aplicação usar o papel observado no banco local, o processo normal tem poderes de superuser/createdb e qualquer comprometimento da API pode alcançar DDL/controle do database. Produção ainda não tem privilégio mínimo nem separação de migração.
- **Evidência:** consulta read-only retornou `current_user=arena_dev`, `rolsuper=true`, `rolcreatedb=true`; configuração ativa Flyway e não define usuário Flyway separado. O `.env` não foi lido e a identidade efetiva do processo Spring não foi presumida.
- **Correção recomendada:** usuário de runtime com `CONNECT`, `USAGE`, DML e sequences estritamente necessários; pipeline/usuário Flyway separado com DDL e permissões de extensão. Gerir segredos fora do Git/cofre.
- **Impede implantação:** **Sim** para produção; não impede tecnicamente uma migration local autorizada, mas não usar o superuser como runtime de produção.

### H-03 — O teste de contexto pode executar Flyway contra datasource arbitrário

- **Severidade:** ALTO
- **Localização:** `src/test/java/br/com/arena/ArenaApplicationTests.java:6–11`; `src/main/resources/application.properties:2–8`.
- **Problema observado:** `ArenaApplicationTests` sobe o contexto sem guarda de URL/perfil; Flyway está habilitado e a URL vem de `SPRING_DATASOURCE_URL`. Os testes PostgreSQL especializados têm guarda `TEST_POSTGRES_URL`, mas essa classe não.
- **Consequência prática:** `mvn test` com variáveis de uma configuração IntelliJ apontando a `arena` ou outro banco pode tentar aplicar migrations nesse destino.
- **Evidência:** código e configuração inspecionados; docs17 também registra essa limitação. Maven não foi executado nesta auditoria.
- **Correção recomendada:** tornar todo teste de contexto explicitamente dependente do datasource descartável, adicionar guarda antes da inicialização/Flyway e separar testes de contexto seguros dos testes integrados.
- **Impede implantação:** **Não diretamente**, mas impede uma rotina de testes/CI segura até correção.

### H-04 — Não há bootstrap implementado para o primeiro ADMIN

- **Severidade:** ALTO operacional
- **Localização:** `UsuarioService.salvar:53–65`; `UsuarioController:51–55`; V1 não insere usuário.
- **Problema observado:** cadastro público sempre grava CLIENTE; rotas de criação/gestão de quadras e operações administrativas exigem ADMIN. Não há seed/bootstrap na migration, command runner ou procedimento automatizado versionado.
- **Consequência prática:** em uma instalação vazia, não existe caminho pela API para criar o primeiro ADMIN; a operação exigiria intervenção externa ainda não especificada.
- **Evidência:** código atual e migration inspecionados.
- **Correção recomendada:** definir e ensaiar bootstrap único, controlado, auditável e fora do cadastro público; garantir rotação/retirada do mecanismo após uso e evitar promover usuário por SQL manual ad hoc.
- **Impede implantação:** **Sim** para operação funcional inicial até existir procedimento seguro.

### M-01 — Falhas do filtro JWT podem ser silenciosas e erros de segurança não têm contrato único

- **Severidade:** MÉDIO
- **Localização:** `JwtAuthenticationFilter.java:43–75`; `SecurityConfig.java:59–96`; `GlobalExceptionHandler.java`.
- **Problema observado:** filtro captura `Exception` ampla, limpa o contexto e continua, sem log/telemetria; não há `AuthenticationEntryPoint`/`AccessDeniedHandler` explícitos.
- **Consequência prática:** falha de carregamento de usuário/DB pode parecer token inválido; 401/403 da cadeia podem divergir do `ErrorResponse`.
- **Evidência:** inspeção estática. Os testes anteriores verificam algumas respostas/status com cadeia real, mas não todos os corpos 401/403 nem falha operacional no filtro.
- **Correção recomendada:** distinguir token inválido/expirado de indisponibilidade; responder 401/403 com contrato estável e registrar falhas operacionais sem logar token.
- **Impede implantação:** Não isoladamente; corrigir antes de uso público para melhorar segurança operacional.

### M-02 — Hardening de autenticação é limitado

- **Severidade:** MÉDIO
- **Localização:** `UsuarioRequestDTO.java:28–30`; `SecurityConfig.java`; `JwtService.java:17–33`.
- **Problema observado:** senha mínima de seis caracteres; não foi encontrada limitação de tentativas, bloqueio progressivo, revogação de token após troca de senha nem validação de validade positiva/força da chave no startup.
- **Consequência prática:** aumenta risco de brute force e erro de configuração; tokens emitidos permanecem utilizáveis até expirar.
- **Evidência:** DTO/serviços/configuração inspecionados. A chave/expiração reais não foram lidas; não se afirma que estejam fracas.
- **Correção recomendada:** política de senha/passphrase, rate limit no login/cadastro, validação startup de segredo forte e expiração positiva; decidir revogação/sessões conforme risco e validade.
- **Impede implantação:** Não por si só se houver controle de perímetro e segredo forte; hardening recomendado antes de exposição pública.

### M-03 — Campos opcionais podem chegar como violações NOT NULL

- **Severidade:** MÉDIO
- **Localização:** `QuadraRequestDTO.java:10–15`; `UsuarioRequestDTO.java:32`; `UsuarioService.java:68–84`; constraints V1.
- **Problema observado:** `tipo` de quadra não é `@NotNull`; `perfil` no DTO de usuário também não. Atualização ADMIN atribui `dto.getPerfil()` diretamente, apesar de coluna/check não nulos.
- **Consequência prática:** JSON incompleto pode virar violação de integridade e HTTP 500 genérico em vez de 400 de validação.
- **Evidência:** DTO, service, V1 e handler inspecionados.
- **Correção recomendada:** definir semântica de PUT (substituição completa vs parcial); validar os campos obrigatórios ou separar DTO de atualização parcial; mapear conflito de e-mail único com tratamento específico sem converter todas as integridades para 409.
- **Impede implantação:** Não, mas causa erros de contrato e suporte.

### M-04 — Contratos de cliente não cobrem o ciclo completo de consulta

- **Severidade:** MÉDIO funcional
- **Localização:** `AgendamentoController.java`; `AgendamentoRequestDTO.java`; `QuadraService.listarTodas`; README.
- **Problema observado:** não há GET de reservas próprias nem disponibilidade; GET de quadras retorna inativas; criação exige `usuarioId` mesmo para CLIENTE, embora o serviço use o principal.
- **Consequência prática:** frontend não consegue listar reservas sem conhecer IDs e precisa tratar indisponibilidade por tentativa/409; pode exibir quadra inativa se não filtrar `ativa`.
- **Evidência:** controllers, DTOs e serviço.
- **Correção recomendada:** definir contratos de `/agendamentos/me` e disponibilidade/filtragem; simplificar DTO de cliente sem `usuarioId` ou documentar claramente o campo ignorado.
- **Impede implantação:** Não para endpoints administrativos/MVP; impede concluir uma experiência de reserva do cliente sem decisões de contrato.

### M-05 — README/OpenAPI não refletem o backend atual

- **Severidade:** MÉDIO
- **Localização:** `README.md:5–7,21–28,41–50,81–90,113–123`; `OpenApiConfig.java:45–54`; controllers.
- **Problema observado:** README cita Boot 3, Auth0, ModelMapper/MapStruct, `/auth/register`, quadras apenas ativas e `ddl-auto=update`; código usa Boot 4.0.7, JJWT, mapeadores manuais, `/usuarios`, inclui quadras inativas e `validate`/Flyway. OpenAPI aplica bearer global a login/cadastro públicos.
- **Consequência prática:** onboarding e geradores/consumidores podem usar endpoint, biblioteca ou configuração errados; Swagger representa mal a autenticação.
- **Evidência:** README comparado diretamente a `pom.xml`, properties e rotas.
- **Correção recomendada:** atualizar README e especificação por endpoint, incluindo permissões, DTOs, status inativa, erros e configuração por variáveis.
- **Impede implantação:** Não por código, mas é bloqueio de documentação/runbook confiável.

### M-06 — CORS não está definido

- **Severidade:** MÉDIO, condicional à topologia do frontend
- **Localização:** `SecurityConfig.java`; ausência de `CorsConfigurationSource`/`WebMvcConfigurer`/`@CrossOrigin`.
- **Problema observado:** não há allowlist de origens/headers/métodos no backend.
- **Consequência prática:** browser em outra origem pode falhar preflight; consumidor server-to-server ou frontend same-origin não depende disso.
- **Evidência:** busca estática no código. Não foi feito teste de navegador.
- **Correção recomendada:** definir origens de desenvolvimento/produção quando domínio do frontend for conhecido; permitir somente métodos/headers necessários e manter CSRF coerente com Bearer.
- **Impede implantação:** Só se frontend browser estiver em origem diferente da API.

### M-07 — Listagens sem paginação e potencial de N+1

- **Severidade:** MÉDIO de escalabilidade
- **Localização:** `AgendamentoService.listarTodos`, `QuadraService.listarTodas`, `UsuarioService.listarTodos`; `Agendamento.usuario/quadra`.
- **Problema observado:** coleções chamam `findAll()` e retornam listas completas; ManyToOne usa fetch padrão EAGER e o mapper lê nomes relacionados.
- **Consequência prática:** memória, latência e consultas podem crescer com o histórico/quantidade de usuários; N+1 é risco, não medido.
- **Evidência:** implementação estática; não houve profiling ou volume representativo.
- **Correção recomendada:** medir SQL e volume; introduzir paginação/ordenação e projeções/entity graph se necessário.
- **Impede implantação:** Não para baixo volume, mas precisa de limites antes de crescer.

### F-01 — Concorrência de edição cliente sem token de versão

- **Severidade:** FUTURO conhecido
- **Localização:** `Agendamento.java` não tem `@Version`; PUT não recebe ETag/versão esperada.
- **Problema observado:** locks serializam as transações, mas duas PUT válidas podem seguir política last-writer-wins; o servidor não detecta intenção baseada em um formulário obsoleto.
- **Consequência prática:** o último PUT pode substituir a edição anterior, embora não haja gravação simultânea de entidade JPA obsoleta.
- **Evidência:** documentação 19/20 e DTO atual.
- **Correção recomendada:** decidir política de atualização concorrente da UI e adicionar versão/ETag/If-Match com resposta de conflito, se exigido pelo produto.
- **Impede implantação:** Não segundo o escopo atual; limitação conhecida e planejada.

### F-02 — Igualdade/toString de entidades e auditoria de estados

- **Severidade:** FUTURO
- **Localização:** `Usuario`, `Quadra` e `Agendamento` usam Lombok `@Data`; não há tabela de eventos de status.
- **Problema observado:** métodos gerados podem percorrer relações bidirecionais e `Usuario.toString()` inclui o hash `senha`; a busca não encontrou uso explícito de `toString` em logs. O banco preserva a linha da reserva, mas guarda somente status atual, não ator/motivo/histórico.
- **Consequência prática:** risco se entidades forem logadas/comparadas; ausência de trilha detalhada limita auditoria, caso isso passe a ser requisito.
- **Evidência:** anotações e relações inspecionadas; ausência de uso de toString procurada no código.
- **Correção recomendada:** excluir credenciais/associações de `toString` e avaliar igualdade JPA; criar auditoria de transições somente se requisito de negócio/legal for aprovado.
- **Impede implantação:** Não no escopo atual; nenhum vazamento ou exigência de auditoria foi comprovado.

## 13. Melhorias futuras já aprovadas

- `@Version`/ETag e política para PUTs de clientes com visão obsoleta.
- Separação operacional de credenciais runtime e Flyway, grants mínimos e rotação.
- Bootstrap seguro, auditável e de uso único do primeiro ADMIN; proteção contra perda do último administrador.
- Rate limit de autenticação e eventual revogação de JWT.
- API de reservas próprias/disponibilidade, paginação e CORS conforme a implantação do frontend.
- Histórico de transições se necessário; health checks/métricas/log correlation para a aplicação.

Esses itens não foram implementados nesta auditoria. Melhoria futura não é automaticamente defeito nem bloqueio; os bloqueadores atuais estão separados em H-01 a H-04.

## 14. Avaliação de prontidão e plano de correções

### O que está suficientemente estável

- Separação básica em camadas e DTOs de resposta sem senha.
- Cadastro público força CLIENTE; alteração de perfil exige ADMIN; perfil próprio não muda o próprio papel.
- Regras principais de ciclo de vida, lock Quadra → Agendamento(s), relógio PostgreSQL e EXCLUDE estão presentes no código e têm testes PostgreSQL documentados.
- V1 combina FK/checks e EXCLUDE parcial; a inspeção atual de `arena` não encontrou tabelas nem histórico Flyway.
- `.env.example` é placeholder, `.env` é ignorado e não foram encontrados segredos literais no código/configuração inspecionados.

### Ordem recomendada

1. **Release/reprodutibilidade (H-01):** revisar e versionar os arquivos de produção/migration/configuração intencionais; validar em checkout limpo.
2. **Segurança operacional (H-02/H-04):** definir runtime e Flyway com papéis separados e privilégios mínimos; provisionar primeiro ADMIN por bootstrap formal, nunca pelo signup público.
3. **Isolamento de testes (H-03):** impedir que `ArenaApplicationTests` use datasource arbitrário; executar CI somente com banco descartável explícito e guardado.
4. **Contratos e erros (M-01/M-03/M-05):** uniformizar respostas 401/403, validação obrigatória e documentação; mapear conflitos de unicidade sem mascarar outras integridades.
5. **Frontend/produção (M-04/M-06):** congelar endpoints de coleção/disponibilidade e definir CORS/origens antes da integração browser.
6. **Escala/hardening (M-02/M-07):** rate limit, política de senha/segredo, paginação e medição de consultas.

### Prontidão

- **Implantação em produção:** **não pronta** até resolver H-01–H-04 e configurar secrets/TLS/roles conforme o ambiente.
- **V1 em `arena`:** o schema vazio observado é compatível com inicialização; a extensão está disponível e a role atual é superuser. Isso não substitui backup, validação do alvo, usuário Flyway dedicado, runbook e autorização operacional. V1 não foi aplicada nesta auditoria.
- **Início do frontend:** possível para protótipo/admin após congelar contratos, mas o ciclo completo de reserva do cliente precisa de lista própria, disponibilidade, CORS se cross-origin e OpenAPI/README corretos.

## 15. Itens não verificáveis nesta auditoria

- Nenhum build, Maven test, migração ou validação Hibernate foi executado. Os resultados **49 testes, 0 falhas/erros** constam em `docs/20`; são resultados anteriores documentados, não reproduzidos nesta auditoria.
- Não foi feito pentest, análise dinâmica da cadeia de autenticação, browser/CORS, scan de dependências/CVEs, profiling/N+1, teste de carga ou teste do deploy real.
- Não foram inspecionados `.env`, variáveis `SPRING_DATASOURCE_*` do processo de produção, ACL do Windows/OneDrive, TLS/proxy, firewall/secret manager ou qualquer ambiente cloud.
- A consulta ao banco foi somente leitura. Nenhuma tabela/dado foi alterado e V1 não foi aplicada.
