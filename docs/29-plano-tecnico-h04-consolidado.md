# Etapa 8 — plano técnico H-04 consolidado

**Status:** planejamento técnico revisado. Não é implementação, migration,
execução de testes ou autorização de implantação.

## 1. Resumo executivo

H-04 ainda existe no código atual: não há bootstrap do primeiro ADMIN nem
proteção contra remover o último. O signup força CLIENTE, mas o DTO expõe
`perfil`; `PUT /usuarios/{id}` altera dados, senha e perfil; e o serviço não
serializa as operações cross-row.

O desenho consolidado estabelece:

- CLI offline, one-shot, para bootstrap e comando distinto para recovery;
- estado singleton com `admin_count`, marcador permanente de bootstrap e
  `recovery_required`;
- um único advisory transaction lock em API, procedures, triggers e CLI;
- triggers para manter contador e impedir zero no estado ativo;
- autorização ADMIN na aplicação e função de banco, sem tratar GUC como
  identidade;
- auditoria append-only e transacional;
- V3/V4/V5 aplicadas com todas as versões antigas drenadas;
- senhas novas/alteradas com mínimo de 12 e reset obrigatório para contas
  existentes.

**Invariante central:** zero ADMIN é válido em PENDING e RECOVERY_REQUIRED;
em CONSUMED/ativo, deve haver ao menos um. Estado inconsistente não autoriza
bootstrap/recovery automático: falha fechado e exige reconciliação autorizada.

## 2. Decisões aprovadas incorporadas

1. Criar `arena_admin_writer` e `arena_offline_operator`, sem substituir
   `arena_runtime`, `arena_migrator` ou o provisionador.
2. Todos os usuários existentes redefinem a senha na primeira utilização após
   a nova política; mínimo de 12 caracteres para toda senha nova/alterada.
3. A identidade do operador vem do ambiente autorizado e é registrada. Nome
   digitado não prova identidade; SSO/IAM pode ser integrado futuramente.
4. Signup não aceita perfil e sempre cria CLIENTE.
5. Perfil só muda por `PATCH /usuarios/{id}/perfil`, exclusivo de ADMIN.
6. Autorrebaixamento/autoexclusão somente se outro ADMIN permanecer. Operação
   que deixaria zero ADMIN retorna HTTP 409.
7. Bootstrap e recovery são comandos separados, offline e auditados.
8. Último ADMIN protegido na aplicação e no PostgreSQL.
9. Auditoria append-only para runtime, sem senha, hash, JWT ou segredo.
10. Preservar H-02/H-03; testes de banco apenas em PostgreSQL descartável.

## 3. Estado atual conferido

- `Usuario.perfil` é enum textual ADMIN/CLIENTE; `isEnabled()` sempre retorna
  true.
- `UsuarioRequestDTO` inclui `perfil` e senha com mínimo atual de 6 caracteres,
  compartilhado em cadastro/atualizações.
- Mapper ignora `perfil` e `UsuarioService.salvar` força CLIENTE. O signup atual
  não promove por `"perfil":"ADMIN"`.
- `UsuarioService.atualizar` atribui perfil e senha do DTO; `deletar` só verifica
  agendamentos. Não há `@Transactional` delimitando essas operações compostas,
  count ou lock de ADMIN no repository.
- `@PreAuthorize` protege endpoints atuais, mas não os métodos públicos do
  service isoladamente. JWT tem email no subject e o filtro recarrega authorities
  do banco em cada request.
- V1 restringe perfis, sem seed/cardinalidade. V2 dá DML table-level em
  `usuario` ao runtime e default DML em tabelas/sequences futuras de `public`.
- Spring Boot 4.0.7, Java 21, WebMVC/Security/JPA/Flyway/JJWT; sem framework CLI.
  `ArenaApplication` habilita scheduling e `SecurityConfig` configura
  `HttpSecurity` sem condição não-web.
- Não há testes dedicados a cadastro/gestão de usuário/bootstrap.

Nenhum banco real foi consultado para inferir o número de ADMINs.

## 4. Invariante `admin_count` por estado

O contador é o número real de linhas `usuario` com perfil ADMIN, mantido
transacionalmente. A invariante é definida no **estado confirmado no commit**:

