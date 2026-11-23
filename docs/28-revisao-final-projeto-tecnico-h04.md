# Etapa 8 — revisão final do projeto técnico H-04

**Status:** planejamento consolidado, não implementação. Nenhum código,
migration ou teste é produzido por este documento.

## 1. Resumo executivo

O H-04 continua presente: não há bootstrap do primeiro ADMIN, e as operações
atuais não impedem que o último administrador seja rebaixado ou excluído.
Signup força `CLIENTE`, mas o DTO expõe `perfil`; `PUT /usuarios/{id}` mistura
dados pessoais, senha e perfil; `UsuarioService` não delimita transação/lock
para uma invariante cross-row.

A arquitetura final recomendada:

1. CLI offline e one-shot para bootstrap e CLI distinto para recovery.
2. Estado PostgreSQL singleton com `admin_count`, marcador permanente de
   bootstrap e indicador independente `recovery_required`.
3. Uma única advisory transaction lock PostgreSQL em API, funções, triggers e
   CLI.
4. Triggers para cada DML relevante: statement-level para serialização e
   row-level AFTER para aplicar o delta real da contagem.
5. Serviço valida ator/admin e retorna 409 para conflitos esperados; PostgreSQL
   impede count abaixo de 1 em estado ativo.
6. `arena_admin_writer` isola DML privilegiado; `arena_offline_operator` é CLI
   only. `arena_runtime` não escreve `perfil`.
7. V3 cria estado/contagem/triggers/auditoria; V4 corta os grants amplos de V2;
   V5 implementa reset obrigatório de senhas existentes.
8. Migrações V3/V4/V5 ocorrem com versões antigas drenadas, ingress fechado e
   validação antes de liberar tráfego.

`arena_migrator` permanece o owner SECURITY DEFINER no baseline para preservar
o H-02 atual de ausência de memberships. A opção de owner `NOLOGIN` dedicado é
avaliada na seção 7, mas exige alterar essa garantia e não é adotada sem
aprovação.

## 2. Decisões aprovadas incorporadas

- Criar `arena_admin_writer` e `arena_offline_operator`, sem substituir
  `arena_runtime`, `arena_migrator` ou provisionador.
- Todos os usuários existentes redefinem senha na primeira utilização após a
  política. Senhas novas/alteradas têm mínimo de 12 caracteres.
- Operador é identificado por identidade verificável do ambiente autorizado;
  nome digitado manualmente não prova identidade. SSO/IAM pode ser integrado
  futuramente.
- Cadastro público não aceita perfil e sempre cria `CLIENTE`.
- Alteração de perfil é exclusiva de ADMIN em `PATCH /usuarios/{id}/perfil`.
- Autorrebaixamento e autoexclusão são permitidos somente se outro ADMIN
  permanecer. Ação que deixaria zero ADMIN retorna HTTP 409.
- Bootstrap e recovery são comandos offline distintos; recovery é autorizado,
  justificado e auditado.
- A proteção do último ADMIN existe na aplicação e no PostgreSQL.
- Auditoria é append-only para runtime, sem senhas, hashes, tokens ou segredos.
- Preservar H-02/H-03 e usar exclusivamente PostgreSQL descartável nos testes.

## 3. Estado atual verificado

O código, migrations, testes, dependências e docs 21/24/25/26/27 foram lidos.
Fatos relevantes:

- `Usuario.perfil` é enum textual `ADMIN`/`CLIENTE`; `Usuario.isEnabled()`
  sempre retorna true.
- `UsuarioRequestDTO` hoje contém `perfil` e senha mínima de seis caracteres;
  é compartilhado por cadastro e atualizações.
- O mapper não copia perfil para entidade; `UsuarioService.salvar` força
  `CLIENTE`. O signup não promove mesmo que receba perfil ADMIN.
- `UsuarioService.atualizar` atribui o perfil do DTO e senha; `atualizarProprioPerfil`
  não altera perfil, mas altera senha; `deletar` só verifica agendamentos.
