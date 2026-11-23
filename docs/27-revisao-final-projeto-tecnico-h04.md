# Etapa 8 — revisão final do projeto técnico H-04

**Status:** planejamento técnico consolidado; não constitui autorização para
implementar. Este documento é a revisão final proposta; `docs/26` e `docs/27`
permanecem como histórico das propostas anteriores.

## 1. Resumo executivo

O estado confirmado continua sem bootstrap do primeiro ADMIN nem proteção
contra rebaixamento/exclusão do último. O signup atual força `CLIENTE`, mas
mantém `perfil` no DTO. `PUT /usuarios/{id}` combina dados pessoais, senha e
perfil; `UsuarioService` não delimita a transação que seria necessária para
contar/alterar ADMIN com segurança.

Arquitetura final recomendada:

1. Bootstrap inicial e recovery são comandos CLI separados, sem HTTP,
   scheduler ou migrations; usam entrada de senha oculta e identidade
   verificável do ambiente autorizado.
2. Um estado singleton guarda `admin_count`, marcador persistente de bootstrap
   e `recovery_required`.
3. Um advisory transaction lock comum serializa todas as operações de usuário
   que possam conflitar com o lifecycle de ADMIN. Triggers statement-level
   INSERT/UPDATE/DELETE e comandos API/CLI usam a mesma chave.
4. Triggers AFTER ROW mantêm `admin_count` a partir das transições realmente
   aplicadas. Elas não alteram o marcador de bootstrap.
5. Uma única função de bootstrap controla a transição do marcador
   `false → true`. Recovery exige bootstrap já consumido e nunca o reabre.
6. V3 instala estado, triggers e auditoria; V4 remove DML amplo de perfil do
   runtime e restringe ACLs; V5 força troca das senhas legadas.
7. As instâncias antigas são drenadas e o ingress fica fechado até V3/V4/V5,
   CLI, validações e novo binário estarem prontos.

**Limite de confiança:** o banco garante a invariante de não chegar a zero e
restringe quais credenciais chamam funções privilegiadas. A associação entre
uma conexão compartilhada e uma pessoa HTTP ainda é validada pela aplicação;
GUC/`SET LOCAL` não é prova de identidade. Uma aplicação ou credencial
privilegiada comprometida permanece fora da proteção absoluta do trigger.

## 2. Estado atual confirmado

Foram lidos integralmente `docs/26`, `docs/24`, `docs/25`, código de usuário,
autenticação e autorização, V1/V2, provisionamento, testes e `pom.xml`.
Conclusões relevantes:

- `POST /usuarios` é público. O DTO contém `perfil`, mas o mapper não o copia e
  `UsuarioService.salvar` define `CLIENTE`.
- `PUT /usuarios/{id}` exige ADMIN, mas altera perfil junto com dados e senha.
  O campo `perfil` é opcional e pode acabar como `null`.
- `PUT /usuarios/me` ignora perfil, mas também aceita/substitui senha pelo DTO
  compartilhado.
- `DELETE /usuarios/{id}` exige ADMIN e impede exclusão se houver agendamentos,
  mas não conta ADMINs.
- `UsuarioService` não declara `@Transactional`; `UsuarioRepository` não tem
  contagem por perfil nem locks de ADMIN.
- `Usuario.isEnabled()` sempre retorna true. A regra atual usa `perfil=ADMIN`
  como definição de administrador ativo.
- JWT guarda email no subject; o filtro recarrega usuário e authority do banco
  em cada requisição.
- V1 permite somente `ADMIN`/`CLIENTE`, sem seed ou mínimo de ADMINs.
- V2 concede INSERT/UPDATE/DELETE sobre `usuario` a `arena_runtime` e grants
  DML padrão em tabelas/sequences futuras do schema `public`.
- H-02 separa credenciais runtime/Flyway; o provisionador cria roles, e
  `arena_migrator` não tem `CREATEROLE`. Nenhum role novo será criado por Flyway.
- `pom.xml`: Spring Boot 4.0.7, Java 21, WebMVC, Security, JPA, Validation,
  Flyway/PostgreSQL e JJWT; não há Picocli/Spring Shell.
- `ArenaApplication` habilita scheduling; `SecurityConfig` cria
  `SecurityFilterChain(HttpSecurity)` incondicionalmente. Um runner acrescentado
  ao contexto web não garante CLI offline.
- Os testes atuais não cobrem diretamente cadastro/administração de usuários.

Não foi consultada nenhuma base para descobrir contagem real de ADMINs.

## 3. Decisões aprovadas incorporadas

1. Criar `arena_admin_writer` e `arena_offline_operator`, além de manter
   `arena_runtime`, `arena_migrator` e provisionador.