| Estado | Marcadores e contagem | Operações permitidas |
|---|---|---|
| **PENDING** | `bootstrap_consumed=false`, `recovery_required=false`, `admin_count=0`, count real=0 | Zero ADMIN é legítimo. Só bootstrap autorizado cria o primeiro ADMIN. Operações normais não alteram marcador nem perfil para ADMIN. |
| **RECOVERY_REQUIRED** | `bootstrap_consumed=true`, `recovery_required=true`, `admin_count=0`, count real=0 | Zero ADMIN é legítimo. Somente recovery offline autorizado pode restaurar ADMIN. Operações normais não exploram esse estado. |
| **CONSUMED/ativo** | `bootstrap_consumed=true`, `recovery_required=false`, `admin_count=count real>=1` | Nenhuma operação normal pode confirmar com zero ADMIN. Rebaixamento/exclusão do último é recusado. |
| **Inconsistente** | Marcadores/counts incompatíveis, singleton ausente/duplicado ou `admin_count != count real` | Fail closed. Não executar bootstrap/recovery automaticamente; exige reconciliação autorizada e auditada. |

Portanto, **não** exigir `admin_count > 0` globalmente: isso impediria
instalação inicial e recovery. Exigir zero somente em PENDING/RECOVERY_REQUIRED
e count positivo em CONSUMED/ativo. Nenhum commit pode deixar uma combinação
intermediária ou inconsistente.

Tabela singleton proposta:

| Campo conceitual | Regra |
|---|---|
| `singleton_id` | SMALLINT PK + CHECK igual a 1; uma linha é semeada por V3. |
| `admin_count` | BIGINT NOT NULL CHECK `>=0`; alterado apenas pelas triggers. |
| `bootstrap_consumed` | Boolean one-shot; false apenas para novo install explicitamente classificado. |
| `bootstrap_consumed_at` | Timestamp do consumo/fechamento de bootstrap. |
| `recovery_required` | Boolean independente, usado para legado sem ADMIN. |
| `last_recovery_at` | Timestamp da recovery confirmada. |
| `updated_at` | Clock PostgreSQL. |

PK/CHECK impedem mais de uma linha, mas não sua ausência. V3 insere e verifica
uma linha; runtime não tem DML nela, trigger bloqueia DELETE/chave, funções
falham fechado se faltar.

Como CHECK não valida contagem cross-row, V3 também instala validação
transacional diferida (`DEFERRABLE INITIALLY DEFERRED`) para operações que
alteram perfil/contador. No fim da transação ela compara singleton com
`COUNT(ADMIN)` e valida a combinação final da tabela de estados. Assim:

- PENDING pode commit count=0 somente sem ADMIN e com marker pendente;
- RECOVERY_REQUIRED pode commit count=0 somente com bootstrap consumido e sem
  ADMIN;
- CONSUMED/ativo só commit com count=COUNT(ADMIN)>=1;
- qualquer combinação inconsistente aborta o commit e reverte a transação.

A validação diferida permite que bootstrap insira ADMIN, a trigger incremente o
count e a função consuma o marker em instruções separadas dentro de uma única
transação, sem estado intermediário confirmado. V3 também valida o estado
inicial antes de commit.

### Gate de transições de perfil no PostgreSQL

V3 adiciona validação de `session_user` nas transições de perfil, sem usar GUC:

- `arena_runtime` pode inserir somente CLIENTE e não pode alterar `perfil` de
  uma linha existente. Em INSERT/UPDATE antigo que lista `perfil` mas conserva o
  mesmo valor, o trigger permite o no-op; mudança efetiva é rejeitada. DELETE
  de `usuario` pelo runtime também é rejeitado.
- `arena_admin_writer` só altera perfil por funções SECURITY DEFINER de
  allowlist; actor_id deve ser revalidado como ADMIN sob lock.
- `arena_offline_operator` só cria/promove ADMIN pelas funções de bootstrap/
  recovery correspondentes ao estado.
- `arena_migrator` não executa operações de negócio sobre `usuario.perfil`;
  ownership/DDL permanece confiado à implantação.

