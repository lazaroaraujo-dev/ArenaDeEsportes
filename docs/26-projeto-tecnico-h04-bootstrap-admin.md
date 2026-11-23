# Etapa 8 — projeto técnico detalhado do H-04

**Escopo:** arquitetura e plano de implementação. Este documento não contém
código executável nem migration. Não foram executados testes ou SQL, acessados
bancos/containers, lido `.env` ou realizadas operações Git.

## 1. Resumo executivo

O estado atual confirma H-04: não existe bootstrap do primeiro ADMIN e não há
proteção contra demissão/exclusão do último. O signup público já força
`CLIENTE`, mas usa DTO com `perfil`; `PUT /usuarios/{id}` permite ao ADMIN
alterar o perfil junto com dados e senha. Esses fluxos não têm transação de
serviço, contagem de ADMIN ou coordenação concorrente.

Arquitetura recomendada:

1. **Bootstrap e recuperação por comandos CLI separados**, ambos executados em
   modo não web, com senha oculta/secret manager e sem senha em argumentos ou
   logs.
2. **Uma tabela singleton de estado administrativo** contendo contador
   transacional, marcador de bootstrap e metadado da última recuperação.
3. **Triggers PostgreSQL** para manter o contador em cada INSERT, alteração de
   `perfil` e DELETE e rejeitar qualquer transição que o leve a zero.
4. **Um único lock transacional compartilhado** entre API, funções PostgreSQL,
   triggers e CLI. O projeto recomenda lock consultivo PostgreSQL com chave fixa:
   a aplicação o obtém antes de bloquear linhas e triggers `BEFORE STATEMENT`
   usam a mesma chave antes do processamento de linhas.
5. **Autorização de ator na aplicação** (`ADMIN` da sessão) e, se aprovada a
   defesa contra DML direto de perfil, um role adicional restrito
   `arena_admin_writer` para executar funções de mudança de perfil/exclusão.
   A conta `arena_runtime` não recebe DML direto em `perfil`.
6. **Auditoria append-only**, com eventos de sucesso na mesma transação da
   mudança e registro separado, após rollback, para recusas HTTP esperadas.
7. **Senha mínima de 12 caracteres em toda operação que define senha.** Como
   hashes atuais não revelam o comprimento original, contas existentes devem
   passar por troca forçada de senha antes de ser consideradas em conformidade.

**Limite de confiança:** PostgreSQL pode garantir a contagem mínima e limitar
quais logins podem invocar operações privilegiadas. Com uma credencial de banco
compartilhada, PostgreSQL não sabe qual pessoa HTTP originou a conexão. Mesmo
com role adicional, a autorização do usuário HTTP continua sendo validada pela
aplicação; uma aplicação comprometida pode usar credenciais de operação que
possui.

## 2. Estado atual confirmado

### 2.1 Fontes e versões

Foram relidos os três documentos obrigatórios, controllers, serviços, DTOs,
entidade, enum, mapper, repositório, configuração Security, filtro e serviço
JWT, handler de exceções, V1/V2, script de provisionamento, configuração
Spring, testes e `pom.xml`.

O `pom.xml` declara Spring Boot **4.0.7**, Java 21, JPA, Validation, WebMVC,
Security, Flyway/PostgreSQL, JJWT 0.12.7 e dependências de teste. Não há
Picocli/Spring Shell; o framework já fornece `ApplicationRunner`. A aplicação
normal é iniciada por `ArenaApplication.main` e `SpringApplication.run`;
`ArenaApplication` habilita scheduling. `SecurityConfig` define
`SecurityFilterChain(HttpSecurity)` sem condição para modo não web.

### 2.2 Usuário, perfil e persistência

- `Usuario.perfil` é `Perfil` persistido com `EnumType.STRING`; os valores são
  `ADMIN` e `CLIENTE`. `getAuthorities()` cria `ROLE_<perfil>`.
- `Usuario.isEnabled()` retorna sempre `true`; hoje não há outro conceito de
  administrador ativo.
- `UsuarioRequestDTO` inclui `perfil` opcional e `senha` com mínimo atual de
  **6** caracteres; o mesmo DTO é usado por cadastro, atualização própria e
  atualização administrativa.
- `UsuarioMapper.toEntity` não copia `perfil`. `UsuarioService.salvar` codifica
  senha com `PasswordEncoder` e define explicitamente `CLIENTE`.
- `UsuarioService.atualizar` copia `dto.getPerfil()` e codifica a senha recebida.
  Sem perfil, pode tentar salvar `null` em coluna `NOT NULL`.
- `UsuarioService.atualizarProprioPerfil` não muda perfil, mas substitui e
  codifica a senha.
- `UsuarioService.deletar` impede exclusão se houver agendamentos, mas não
  verifica ADMIN. O serviço não declara `@Transactional`; as operações
  compostas hoje podem usar transações de repositório separadas e dependem de
  Open Session in View para ler a coleção lazy de agendamentos.
- `UsuarioRepository` declara somente `findByEmail`; não há contagem por perfil
  nem lock de ciclo de vida de ADMIN.
- A resposta omite senha e inclui perfil.

### 2.3 Cadastro, autenticação e autorização

Rotas atuais:

| Método/rota | Permissão atual | Comportamento |
|---|---|---|
| `POST /auth/login` | Público | Autentica email/senha e emite JWT. |
| `POST /usuarios` | Público | Cria usuário; perfil sempre `CLIENTE`. |
| `GET /usuarios` | ADMIN | Lista usuários. |
| `GET /usuarios/me` | Autenticado | Consulta o usuário do principal. |
| `PUT /usuarios/me` | Autenticado | Atualiza dados/senha; ignora perfil. |
| `GET /usuarios/{id}` | ADMIN | Consulta usuário. |
| `PUT /usuarios/{id}` | ADMIN | Atualiza dados, senha e perfil. |
| `DELETE /usuarios/{id}` | ADMIN | Exclui se não houver agendamentos. |

O JSON público com `"perfil":"ADMIN"` é desserializado pelo DTO atual, mas
ignorado/forçado a `CLIENTE`; não é uma escalada reproduzível no código atual.
Não existe `POST /auth/register`, nem endpoint de bootstrap.

`@EnableMethodSecurity` habilita `@PreAuthorize` nos controllers. As funções
públicas do service, isoladamente, não verificam o ator. `JwtService` põe só o
email no subject; `JwtAuthenticationFilter` recarrega usuário e authorities
do banco em cada request. Uma promoção/redução deve refletir no próximo request
com JWT existente.

### 2.4 Migrations, grants e testes existentes

- V1 cria `usuario.perfil NOT NULL` com CHECK para `ADMIN`/`CLIENTE`; não cria
  ADMIN nem impõe cardinalidade.
- V2 concede DML completo nas três tabelas da aplicação, incluindo
  `INSERT/UPDATE/DELETE` em `usuario`, e default privileges para tabelas futuras
  em `public`.
- V2 também concede `USAGE/SELECT` em sequences existentes e futuras em
  `public`.
- Não há migration de bootstrap, contador, trigger de ADMIN ou auditoria.
- O provisionamento de H-02 cria `arena_runtime` e `arena_migrator`, não cria
  roles de ADMIN da aplicação e não concede `CREATEROLE` ao migrator.
- Não há testes dedicados a `UsuarioController`, `UsuarioService` ou
  `AuthController`. Testes de agendamento criam usuários ADMIN como fixtures;
  isso não testa cadastro/gestão de perfil. `TestPostgresContextInitializer`
  preserva o guard H-03 e injecta credenciais distintas.

## 3. Arquitetura recomendada

### 3.1 Fluxos e limites

```text
Operador ──CLI offline──> função de bootstrap/recovery ──> PostgreSQL
                                 │                           │
                                 └──── evento de auditoria ──┘

ADMIN ──JWT──> Controller/Service (autoriza ator e obtém lock compartilhado)
                              │
                              ├── DML normal: arena_runtime
                              └── perfil/exclusão: função restrita
                                  via arena_admin_writer ──> PostgreSQL

PostgreSQL: lock comum + estado singleton + triggers
            garantem admin_count >= 1 após existir ADMIN
```

O caminho web não chama bootstrap nem recovery. O caminho CLI não abre HTTP,
não habilita scheduler e não executa migrations. As regras de negócio são
checadas antes da chamada ao banco privilegiado; funções e triggers repetem as
invariantes críticas no banco.

### 3.2 Papéis PostgreSQL