2. Todos os usuários existentes redefinem senha na primeira utilização após a
   implantação; o mínimo de senha nova/alterada é 12 caracteres.
3. Operador é identificado pelo ambiente autorizado; nome digitado não prova
   identidade. SSO/IAM é integração futura.
4. Signup não aceita perfil e sempre cria `CLIENTE`.
5. Perfil muda somente em `PATCH /usuarios/{id}/perfil`, por ADMIN.
6. Autorrebaixamento e autoexclusão são permitidos apenas se outro ADMIN
   permanecer. Operação que deixaria zero retorna `409 Conflict`.
7. Bootstrap offline one-shot; recovery separado, offline, autorizado,
   justificado e auditado.
8. Proteção do último ADMIN existe na aplicação e no PostgreSQL.
9. Auditoria é append-only para runtime, sem segredos.
10. H-02/H-03 são preservados; todos testes PostgreSQL usam somente database
    descartável `arena_flyway_test`.

## 4. Concorrência e triggers

### 4.1 Lock único

Usar uma chave `pg_advisory_xact_lock` PostgreSQL fixa, reservada e documentada
para lifecycle de ADMIN. O mesmo lock é adquirido por funções API, bootstrap,
recovery e triggers:

```text
advisory xact lock
  → revalidar ator/alvo e estado
  → modificar usuario
  → atualizar contador singleton
  → gravar auditoria
  → commit
```

As operações administrativas de aplicação usam uma transação de uma única
conexão writer; não abrir transação em `arena_runtime` e chamar outro datasource
esperando atomicidade distribuída. A transação é `READ COMMITTED`; funções
mutáveis são `VOLATILE`, e leituras após espera pelo lock usam snapshot atual.
Não fazer contagem antes de esperar pelo lock nem usar uma transação externa
`REPEATABLE READ` com snapshot antigo.

### 4.2 Statement-level, multi-row e UPSERT

Triggers `BEFORE STATEMENT` para INSERT, UPDATE e DELETE adquirem a mesma chave
antes do processamento das linhas. O serviço/função também a adquire primeiro.
Assim, statements multi-row são serializados uma vez e os triggers de linha
processam cada mudança na mesma transação.

Para `INSERT ... ON CONFLICT DO UPDATE`:

- INSERT e UPDATE statement triggers podem ambos pedir a mesma advisory lock;
  a reaquisição da chave na mesma transação é segura e não cria segundo lock;
- não atualizar contador em BEFORE INSERT, pois o ramo de conflito pode não
  inserir a linha;
- usar AFTER INSERT apenas para insert realmente efetivado e AFTER UPDATE para
  o delta `OLD.perfil → NEW.perfil`;
- `DO NOTHING` não altera contador; update que mantém perfil tem delta zero.

Triggers AFTER ROW incrementam o contador em INSERT ADMIN/promoção e decrementam
somente quando `admin_count > 1` em demissão/exclusão de ADMIN. O decremento é
condicional e atômico na linha singleton. Se não atualiza linha, a trigger
rejeita; o statement/transaction rollback desfaz a mudança do usuário e outros
deltas do statement. Multi-row não deixa uma remoção parcial.

Todas as operações de usuário são serializadas, inclusive signup e atualização
pessoal; é o custo de uniformidade escolhido para o volume MVP. `TRUNCATE` não
dispara row triggers: não conceder TRUNCATE a roles da aplicação e rejeitar/
restringir explicitamente esse comando.

Deadlocks são evitados por ordem única: advisory lock primeiro, depois alvo/
estado/auditoria; nenhuma função pega um lock de usuário antes da chave global.
Triggers statement-level garantem a mesma ordem em DML direto. `arena_runtime`
não tem DML de perfil na ACL final; a trigger permanece como segunda barreira
para ações privilegiadas.

## 5. Estado singleton e bootstrap

### 5.1 Modelo

V3 cria `admin_lifecycle_state` com uma linha:

| Campo | Propósito |
|---|---|
| `singleton_id SMALLINT PRIMARY KEY CHECK (=1)` | No máximo uma linha. |
| `admin_count BIGINT CHECK (>=0)` | Contador do trigger; zero permitido antes do primeiro ADMIN/recovery. |
| `bootstrap_consumed BOOLEAN` | Marcador persistente one-shot. False somente em nova instalação explicitamente aprovada. |
| `bootstrap_consumed_at TIMESTAMPTZ` | Nulo enquanto false; ao consumir ou fechar marker de legado guarda timestamp de registro, não necessariamente criação histórica do ADMIN. |
| `recovery_required BOOLEAN` | True para database legado sem ADMIN que requer recovery. Independente do bootstrap. |
| `last_recovery_at TIMESTAMPTZ` | Última recuperação concluída. |
| `updated_at TIMESTAMPTZ` | Atualização do estado. |