Isso protege o intervalo em que V3 já instalou triggers mas V4 ainda está
aplicando column grants: o GRANT table-level herdado de V2 não permite ao
runtime usar um perfil de sessão inválido para escalada. V4 retira o privilégio
de tabela e deixa a ACL coerente com o gate. Durante esse intervalo, a
manutenção continua obrigatória e nenhuma versão antiga atende requests.

## 5. A — lock, triggers e concorrência

### 5.1 Uma chave comum e ordem fixa

Todas as operações de lifecycle usam a mesma `pg_advisory_xact_lock`, com chave
reservada/documentada:

```text
advisory xact lock
 → validar/reler ator, alvo e estado
 → mutar usuario
 → atualizar contador
 → gravar auditoria
 → commit
```

API, funções, bootstrap, recovery, reconciliation e triggers usam a mesma
chave. Nenhum caminho adquire lock de usuário/estado antes dela. Conexões
distintas usam a mesma chave no mesmo PostgreSQL; locks locais de JVM não são
suficientes. Transação `READ COMMITTED`; funções mutáveis `VOLATILE`; não
executar por uma transação externa `REPEATABLE READ` com snapshot antigo.

### 5.2 Statement-level, multi-row e UPSERT

V3 instala triggers `BEFORE STATEMENT` para INSERT, UPDATE e DELETE em
`usuario`; cada uma adquire a mesma chave antes de processar linhas. A função
writer/CLI toma a chave primeiro; o lock transacional repetido pela trigger na
mesma transação é reentrante.

O contador muda em trigger AFTER ROW somente pelo resultado efetivo:

- INSERT CLIENTE ou DELETE CLIENTE: delta zero;
- INSERT ADMIN e CLIENTE→ADMIN: `+1`;
- ADMIN→CLIENTE e DELETE ADMIN: `-1` somente se `admin_count > 1`;
- UPDATE sem mudança de perfil: delta zero.

Nunca incrementar em BEFORE INSERT: com `INSERT ... ON CONFLICT DO UPDATE`, a
linha pode não ser inserida. AFTER INSERT conta a inserção real; ramo de conflito
usa OLD/NEW no AFTER UPDATE; DO NOTHING não altera contador. Triggers statement
de INSERT e UPDATE podem ambas adquirir a mesma chave, sem criar uma segunda
ordem de lock. Em multi-row, uma tentativa inválida aborta a instrução toda e
reverte os deltas anteriores.

O decremento é condicional/atômico na linha singleton. Se count=1, o
decremento não atualiza linha e o trigger rejeita. Função de aplicação detecta
o caso sob o lock **antes** da mutação e retorna resultado LAST_ADMIN para
auditar e mapear em 409; trigger fica como barreira final para DML. Triggers,
usuario, marcador/state e audit participam da mesma transação.

Deadlocks: uma chave global, ordem advisory→alvo→state/audit, nenhuma segunda
chave de lifecycle. TRUNCATE não aciona row triggers: roles de app/writer/
offline não recebem TRUNCATE; V3 o rejeita ou restringe explicitamente. A
serialização uniforme também serializa signup e alterações pessoais de
`usuario`; é um custo assumido para o volume MVP.

## 6. B — bootstrap, recovery e reconciliação sem responsabilidade duplicada

### 6.1 Único owner da transição do marcador

**A trigger não modifica `bootstrap_consumed`.** Um helper interno
`consume_bootstrap_marker` é o único writer do marcador. Ele só pode ser chamado
por:

- `bootstrap_first_admin`, após inserir o primeiro ADMIN em PENDING; e
- `reconcile-admin-state`, excepcionalmente, se o estado está PENDING mas há
  ADMIN real, depois de validar count/tabela, obter autorização operacional e
  gravar RECONCILE.

Runtime/writer/offline não recebem EXECUTE direto no helper; usam apenas as
funções de operação correspondentes. A função bootstrap só atua quando:

- `bootstrap_consumed=false`;
- `admin_count=0` e count real=0;
- `recovery_required=false`;
- novo install foi explicitamente classificado;
- dados/email passam validação.

Dentro da mesma transação: inserir ADMIN, trigger incrementa count, o helper
consome marcador/timestamp e grava evento BOOTSTRAP. Qualquer falha reverte
tudo. Promoção normal, trigger e recovery não reabrem/nem consomem o marcador.

### 6.2 Recovery normal: condições exatas