| Role | Uso no projeto proposto | Acesso |
|---|---|---|
| `arena_runtime` | Datasource normal Spring/JPA | SELECT de usuários; INSERT de usuário restrito a campos não privilegiados; UPDATE somente colunas pessoais aprovadas. Sem UPDATE de `perfil`, DELETE de usuário, DML no estado administrativo ou UPDATE/DELETE da auditoria. |
| `arena_migrator` | Flyway e ownership de tabelas/funções/triggers | DDL de schema/migration, sem uso nas operações HTTP. Continua sem `SUPERUSER`, `CREATEDB` ou `CREATEROLE`. |
| Provisionador | Setup aprovado e gestão de roles | Criado/configurado pelo procedimento administrativo do cluster; não entra no Spring. Cria roles adicionais, pois Flyway não pode criar roles globais. |
| `arena_admin_writer` — proposta adicional | Pool restrito para operações HTTP de perfil/exclusão | `CONNECT`, `USAGE` e `EXECUTE` somente em funções específicas; sem DML direto em tabelas e sem DDL. |
| `arena_offline_operator` — proposta adicional | CLI de bootstrap e recovery | Credencial disponível somente ao operador/runner offline; `EXECUTE` somente nas funções CLI. Sem DML/DDL direto. |

Os dois últimos são **roles adicionais**, não substituem os três do H-02. Criá-los
exige atualizar o script de provisionamento como operação do provisionador.
Não se deve entregar `arena_migrator` nem provisionador à API/CLI de rotina.
O uso dos roles adicionais está entre as decisões técnicas pendentes da seção
15.

Ambos devem ser logins isolados com `NOSUPERUSER`, `NOCREATEDB`,
`NOCREATEROLE`, `NOREPLICATION`, `NOBYPASSRLS`, sem memberships. O web
processo usaria um datasource separado para `arena_admin_writer`; a CLI recebe
`arena_offline_operator` apenas pelo secret manager/processo offline. São nomes
de configuração propostos, não variáveis adicionadas ao projeto.

## 4. Migration V3 — estado, contador e triggers

V3 é proposta como migration transacional executada por `arena_migrator`. Ela
não deve executar `CREATE ROLE`: roles são objetos globais e o migrator não tem
`CREATEROLE`. O provisionador prepara os logins de função antes de V3.

### 4.1 Modelo singleton

Uma tabela de controle, por exemplo `admin_lifecycle_state`, com uma única linha
e colunas conceituais:

| Campo | Tipo/semântica proposta |
|---|---|
| `singleton_id` | `SMALLINT PRIMARY KEY`, CHECK para o único valor `1`. Impede mais de uma linha. |
| `admin_count` | `BIGINT NOT NULL CHECK (admin_count >= 0)`; contador mantido pelas triggers. Não usar CHECK `>= 1`, pois instalação nova e recovery podem estar legitimamente em zero. |
| `bootstrap_consumed` | `BOOLEAN NOT NULL`; marcador one-shot. `false` somente em instalação nova explicitamente aprovada; nunca volta a false. |
| `bootstrap_consumed_at` | `TIMESTAMPTZ NULL`; nulo antes do bootstrap inicial; timestamp de execução ao consumir. Em banco legado, preenchido com o instante do backfill para indicar que bootstrap foi fechado, não a data histórica do ADMIN. |
| `recovery_required` | `BOOLEAN NOT NULL`; inicia true para banco legado com zero ADMIN. É independente do marcador de bootstrap; recovery pode limpá-lo, mas nunca reabre bootstrap. |
| `last_recovery_at` | `TIMESTAMPTZ NULL`, atualizado por recovery bem-sucedido. |
| `updated_at` | `TIMESTAMPTZ NOT NULL`, mantido no banco. |

PK + CHECK garantem no máximo uma linha, não garantem por si sós que ela exista.
V3 insere exatamente uma linha antes de instalar os gatilhos; um CHECK mantém
`bootstrap_consumed_at` coerente com o marcador. Acesso de runtime é revogado,
gatilho rejeita DELETE/alteração da chave singleton e todas as funções falham
fechado se a linha estiver ausente. Migração/verificação operacional confirma
que há exatamente uma linha.

O contador é uma cópia transacional da contagem de `usuario.perfil='ADMIN'`;
auditoria é a fonte de histórico, não o contador. Operações privilegiadas
podem comparar contador e `COUNT(*)` enquanto seguram o lock e falhar fechado
se divergirem. Não corrigir divergência silenciosamente.

### 4.2 Backfill e instalação segura

Antes de ativar triggers:

1. provisionar roles adicionais aprovados;
2. drenar operações de escrita/alteração de perfil durante o corte;
3. em uma transação, bloquear `usuario` com lock exclusivo, validar os valores
   de perfil e contar ADMINs;
4. classificar explicitamente o destino por um marcador Flyway não secreto,
   fornecido pelo pipeline/provisionador somente para uma instalação realmente
   nova (por exemplo, placeholder `h04InitialInstall`, default `false`);
5. inserir a linha singleton com `admin_count=N`: em instalação nova aprovada e
   tabela `usuario` vazia, `bootstrap_consumed=false` e
   `recovery_required=false`; se existe ADMIN, consumed=true/recovery=false; se
   é legado sem ADMIN, consumed=true/recovery=true;
6. se o operador marcou “nova instalação” mas já há usuários, falhar fechado;
   não inferir novidade somente de `admin_count=0`;
7. criar tabelas/funções/triggers, ajustar grants e verificar novamente
   contador versus tabela antes do commit.

Esse marcador é uma afirmação operacional aprovada, não uma propriedade que
PostgreSQL consiga provar retrospectivamente. Para uma base vazia recém-criada,
o pipeline define a afirmação uma vez. Para qualquer base existente, usa o
default falso: bootstrap inicia consumido. Se não houver ADMIN, V3 define
`recovery_required=true`. Se todos os usuários/admins de um sistema antigo
foram apagados antes de V3, o banco não consegue distinguir isso de uma
instalação nova; não inferir elegibilidade de bootstrap a partir de `COUNT(*)=0`
sozinho.

O lock exclusivo impede que cadastros/mudanças concorrentes passem entre o
backfill e a ativação da trigger. A implantação deve prever espera/timeout e
janela de manutenção se a tabela tiver volume ou transações longas. Qualquer
erro antes do commit deve reverter a migration inteira.

### 4.3 Protocolo único de serialização

Usar uma chave PostgreSQL advisory transaction lock, fixa, documentada e
reservada para o ciclo de vida de ADMIN. Ela é o mesmo mecanismo em todos os
participantes:

1. Serviço API, função de banco, bootstrap CLI e recovery CLI usam
   `pg_advisory_xact_lock` como primeira operação da transação, antes de
   bloquear linha de usuário.
2. Triggers `BEFORE STATEMENT` de INSERT, UPDATE e DELETE adquirem a **mesma
   chave**, antes de processar qualquer linha. Isso dá um único protocolo
   inclusive para DML direto e statements com várias linhas.
3. Triggers de linha atualizam a linha singleton. O update dessa linha também
   é atômico; lock/contador não pode ser manipulado por runtime.
4. O lock consultivo de transação é liberado automaticamente em commit/rollback.

Não criar um segundo protocolo independente com outra chave/ordem de row lock.
Todas as instâncias Spring e os processos CLI usam o mesmo PostgreSQL e a mesma
chave. No caminho sem role extra, a transação de serviço usa `READ COMMITTED`;
após esperar pelo lock, a contagem em instrução posterior vê o commit
precedente. No desenho recomendado com `arena_admin_writer`, a função executada
na conexão writer adquire o lock, valida ator/alvo, faz a alteração e grava
auditoria em **uma transação nessa mesma conexão**. A aplicação não deve iniciar
transação `arena_runtime` e depois chamar outro pool esperando atomicidade
distribuída. O alvo é relido/lockado somente depois da chave global.

As funções PL/pgSQL de mutação devem ser `VOLATILE`, não `STABLE`, e a conexão
deve operar em `READ COMMITTED`; contagens/leitura de ator feitas depois de
esperar pelo lock precisam usar snapshot atual. O contador é alterado por UPDATE
condicional da linha singleton, não por `COUNT(*)` sem serialização. Não chamar
essas operações dentro de uma transação `REPEATABLE READ` com snapshot antigo;
tratar SQLSTATE de serialização como falha/retry seguro conforme política.