PK+CHECK impedem múltiplas linhas, mas não ausência. Migration insere e valida
exatamente uma; runtime não recebe DML, trigger impede DELETE/alteração da chave
e funções falham fechado se a linha estiver ausente.

### 5.2 Backfill e marcador

V3 bloqueia `usuario` em modo de manutenção, valida perfis, conta ADMINs e
cria estado em uma transação:

| Destino classificado | Estado inicial |
|---|---|
| Database realmente novo, tabela `usuario` vazia e placeholder Flyway não secreto explicitamente true | `admin_count=0`, `bootstrap_consumed=false`, `recovery_required=false`. |
| Legado com um ou mais ADMINs | Count existente, `bootstrap_consumed=true`, `recovery_required=false`. |
| Legado sem ADMIN | Count zero, `bootstrap_consumed=true`, `recovery_required=true`. |
| Marcado como novo mas já com usuário | Falha da migration. |

Default do marcador de instalação é false. A contagem zero sozinha não prova
novidade: usuário/admin antigo pode ter sido removido antes de V3. O pipeline/
provisionador autorizado fornece a classificação. O pipeline registra quem
aprovou o placeholder. Se o marcador estiver false e não houver ADMIN, usar
recovery, nunca bootstrap.

### 5.3 Um único responsável pelo consumo

Somente a função `bootstrap_first_admin` muda `bootstrap_consumed=false` para
true:

1. lock global;
2. validar false, `admin_count=0`, contagem real zero, usuário/email válido;
3. inserir primeiro ADMIN;
4. trigger AFTER INSERT incrementa `admin_count`;
5. a função define `bootstrap_consumed=true`, `bootstrap_consumed_at`, evento
   BOOTSTRAP e valida estado;
6. commit.

**Triggers nunca leem/escrevem o marcador bootstrap.** Elas somente mantêm
`admin_count` e a invariante. Promoções normais não alteram o marcador.
Recovery também não altera o marcador/timestamp; apenas, em sucesso, limpa
`recovery_required` e atualiza `last_recovery_at`. Falha em qualquer etapa
reverte tudo.

## 6. Proprietário das funções SECURITY DEFINER

### 6.1 Baseline que preserva H-02

H-02 registra `arena_migrator` como role Flyway sem memberships, sem
`SUPERUSER/CREATEDB/CREATEROLE`; os testes esperam isso. A recomendação baseline
é manter `arena_migrator` como owner das funções privilegiadas:

- Flyway pode criar/alterar as funções sem `SET ROLE` adicional;
- preserva a regra/teste atual de zero memberships;
- `arena_migrator` já é o trusted DDL owner das migrations;
- deve-se mitigar a authority ampla com SQL fixo pequeno, sem SQL dinâmico,
  nomes qualificados, `search_path` fixo, `EXECUTE` revogado de PUBLIC e grants
  específicos para funções.

### 6.2 Papel dedicado `NOLOGIN` avaliado

Um `arena_admin_fn_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE` é a
opção de least privilege: dono de estado/auditoria/funções, somente SELECT dos
campos necessários e INSERT/UPDATE/DELETE/sequence estritamente necessários.
Não seria membro de runtime, writer ou offline operator.

**Limitação Flyway:** `arena_migrator` não pode criar funções como outro owner
sem membership `SET ROLE` ou transferência de ownership por provisionador após
cada migration. Membership teria de ser restrita (`INHERIT=false`,
`ADMIN=false`, `SET` apenas), mas muda a garantia H-02 de migrator sem
membership e amplia sua capacidade sobre estado/auditoria. Transferência manual
por provisionador torna as migrations menos reproduzíveis.

Portanto, o baseline que não enfraquece H-02 usa `arena_migrator` como owner.
Adotar o papel NOLOGIN exige aprovação explícita para mudar H-02 e atualizar
seu teste de membership; não conceder essa membership silenciosamente.

### 6.3 Relação com writer/offline

`arena_admin_writer` e `arena_offline_operator` são logins sem DML direto:
EXECUTE somente nas funções necessárias. Não são owners, membros ou migrators.
As funções validam ator/alvo e recebem apenas dados de operação; `session_user`
indica qual login privilegiado chamou. `arena_runtime` nunca é membro dessas
roles. Credenciais são separadas, protegidas e fornecidas por secret manager.

## 7. Fluxos finais

### 7.1 Bootstrap CLI

Spring Boot é 4.0.7; não há Picocli/Spring Shell. Usar um launcher estreito
`AdminCliApplication` com `WebApplicationType.NONE`, `ApplicationRunner`,
JDBC/TransactionTemplate e PasswordEncoder BCrypt compartilhado. Não subir
`ArenaApplication` web, controllers, `SecurityFilterChain(HttpSecurity)`,
JWT/OpenAPI ou scheduler. CLI fecha contexto/pool e retorna exit code.