Recovery normal só pode criar/promover ADMIN se **todas** forem verdadeiras:

```text
admin_count = 0
AND recovery_required = true
AND bootstrap_consumed = true
AND COUNT(usuario WHERE perfil = ADMIN) = 0
```

Com `admin_count=0` e `recovery_required=false`, a recovery normal recusa e
indica `reconcile-admin-state`. Não corrigir flag/count silenciosamente. Com
bootstrap ainda PENDING, indicar bootstrap, não recovery.

Sob lock e identidade de operador verificada, a recovery promove CLIENTE
validado ou cria conta; trigger incrementa count; função limpa
`recovery_required`, grava `last_recovery_at` e evento RECOVERY. Mantém
`bootstrap_consumed=true` e timestamp bootstrap inalterado. O commit final é
CONSUMED/ativo com count real positivo.

### 6.3 Estado inconsistente

Combinação fora dos quatro estados da seção 4, count divergente, linha
singleton ausente/duplicada ou trigger/função ausente resulta em fail closed.
`reconcile-admin-state` é comando offline separado, com operador autorizado,
justificativa/ticket e audit:

- adquire o mesmo lock de manutenção;
- calcula estado real e compara com contador/marcadores;
- ajusta apenas estado/counter para refletir as linhas, sem criar/apagar usuário;
- se existe ADMIN e marcador ainda false por corrupção, consome/fecha marker
  pelo caminho de reconciliação explicitamente auditado; não reabre marker;
- se count real zero e bootstrap já consumido, marca recovery_required;
- se audit não pode ser gravada, não efetua reparo e exige procedimento DBA/log
  externo aprovado.

Recovery normal nunca chama reconciliação automaticamente. O operador executa
reconcile, revisa o evento/estado e depois chama recovery em outro comando.

## 7. C — SECURITY DEFINER e owner

### 7.1 Decisão fechada desta implementação

`arena_migrator` será owner das funções SECURITY DEFINER. Não criar
`arena_admin_fn_owner` nesta etapa e não adicionar memberships/`SET ROLE` para
esse fim. Isso preserva a separação H-02 e o teste de ausência de memberships.

Proteções obrigatórias:

- `REVOKE EXECUTE FROM PUBLIC`; conceder EXECUTE somente a cada role necessário;
- `search_path` fixo/seguro e todas as tabelas/sequences qualificadas;
- SQL estático; evitar SQL dinâmico;
- funções pequenas, específicas, argumentos tipados/validados, sem executor
  genérico de SQL;
- auditoria/revisão do corpo e testes de ACL/privilégios efetivos;
- nenhum DML/DDL/membership para runtime, writer ou offline operator além do
  EXECUTE explicitamente necessário.

Limite: SECURITY DEFINER com owner migrator herda authority do owner/migrator,
que é trusted para DDL e possui objetos V1. Mitigar com allowlists e funções
restritas; não afirmar que o owner é least-privilege equivalente a NOLOGIN.

### 7.2 Possível owner NOLOGIN no futuro

Um `arena_admin_fn_owner NOLOGIN` reduziria blast radius e poderia receber só
SELECT necessário em usuario, operações de coluna requeridas, DML do state e
INSERT audit. Flyway como arena_migrator não pode criar/alterar funções com
esse owner sem membership SET ou transferência de ownership externa. Qualquer
uma altera o baseline H-02/reprodutibilidade. Só reavaliar em etapa futura,
com nova decisão técnica e revisão dos testes de isolamento.

Relação final atual: migrator é owner; admin_writer/offline têm EXECUTE
allowlist e não são membros; runtime não tem EXECUTE administrativo nem
membership. Provisionador cria roles, migrator não tem CREATEROLE.

## 8. D — implantação V3/V4/V5 sem janela incompleta

Sequência obrigatória:

1. Provisionar e validar roles writer/offline; backup verificado.
2. Parar/drenar todas as instâncias antigas, workers, writers e transações
   longas; fechar ingress e restringir tráfego ao executor de migration.
3. Flyway V3→V4→V5 roda na mesma janela de maintenance; nenhuma versão antiga
   atende durante a janela. Se auto-Flyway no startup, usar apenas binário novo,
   com readiness/ingress fechado até completar tudo.