Os triggers `BEFORE STATEMENT` adquirem o lock antes de locks de tupla de DML
direto; a função writer/offline o adquire antes de ler/lockar o alvo. Isso evita
a ordem conflitante “serviço segura estado e espera tupla / DML segura tupla e
espera estado”. A operação sensível inteira fica em uma conexão/transação
PostgreSQL. Funções `SECURITY DEFINER` usam search path fixo e nomes de objetos
qualificados. A contrapartida é serializar também signup e alterações pessoais
de usuário; é um custo deliberado para uniformidade no volume esperado do MVP.

### 4.4 Lógica das triggers

Com o lock global já obtido, trigger `AFTER ROW` atualiza o contador no mesmo
transaction que a alteração efetivamente aplicada a `usuario`:

| Operação em `usuario` | Mudança de `admin_count` | Regra |
|---|---:|---|
| INSERT CLIENTE | 0 | Perfil fornecido pelo runtime não pode definir ADMIN; default é CLIENTE. |
| INSERT ADMIN | +1 | Somente função de bootstrap/recovery/admin_writer autorizada. A trigger atualiza apenas `admin_count`; não altera marcador bootstrap. |
| CLIENTE → ADMIN | +1 | Só role/função de escrita administrativa; função verifica ator ADMIN. |
| ADMIN → CLIENTE | −1 | Update condicional só é aceito se `admin_count > 1`; caso contrário retorna código de conflito/rejeita. |
| DELETE CLIENTE | 0 | Aplicam-se regras de FK e serviço. |
| DELETE ADMIN | −1 | Só role/função autorizada e somente se `admin_count > 1`. |
| UPDATE sem mudança de perfil | 0 | Nenhuma mudança de contagem. |

Não atualizar o contador em `BEFORE INSERT`: em
`INSERT ... ON CONFLICT DO UPDATE`, o ramo de conflito não insere a linha.
Contabilizar somente no `AFTER INSERT` realmente executado ou pelo delta
`OLD.perfil`/`NEW.perfil` no `AFTER UPDATE`. `ON CONFLICT DO UPDATE` pode acionar
triggers de statement de INSERT e UPDATE; ambos adquirem a mesma advisory
transaction lock, reentrante na mesma transação, sem bloquear um ao outro.
Triggers de linha aplicam apenas o delta real do conflito.

O decremento deve ser atômico e condicionado ao valor atual (`admin_count > 1`)
na linha singleton; não fazer `COUNT(*)` seguido de decremento sem lock. Se o
condicional não alterar uma linha, a trigger rejeita. Como contador, usuário e
auditoria participam da mesma transação, falha posterior, violação FK ou
rollback desfaz tudo.

**Responsabilidade única do marcador:** somente a função
`bootstrap_first_admin` pode transicionar `bootstrap_consumed=false` para true,
depois de validar contador zero e inserir o primeiro ADMIN. A trigger não lê,
consome nem rearma esse marcador; ela só mantém a contagem e rejeita a remoção
do último ADMIN. A função de recovery exige bootstrap já consumido e nunca
altera `bootstrap_consumed` ou `bootstrap_consumed_at`.

Uma instrução que altere vários usuários ADMIN é processada por linha sob o
mesmo lock; se qualquer decremento levaria a zero, a instrução/transaction
inteira falha e seus decrementos anteriores são desfeitos. `TRUNCATE` não
dispara triggers por linha: runtime e roles de escrita não recebem `TRUNCATE`;
V3 também deve rejeitar `TRUNCATE usuario` por trigger de statement ou manter
explicitamente essa operação restrita a manutenção aprovada.

Na configuração final (V4), triggers/funções também verificam o
`session_user` privilegiado para qualquer transição de perfil e bloqueiam
`arena_runtime`. Durante a etapa compatível V3, a trigger pode permitir a
transição legada para proteger imediatamente a contagem enquanto o GRANT V2
ainda existe; esse intervalo deve ser curto e não equivale à proteção final
contra promoção via DML direto.

### 4.5 Bootstrap consumido, recuperação e consistência

- V3 não pode inferir “primeiro uso” somente da contagem. Uma base legada pode
  ter perdido o último ADMIN antes da migration. O pipeline fornece, apenas para
  novo cluster/database aprovado, um placeholder Flyway não secreto e de uso
  único; migration default é não-novo.
- Database novo explicitamente classificado inicia `bootstrap_consumed=false`,
  timestamp nulo, `recovery_required=false`; legado com ADMIN inicia
  consumed=true/recovery=false; legado sem ADMIN inicia consumed=true/recovery=true.
- A função `bootstrap_first_admin` é a **única** responsável pela transição
  false→true: sob lock, confirma marcador falso, contador/contagem real zero,
  cria o primeiro ADMIN; a trigger atualiza o contador; em seguida a função
  marca consumed/timestamp e grava auditoria, tudo na mesma transação. Erro
  reverte os três efeitos.
- Promoção normal e triggers não mudam marcador. Recovery requer
  `bootstrap_consumed=true` e `recovery_required=true` ou estado inconsistente
  reconciliado; restaura/cria ADMIN e limpa `recovery_required`, sem alterar
  marcador/timestamp de bootstrap. Uma segunda chamada bootstrap continua
  impossível.
- Database legado com zero ADMIN usa recovery, não bootstrap. Database
  existente com ADMIN inicia consumed/recovery=false.
- Se estado singleton estiver ausente/duplicado ou `admin_count` divergir do
  `COUNT(*)`, funções e triggers falham fechado. Recovery normal não tenta
  adivinhar/corrigir estado.
- Reconciliation do estado deve ser uma operação offline separada, autorizada,
  com lock exclusivo, comparação explícita antes/depois e evento auditado. Se
  a auditoria não puder ser persistida, não executar a correção.

### 4.6 Compatibilidade V2 e privilégios das tabelas internas

V2 concede grants de tabela e `ALTER DEFAULT PRIVILEGES` que entregam
`SELECT/INSERT/UPDATE/DELETE` a `arena_runtime` para tabelas futuras em
`public`, e `USAGE/SELECT` em sequences. Portanto, criar `admin_lifecycle_state`
ou `admin_audit_event` em `public` requer **revogar explicitamente** os grants
indesejados após a criação; não confiar somente na ausência de GRANT escrito em
V3. Alternativa é schema interno pré-criado pelo provisionador, com grants
específicos para `arena_migrator`.

Também há EXECUTE público por default em funções PostgreSQL: V3 deve revogar
EXECUTE de PUBLIC nas funções privilegiadas e conceder somente às roles
aprovadas. Auditoria por identity precisa considerar a sequence ACL que V2
também amplia. Runtime não recebe SELECT/UPDATE/DELETE/TRUNCATE no estado ou
auditoria.

**Hardening preferido em V4:** remover os `ALTER DEFAULT PRIVILEGES` genéricos
de V2 que dão DML/sequence a todo objeto futuro no schema `public`; conceder
runtime explicitamente somente para novas tabelas/sequences de negócio em cada
migration correspondente. Se a decisão for preservar esses defaults, toda
tabela/sequence de controle, auditoria, autenticação ou função nova deverá ter
`REVOKE` explícito na mesma migration, com teste de catálogo. Não depender de
revisor lembrar disso depois.

## 5. Autorização de perfil e DML direto

### 5.1 Duas propriedades distintas

1. **Invariante de existência:** a contagem de ADMINs nunca chega a zero.
   O estado singleton + trigger transacional garante isso contra DML comum,
   independentemente de `UsuarioService`.
2. **Autorização do ator:** somente um ADMIN HTTP promove/rebaixa/exclui outro
   ADMIN. PostgreSQL não recebe o JWT e todas as requisições atuais usam
   `arena_runtime`; `current_user`/`session_user` apenas identificam a conta do
   pool, não a pessoa HTTP. A trigger não pode inferir o ator a partir dessa
   conexão.

`SET LOCAL`, GUC ou variável de sessão enviada pelo runtime não prova
autorização: qualquer cliente com a mesma credencial pode defini-la. Ela pode
ser metadado declarado para auditoria, nunca a decisão de autorização.

### 5.2 Alternativas para reduzir DML direto de perfil