O CLI usa credencial `arena_offline_operator`, não runtime/migrator/
provisionador. Flyway fica desabilitado; ausência das funções/estado esperados
é falha fechada. Migrations são aplicadas antes por Flyway/migrator no corte.
Launcher CLI exige exatamente `bootstrap-admin` ou `recover-admin`, sem porta,
listener, scheduler ou modo alternativo que possa iniciar o servidor.

Senha via prompt oculto (`Console.readPassword`) ou secret manager/STDIN sem
echo. Sem canal seguro, abortar. Nunca como argv, `.env`, logs, SQL trace, DTO
logado ou arquivo versionado. Operador autenticado pelo ambiente autorizado;
referência de ticket não é prova de identidade.

Fluxo bootstrap:

1. Confirmar migrações/estado e classificação de instalação nova.
2. Verificar identidade operacional, nome/email e senha >=12; codificar antes
   do banco.
3. Iniciar transação offline e adquirir lock comum.
4. A função bootstrap verifica `bootstrap_consumed=false`, count zero, count
   real zero e email livre.
5. Inserir ADMIN; trigger mantém contador; função consome marker e grava auditoria.
6. Confirmar tudo junto; falha reverte tudo.
7. Segunda execução: marker true, conflito/exit code !=0, sem criar outra conta.

### 7.2 Recovery CLI

`recover-admin` é comando/runner distinto; não existe endpoint HTTP. Exige
identidade verificável do ambiente, justificativa/change ticket e confirmação
operacional explícita. Operador digitado não prova identidade.

Condições da **recovery normal**:

```text
admin_count = 0
AND recovery_required = true
AND bootstrap_consumed = true
AND COUNT(usuario WHERE perfil=ADMIN) = 0
```

Se count real zero e `recovery_required=false`, recusar recovery e indicar
`reconcile-admin-state`/procedimento separado; não alterar contador/marker
silenciosamente. Também recusar se houver ADMIN, se bootstrap estiver pendente,
se estado divergir ou se auditoria não puder ser gravada.

Com condições válidas, o operador pode promover um CLIENTE validado ou criar
conta nova; senha >=12 entra em prompt seguro e é hash, sem ser auditada.
Função recovery adquire lock, muda usuário/contador, grava RECOVERY com ator/
alvo/origem/motivo/timestamp, limpa `recovery_required`, confirma tudo; mantém
`bootstrap_consumed=true` e `bootstrap_consumed_at` inalterado.

Se existir qualquer ADMIN, usar a administração normal. Se `admin_count` não
coincide com count real, recovery normal falha; modo reconcile offline exige
autorização, lock de manutenção, snapshot antes/depois e evento RECONCILE. Se
audit/state estiverem tão inconsistentes que o evento não possa ser persistido,
parar e usar procedimento DBA com registro externo aprovado.

## 8. Triggers, count e DML direto

O contador do singleton é mantido por AFTER ROW na mesma transação:

| Transição efetiva | Delta | Resultado |
|---|---:|---|
| INSERT CLIENTE | 0 | Runtime tem DEFAULT CLIENTE e não pode inserir a coluna perfil após V4. |
| INSERT ADMIN autorizado | +1 | Bootstrap/recovery/função admin; bootstrap marker somente na função bootstrap. |
| CLIENTE→ADMIN | +1 | Função autorizada e ator validado. |
| ADMIN→CLIENTE | −1 | Condição atômica `admin_count > 1`, senão rejeita. |
| DELETE CLIENTE | 0 | Serviço mantém FK/agendamento. |
| DELETE ADMIN | −1 | Condição atômica `admin_count > 1`, senão rejeita. |
| Perfil não muda | 0 | Sem delta. |

V3 usa trigger de statement para adquirir lock em INSERT/UPDATE/DELETE e trigger
de linha para contar o efeito final. Em `ON CONFLICT DO UPDATE`, não incrementar
em BEFORE INSERT: o conflito não insere; AFTER INSERT/AFTER UPDATE representam
somente o ramo ocorrido. `DO NOTHING` não altera o count. No V4, runtime não
pode atualizar/inserir perfil nem excluir ADMIN; writer/offline usam função
controlada.

`arena_admin_writer`/offline não têm DML direto, apenas EXECUTE. A função
SECURITY DEFINER:

- é owner controlado conforme seção 6;
- verifica `session_user` permitido, actor id em estado ADMIN, alvo/perfil e
  count sob lock;
- escreve user, count via trigger e audit em uma transação;
- devolve código de domínio, sem exceção SQL para conflito esperado.