4. Validar count real/singleton, estados, triggers/functions, ACL/default ACL,
   marker e flags de senha. Qualquer divergência mantém sistema fechado.
5. Bootstrap/recovery CLI offline conforme estado, audit/hash/perfil verificados.
6. Subir somente versão nova, smoke test e depois reabrir tráfego.

V3 protege count mesmo durante sua migration, mas V2 grants amplos podem deixar
profile DML legado possível até V4. Por isso ingress/processos antigos ficam
parados; não há janela servida. Se V3 commitou e V4/V5 falhou, não subir binário
antigo: reparar por forward migration ou restore coordenado. Trigger em DML raw
gera rollback; log durável da tentativa exige PostgreSQL server logs/pgAudit se
disponível; não se afirma que pgAudit está instalado.

V3 usa DDL transacional e `LOCK TABLE usuario` para backfill. Se falhar antes
do commit, reverte. Roles globais provisionadas sobrevivem e não são apagadas
automaticamente. Depois do commit, preservar audit/state/Flyway history e
corrigir para frente.

## 9. Fluxos finais

### 9.1 Bootstrap CLI

Spring Boot 4.0.7 não tem CLI framework. `AdminCliApplication` estreita inicia
com `WebApplicationType.NONE`, sem carregar controllers, HttpSecurity, JWT,
OpenAPI ou scheduling; usa JDBC, PasswordEncoder comum e
`arena_offline_operator`. Flyway fica disabled e a CLI só prossegue se V3+
estiver aplicada. Contexto/pool fecham e runner retorna exit code.

Operador autentica-se pelo ambiente autorizado (runner/identidade de
workload/host controlado), registra ticket não secreto e insere nome/email
validados. Senha via prompt oculto/secret manager/STDIN sem echo, mínimo 12;
sem canal seguro aborta. Só hash sai da memória para a função. Nenhum segredo
em argv/log/audit.

A função bootstrap exige o estado PENDING da seção 4, adquire lock, cria ADMIN,
deixa trigger contar, consome marcador e audita atomicamente. Segunda execução
é recusada. Nunca expor endpoint HTTP de bootstrap.

### 9.2 Recovery CLI

Comando distinto; offline only, identidade verificável, motivo/ticket e
confirmação explícita. Recovery normal exige as quatro precondições da seção
6.2. Zero ADMIN com recovery_required=false recusa e direciona para reconcile.
Recovery não altera bootstrap marker; cria/promove alvo verificado, senha >=12,
trigger incrementa, limpa recovery_required, grava RECOVERY e commit.

Estado inconsistente exige reconcile autorizado/auditado primeiro; recovery
nunca “corrige” contador/marker e nunca é chamado por HTTP.

## 10. Migrations, grants e default privileges

V3: `admin_lifecycle_state`, `admin_audit_event`, functions/triggers,
backfill e grants internos. V4: remover grants table-level V2 em usuario,
grants por coluna/função, DEFAULT CLIENTE, EXECUTE allowlist e ACL append-only.
V5: `password_change_required`/`credential_version`, reset forçado.

V2 concede DML e default privileges DML para novas tabelas/sequences em public.
V3 revoga ACL herdada de state/audit. V4 remove defaults genéricos ou deixa
grants explícitos em cada objeto de negócio; não depender de lembrar REVOKE
caso a caso.

Matriz:

| Role | ACL |
|---|---|
| `arena_runtime` | CONNECT/USAGE, SELECT usuario, INSERT sem perfil/default CLIENTE, UPDATE colunas pessoais sem perfil, EXECUTE append audit limitado; sem DELETE usuario, profile DML, state DML ou audit UPDATE/DELETE/TRUNCATE. |
| `arena_migrator` | Flyway/DDL/owner SECURITY DEFINER no baseline; sem SUPERUSER/CREATEDB/CREATEROLE/membership. |
| Provisionador | Roles/dados globais, fora da aplicação/testes. |
| `arena_admin_writer` | EXECUTE funções perfil/delete/reset, datasource web separado, sem DML/DDL direto/membership. |
| `arena_offline_operator` | EXECUTE bootstrap/recovery/reconcile, CLI only, sem DML/DDL/membership. |