| Opção | O que protege | Limite |
|---|---|---|
| Manter V2 e validar somente no Controller/Service | Clientes HTTP sem autoridade recebem 403; serviço protege operações normais. | SQL direto/SQL injection usando runtime pode atualizar perfil; DB não distingue ator HTTP. |
| Trigger de contagem sem restringir profile DML | Impede chegar a zero, mesmo em DML direto. | Runtime ainda pode inserir/promover ADMIN diretamente; não garante “só ADMIN promove”. |
| Revogar INSERT/UPDATE de `perfil` de runtime e usar funções | Bloqueia SQL direto pelo runtime; trigger preserva mínimo. | Função chamada pela mesma credencial e com `actor_id` livre não é prova de ADMIN; não conceder EXECUTE genérico e afirmar que resolveu autorização. |
| **Role `arena_admin_writer` separado + funções estreitas** | Runtime comum não altera perfil; role extra só executa funções de perfil/exclusão; a função consulta se `actor_id` ainda é ADMIN e trigger protege mínimo. | API ainda tem acesso ao pool privilegiado; DB confia que a aplicação só chama após autorizar o principal. Comprometimento do processo/credencial pode abusar do writer. Requer role, segredo, datasource e provisionamento adicionais. |
| Uma conexão DB por usuário HTTP | DB poderia mapear login ao ator | Não existe hoje; gestão de credenciais/sessões por pessoa é desproporcional para este backend. |

**Recomendação:** manter `@PreAuthorize` + autorização no serviço, e usar
`arena_admin_writer` separado se a aprovação incluir a restrição contra SQL
direto de `arena_runtime`. Revogar o GRANT de tabela `UPDATE/INSERT` que hoje
inclui `perfil`; conceder somente colunas pessoais permitidas e usar default
CLIENTE para INSERT. Como Hibernate pode incluir colunas não alteradas em
UPDATE/INSERT, o modelo deve usar atualização dinâmica/queries específicas ou
funções para profile. Mudanças de perfil/exclusões passam por funções
`SECURITY DEFINER` de allowlist. `arena_admin_writer` não recebe DML/DDL
direto. A função valida ator atual por ID no banco, sob o lock comum, e grava
auditoria. O `session_user` da conexão pode ser conferido pela função/trigger;
não depender de GUC.

Para isolar bootstrap/recovery do processo web, um role
`arena_offline_operator`, com EXECUTE apenas nas funções CLI, é opção mais
forte. Esses roles novos não substituem nem recebem membership de
`arena_runtime`, `arena_migrator` ou provisionador; a criação global é tarefa
do provisionador.

Mesmo nessa opção, o banco não prova que o usuário HTTP correspondente ao
`actor_id` realmente é o dono do request. Essa associação continua sendo
responsabilidade do código Spring Security. Um atacante que obtenha o segredo
de `arena_admin_writer` ou controle o processo que o contém pode chamar suas
funções; não afirmar isolamento contra comprometimento total da aplicação.

### 5.3 Limite transacional do datasource privilegiado

O endpoint valida ADMIN com o contexto Spring e extrai o actor id do principal.
O serviço então abre **uma transação no transaction manager do datasource
`arena_admin_writer`** e chama uma função única para a operação. Essa função,
na mesma sessão:

1. confere `session_user=arena_admin_writer`;
2. adquire o lock global antes de lockar usuário;
3. confirma que actor id ainda existe como ADMIN e recarrega target;
4. aplica regra de último ADMIN, atualiza usuário/contador e insere auditoria;
5. retorna resultado de negócio (`OK`, `LAST_ADMIN`, `NOT_ADMIN`, `NOT_FOUND`)
   sem lançar exceção para conflitos esperados.

A transação writer confirma o registro de auditoria/resultado; só depois o
controller traduz `LAST_ADMIN` para 409. Não iniciar uma transação JPA em
`arena_runtime` e chamar dentro dela o writer esperando atomicidade: são duas
conexões sem transação distribuída. Operações pessoais que não mudam perfil
continuam no datasource runtime.

## 6. Privilégios PostgreSQL

| Role | Privilégios propostos |
|---|---|
| `arena_runtime` | `CONNECT/USAGE`; SELECT de usuário e DML pessoal restrito. INSERT em `usuario` somente sem coluna `perfil`, que usa DEFAULT `CLIENTE`; UPDATE de colunas pessoais sem `perfil`; sem DELETE de usuário se a operação é administrativa. Auditoria por EXECUTE em função de append controlada, sem DML direto na tabela; sem UPDATE/DELETE/TRUNCATE e sem acesso ao estado. |
| `arena_migrator` | DDL Flyway, ownership dos objetos V3, funções e triggers. Não usado pela API/CLI. Sem `CREATEDB/CREATEROLE/SUPERUSER`. |
| Provisionador | Cria database/roles adicionais e aplica grants de roles cluster-wide. Segredo fora de aplicação/testes. |
| `arena_admin_writer` — se aprovado | CONNECT, USAGE e EXECUTE apenas nas funções de mudança de perfil/exclusão. Sem UPDATE/INSERT/DELETE direto nas tabelas, sem DDL, sem membership. Datasource separado do runtime. |
| `arena_offline_operator` — se aprovado | Credencial somente do processo CLI; EXECUTE apenas bootstrap/recovery/reconciliation aprovada. Sem DML/DDL direto e sem uso no servidor web. |

Ao restringir `usuario.perfil`, não basta conceder column-level UPDATE por cima
do GRANT amplo da V2: primeiro é necessário revogar o privilégio table-level,
depois conceder apenas colunas autorizadas. INSERT público deve omitir `perfil`
e receber DEFAULT `CLIENTE`; atualizar o mapping/SQL do JPA para não incluir a
coluna. Perfil ADMIN entra por função privilegiada.

Para `admin_lifecycle_state`, runtime não recebe ACL de tabela. Triggers/funções
executam como owner controlado. Para `admin_audit_event`, runtime não recebe
UPDATE, DELETE ou TRUNCATE. Se runtime mantiver INSERT:

- o owner é `arena_migrator`, não runtime;
- remover explicitamente os grants UPDATE/DELETE/SELECT herdados de V2;
- conceder somente INSERT e, se necessário, somente USAGE na sequence;
- preferir operação de serviço ou função limitada; não registrar payloads
  arbitrários.

INSERT-only impede editar/apagar eventos existentes, mas permite forjar novas
linhas a quem tenha a credencial runtime. Para integridade de autoria mais forte,
usar funções concedidas a role separado e/ou encaminhar cópia append-only para
um serviço de auditoria externo/WORM. Não apresentar INSERT-only como
autenticação do ator.

## 7. Bootstrap CLI

### 7.1 Processo sem listeners

O projeto usa Spring Boot 4.0.7 e não tem framework CLI. Proposta:

1. Criar launcher/configuração CLI estreita (`AdminCliLauncher` /
   `AdminCliApplication`) com `WebApplicationType.NONE` definido antes do
   refresh. Não iniciar `ArenaApplication` no modo normal nem deixar o
   componente de comando no component scan web.
2. Importar somente JDBC, `PasswordEncoder` BCrypt comum e serviços/comandos
   CLI; não carregar controllers, `JwtService`, SecurityFilterChain, OpenAPI ou
   scheduler. O contexto pode chamar funções PostgreSQL via `JdbcTemplate` /
   `TransactionTemplate`, sem JPA se não for necessário.
3. Registrar runner somente nesse contexto; exigir exatamente um comando
   (`bootstrap-admin` ou `recover-admin`); comandos extras/desconhecidos falham.
4. Configurar datasource CLI próprio com `arena_offline_operator`; não herdar
   `SPRING_DATASOURCE_*`, senha runtime, Flyway ou provisionador. Uma
   configuração CLI própria fornece somente URL/credencial offline; Flyway
   fica desabilitado e a operação falha se as funções V3 requeridas não
   existirem.
5. Depois do runner, fechar `ApplicationContext`/pool e retornar exit code.
   Confirmar ausência de `WebServer`, porta/listener e scheduler em teste.

`ApplicationRunner` executado no contexto web não basta: no fluxo normal o
servidor pode ser criado durante o refresh antes de o runner rodar. O tipo NONE
precisa ser escolhido pelo launcher antes do refresh, e o runner não pode ser
registrado no component scan da aplicação web. Essa estratégia usa APIs já
trazidas pelo Boot; não exige Picocli/Spring Shell.

### 7.2 Bootstrap de uso único

Fluxo recomendado:

1. O operador executa o comando em host/runner autorizado, sem tráfego HTTP.
2. CLI exige identidade operacional e referência de change/ticket não secreta.
3. Nome/email são validados e a senha é lida por `Console.readPassword()` ou
   pipe de secret manager sem echo. Sem console nem canal seguro, falhar fechado;
   nunca cair para argumento de processo ou leitura ecoada.
4. Validar senha com ≥12 caracteres e confirmação; nunca logar input, hash,
   DTO ou SQL bind. Codificar via `PasswordEncoder` BCrypt comum à aplicação.