- `UsuarioService` não delimita transação para essas sequências; repository não
  tem contagem ou lock de ADMIN.
- Controller exige ADMIN para `PUT/DELETE /usuarios/{id}`; service não valida o
  ator por si próprio. `/usuarios/me` usa o principal autenticado.
- JWT leva email no subject; o filtro recarrega usuário/authorities do banco.
- V1 restringe os valores do perfil, mas não semeia ADMIN nem impõe mínimo.
- V2 concede DML table-level em `usuario` a `arena_runtime` e default DML para
  tabelas/sequences futuras em `public`.
- Spring Boot é 4.0.7/Java 21, com WebMVC, Security, JPA, Validation,
  Flyway/PostgreSQL e JJWT; não há framework CLI.
- `ArenaApplication` habilita scheduling; `SecurityConfig` declara
  `SecurityFilterChain(HttpSecurity)` incondicionalmente.
- Não há teste específico de cadastro/administração de usuários.

Nenhum banco real foi consultado para saber quantos ADMINs há.

## 4. A — aquisição uniforme do lock e triggers

### 4.1 Lock comum e ordem

Reservar uma chave `pg_advisory_xact_lock` fixa/documentada para o lifecycle
ADMIN. Cada transação privilegiada segue:

```text
advisory xact lock
  → revalidar ator/alvo e estado
  → DML em usuario
  → delta do contador singleton
  → auditoria
  → commit
```

Serviço, stored functions, bootstrap/recovery CLI e triggers usam a mesma chave.
O lock é transaction-scoped, compartilhado entre instâncias e liberado em
commit/rollback. Não usar lock local de JVM nem outra chave/ordem. A transação
usa `READ COMMITTED`; funções mutáveis são `VOLATILE`, e leituras feitas após
espera pelo lock usam snapshot atual. Não iniciar lifecycle dentro de uma
transação externa `REPEATABLE READ` com snapshot antigo.

### 4.2 Statement-level, multi-row e UPSERT

V3 define triggers `BEFORE STATEMENT` de INSERT, UPDATE e DELETE em `usuario`.
Cada um adquire a mesma chave antes de processar rows; DML direto também entra
no protocolo. Triggers de statement disparados por INSERT e por
`ON CONFLICT DO UPDATE` podem pedir a mesma chave novamente na transação atual;
isso é reentrante e não cria uma segunda ordem de lock.

O contador muda em trigger `AFTER ROW`, somente pelo efeito real:

| Efeito efetivado | Delta |
|---|---:|
| INSERT CLIENTE | 0 |
| INSERT ADMIN ou CLIENTE→ADMIN | +1 |
| ADMIN→CLIENTE ou DELETE ADMIN | −1, condicionado a `admin_count > 1` |
| DELETE CLIENTE ou perfil inalterado | 0 |

Não incrementar em BEFORE INSERT: em UPSERT, o conflito pode seguir UPDATE e
não inserir. AFTER INSERT só conta linha inserida; o ramo de conflito usa
OLD/NEW no AFTER UPDATE; DO NOTHING não muda count. Multi-row roda na mesma
transação; se qualquer decremento inválido ocorrer, a instrução inteira e seus
deltas anteriores revertem.

Deadlock é evitado pela ordem única: advisory lock antes do lock de usuário ou
estado, sem segunda chave do lifecycle. As triggers BEFORE STATEMENT fazem a
mesma ordem para DML direto. O update condicional da linha singleton é atômico;
não usar `COUNT(*)` como verificação sem serialização.

Essa política serializa também signup e updates pessoais de `usuario`, não só
mudanças de perfil. É um custo explícito para o volume atual; caso o volume
cresça, otimizar apenas mantendo prova de que todo DML capaz de alterar perfil
usa a mesma chave. `TRUNCATE` não executa row triggers: revogar o privilégio e
rejeitar/limitar a operação também em nível statement.

## 5. Estado administrativo e invariante por estado

### 5.1 Singleton

V3 cria `admin_lifecycle_state`, com PK + CHECK em um único `singleton_id=1`,
e seed exatamente uma linha:

| Campo | Semântica |
|---|---|
| `admin_count BIGINT CHECK >=0` | Contagem transacional do trigger. |
| `bootstrap_consumed BOOLEAN` | Marcador one-shot; só passa de false para true uma vez. |
| `bootstrap_consumed_at TIMESTAMPTZ` | Hora de consumo/fechamento do marcador. |
| `recovery_required BOOLEAN` | Estado separado para legado sem ADMIN que precisa de recovery. |
| `last_recovery_at TIMESTAMPTZ` | Última recuperação confirmada. |
| `updated_at TIMESTAMPTZ` | Última alteração do estado. |

PK + CHECK garantem no máximo uma linha, não a existência. Migration semeia e
valida presença; grants runtime são revogados, trigger proíbe DELETE/alteração
da chave, e funções falham fechado se a linha não existir.

### 5.2 Estados e invariante

As invariantes são verificadas no limite do commit; estados intermediários da
transação de bootstrap/recovery não são visíveis a outras sessões:

| Estado | Valores | Regra permitida |
|---|---|---|
| **PENDING** | `bootstrap_consumed=false`, `recovery_required=false`, count=0 e count real=0 | Zero ADMIN é legítimo. Só `bootstrap_first_admin` cria o primeiro ADMIN e consome marcador. Operações normais de perfil não podem criar ADMIN. |
| **RECOVERY_REQUIRED** | `bootstrap_consumed=true`, `recovery_required=true`, count=0 e count real=0 | Zero ADMIN é legítimo. Só recovery CLI autorizado restaura um ADMIN. Operações normais não exploram esse estado. |
| **CONSUMED/ativo** | `bootstrap_consumed=true`, `recovery_required=false`, count≥1 e count real igual | Deve existir pelo menos um ADMIN. Rebaixar/excluir o último é negado. |
| **Inconsistente** | Combinação inválida, count divergente, row/trigger ausente | Falhar fechado; bootstrap/recovery normais não corrigem. Reconciliation separada, autorizada e auditada. |

Check constraint mantém count não negativo e coerência dos campos estáveis; as
funções/trigger validam as combinações completas. O desenho não usa CHECK
cross-row para afirmar mínimo de ADMIN.

### 5.3 Backfill V3 e classificação de instalação

V3 obtém lock exclusivo em `usuario`, valida valores, conta ADMINs e semeia:

- novo, explicitamente classificado e tabela vazia: PENDING;
- banco existente com um ou mais ADMINs: CONSUMED/ativo e count correspondente;
- banco legado com zero ADMIN: bootstrap fechado e RECOVERY_REQUIRED;
- marcador “novo” com usuário preexistente: abortar.

Default é “não novo”. `COUNT(*)=0` não prova database novo; todos os usuários
podem ter sido removidos antes de V3. Um placeholder Flyway não secreto, default
false, é uma afirmação operacional do pipeline; registrar quem aprovou. Se o
pipeline não puder provar/confirmar novo destino, usar recovery, não bootstrap.

## 6. B — ownership do bootstrap/recovery

Somente `bootstrap_first_admin` controla `bootstrap_consumed=false → true`:

1. toma o lock compartilhado;
2. exige PENDING, count do estado e count real ambos zero;
3. verifica email e input;
4. cria o ADMIN; trigger incrementa count;
5. função consome marcador, timestamp e evento BOOTSTRAP;
6. confirma estado consistente na mesma transação.

Trigger **nunca** altera marcador de bootstrap. Ela só mantém count e impede
decremento abaixo de um quando o estado está ativo. Promoção normal só é
possível por actor ADMIN e não muda o marker.

Recovery só roda com `bootstrap_consumed=true`, count real/estado zero e
`recovery_required=true`. Recovery restaura/promove ou cria ADMIN, trigger conta,
função limpa `recovery_required`, grava `last_recovery_at` e RECOVERY; marker
permanece true e timestamp de bootstrap não muda. O marcador nunca volta a
false.