Todos logins app/operator são NOINHERIT e sem atributos administrativos.
Runtime pode inserir evento apenas por função controlada, não por DML direto.
Hashes/credenciais do writer/offline vêm de secret manager; migrator não é
usado em API/CLI.

## 11. Auditoria append-only

Tabela `admin_audit_event`, owner migrator, sem FK destrutiva:

- event id, `TIMESTAMPTZ`, operação, outcome, source;
- ator HTTP (id do principal) ou operador verificável/role DB;
- alvo id/snapshot, perfil before/after, reason/ticket/request/correlation id;
- error code estável, sem body/stack trace/password/hash/token/secret.

Registra BOOTSTRAP, RECOVERY, PROMOTE, DEMOTE, ADMIN_DELETE, PASSWORD_RESET,
PASSWORD_CHANGE e RECONCILE. Índices por tempo, ator, alvo e operação. Snapshots
email/motivos são PII; definir retention/leitores.

Eventos de sucesso são escritos na mesma transação da operação. LAST_ADMIN
esperado retorna resultado da função, registra REJECTED e commit; aplicação
converte para 409 depois do commit. Falha técnica inesperada rollback. Trigger
raw DML que lança exception não consegue preservar insert de audit dentro da
mesma transaction; logs externos são necessários para essa tentativa.

Runtime não tem SELECT/UPDATE/DELETE/TRUNCATE na tabela. Função append limita
operação/campos; mesmo assim o banco não autentica a pessoa HTTP, e o ator
informado pela aplicação é uma claim. pgAudit/WORM não se afirma instalado.

## 12. Senhas, API e JWT de troca

DTO signup sem perfil; service força CLIENTE. `PUT /usuarios/me` e `PUT
/usuarios/{id}` (se mantido) tratam dados pessoais, sem senha/perfil.
`PATCH /usuarios/{id}/perfil` é ADMIN-only, actor do principal, body não decide
actor. Senha própria e reset ADMIN ficam em endpoints separados com min12 e
audit sem valor/hash.

V5 marca todas as contas existentes para reset, porque BCrypt não revela
comprimento original. Login valida senha antiga; se flagged, não emite JWT
normal, mas challenge de purpose/expiry/credential_version. Filtro concede
authority de troca somente no endpoint dedicado; `/auth/**` genérico não pode
permitir o challenge em outras rotas. Troca verifica versão, atualiza hash,
limpa flag e incrementa credential_version atomicamente; replay falha.

Definir regra de 12 caracteres Unicode/normalização e limite de bytes do BCrypt
antes de implementação. Se não atender, aprovar encoder alternativo. Não logar
entidades `Usuario` (`@Data.toString` inclui senha).

## 13. Plano de testes

- Bootstrap PENDING vazio, hash-only, marker uma vez, segunda execução e
  concorrência; rollback em cada ponto.
- Transação que altera usuario/count mas não conclui marker ou
  `recovery_required` falha na validação diferida antes do commit; PENDING e
  RECOVERY_REQUIRED válidos com count zero confirmam se suas precondições valem.
- Recovery com exatamente count=0/recovery_required=true/consumed=true; recusas
  em cada precondição; recovery_required=false exige reconcile; recovery não
  altera bootstrap marker.
- Estados CONSUMED/ativo mantêm count>0 e count real igual; estados PENDING e
  RECOVERY_REQUIRED aceitam zero; inconsistent não confirma.
- Cliente/admin HTTP, signup com perfil malicioso, PATCH perfil, self-demotion/
  self-delete, FK de agendamento e respostas 403/409.
- Senhas 11/12 em todo fluxo, legacy challenge, token scope e replay/version.
- INSERT/UPDATE/DELETE statement trigger; multi-row; `INSERT ... ON CONFLICT DO
  UPDATE` e DO NOTHING; rollback e counter final.
- Duas remoções com dois ADMINs, demotion+delete, promotion+demotion, bootstrap
  vs recovery em conexões/instâncias separadas; nunca zero no estado ativo.
- Runtime direct profile/insert-admin/delete/truncate/audit mutation negados.
  Em PG descartável, concessão DML temporária ao login writer de teste exercita
  a trigger diretamente; nunca conceder esse grant em produção.