5. Confirmar que as migrations requeridas foram aplicadas com credenciais
   Flyway/migrator aprovadas e que o ingresso HTTP continua fechado até concluir
   bootstrap/recovery. CLI falha fechado se estado/funções V3 estiverem ausentes.
6. Abrir transação com datasource da role offline, adquirir o lock comum,
   verificar contador, marcador, email e consistência.
7. Somente se `bootstrap_consumed=false`, `recovery_required=false` e
   `admin_count=0`, chamar `bootstrap_first_admin`, criar ADMIN, consumir
   marcador e gravar evento de auditoria na mesma transação. A trigger
   incrementa o contador; a função confirma consistência antes de commit.
8. Segunda execução retorna conflito e exit code não zero; nenhum token, senha
   ou hash aparece em stdout/stderr.

O role offline não recebe DML direto; só `EXECUTE` na função bootstrap e
recovery. A função não recebe senha em claro se a CLI a codifica primeiro; o
processo mantém o hash apenas em memória e a tabela de auditoria jamais o recebe.
Como o hash BCrypt não permite inferir o comprimento original, a validação dos
12 caracteres ocorre antes da codificação na CLI/API.

## 8. Comando de recuperação

`recover-admin` é um executável/subcomando distinto, offline, com perfil e role
separados. Não existe rota HTTP de recovery, e o servidor web não carrega a
credencial `arena_offline_operator`.

Fluxo:

1. exigir autenticação operacional fora do argumento (runner/SSO/IAM aprovado),
   justificativa/change ticket e confirmação explícita; operador informado em
   texto livre serve como registro, não como prova de identidade;
2. adquirir o mesmo lock e ler estado + contagem real;
3. exigir `bootstrap_consumed=true`, `admin_count=0` e
   `recovery_required=true` (ou estado inconsistente reconciliado). Nunca
   resetar/reabrir bootstrap;
4. operar somente se não existir ADMIN. Se o target existir como CLIENTE,
   promovê-lo; se não existir, criar novo usuário ADMIN. Validar identidade e
   email por procedimento operacional e pedir nova senha ≥12 em canal seguro;
5. trigger atualiza contador; função grava `RECOVERY`, ator operador, alvo,
   origem CLI, motivo e timestamps no mesmo commit; `last_recovery_at` é
   atualizado;
6. falha em qualquer passo reverte usuário, contador, marcador/estado e auditoria.

Se já existir um ADMIN, recovery recusa criar outro ou alterar conta; a
administração normal trata o caso. `bootstrap_consumed=false` direciona para
bootstrap, não recovery. Recovery limpa somente `recovery_required`; eventos de
auditoria anteriores são somente append e nunca são reescritos.

### Estado inconsistente

Se a linha singleton estiver ausente/duplicada, contador divergente de
`COUNT(*)`, trigger ausente/desabilitada ou auditoria indisponível, o comando
normal falha fechado e não altera usuário. Propõe-se um modo de reconciliação
offline distinto, com autorização operacional, lock de manutenção, verificação
dos dados reais, snapshot antes/depois, motivo e evento `RECONCILE`. Ele não
reabre o estado de bootstrap nem volta `bootstrap_consumed` para false. Se a
tabela/auditoria não puder registrar a reconciliação, exigir procedimento DBA
com backup e log externo aprovado; não improvisar ajuste manual silencioso.

## 9. Auditoria administrativa

### 9.1 Esquema proposto

Tabela própria, por exemplo `admin_audit_event`; identificadores de usuário
devem ser mantidos como valores históricos sem FK `ON DELETE SET NULL`, para
que excluir um ADMIN não altere um evento anterior.

| Campo conceitual | Tipo/uso |
|---|---|
| `event_id` | BIGINT identity, PK. |
| `occurred_at` | `TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`. |
| `operation` | CHECK: `BOOTSTRAP`, `RECOVERY`, `PROMOTE`, `DEMOTE`, `ADMIN_DELETE`, `ADMIN_PASSWORD_RESET`, `PASSWORD_CHANGE`, `RECONCILE`. |
| `outcome` | CHECK: `SUCCEEDED` ou `REJECTED`. |
| `source` | CHECK: `HTTP`, `BOOTSTRAP_CLI`, `RECOVERY_CLI`, `RECONCILE_CLI` ou `DB_DIRECT`. |
| `actor_type` | `USER`, `OPERATOR` ou `DATABASE`. |
| `actor_user_id` | Nullable; id do usuário HTTP, sem FK destrutiva. |
| `actor_ref` | Identidade verificada do operador/role; nunca senha/token. |
| `target_user_id` | Nullable, id histórico sem FK destrutiva. |
| `target_email_snapshot` | Opcional; PII sujeita à retenção aprovada. |
| `profile_before`, `profile_after` | Valores limitados a `ADMIN`/`CLIENTE`; nulos quando não aplicável. |
| `reason` | Texto de tamanho limitado para recovery/reconciliation; filtrado contra segredos. |
| `change_ticket`, `request_id` | Referências de operação/correlação, sem credenciais. |
| `error_code` | Código estável como `LAST_ADMIN`, sem stack trace/payload. |

Índices: `(occurred_at, event_id)`, `(actor_user_id, occurred_at)`,
`(target_user_id, occurred_at)` e `(operation, occurred_at)`. Restringir
`reason`/snapshots segundo retenção e LGPD. Não armazenar corpo HTTP, senha,
hash, JWT, segredo, SQL com bind values ou stack trace.

### 9.2 Atomicidade e eventos rejeitados

- Evento de sucesso é inserido na mesma transação da mudança de perfil/usuário,
  bootstrap ou recovery. Se a auditoria não inserir, a mudança não confirma.
- No desenho final, a função de operação (writer/offline) grava o evento
  enriquecido; a trigger se limita à invariante/contador, evitando evento
  duplicado e deixando o ator HTTP/CLI no fluxo transacional correto. Durante
  V3 legado, manter a janela de corte sem mutações de ADMIN.
- Para recusa esperada do último ADMIN, não lançar exceção antes de gravar o
  evento na mesma transação. Sob o lock, a operação retorna um resultado
  `LAST_ADMIN`, grava `REJECTED`, confirma somente o evento e depois a camada
  HTTP converte o resultado em 409.
- Se a operação já iniciou e a transação precisa rollback, registrar recusa
  depois do rollback em transação independente (`REQUIRES_NEW` em bean separado
  ou `TransactionTemplate` externo). Não depender de self-invocation de
  `@Transactional`.
- Trigger que rejeita SQL direto por exceção não consegue inserir e preservar
  auditoria na própria transação: o INSERT de log também sofre rollback.
  Tentativas SQL diretas requerem server log/pgAudit/collector externo se também
  precisarem de registro durável.

### 9.3 Append-only e confiança

Owner é `arena_migrator`; roles de aplicação não são owner. V2 concede DML a
tabelas futuras: V3 deve revogar grants herdados e então conceder somente o
mínimo. O desenho preferido não dá INSERT direto na tabela: runtime recebe
EXECUTE em função de append com validação de campos/operation allowlist; as
funções privilegiadas também registram eventos na mesma transação. A função
owner/security-definer usa a sequence, sem conceder seu acesso diretamente ao
runtime. Se, em vez disso, for aprovada inserção direta, runtime recebe somente
INSERT e USAGE (nunca SELECT/UPDATE/DELETE) e a V2 `SELECT` sequence deve ser
explicitamente revogada.

`arena_runtime` pode acrescentar eventos pela função controlada, mas não
alterar/excluir eventos antigos nem tem INSERT/SELECT direto na tabela. A
função pode validar shape, operation, perfil e existência do alvo; ainda assim,
ator HTTP e motivo são valores afirmados pelo serviço. Com credencial
compartilhada, o banco não prova qual pessoa HTTP originou a chamada. Não usar
GUC/`SET LOCAL` como prova de autorização. Para resistência à adulteração por
comprometimento do runtime, exportar eventos para armazenamento externo
append-only/WORM ou serviço de auditoria de credencial independente.

### 9.4 Origem do ator

- Em HTTP, `actor_user_id` vem do principal autenticado no `SecurityContext`
  (`Usuario.id` carregado pelo filtro JWT), nunca de campo do DTO. A função
  privilegiada revalida esse id como ADMIN sob o lock.
- Em CLI, `actor_ref` deve vir de identidade verificável do executor
  (identidade de workload/SSO/IAM do runner ou mecanismo operacional
  equivalente), além do ticket/justificativa. `--operator-id` digitado sozinho
  é apenas uma declaração.