Se count é zero e `recovery_required=false`, recovery normal recusa e indica
reconcile. Se `recovery_required=true` mas count real não é zero, também falha.
Reconcile não cria ADMIN: sob lock e aprovação operacional, corrige estado
consistente e audita; então recovery roda como comando separado.

## 7. C — SECURITY DEFINER e papéis de função

### 7.1 Decisão baseline que preserva H-02

Manter `arena_migrator` como owner das funções SECURITY DEFINER nesta
implementação. O role já é o executor Flyway, não tem `SUPERUSER/CREATEDB/
CREATEROLE`, e o H-02 atual exige zero memberships. Não conceder membership
nova; isso preserva a separação/expectativa do teste H-02.

O custo é que SECURITY DEFINER herda authority ampla do migrator, que é owner
de V1/usuario e tem DDL. Mitigações obrigatórias:

- `EXECUTE` revogado de PUBLIC, grants só para função/role necessário;
- `search_path` fixo e seguro, nomes totalmente qualificados;
- SQL estático, sem SQL dinâmico;
- funções curtas, allowlistadas, argumentos tipados/validados, revisão;
- nenhuma função genérica “executa SQL”;
- runtime/writer/offline não recebem DML de tabela nem membership em migrator.

### 7.2 NOLOGIN dedicado avaliado

`arena_admin_fn_owner NOLOGIN` poderia ser owner de state/audit/functions, com
somente os grants de usuário/estado/auditoria necessários e sem DDL de schema.
Isso reduz blast radius se a função tiver bug.

Para Flyway criar/alterar objetos com esse owner, migrator precisaria
`SET ROLE` membership ou um passo provisionador transferiria ownership fora
do Flyway. A membership muda a regra/teste H-02 e amplia o poder efetivo do
migrator; o passo externo reduz reprodutibilidade. Assim, **não criar esse role
nesta implementação**. Reavaliar somente com aprovação explícita para mudar a
garantia H-02 e seu teste.

### 7.3 Papéis de operação

- `arena_admin_writer`: login/pool web, EXECUTE somente funções de perfil,
  exclusão e reset de senha; sem DML direto/DDL/membership.
- `arena_offline_operator`: login usado apenas pelo CLI; EXECUTE bootstrap,
  recovery, reconcile; sem DML direto, DDL ou configuração web.
- Provisionador cria os logins globais; migrator não cria roles.
- `arena_runtime` não é member das roles privilegiadas. `session_user` pode
  limitar função ao login writer/offline, mas não identifica pessoa HTTP.
- Função writer recebe ator do SecurityContext e confirma no banco que ele
  ainda é ADMIN sob lock. Isso é autorização de aplicação e não uma prova
  criptográfica contra comprometimento do processo/credencial writer.

## 8. D — implantação V3/V4 sem janela incompleta

V3 mantém a invariante do contador, mas durante V2 grants amplos ainda podem
permitir profile DML via runtime. V4 remove esse caminho. Não deixar versão
antiga operar entre essas fases.

Sequência obrigatória:

1. Provisionar roles adicionais e validar ACLs sem disponibilizar credenciais
   ao cliente/conta runtime.
2. Drenar e parar todas as instâncias antigas, workers e writers; fechar ingress
   e esperar transações longas terminarem.
3. Fazer backup/snapshot verificado.
4. Executar V3/V4/V5 por Flyway/migrator em maintenance com ingress fechado.
   Se Flyway é aplicado pelo startup Spring, iniciar somente o binário novo e
   mantê-lo sem tráfego/readiness até todas as migrations terminarem.
5. Validar singleton, `admin_count=COUNT(ADMIN)`, bootstrap/recovery flags,
   triggers/functions, EXECUTE, column/default grants e ACL audit.
6. Executar bootstrap/recovery CLI offline de acordo com estado e validar audit/
   perfil/login.
7. Subir/liberar somente a versão nova; smoke tests cliente/admin; abrir ingress
   após aprovação.

Nenhuma versão antiga atende tráfego enquanto grants ou trigger são
incompletos. Se V3 commitou e V4 falhou, ingress fica fechado; corrigir por
migration forward/restore coordenado. Nunca reabrir a versão antiga com V2
grants amplos.