O trigger é a defesa final contra count<1 mesmo quando um writer chama a função
diretamente. `arena_runtime` direto recebe privilege-denied para perfil/DELETE.
No V3 transitional antes do V4, o GRANT legado ainda pode permitir certas
mudanças; ingress e todas instâncias antigas ficam parados durante esse período.

V2 default privileges precisam ser neutralizados para admin_state/audit.
V4 remove defaults genéricos ou concede grants explícitos somente a tabelas
business. Funções SECURITY DEFINER revogam EXECUTE de PUBLIC. `TRUNCATE` é
negado e, se necessário, bloqueado por trigger de statement. Superuser/owner
trusted ainda pode desabilitar triggers; isso não é defesa contra DBA
malicioso.

## 9. Auditoria

Tabela `admin_audit_event`, owner migrator/function owner, com:

- `event_id BIGINT identity PK`;
- `occurred_at TIMESTAMPTZ DEFAULT clock_timestamp()`;
- `operation`: BOOTSTRAP, RECOVERY, PROMOTE, DEMOTE, ADMIN_DELETE,
  ADMIN_PASSWORD_RESET, PASSWORD_CHANGE, RECONCILE;
- `outcome`: SUCCEEDED/REJECTED;
- `source`: HTTP, BOOTSTRAP_CLI, RECOVERY_CLI, RECONCILE_CLI/DB_DIRECT;
- `actor_type`, `actor_user_id` (HTTP), `actor_ref` (identidade verificável do
  operador/role);
- `target_user_id`, snapshot de identificação opcional, `profile_before/after`;
- `reason`/change ticket, request/correlation id, `error_code`.

Índices por data/id, ator, alvo e operação. `target_user_id`/`actor_user_id`
não têm FK destrutiva para preservar histórico após DELETE. Snapshots email/
reason são PII com retenção controlada. Proibido incluir senhas, hashes, JWTs,
segredos, bodies completos ou SQL bind values.

Sucesso: evento e mutação na mesma transação. `LAST_ADMIN` esperado:

1. Função writer verifica estado sob lock antes de DML.
2. Se a regra proíbe operação, não altera usuario/count.
3. Insere `REJECTED/LAST_ADMIN` e confirma somente o evento.
4. Retorna código de resultado à aplicação.
5. Controller traduz para HTTP 409 após commit do evento.

Não levantar exception SQL previsível dentro da transação: rollback apagaria o
evento. Falha técnica inesperada continua causando rollback. Se já ocorreu
rollback, tentativa HTTP pode ser auditada em `REQUIRES_NEW` externo, sem
self-invocation; a falha no writer de auditoria é monitorada.

Runtime não recebe SELECT/UPDATE/DELETE/TRUNCATE em audit/state. Pode inserir
evento somente por função append controlada, se esse caminho for necessário;
writer/offline functions gravam eventos de lifecycle. V2 default DML e
`SELECT` sequence são revogados explicitamente. Uma tentativa SQL direta
rejeitada pela trigger aborta a transação e não pode deixar audit-row na mesma
transação; para logs duráveis de tentativa raw SQL usar logs PostgreSQL/pgAudit
se instalados/configurados. **pgAudit não está confirmado como instalado.**

Ator HTTP: `Usuario.id` do principal SecurityContext, nunca do DTO; função
revalida ADMIN. Ator CLI: identidade verificável fornecida pelo ambiente
autorizado mais ticket; nome digitado isoladamente não é prova. A conexão
database identifica o role, não o humano. WORM/collector externo é necessário
contra adulteração com comprometimento total do app.

## 10. API e senhas

| Endpoint final | Acesso/contrato |
|---|---|
| `POST /usuarios` | Público; DTO sem perfil; service fixa CLIENTE. `perfil=ADMIN` nunca promove: rejeitar campo desconhecido ou ignorar e persistir CLIENTE, com teste. |
| `PUT /usuarios/me` | Autenticado; dados pessoais apenas, sem senha/perfil. |
| `PATCH /usuarios/me/senha` | Autenticado; senha atual + nova >=12; update separado. |
| `PUT /usuarios/{id}` | ADMIN se mantido; atualização pessoal, sem senha/perfil. |
| `PATCH /usuarios/{id}/perfil` | ADMIN; DTO perfil ADMIN/CLIENTE e justificativa opcional; método/service verifica ator, writer function confirma novamente. |
| `DELETE /usuarios/{id}` | ADMIN; função writer mantém FK/booking rule e contador; último ADMIN => 409. |
| `PATCH /usuarios/{id}/senha` | ADMIN; reset separado, nova senha >=12; marca troca obrigatória se temporária e audita sem valor/hash. |
| Bootstrap/recovery/reconcile | Só CLI offline, sem rota HTTP. |

Controllers e services verificam `hasRole('ADMIN')`; actor vem do SecurityContext.
`UserDetails` é recarregado do banco em cada request, então token anterior não
conserva role após demissão.