- A conexão PostgreSQL registra `arena_admin_writer`/
  `arena_offline_operator`, não a pessoa. Armazenar ambos: ator de negócio
  validado pela aplicação/runner e `source`/role da conexão. Não os confundir.
- Se runtime tiver INSERT-only, a autoria por `actor_user_id` continua sendo
  confiança na aplicação; o banco garante que o evento existente é
  append-only, não que o autor HTTP informado é inforjável.

## 10. API e política de senhas

| Rota proposta | Acesso | DTO/efeito |
|---|---|---|
| `POST /usuarios` | Público | DTO de cadastro sem `perfil`; cria sempre CLIENTE. |
| `PUT /usuarios/me` | Autenticado | Somente dados pessoais; não contém senha nem perfil. |
| `PATCH /usuarios/me/senha` | Autenticado | Senha atual + nova senha ≥12; nunca atualiza perfil. |
| `PUT /usuarios/{id}` | ADMIN, se mantida | Dados pessoais do alvo; sem senha/perfil. |
| `PATCH /usuarios/{id}/perfil` | ADMIN | DTO de perfil limitado a `ADMIN`/`CLIENTE`; lock/contagem/função. |
| `DELETE /usuarios/{id}` | ADMIN | Exclusão via serviço/função; trigger preserva último ADMIN e FK mantém regra de agendamentos. |
| `PATCH /usuarios/{id}/senha` | ADMIN | DTO separado para reset administrativo, nova senha ≥12 e eventual `password_change_required`; nunca dentro do PUT de perfil/dados. |
| Login com senha legada que exige troca | Público | Não devolve JWT full; devolve desafio de uso único e escopo exclusivo de troca. |

`@PreAuthorize("hasRole('ADMIN')")` permanece no endpoint e também deve
proteger o serviço de operação administrativa; o actor ID vem do principal,
nunca do DTO. O banco revalida que o actor continua ADMIN dentro da função
privilegiada. Auto-rebaixamento/autoexclusão só confirma com pelo menos outro
ADMIN; caso contrário 409. Erros de permissão são 403, usuário ausente 404,
conflito do último ADMIN 409 e validação de senha 400.

### Política de 12 caracteres e contas existentes

- Aplicar mínimo 12 no cadastro, troca própria, reset administrativo,
  bootstrap e recovery. Validar confirmação/normalização conforme política
  aprovada antes de BCrypt.
- Não aplicar `@Size(min=12)` cegamente ao campo recebido no login: hashes
  existentes não revelam o comprimento original e isso poderia bloquear
  credenciais antigas antes do reset.
- Uma migration não pode descobrir tamanho de senha original a partir de BCrypt.
  Para cumprir “todas as contas”, marcar todos os usuários existentes
  `password_change_required=true` e exigir troca antes de emitir JWT de acesso
  normal. Isso pode exigir troca até para quem já usava 12+ caracteres.
- Proposta de fluxo: após autenticação correta de conta marcada, emitir desafio
  curto de propósito `PASSWORD_CHANGE`, sem authorities de API; filtro autoriza
  esse token somente no endpoint de troca. Ao gravar novo hash ≥12, limpar a
  flag e emitir JWT normal. Alternativa é reset out-of-band, mas o repositório
  atual não contém fluxo de email/recuperação.
- Para tornar o desafio realmente de uso único, V5 pode adicionar
  `credential_version` à conta. O desafio assinado leva `purpose` e a versão
  atual; a troca compara a versão sob lock, grava o hash, limpa
  `password_change_required` e incrementa a versão na mesma transação. Replay ou
  reset concorrente com versão antiga é recusado.
- O token/desafio de troca deve ter propósito/expiração próprios, não receber
  `ROLE_ADMIN`/`ROLE_CLIENTE` e não satisfazer apenas `.authenticated()` nas
  demais rotas. A cadeia deve permitir explicitamente o desafio somente no
  endpoint de troca e exigir autoridade normal nos endpoints de usuário.
  Substituir a liberação genérica atual de `/auth/**` por regras específicas
  para login e troca de senha. O filtro JWT atual precisa distinguir esse token
  do JWT de acesso.
- O reset administrativo usa endpoint específico e/ou procedimento de reset,
  define uma senha temporária de pelo menos 12 caracteres por canal seguro e
  marca a conta para troca; não retorna nem audita a senha. Alternativa é fluxo
  de reset por email, que depende de infraestrutura ainda ausente.
- Separar DTO de alteração pessoal do DTO de senha evita que um PUT de perfil
  altere senha acidentalmente. Remover o `senha` de `UsuarioRequestDTO` e não
  logar entidade `Usuario` (`@Data` inclui senha no `toString`).

Atualizar OpenAPI e README para novos DTOs, `/usuarios/{id}/perfil`, mudanças
de senha e respostas 403/409. Testar formato real da cadeia Security, pois
algumas respostas 401/403 podem ser geradas antes do advice MVC atual.

## 11. Testes PostgreSQL e HTTP

Nenhum teste foi executado nesta etapa. Plano para a implementação:

### Bootstrap/CLI

- Banco sem ADMIN: bootstrap cria exatamente um, marcador consumido e hash
  BCrypt salvo.
- Banco com marcador consumido ou ADMIN existente: bootstrap falha sem mutação.
- Duas execuções simultâneas: uma confirma e outra falha por estado consumido.
- Erro depois do INSERT e antes do marcador/auditoria: rollback integral.
- Recovery com marcador consumido e zero ADMIN: restaura/cria alvo e mantém o
  marcador consumido; recovery com bootstrap ainda pendente direciona para o
  comando bootstrap.
- Recovery recusado por ADMIN existente, inconsistência ou motivo ausente:
  nenhuma mudança; quando o estado permite, evento de recusa contém operador,
  target, ticket e motivo, sem segredo.
- CLI sem console/secret channel seguro: falha sem solicitar senha em echo ou
  usar argv.
- `WebApplicationType.NONE`: nenhum `WebServer`, porta/listener ou scheduler;
  web normal não registra runner.
- CLI não executa Flyway e não usa datasource/segredo migrator/provisionador.

### Cadastro e senha

- Cadastro com `perfil=ADMIN` e sem perfil: nunca persiste ADMIN; resposta
  rejeitada ou CLIENTE conforme contrato explicitado.
- Senhas de 11 e 12 caracteres nos endpoints de criação/troca/reset/CLI.
- Atualização de dados pessoais não requer nem altera senha/perfil.
- Troca de senha exige credencial atual ou desafio limitado; hash muda, sem
  valor/hash nos logs/auditoria.
- Conta legada marcada não recebe JWT normal antes da troca; JWT de desafio
  não acessa rotas gerais; após troca, acesso normal.
- Replay do challenge após troca/reset simultâneo falha por `credential_version`
  consumida.
- Reset administrativo e bootstrap/recovery validam 12 antes do encoder.

### Autorização e lifecycle

- ADMIN promove CLIENTE via `PATCH`; CLIENTE recebe 403 e nenhuma função
  privilegiada é chamada.
- Rebaixamento/exclusão com vários ADMINs pode confirmar; com último retorna
  409 e conta permanece.
- Auto-rebaixamento/autoexclusão com outro ADMIN conforme decisão aprovada;
  sendo o último, 409.
- Usuário removido/demovido não mantém autoridade em JWT já emitido: o próximo
  request recarrega perfil do banco e recebe 403 nas operações admin.
- Operações com agendamento continuam respeitando FK/regra existente.

### Concorrência

- Duas remoções concorrentes iniciadas com dois ADMINs: no máximo uma redução
  confirma; estado final count=1.
- Rebaixamento concorrente com exclusão e promoção concorrente com rebaixamento:
  todas as ordens serializadas pelo mesmo lock; count nunca zero.
- Bootstrap/recovery concorrentes: um evento/usuário conforme marcador e estado.
- Testar duas conexões independentes e sincronização; verificar tabela de
  controle, `COUNT(*)`, perfis e auditoria após commit/rollback.

### DML e grants

- Migration/backfill: instalação nova explicitamente marcada e vazia fica
  `bootstrap_consumed=false/recovery_required=false`; database existente com
  zero ADMIN fica consumed/recovery-required; banco com um/vários ADMIN fica
  consumed/não-recovery e contador correto.
- Marcador “nova instalação” com dados preexistentes falha fechado; placeholder
  ausente usa default false.