V3 falhando antes do commit: DDL/state transacional reverte. Roles cluster-wide
criadas pelo provisionador permanecem sem grants/uso e não são apagadas
automaticamente. Após commit, migrations são forward-only; audit/state não são
apagados para rollback.

## 9. Fluxos operacionais

### 9.1 Bootstrap

Launcher CLI separado com `WebApplicationType.NONE`; não inicia `ArenaApplication`
web, controllers, `HttpSecurity`, JWT, OpenAPI ou scheduler. `ApplicationRunner`
é do Boot 4.0.7; não é necessária dependência CLI nova. Contexto usa JDBC/
PasswordEncoder compartilhado, datasource `arena_offline_operator`, Flyway
disabled e exige funções V3 presentes. Fecha pool/contexto e retorna exit code.

Operador autenticado pelo ambiente de execução autorizado (runner/identidade de
workload/host controlado), change ticket não secreto, senha via
`Console.readPassword()` ou secret manager/STDIN sem echo. Sem canal seguro,
falha fechada. Senha >=12, codificada antes do DB; senha/hash nunca em argv,
log, request dump, SQL bind trace ou audit. Segunda execução é rejeitada pelo
marker persistente.

### 9.2 Recovery

Comando separado e fora de HTTP. Exige operador verificável, motivo, ticket e
confirmação explícita. Exige as precondições exatas da seção 6. Pode promover
CLIENTE ou criar novo ADMIN depois de validar o alvo; registra RECOVERY, ator,
alvo, source, motivo, timestamp; count e recovery_required mudam na mesma
transação. Bootstrap não reabre.

Se estado inconsistente, comando normal recusa; reconciliation é modo offline
separado, com aprovação/audit e sem criação automática do usuário. Se audit não
puder ser gravada, não prosseguir com recovery/reconcile.

## 10. Matriz de roles e grants finais

| Role | Grant/uso |
|---|---|
| `arena_runtime` | CONNECT/USAGE, SELECT usuario, INSERT só colunas sem perfil (DEFAULT CLIENTE), UPDATE colunas pessoais sem perfil; sem DELETE usuario, sem DML state/audit. EXECUTE append function allowlist, se mantida. |
| `arena_migrator` | Flyway/DDL e owner SECURITY DEFINER no baseline; sem superuser, createdb, createrole, membership. |
| Provisionador | Cria database/roles e grants, fora do Spring; nunca em testes/API. |
| `arena_admin_writer` | CONNECT/USAGE e EXECUTE de funções específicas; sem DML direto, DDL ou memberships. |
| `arena_offline_operator` | EXECUTE apenas bootstrap/recovery/reconcile no CLI; sem DML/DDL/membership/web config. |

V2 grants table-level precisam ser revogados antes de conceder por coluna.
JPA deve omitir `perfil` do INSERT/UPDATE runtime e o database default é
CLIENTE. Default privileges genéricos de tabela/sequence de V2 são removidos
em V4 ou neutralizados com REVOKE por tabela interna. Estado/audit são
owner-only; runtime não tem UPDATE/DELETE/TRUNCATE. Funções revogam EXECUTE de
PUBLIC e concedem só aos roles necessários. V1/V2 não são modificadas; novas
migrations são versionadas depois delas.

## 11. SECURITY DEFINER e auditoria

Owner baseline é `arena_migrator`, sem membership novo. Funções:

- `bootstrap_first_admin` só `arena_offline_operator`;
- `recover_admin`/`reconcile_admin_state` só offline operator;
- `admin_change_profile`, `admin_delete_user`, `admin_reset_password` só
  arena_admin_writer;
- append audit só roles explicitamente definidos.

Auditoria em tabela própria owner migrator: event id, `TIMESTAMPTZ`, operação,
outcome, source, actor type/id/ref, target id/snapshot, perfil antes/depois,
reason/ticket/request id/error code. Índices temporais/ator/alvo/operação.
Ids históricos sem FK destrutiva; snapshots PII com retenção aprovada.
Proibido registrar senha/hash/JWT/token/secret/body/SQL bind.