### Contas legadas

BCrypt não revela comprimento original. V5 marca todas as contas existentes
`password_change_required=true`, inclusive as que já tinham 12+, pois não se
pode distingui-las pelo hash. Login verifica credencial atual; conta marcada
recebe token challenge de propósito/curta duração, sem authorities ADMIN/CLIENTE.
SecurityConfig permite esse token somente na rota de troca; o filtro distingue
challenge do JWT de acesso e não deixa `.authenticated()` genérico abrir outras
rotas.

V5 pode adicionar `credential_version`; challenge inclui purpose/version.
Troca válida atualiza BCrypt, limpa flag e incrementa versão atomicamente;
replay ou reset concorrente com versão antiga falha. Nova senha validada >=12
antes do encoder em cadastro, troca própria, reset ADMIN, bootstrap e recovery.
No login não rejeitar legado só por comprimento antes de validar a senha.

O comprimento Unicode e máximo por bytes do BCrypt devem ser documentados e
testados (inclusive multibyte). Se a política exigir senha acima do limite do
encoder, aprovar outro algoritmo/encoder antes da implementação. Nunca logar
entrada, hash, token ou DTO com senha; `Usuario @Data.toString()` não deve ser
logado.

## 11. Matriz final de papéis

| Role | Uso/ACL |
|---|---|
| `arena_runtime` | CONNECT/USAGE, SELECT e DML por coluna para dados pessoais; INSERT usuário sem perfil (DEFAULT CLIENTE); sem UPDATE perfil, DELETE usuario, DML state ou UPDATE/DELETE audit. EXECUTE somente em append function restrita. |
| `arena_migrator` | Flyway/DDL, owner de V1/V3/V4/V5 e das SECURITY DEFINER no baseline; sem CREATEROLE/CREATEDB/SUPERUSER/membership. Não usado pelo API/CLI. |
| Provisionador | Cria database/roles globais e grants fora do app. |
| `arena_admin_writer` | Login e datasource só para operações web privilegiadas; EXECUTE em mudança de perfil/exclusão/reset, sem DML direto/DDL/membership. |
| `arena_offline_operator` | Login só no CLI; EXECUTE bootstrap/recovery/reconcile, sem DML direto/DDL/web config. |
| `arena_admin_fn_owner` | **Não criado no baseline H-02.** Se aprovado, NOLOGIN e ACL mínima descrita na seção 12; requer decidir relação com migrator. |

V2 table-level grants devem ser revogados antes de grants column-level; caso
contrário o grant de tabela continua permitindo `perfil`. Default profile
CLIENTE no banco e mapping JPA que omite perfil no INSERT normal. Default
privileges genéricos V2 são removidos na V4 ou explicitamente neutralizados
para todas as tabelas/sequences internas. Roles novos não herdam uns dos outros.

## 12. SECURITY DEFINER: decisão e privilégios

V3 funções são pequenas, allowlisted, `SECURITY DEFINER`, SQL estático,
`search_path` fixo/seguro, objetos qualificados e argumentos validados.
Revogar EXECUTE de PUBLIC e conceder apenas funções certas:

- `arena_offline_operator`: bootstrap, recovery, reconcile;
- `arena_admin_writer`: perfil, delete, admin password reset;
- `arena_runtime`: append-audit limitado, caso mantido;
- nenhum desses roles tem membership em owner/migrator.

**Recomendação baseline:** owner `arena_migrator`, preservando H-02 sem
membership. O owner é confiável para DDL e já controla objetos da migration;
blast radius do SECURITY DEFINER é mitigado por funções mínimas/revisadas.

Um owner NOLOGIN dedicado é tecnicamente melhor em least privilege. Para
Flyway criar/alterar funções como esse owner, migrator precisaria SET ROLE
membership (INHERIT/ADMIN false, SET controlado) ou provisionador precisaria
transferir ownership fora da migration. A membership altera teste e confiança
H-02; o pós-processo externo reduz reprodutibilidade. Não adotar sem aprovação
explícita. Runtime, writer e offline nunca recebem membership.

## 13. Implantação e compatibility V3/V4/V5

1. Provisionador cria roles writer/offline antes de V3; Flyway não executa
   CREATE ROLE.
2. Fechar ingress, drenar/encerrar todas as instâncias antigas, workers e
   transações de escrita; fazer backup verificado.
3. Rodar Flyway V3/V4/V5 em maintenance sob `arena_migrator`, com trigger state
   backfill e revogação ACL concluídos. Default V3/V4 não abre tráfego.
4. V3 classifica ambiente por placeholder não secreto `H04_INITIAL_INSTALL`,
   default falso; se true mas tabela usuario não vazia, aborta. Legado zero ADMIN
   fica recovery_required, não bootstrap pendente.