- DML de runtime tentando `UPDATE perfil`, INSERT de ADMIN explícito, DELETE de
  ADMIN ou TRUNCATE falha por ACL/trigger conforme desenho escolhido.
- DML autorizado por `arena_admin_writer`/`arena_offline_operator` só ocorre
  via funções e não deixa count<1.
- Runtime não consegue SELECT/INSERT/UPDATE/DELETE/TRUNCATE direto na auditoria
  ou estado; EXECUTE na função de append permitida e rejeição de alteração/
  exclusão de eventos são testadas explicitamente.
- Auditoria registra operação/ator/alvo/antes/depois/data/source para bootstrap,
  recovery, promoção, rebaixamento e exclusão; rejected last-admin registra
  `409` sem gravar senha/hash/token.
- INSERT de auditoria e mutation confirmam ou fazem rollback juntos; rejeição
  esperada continua registrada após commit do resultado `REJECTED`.
- `arena_migrator` executa V3/V4; nenhum dos papéis de app tem DDL,
  `CREATEROLE`, `CREATEDB` ou `SUPERUSER`.
- Rollback entre usuário/state/audit não deixa contadores/eventos parciais.
- Reconcile encontra inconsistência, falha sem side effect no modo normal e
  exige execução offline auditada no modo de correção.

Todo teste com PostgreSQL usa `@IsolatedPostgresIntegrationTest`,
`TEST_POSTGRES_URL` em `127.0.0.1:<porta>/arena_flyway_test`, senhas runtime e
migrator separadas, container descartável `tmpfs`; nunca `arena`, `arena_dev` ou
`arena_postgres_data`.

Para primeira migration de uma base de teste vazia, o initializer H-03 deve
receber uma variável de teste separada (por exemplo,
`TEST_H04_INITIAL_INSTALL=true`) e mapear somente esse valor validado para o
placeholder Flyway. Não usar fallback de `H04_INITIAL_INSTALL` de produção.
Todos os outros testes usam o valor seguro/default adequado ao estado da base.
Se roles separados forem aprovados, ampliar `TestPostgresSettings` para exigir
credenciais `TEST_*` próprias de `arena_admin_writer` e
`arena_offline_operator`, distintas das de runtime/migrator. Manter o regex
exato de loopback/database do H-03 e não aceitar fallback para `SPRING_*`,
`.env` ou segredos de produção. O provisionador fica restrito ao setup do
container descartável.

## 12. Sequência de implementação proposta

### Fase 0 — especificação e inventário operacional

- Definir roles adicionais, origem confiável do operador CLI, política de
  auditoria/retention e reset forçado de senhas legadas.
- **Arquivos:** documentação de runbook/decisões.
- **Aceite:** contratos, status, owners, credenciais e change process aprovados;
  sem segredos nos repositórios.

### Fase 1 — estratégia de permissões e provisionamento

- Atualizar desenho/script do provisionador para roles adicionais aprovados,
  sem dar DDL ou membership amplo a runtime; criar pool específico somente se
  aprovado.
- O script H-02 é one-shot para database novo; não o rerodar em instalação
  existente. Criar roles novos por procedimento de upgrade separado e aprovado.
- Testar roles no PostgreSQL descartável.
- **Arquivos prováveis:** `scripts/postgres/provision-arena-roles.sql`,
  `.env.example` somente com nomes/placeholders,
  `src/main/resources/application.properties`/configuração de datasource
  (nenhum segredo em arquivo versionado).
- **Aceite:** catálogo comprova atributos e grants mínimos; nenhuma role nova
  ganha `SUPERUSER/CREATEDB/CREATEROLE`.

### Fase 2 — V3 expand: estado, triggers e auditoria

- Implementar V3 com backfill singleton, contador, triggers e auditoria; V3
  garante o mínimo de um ADMIN mesmo no período de corte.
- V3 ainda não deve afirmar que `arena_runtime` não consegue mudar `perfil` se
  o GRANT amplo da versão antiga continua ativo. O hardening de autorização e
  ACL fica em V4.
- **Arquivos:** `src/main/resources/db/migration/V3__admin_lifecycle.sql`
  (nome ilustrativo), entidades/repositories de estado/auditoria, funções e
  triggers, grants e testes de migration.
- **Aceite:** cenários 0/1/vários ADMINs passam; contador=COUNT; last-admin
  protegido no trigger; uma falha reverte migration.

### Fase 3 — CLI offline

- Criar launcher não web, separar config de senha/Security/web/scheduler,
  bootstrap e recovery com conexões/roles offline aprovadas; não executar
  Flyway.
- **Arquivos:** `ArenaApplication`/configuração de scheduling e Security web,
  launcher/configuração `AdminCliApplication`, configuração CLI de datasource,
  runners/comandos, beans PasswordEncoder/datasources e testes CLI.
- **Aceite:** nenhum listener/scheduler; command funciona uma vez; segunda
  execução falha; segredo não aparece em processo/log/arquivos.

### Fase 4 — serviço e API

- DTO signup sem perfil; DTOs separados de pessoa, senha e role; `PATCH
  /usuarios/{id}/perfil`; autorização em controller/service; 409 de último
  ADMIN; chamada da função de perfil pela conexão apropriada.
- **Arquivos:** `UsuarioController`, `UsuarioService`, `UsuarioRepository`,
  `Usuario` (mapping de `perfil` sem INSERT/UPDATE runtime), DTOs, mapper,
  `application.properties`/configuração do datasource writer,
  `SecurityConfig`, gateway do datasource writer,
  `GlobalExceptionHandler`, `OpenApiConfig`, README e testes HTTP.
- **Aceite:** HTTP status e side effects corretos; perfil nunca vem do signup;
  método de perfil obtém actor do principal, nunca do corpo.

### Fase 5 — contrato final de grants

- Após drenar versões antigas, revogar INSERT/UPDATE/DELETE de tabela
  incompatíveis com o novo caminho, aplicar grants por coluna/função, grants
  append-only e revoke do default ACL nas tabelas internas.
- **Arquivos:** migration V4 de hardening e provisionamento.
- **Aceite:** versões antigas não atendem mais tráfego; novos fluxos passam;
  `arena_runtime` não pode alterar perfil nem histórico.

### Fase 6 — senha legada e policy

- Adicionar indicador de troca obrigatória; migrar contas existentes para
  reset; implementar token/challenge limitado ou reset out-of-band aprovado;
  separar mudança de senha dos PUTs pessoais.
- **Arquivos:** migration V5 para `password_change_required` e
  `credential_version`, `Usuario`, DTOs, `AuthService`, `AuthController`,
  `LoginResponseDTO`, `JwtService`, `JwtAuthenticationFilter`,
  `SecurityConfig`, services e testes.
- **Aceite:** nenhum usuário não conforme recebe sessão normal antes do reset;
  todos os caminhos de gravação exigem 12 caracteres.

### Fase 7 — auditoria, recovery/reconcile e rollout

- Validar `actor_ref`, change ticket, retenção, recuperação, export para storage
  protegido e runbook. Ensaio de deploy/rollback em ambiente isolado.
- **Aceite:** eventos success/rejected/CLI atômicos, nenhum segredo registrado,
  recuperação autorizada e testada, comparação contador/tabela exata.

Resumo de dependências, testes e riscos por fase:

| Fase | Dependência | Testes principais | Risco/aceite específico |
|---|---|---|---|
| 0 — especificação | Decisões técnicas/operacionais | Revisão dos contratos, threat model e runbook por DBA/SecOps | Não avançar sem identidade verificável do operador e classificação de instalação. |
| 1 — roles | Provisionador aprovado | Catálogos de atributos, memberships e grants no PG descartável | Segredos/roles globais; falha fechada se roles já existirem ou grants excederem a matriz. |
| 2 — V3 | Roles preparados e writers drenados | Backfill zero/um/muitos, permissões, trigger concorrente, rollback Flyway | ACCESS EXCLUSIVE/timeout; contador precisa coincidir antes do commit. |
| 3 — CLI | V3 e funções implantadas | WebServer ausente, Flyway/scheduler ausentes, segredo oculto, execução única | Launcher amplo pode carregar config web/JWT; usar contexto CLI mínimo. |
| 4 — API | Função writer e auditoria disponíveis | JWT real, 403 para CLIENTE, 409 último ADMIN, auditoria ator/alvo | DTOs quebram clientes antigos; atualizar OpenAPI/README. |
| 5 — V4 grants | Todas as instâncias antigas drenadas | DML direto runtime negado, funções writer aprovadas, cadastro cliente funciona | V4 aplicada com código antigo causa falha; cutover sem versão mista. |
| 6 — V5 senhas | Challenge/reset e encoder alinhados | 11/12 chars, replay challenge, sessões limitadas, todos os fluxos | Reset obrigatório pode bloquear usuários até trocar senha; comunicar e ensaiar. |
| 7 — rollout/auditoria | Fases anteriores aprovadas | eventos success/rejected/CLI, sem segredo, recovery/reconcile | Audit failure cancela mutação; export externo/retention precisam owner. |