Sucessos gravam audit na mesma transação. Conflito previsível `LAST_ADMIN` é
resultado funcional, não exception SQL: função grava `REJECTED` e confirma só
audit, depois camada HTTP mapeia para 409. Falha técnica inesperada aborta tudo.
Trigger que rejeita DML raw por exception não pode deixar audit row nessa mesma
transação; log durável desse intento exige logs PostgreSQL/pgAudit se
instalados/configurados (não se presume que pgAudit exista).

Runtime não pode alterar/apagar eventos. Preferir append via função allowlist,
sem INSERT direto. Isso protege histórico existente, mas não torna actor HTTP
enviado pela aplicação criptograficamente verdadeiro. Para proteção contra
comprometimento total do runtime, exportar eventos para storage externo
append-only/WORM.

## 12. Senhas e API

| Endpoint | Regra |
|---|---|
| `POST /usuarios` | DTO sem perfil; grava CLIENTE. `perfil=ADMIN` extra nunca promove (400 ou ignorado+CLIENTE conforme contrato testado). |
| `PUT /usuarios/me` | Dados pessoais apenas; sem senha/perfil. |
| `PATCH /usuarios/me/senha` | Autenticado; valida senha atual e nova >=12. |
| `PUT /usuarios/{id}` | Se mantido, dados pessoais, sem senha/perfil. |
| `PATCH /usuarios/{id}/perfil` | ADMIN; perfil permitido e ator do SecurityContext, não DTO. |
| `PATCH /usuarios/{id}/senha` | ADMIN; reset separado >=12, audita sem valor/hash e força troca se temporária. |
| Login legacy | Credencial atual válida recebe desafio purpose PASSWORD_CHANGE, não JWT normal. |

V5 adiciona `password_change_required` e `credential_version`; todas as contas
existentes ficam marcadas para troca, pois BCrypt não revela comprimento
original. Desafio curto é aceito somente no endpoint de troca, não tem
authorities ADMIN/CLIENTE, inclui versão; a troca incrementa versão, limpa
flag e impede replay. Atualizar SecurityConfig para liberar challenge apenas
naquela rota e substituir o matcher genérico `/auth/**` por regras explícitas.

Mínimo 12 é validado antes de BCrypt em signup, troca, reset, bootstrap e
recovery. Login não rejeita uma senha antiga curta antes de permitir o challenge.
Definir contagem Unicode/normalização e máximo de bytes compatível com BCrypt;
se não atender produto, aprovar outro encoder antes da implementação. Separar
DTO de dados pessoais, perfil e senha para não alterar perfil/senha
acidentalmente. Atualizar OpenAPI/README; nunca logar `Usuario.toString()`
(`@Data` inclui senha).

## 13. Testes e critérios de aceite

### CLI/bootstrap/recovery

- bootstrap novo classificado, sem ADMIN: cria exatamente um, marker consumido,
  contador 1, hash BCrypt, evento auditado;
- segundo/two bootstraps concorrentes: somente um sucesso;
- falha entre insert/counter/marker/audit: rollback integral;
- recovery exige exatamente `admin_count=0`, `recovery_required=true`,
  `bootstrap_consumed=true`;
- zero ADMIN e recovery_required=false: recovery recusado, indica reconcile;
- bootstrap pendente: recovery recusado e direcionado a bootstrap;
- recovery autorizada restaura ADMIN, mantém consumed, limpa recovery_required;
- CLI sem canal de senha seguro, migration ausente ou sem identidade operador
  falha sem HTTP/listener/scheduler/Flyway.

### API/senhas

- payload signup perfil ADMIN e signup sem perfil nunca persistem ADMIN;
- CLIENTE em PATCH perfil/delete/reset recebe 403 sem chamar função writer;
- promoção, demissão com vários admins, último admin 409, auto-ops com/outro
  admin, exclusão com FK de agendamento;