5. Validar count == COUNT(ADMIN), singleton, marcador, triggers, functions,
   grants, EXECUTE e sequences; ingress permanece fechado em qualquer divergência.
6. Subir apenas a nova versão; CLI de boot/recovery é executada com Flyway
   disabled, sem HTTP. Verificar estado/evento/senha/reset.
7. Smoke test CLIENTE/ADMIN e ACL; somente então abrir tráfego.

V3 conta/transitions protege o mínimo, mas V2 ainda pode permitir profile DML;
V4 final ACL remove essa capacidade. Não manter versão antiga servindo durante
essa janela. Se migration de versionamento falhar após V3 commit, deixar sistema
fechado e reparar por forward migration ou restore coordenado, nunca reativar
sem aprovação o binário antigo/grants amplos.

Rollback antes do commit V3: PostgreSQL reverte migration. Roles criados pelo
provisionador ficam sem uso; não removê-los automaticamente. Depois de commits
e audit events, preservar estado/auditoria e corrigir forward. Recovery/
reconcile sempre gera evento ou falha fechado se audit não estiver disponível.

## 14. Plano de testes

### Bootstrap/recovery/CLI

- Fresh install explicitamente classificada, zero usuários: bootstrap cria um
  ADMIN, count=1, marker consumido, audit emitida.
- Marker consumido/ADMIN existente: segunda execução recusada sem mutação.
- Legacy sem ADMIN: recovery_required; bootstrap recusado; recovery offline
  restaura conta, limpa recovery_required, conserva bootstrap consumed.
- `admin_count=0` com `recovery_required=false`: recovery normal recusado e
  indica reconcile separado.
- Falha entre INSERT, count, marker e audit: rollback integral.
- Duas execuções bootstrap/recovery simultâneas: lock serializa, sem duplicata.
- CLI inválida/sem input seguro falha; senha nunca em argv/echo/log.
- Nenhum WebServer, scheduler ou Flyway no contexto CLI; contexto fecha/exit code.

### API e senha

- Signup sem perfil e payload com perfil ADMIN: nunca cria ADMIN.
- CLIENTE tenta PATCH perfil/delete/reset => 403 e nenhuma função writer.
- ADMIN promove/rebaixa; autorrebaixamento/autoexclusão permitido só com outro
  ADMIN; último => 409 e estado unchanged.
- Exclusão com agendamento preserva FK/regra.
- Senhas 11/12 em cadastro, troca, reset, bootstrap/recovery; pessoal update
  não altera senha/perfil.
- Legacy login tem challenge restrito; replay challenge falha; senha alterada
  permite token normal. Hash/senha ausente em audit/log.

### Trigger, concurrency e grants

- INSERT cliente/admin, transição C→A/A→C, delete cliente/admin, update sem
  mudança de profile.
- Multi-row update/delete; `ON CONFLICT DO UPDATE` INSERT/UPDATE trigger,
  DO NOTHING e rollback em FK.
- Duas operações removendo dois ADMINs, demotion+delete, promotion+demotion,
  bootstrap/recovery concorrentemente; estado final jamais zero e audit
  consistente.
- Como runtime: UPDATE perfil, INSERT admin explícito, DELETE admin,
  TRUNCATE usuário e UPDATE/DELETE audit negados; append function controlada só
  aceita tipos/argumentos previstos.
- Como writer/offline: DML direto negado; EXECUTE autorizado funciona e trigger
  ainda protege count.
- Catálogos confirmam ausência de SUPERUSER/CREATEDB/CREATEROLE e memberships
  não autorizadas; tests H-02 atualizados somente se owner NOLOGIN aprovado.
- State/audit não herdam ACL DML da V2; sequences internas não herdam SELECT.
- Uma recusa esperada LAST_ADMIN persiste audit e retorna 409; falha técnica
  causa rollback. Raw SQL rejeitado é coberto por server logs/pgAudit somente se
  disponíveis, sem presumir instalação.

Todos os testes DB usam PostgreSQL descartável H-03 (`arena_flyway_test`,
loopback/porta aleatória/tmpfs, sem volume nomeado), credenciais por role e sem
fallback a produção. `TEST_H04_INITIAL_INSTALL` é validado separadamente e
somente para base descartável vazia. Nunca usar `arena`, `arena_dev` ou
`arena_postgres_data`.

## 15. Sequência revisável de implementação