**Rollout recomendado:** não fazer rolling deployment de V3/V4 com a versão
antiga. Drenar instâncias antes do corte; aplicar V3/V4 e V5 de forma coordenada
enquanto o ingress está fechado/manutenção; subir o binário novo; executar/
confirmar CLI e verificações; só então reabrir tráfego. V3 pode ser expandida e
testada antes, mas a janela entre V3 e V4 não deve ser tratada como proteção
contra promoção por DML direto. Se downtime não for aceitável, é necessário um
desenho expand/contract adicional com auditabilidade durante a janela.

## 13. Rollback e compatibilidade

V3 deve usar somente operações PostgreSQL transacionais. Flyway rollback por
remoção manual da linha de histórico não é estratégia. Se V3 falhar antes do
commit, PostgreSQL reverte DDL/DML da migration; verificar lock timeout e
relatório da migration.

Roles novos são objetos de cluster criados pelo provisionador, fora da
transação Flyway; se V3 falhar, eles podem continuar existindo sem grants/uso.
Registrar esse estado operacional e não apagá-los automaticamente. Qualquer
limpeza é uma ação administrativa separada, após confirmar que não há objetos
dependentes.

Após commit e geração de eventos, não apagar estado/auditoria para “voltar ao
zero”. Corrigir por migration forward. Antes de V4, a trigger de contagem
protege a invariante, mas o GRANT legado ainda pode permitir mudança direta de
perfil. V4 e V5 só são aplicadas com instâncias antigas drenadas. Rollback da
aplicação após V4/V5 requer migration forward temporária/revisada e cutover
controlado, não reverter V3 nem reabrir DML amplo sem aprovação.

Compatibilidade de estado:

| Estado no início de V3 | Inicialização | Bootstrap/recovery |
|---|---|---|
| Banco novo, zero usuários, classificado explicitamente como instalação nova | `admin_count=0`, `bootstrap_consumed=false`, `recovery_required=false` | `bootstrap-admin` disponível uma vez. |
| Database legado, zero ADMIN | `admin_count=0`, `bootstrap_consumed=true`, `recovery_required=true` | Usar recovery offline; bootstrap permanece fechado. |
| Existente, um ADMIN | `admin_count=1`, `bootstrap_consumed=true`, `recovery_required=false` | Não permitir bootstrap; administração segue API normal. |
| Existente, vários ADMINs | `admin_count=N`, `bootstrap_consumed=true`, `recovery_required=false` | Administração normal; nunca remover até zero. |
| Marcado como novo, mas contém usuário | Migration falha antes de publicar estado/trigger | Corrigir a classificação; não prosseguir automaticamente. |

Migration deve bloquear escrita, contar e criar estado atomicamente. Se o
backfill falhar ou encontrar perfil fora do CHECK, abortar e pedir correção
administrativa aprovada; não escolher ADMIN automaticamente.

## 14. Critérios de aceitação

H-04 só é aceito quando:

1. bootstrap CLI funciona sem HTTP/scheduler/Flyway e é transacional, escondendo
   senha e consumindo marcador uma única vez;
2. signup cria CLIENTE mesmo com JSON malicioso e DTO público não declara perfil;
3. somente a rota/método ADMIN promove/rebaixa via endpoint dedicado;
4. nenhuma operação da aplicação, função ou DML permitido leva count a zero;
5. a contagem singleton coincide com `COUNT(ADMIN)` em todos os testes e na
   verificação operacional;
6. operações concorrentes testadas com conexões independentes não deixam zero
   ADMIN;
7. recuperação CLI é independente, exige justificativa/autorização, não reabre
   bootstrap e registra auditoria;
8. senhas novas/alteradas em todos os fluxos têm ≥12 caracteres; contas legadas
   cumprem reset obrigatório antes de acesso normal;
9. auditoria registra as operações necessárias, não contém segredos, não aceita
   UPDATE/DELETE/TRUNCATE do runtime e tem caminho para export append-only;
10. grants efetivos seguem a matriz, V2 default privileges foram neutralizados
    para estado/auditoria e profiles sensíveis;
11. testes de integração passam apenas no PostgreSQL descartável com guard H-03
    e credenciais H-02 separadas;
12. runbook de deploy/recovery/rollback foi ensaiado sem uso dos recursos
    protegidos `arena`, `arena_dev` ou `arena_postgres_data`.

## 15. Riscos, limitações e decisões técnicas restantes

1. **Role writer adicional:** separar o DML de perfil por role/login e datasource
   aumenta secrets, pools e provisioning; alternativa é aceitar autorização
   apenas na aplicação e declarar que runtime SQL pode promover usuários. A
   escolha deve ser explícita.
2. **Identidade do operador:** CLI deve receber identidade verificável do
   runner/SSO/IAM; argumento autoescrito não comprova quem executou.
3. **Ator HTTP na auditoria:** um campo actor enviado pela app não é prova
   criptográfica para PostgreSQL. Com role compartilhado, a auditoria confia no
   serviço; um comprometimento da app pode falsificar evento. WORM/collector
   externo é necessário contra esse nível de ameaça.
4. **Rejeição de DML direto:** exceção aborta transação e também o INSERT de
   auditoria local; log durável da tentativa exige PostgreSQL server logs,
   pgAudit ou canal externo.
5. **Senhas já armazenadas:** comprimento original é irrecuperável a partir do
   hash; forçar troca em todas as contas pode exigir novo fluxo de challenge/
   reset e suporte operacional.
6. **Migração e triggers:** tabela V2 pode ter volume/locks; ACCESS EXCLUSIVE e
   deploy das versões antigas precisam de janela, timeout e ensaio.
7. **Migrations versus roles:** Flyway não cria roles. Role `arena_admin_writer`
   e `arena_offline_operator` precisam estar provisionadas antes de V3 por
   conta administrativa aprovada.
8. **Bypass de superuser/owner:** provisionador/superuser pode desabilitar
   trigger, alterar contador ou apagar audit. Esses papéis são parte da base
   confiável e devem ser restritos, monitorados e auditados fora da aplicação.
9. **Reconcile:** correção automática de contador pode mascarar corrupção.
   Modo normal falha fechado; reconciliation exige aprovação, evidência e evento
   externo caso a tabela de audit também esteja inconsistente.
10. **Retenção/PII:** snapshots de email e motivos da auditoria requerem política
     de retenção e controle de leitura. Hashes, senhas, tokens e segredos nunca
     entram no esquema.
11. **Classificação de instalação:** V3 não consegue provar pelo estado atual se
    um database sem usuários é recém-criado ou se seus usuários foram removidos
    antes da migration. A migration usa default seguro (não-novo) e exige uma
     afirmação não secreta de pipeline/provisionador para iniciar o marcador
     `bootstrap_consumed=false`; é preciso
    aprovar quem fornece essa classificação e guardar sua evidência.
12. **Credencial do writer:** aprovar o role adicional e o datasource/secret
    isolado usado somente após autorização ADMIN. Sem ele, conceder DML de
    perfil ao runtime significa aceitar que SQL direto do runtime pode promover.
13. **Política de comprimento:** definir contagem de caracteres Unicode,
    normalização e máximo compatível com BCrypt (limite por bytes). Testar
    entradas multibyte para que “12 caracteres” não tenha interpretações
    divergentes.
14. **Reset administrativo e legado:** escolher entre senha temporária ≥12 com
    troca obrigatória e reset de usuário por canal out-of-band. O repositório
    não tem infraestrutura de email/recuperação pronta.

## 16. Limites desta etapa

Foi realizada leitura estática dos documentos obrigatórios, fontes, migrations,
provisionador, testes e `pom.xml`. Não foram executados build/testes/SQL, não se
conectou a banco, não se iniciou container, `.env` real não foi lido e nenhuma
operação Git foi executada. **O único arquivo criado é
`docs/26-projeto-tecnico-h04-bootstrap-admin.md`; código, banco, containers e
histórico Git permanecem sem alteração por esta etapa.**