- dados pessoais não alteram senha/perfil; passwords 11/12 em todos os fluxos;
- legacy account sem JWT normal antes do reset; challenge só acessa password
  route e replay/version antiga é recusado.

### PostgreSQL/concorrência/auditoria

- V3 backfill fresh/legado zero/um/muitos; new-install flag com dados falha;
- DML runtime direto perfil/admin/delete/truncate e audit UPDATE/DELETE negados;
- funções writer/offline permitidas e trigger bloqueia último ADMIN;
- em PostgreSQL descartável, conceder temporariamente DML de perfil/delete ao
  login `arena_admin_writer` de teste para tentar DELETE/demote direto do último
  ADMIN e provar o guard da trigger; esse GRANT temporário nunca existe em
  produção. Separadamente, provar que runtime não tem o DML;
- INSERT/UPDATE/DELETE multi-row, ON CONFLICT DO UPDATE/DO NOTHING, contador
  e rollback;
- duas remoções, demotion+delete, promoção+demotion, bootstrap/recovery em duas
  conexões; count nunca zero e count real sempre igual ao singleton;
- evento audit sucesso/rejected com ator/alvo/antes/depois/data/source; sem
  segredo/hash/token; runtime não altera/apaga;
- catálogo comprova grants, memberships e ausência de CREATEDB/CREATEROLE/
  SUPERUSER em roles de app.

Todos testes PostgreSQL seguem H-03 (`arena_flyway_test`, loopback/porta
aleatória, container descartável tmpfs, sem volume nomeado), com TEST_* e
credenciais próprias; nunca `arena`, `arena_dev` ou `arena_postgres_data`.

H-04 não é aceito antes de todos cenários passarem, deploy/rollback ser ensaiado
e roles/triggers serem validados no banco descartável.

## 14. Deployment e rollback

1. Provisioner cria writer/offline; verificar ACL e atributos.
2. Drenar todas instâncias antigas/workers, fechar ingress, aguardar transações
   e fazer backup/snapshot verificado.
3. Migrator aplica V3/V4/V5 numa única janela; versões antigas não operam em
   qualquer intervalo incompleto.
4. Validar count, marker, recovery flag, funções, triggers, grants/default ACL.
   Qualquer erro mantém ingress fechado.
5. Rodar CLI offline conforme estado; validar audit/hash/perfil.
6. Subir versão nova, smoke test, abrir tráfego.

V3 falha antes de commit => rollback transacional; roles do provisioner ficam
sem uso e não são apagadas automaticamente. Após commit, não deletar audit/
state nem apagar histórico Flyway: corrigir por migration forward ou restore
coordenado. Depois de V4/V5 não subir binário antigo que dependa de grants
revogados. Recovery/reconcile têm runbook próprio e deixam registro.

## 15. Decisões técnicas ainda pendentes

1. Manter `arena_migrator` como function owner (baseline H-02) ou aprovar
   exceção explícita de membership SET-only para um owner NOLOGIN. Baseline
   recomendado é migrator; dedicated owner melhora least privilege, mas muda o
   teste H-02.
2. Qual pipeline/operador tem autoridade para declarar `H04_INITIAL_INSTALL`
   true e qual evidência fica no change record; default false.
3. Qual identidade verificável concreta (runner/host/IAM atual) será armazenada
   como actor_ref; SSO/IAM completo pode vir depois.
4. Retenção de audit, leitura por auditor e storage WORM/collector; pgAudit não
   foi confirmado instalado.
5. Canal de troca forçada das contas legadas (challenge atual ou reset
   out-of-band) e política de BCrypt para Unicode/max length.
6. Janela de maintenance aprovada e estratégia de restore/forward se V3 commitou
   mas V4/V5 falharam.
7. Quem autoriza reconcile e o procedimento se state/audit estão tão
   inconsistentes que não registram o próprio reconcile.

## 16. Limites

Planejamento por leitura estática; nenhum código, migration, teste, SQL, banco,
container, `.env` ou operação Git foi executado nesta consolidação. Este
documento não declara H-04 implementado ou pronto para produção.