| Fase | Objetivo/arquivos | Migration | Testes/aceite | Risco/dependência |
|---|---|---|---|---|
| 0. Fechar decisões técnicas | Runbook/owner/operador/reset/retention | — | Revisão DBA/Security | Sem identidade operacional ou classificação de instalação, não avançar. |
| 1. Roles | Atualizar provisioning script, placeholders, `application.properties` | Roles por provisionador antes de V3 | Catálogo ACL e memberships em PG descartável | Roles são cluster-wide; não expor credenciais; script H-02 é one-shot. |
| 2. Estado/trigger/audit | Nova V3, state/audit/functions/triggers/repositories | V3 | backfill 0/1/N, ON CONFLICT, multirow, lock, rollback | ACCESS EXCLUSIVE/maintenance; placeholder classifica novo. |
| 3. CLI | AdminCliApplication/launcher, password encoder, runner e JDBC config estreitos | Usa V3 functions; Flyway disabled | no WebServer/scheduler, marker one-use, no secret leaks | Não carregar web/Security/JWT config. |
| 4. API | DTOs/controller/service/repository/security/OpenAPI | — | Auth real, 403/409, actor/target audit, self actions | Compatibilidade do contrato e credencial writer no processo. |
| 5. ACL final | Grants por coluna/função, default privileges, role writer/offline | V4 | runtime DML direto negado; function path permitido | Todas instâncias antigas devem estar drained. |
| 6. Senha | `must_change`, `credential_version`, challenge/reset | V5 | 11/12, legacy, replay, admin reset | Lockout operacional e dimensionamento BCrypt. |
| 7. Cutover | Backup, migrator, CLI, smoke, ingress | V3/V4/V5 em manutenção | runbook/rollback ensaiado | Sem versão antiga entre V3 e V4. |

## 16. Critérios de aceite

H-04 não é declarado pronto até:

1. Bootstrap é offline, marker é consumido por uma função apenas, segunda
   execução falha e recuperação não reabre bootstrap.
2. Recovery normal exige `admin_count=0`, `recovery_required=true` e
   `bootstrap_consumed=true`; divergência com recovery_required=false manda ao
   reconcile separado.
3. Trigger protege último ADMIN em todo INSERT/UPDATE/DELETE/UPSERT e transações
   multi-row; API também verifica antes da mutação.
4. Apenas ADMIN HTTP promove/rebaixa pela rota indicada; runtime DML profile
   está revogado em V4 e funções writer validam actor.
5. HTTP LAST_ADMIN grava evento confirmado e retorna 409; não usa exception SQL
   previsível que apague audit; falha técnica reverte.
6. Auditoria inclui ator/alvo/operation/source/antes/depois/time/reason quando
   exigido, sem credenciais; runtime não altera/exclui.
7. Todos endpoints de set/reset senha exigem >=12; legado não recebe JWT normal
   até reset; replay challenge falha.
8. Matriz de roles e ACL defaults foi verificada; H-02 sem alteração não
   aprovada; qualquer exceção de membership é explícita.
9. Suíte completa de H-03 em PostgreSQL descartável aprovada, sem conexão a
   bancos/volumes protegidos.
10. Deploy/rollback/recovery ensaiados e tráfego só abre quando versão e grants
    finais estão alinhados.

## 17. Riscos e decisões que ainda exigem aprovação

1. **Owner `SECURITY DEFINER`:** baseline recomenda migrator para preservar H-02
   sem membership; dedicada NOLOGIN é least-privilege, mas exige exceção
   explícita de membership/alteração do teste ou pipeline de ownership externo.
2. **Marcador fresh-install:** qual pipeline/provisionador verificável pode
   afirmar `H04_INITIAL_INSTALL=true`? Default é false; instalar flag em database
   legado vazio não deve ser decisão automática.
3. **Writer datasource:** confirmar armazenamento/rotação da credencial
   `arena_admin_writer`; ela estará disponível ao processo web e precisa de
   acesso mínimo/monitoramento.
4. **Identidade operator:** definir fonte verificável atual (runner corporativo,
   IAM/SSO quando disponível ou mecanismo operacional local auditável); valor
   digitado nunca prova identidade.
5. **Senhas legadas:** aprovar canal do challenge/reset e comunicação. Não é
   possível descobrir hashes correspondentes a senha curta.
6. **Política de tamanho:** definir Unicode normalization/counting e máximo
   compatível com BCrypt, ou aprovar encoder alternativo.
7. **Auditoria:** aprovar retention/PII, quem pode consultar, export WORM e
   política para tentativa SQL bloqueada; pgAudit não é presumido.
8. **Disponibilidade:** aprovar janela de manutenção e plano de restore se V3
   commitou e V4 falhou.
9. **Recuperação/reconcile:** definir quem pode aprovar, qual change ticket é
   aceito e como agir se a própria tabela de auditoria/state estiver corrompida.

## 18. Limites desta revisão

As fontes foram lidas estaticamente. Nenhum teste/build/SQL foi executado,
nenhum banco/container foi acessado, `.env` real não foi lido e não houve
operação Git. Este documento é planejamento, não implementação nem autorização
para deploy.