- Writer/offline apenas executam funções; H-02 role attrs/membership e ACL V2/
  V4 conferidos em catálogos.
- Audit success/rejected append, atomicidade, campos, nenhum segredo; tentativas
  raw trigger-fail verificadas apenas por logs disponíveis.
- V3 backfill novo/legado com zero/um/vários; marker new com dados falha.
- PENDING com ADMIN real é inconsistente: bootstrap/recovery recusam; só
  reconcile autorizado consome o marcador via helper e grava RECONCILE.
- CLI não instancia WebServer/scheduler/Flyway e termina com exit code.

Todos os testes DB usam H-03: PostgreSQL descartável,
`127.0.0.1:<porta>/arena_flyway_test`, `tmpfs`, sem volume nomeado, TEST_* sem
fallback de produção; nunca `arena`, `arena_dev`, `arena_postgres_data`.
O initializer H-03 deve validar `TEST_H04_INITIAL_INSTALL` e credenciais
TEST_* próprias para writer/offline; não aceitar `H04_INITIAL_INSTALL`,
`SPRING_*` ou segredo de produção como fallback.

## 14. Implementação e aceite

Fases:

1. Confirmar source operacional do operador, marker de instalação, retenção/
   auditoria, reset legacy e janela de maintenance. Owner SECURITY DEFINER desta
   implementação já está decidido: `arena_migrator`.
2. Provisionar `arena_admin_writer`/`arena_offline_operator` com privilégio
   mínimo por provisionador; sem Spring/TEST roles em segredo de produção.
3. Implementar V3 state/audit/triggers; backfill e testes de transição.
4. Criar CLI minimal não web e bootstrap/recovery/reconcile.
5. Separar DTOs/endpoints, autorização service/controller, transação/lock e
   datasources; atualizar OpenAPI/README.
6. V4 grants: retirar DML amplo perfil/runtime, DEFAULT CLIENTE, ACL interna e
   EXECUTE allowlists.
7. V5 credential policy e challenge reset; forçar todas contas antigas.
8. Ensaiar migration, cutover, CLI, restore/forward, monitorar audit e liberar.

Critérios:

- PENDING/RECOVERY_REQUIRED podem confirmar zero; CONSUMED/ativo não.
- Bootstrap só em PENDING e é one-shot; recovery exige as quatro condições e
  não reabre bootstrap.
- Reconcile só offline, autorizado, auditado; inconsistent nunca se auto-corrige.
- Trigger/serviço/funções compartilham um lock e preservam count real.
- Signup sempre CLIENTE; profile admin API somente ADMIN; último ADMIN 409.
- Audit append-only runtime, sem secrets; rejected LAST_ADMIN persiste sem
  exception que faça rollback.
- Senhas 12 em todos set flows e contas legacy não recebem access JWT antes de
  reset.
- Roles testadas; H-02/H-03 intactos.
- V3/V4/V5 executadas sem instâncias antigas; suite completa passa em DB
  descartável; restore/recovery ensaiados.

## 15. Decisões técnicas pendentes

1. Qual identidade verificável concreta do ambiente (runner/host/IAM) será
   registrada como operador; SSO/IAM fica futuro.
2. Quem aprova/assina o placeholder não secreto de instalação nova e onde
   guardar evidência.
3. Retention, leitura de auditoria, export WORM/collector e observabilidade para
   tentativas SQL negadas; pgAudit não foi confirmado instalado.
4. Canal e suporte da troca forçada legacy; normalização, contagem Unicode e
   máximo BCrypt/encoder alternativo.
5. Janela e autorização de maintenance/restore para V3/V4/V5.
6. Quem autoriza reconciliation quando próprio state/audit está inconsistente.
Não há decisão de owner pendente para esta implementação: `arena_migrator` é o
owner SECURITY DEFINER e nenhuma membership nova é criada.
`arena_admin_fn_owner NOLOGIN` fica apenas como possibilidade futura, mediante
aprovação explícita e revisão das garantias/testes H-02.

## 16. Limites desta consolidação

Esta é uma referência de planejamento. Não declara implementação, testes,
validação de migrations ou prontidão de produção. Nenhum código, migration,
banco, container, `.env` ou histórico Git deve ser alterado até aprovação e
início explícito da fase de implementação.
