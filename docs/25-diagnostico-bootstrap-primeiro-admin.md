# Etapa 8 — diagnóstico do H-04: bootstrap do primeiro ADMIN

**Escopo:** auditoria estática e proposta técnica. Nenhuma implementação,
migration, execução de teste ou operação de banco foi realizada nesta etapa.
O `.env` real não foi lido; nenhuma credencial ou segredo foi consultado.

## 1. Resumo executivo

O código atual **não tem bootstrap do primeiro administrador**. O cadastro
público `POST /usuarios` é seguro quanto ao perfil: mesmo se o JSON enviar
`"perfil": "ADMIN"`, `UsuarioService.salvar` grava `CLIENTE`. O acesso às rotas
de administração de usuários é restrito a `ADMIN`.

Entretanto, depois de criada uma conta `ADMIN` por algum meio externo, a API
permite que um administrador altere ou exclua qualquer usuário, inclusive a si
mesmo. Não existe verificação de quantos administradores restariam. Também não
há transação ou lock que coordene alterações concorrentes de perfil/exclusões.
Uma instalação vazia, portanto, não consegue criar o primeiro ADMIN pela API;
uma instalação já operável pode perder todos os ADMINs por operações
administrativas válidas individualmente.

**Recomendação para aprovação:** provisionar o primeiro ADMIN por um comando
administrativo de uso único, fora da API pública, com senha lida de entrada
segura e codificada pelo `PasswordEncoder`. Serializar bootstrap, promoção,
rebaixamento e exclusão de ADMIN em transação. Para garantir o mínimo de um
ADMIN mesmo diante de DML direto, acrescentar proteção no PostgreSQL em uma
migration futura. A V2 já concede DML sobre `usuario` ao runtime, portanto uma
verificação exclusivamente na camada de serviço não protege contra caminhos
que contornem o serviço.

As propostas abaixo dependem de decisões de negócio registradas na seção 12.
**Nada foi implementado.**

## 2. Situação atual identificada

### 2.1 Persistência e perfil

- `src/main/java/br/com/arena/model/Usuario.java` persiste `perfil` como enum
  textual (`ADMIN` ou `CLIENTE`), expõe `ROLE_<perfil>` em
  `getAuthorities()` e implementa `UserDetails`.
- `Usuario.isEnabled()` retorna sempre `true`; não há estado de usuário ativo
  que mude a contagem. Hoje, a definição prática de administrador ativo é
  `perfil = ADMIN`.
- `src/main/java/br/com/arena/repository/UsuarioRepository.java` só declara
  `findByEmail`. Não há método de contagem por perfil nem método de lock para
  mudanças de ADMIN.
- V1 define `usuario.perfil NOT NULL` e limita os valores a `ADMIN`/`CLIENTE`.
  Não insere usuário e não impõe mínimo/máximo de administradores.
- V2 concede ao `arena_runtime` `SELECT/INSERT/UPDATE/DELETE` nas tabelas da
  aplicação. Isso inclui DML sobre `usuario.perfil`; o banco atualmente não
  restringe a mudança de perfil ao `UsuarioService`.

### 2.2 Cadastro e atualização

`UsuarioRequestDTO` contém `nome`, `email`, `telefone`, `senha` e `perfil`.
`perfil` é opcional e não tem validação própria. O mesmo DTO é usado em cadastro
público, atualização própria e atualização administrativa.

`UsuarioMapper.toEntity` não copia `perfil`. Além disso,
`UsuarioService.salvar` define explicitamente `Perfil.CLIENTE` antes de salvar.
Assim, um `POST /usuarios` com `perfil=ADMIN` válido é desserializado, mas o
valor é ignorado/forçado para `CLIENTE`; não é rejeitado pelo contrato atual.
O serviço codifica a senha com `PasswordEncoder`, configurado como BCrypt. A
resposta (`UsuarioResponseDTO`) inclui o perfil, mas não a senha.

`UsuarioService.atualizar` atribui `dto.getPerfil()` diretamente. Como não há
validação `@NotNull` para esse campo, um `PUT /usuarios/{id}` sem `perfil` pode
tentar gravar `null` e resultar em erro de persistência, em vez de erro de
validação claro. O método também substitui a senha do usuário alvo pela senha
enviada no mesmo DTO.

`UsuarioService.atualizarProprioPerfil` atualiza os dados e a senha, mas não
altera o perfil; o comentário do código registra essa proteção contra
autopromoção.

### 2.3 Autenticação e autorização

- `AuthController` publica somente `POST /auth/login`; não há
  `POST /auth/register`.
- `AuthService` autentica email/senha pelo `AuthenticationManager` e emite JWT.
- `JwtService` coloca o email no `subject`; não coloca um role no token.
- Em cada requisição autenticada, `JwtAuthenticationFilter` carrega novamente
  o usuário pelo email via `CustomUserDetailsService` e obtém suas authorities
  do banco. Portanto, uma mudança de perfil deve refletir na autorização da
  próxima requisição, mesmo com um JWT já emitido.
- `SecurityConfig` habilita `@EnableMethodSecurity`; libera `/auth/**` e
  `POST /usuarios`, exige autenticação para as demais rotas e deixa a verificação
  de ADMIN para `@PreAuthorize`.
- A autorização dos métodos do controller está presente; os métodos públicos do
  `UsuarioService`, isoladamente, não verificam quem os chamou.

### 2.4 Endpoints atuais

| Método e rota | Acesso atual | Efeito relevante para H-04 |
|---|---|---|
| `POST /auth/login` | Público | Autentica usuário existente; não cria usuário. |
| `POST /usuarios` | Público | Cadastro; serviço sempre grava `CLIENTE`. |
| `GET /usuarios` | `ADMIN` | Lista usuários. |
| `GET /usuarios/me` | Autenticado | Consulta o próprio usuário. |
| `PUT /usuarios/me` | Autenticado | Atualiza dados/senha; não altera perfil. |
| `GET /usuarios/{id}` | `ADMIN` | Consulta um usuário. |
| `PUT /usuarios/{id}` | `ADMIN` | Atualiza dados, senha e perfil; sem regra do último ADMIN. |
| `DELETE /usuarios/{id}` | `ADMIN` | Exclui se não houver agendamentos; sem regra do último ADMIN. |

Não há endpoint de bootstrap nem endpoint específico para promover/rebaixar
perfil. A promoção/redução está acoplada ao `PUT /usuarios/{id}`.

## 3. Testes existentes relacionados

Na árvore de testes inspecionada não há classe dedicada a `UsuarioController`,
`UsuarioService` ou `AuthController`, nem testes de cadastro/perfil/bootstrap.

Há testes de agendamento que criam registros de `Usuario` diretamente com
`Perfil.ADMIN` como fixture. `AgendamentoControllerSecurityTest` verifica
regras de autorização de agendamentos, não o gerenciamento de usuários. A
suíte existente também tem testes de contexto e do guard PostgreSQL H-03, mas
eles não cobrem os cenários do H-04.

O plano de testes da seção 10 é uma proposta; não foi executado nesta etapa.

## 4. Vulnerabilidades e riscos

1. **Bloqueio operacional numa instalação vazia — alto:** signup sempre cria
   `CLIENTE`, e não existe comando/API controlada para criar o primeiro ADMIN.
   As operações administrativas ficam indisponíveis até intervenção externa.
2. **Perda do último ADMIN — alto:** `PUT /usuarios/{id}` pode rebaixar o último
   administrador; `DELETE /usuarios/{id}` pode excluí-lo se não tiver
   agendamentos. Também é possível um ADMIN agir sobre a própria conta.
3. **Condição de corrida:** duas operações de rebaixamento/exclusão podem ler a
   mesma contagem antiga e ambas concluir que ainda há outro ADMIN.
4. **Regra só no controller:** `@PreAuthorize` protege as rotas atuais, mas o
   `UsuarioService` não impõe a autorização em si. Novos chamadores internos
   podem usar `atualizar`/`deletar` sem passar pelo mesmo controller.
5. **DML direto do runtime:** pela V2, a credencial runtime tem UPDATE/DELETE
   sobre `usuario`. Sem trigger ou procedimento de banco, uma instrução SQL
   direta pode contornar uma validação de serviço. A aplicação continua sendo
   o limite de autorização entre usuários HTTP, mas o banco não garante a
   invariante de contagem.
6. **Contrato de atualização amplo:** o DTO administrativo combina alteração
   de dados, senha e perfil. Isso amplia o impacto de um erro de cliente e
   deixa a omissão de `perfil` mal definida.
7. **Sem trilha específica:** não foi encontrado registro de ator, alvo,
   mudança de perfil ou operação de bootstrap para auditoria.
8. **Recuperação indefinida:** não há procedimento aprovado no código para
   recuperar acesso se nenhum ADMIN existir.

**Proteção atualmente presente:** o cadastro público não permite escalada para
ADMIN pelo campo JSON, porque o serviço força `CLIENTE`; a alteração de perfil
existente exige `ADMIN` no controller. Isso não cobre bootstrap, remoção do
último ADMIN nem bypass direto do serviço.

## 5. Alternativas para o bootstrap inicial

| Alternativa | Vantagens | Riscos / avaliação |
|---|---|---|
| Endpoint HTTP público de bootstrap, protegido por token/variável | Fácil de integrar à interface/deploy | Expõe superfície remota; segredo pode vazar por proxy, logs, métricas ou configuração; requer lock e estado durável. **Não recomendado.** |
| Variável de ambiente consumida no startup normal | Automatiza instalação | Segredo fica disponível a cada processo/restart; múltiplas réplicas podem competir; erro de configuração pode recriar ou tentar recriar a conta. Só seria aceitável com marcador transacional, execução única e remoção imediata do segredo. |
| **Comando administrativo/CLI executado uma vez** | Fora do tráfego público; operação explícita e auditável; não precisa deixar endpoint de bootstrap exposto | Exige procedimento operacional e modo CLI que não suba o servidor HTTP. **Recomendado.** |
| INSERT/UPDATE manual direto no PostgreSQL | Não exige API | Requer credencial SQL, codificação correta da senha e contorno das regras da aplicação; difícil de auditar e propenso a erro. **Não recomendado como procedimento normal.** |

### Recomendação

Criar um comando específico, por exemplo `bootstrap-admin`, executável em modo
CLI sem iniciar o servidor HTTP. O comando deve:

1. receber nome/email e senha por prompt oculto ou secret manager/STDIN; senha
   nunca como argumento de linha de comando, literal em script, `.env`
   versionado ou log;
2. validar o input e codificar a senha pelo `PasswordEncoder` existente;
3. iniciar uma única transação, adquirir o mesmo lock usado pelas operações de
   perfil e verificar estado do bootstrap **e** contagem de ADMIN;
4. criar exatamente um usuário `ADMIN`, marcar o bootstrap como consumido na
   mesma transação e confirmar; qualquer erro reverte ambos;
5. falhar sem revelar se email/senha já existe em logs; não criar outro ADMIN
   numa segunda execução;
6. terminar sem abrir listener HTTP. O operador remove/desabilita a configuração
   temporária após o uso.

Para tornar o uso único durável, recomenda-se um marcador persistido
(`bootstrap_consumed`/`bootstrap_completed_at`) associado ao estado de
administração. Se o banco já possuir ADMINs quando a migration for aplicada, o
marcador deve iniciar como consumido. Se não possuir, o marcador fica pendente
até o comando concluir. Não armazenar o segredo de bootstrap no banco.

Uma variável temporária de ambiente pode ser o meio pelo qual o secret manager
entrega o segredo ao processo CLI, mas não deve por si só ser a autorização nem
ficar habilitada no processo web normal. O controle real é o comando separado,
estado persistido e operação transacional única.

## 6. Cadastro público

No código atual, o campo `perfil` está presente no `UsuarioRequestDTO`, mas o
mapper não o copia e `UsuarioService.salvar` sempre define `CLIENTE`. Portanto:

- a requisição com `perfil=ADMIN` atualmente é aceita, mas o usuário persistido
  e retornado é `CLIENTE`;
- não é uma vulnerabilidade de escalada reproduzível pelo endpoint atual;
- manter o campo no DTO público é confuso e deixa a garantia dependente da
  sobrescrita no serviço.

**Proposta:** separar DTO de cadastro público do DTO usado para administração
de perfil, removendo `perfil` do contrato de signup e mantendo a atribuição
explícita `Perfil.CLIENTE` no serviço. Mesmo que o framework ignore um campo
JSON desconhecido, a camada de serviço deve continuar impondo `CLIENTE`.

Para administração, preferir uma operação específica de perfil (por exemplo,
`PATCH /usuarios/{id}/perfil`) protegida por `ADMIN`, em vez de compartilhar o
DTO de cadastro/dados pessoais/senha. A regra de autorização deve estar no
limite de serviço também, ou a API deve garantir que todos os chamadores usem
um serviço administrativo único.

## 7. Proteção contra demissão/exclusão do último ADMIN

Quando um alvo é ADMIN e a operação reduziria a contagem de administradores:

- permitir rebaixar ou excluir somente se a contagem atual for maior que 1;
- se a contagem for 1, rejeitar e deixar o registro inalterado;
- usar erro de conflito (`409 Conflict`, já há `ConflictException` e tratamento
  global) para comunicar que o estado atual impede a operação;
- preservar a regra existente que não exclui usuário com agendamentos;
- aplicar a mesma regra a qualquer outro caminho de alteração de perfil,
  exclusão em lote ou ferramenta administrativa da aplicação.

Autopromoção via `/usuarios/me` já é impedida pelo serviço. Autorrebaixamento e
autoexclusão não são impedidos se o usuário chamar a rota administrativa com
papel ADMIN. Se houver outro ADMIN, permitir essas operações é uma decisão de
negócio; se for permitido, ainda deve seguir o mesmo lock e contagem.

## 8. Concorrência, transações e integridade no banco

### 8.1 Serialização na aplicação

O serviço atual não declara `@Transactional` nos fluxos de usuário e não faz
contagem de ADMIN. A proteção não deve ser implementada como “contar e depois
salvar” em chamadas independentes.

Para as operações web, a proposta mínima é:

1. `@Transactional` cobrindo lock, leitura, validação e gravação;
2. adquirir um lock compartilhado por todo o ciclo de vida de ADMIN antes de
   ler/alterar a conta alvo;
3. depois do lock, recarregar o alvo para escrita e contar ADMINs;
4. validar a regra e fazer a mutação na mesma transação;
5. usar o mesmo lock no bootstrap, promoções, rebaixamentos e exclusões de
   ADMIN, em todas as instâncias da aplicação.

Como o alvo é PostgreSQL, `pg_advisory_xact_lock` com chave constante e
documentada pode serializar essas operações sem tabela de lock. O lock é
liberado automaticamente no commit/rollback. A contagem deve ocorrer em uma
instrução posterior à aquisição do lock, sob `READ COMMITTED`; não usar uma
contagem feita antes de esperar pelo lock. Também se deve manter ordem de lock
uniforme para evitar deadlocks. Uma linha de controle bloqueada com
`SELECT ... FOR UPDATE` é outra opção, mas requer projetar e conceder com
cuidado a permissão necessária ao runtime.

### 8.2 Serviço versus proteção adicional no PostgreSQL

Uma transação e lock no serviço protegem as rotas que seguem esse protocolo.
Não impedem uma instrução DML executada diretamente com `arena_runtime`, que
pela V2 tem `UPDATE/DELETE` em `usuario`. Uma constraint `CHECK` simples não
pode validar uma contagem entre linhas.

**Recomendação de defesa em profundidade:** além das validações no serviço,
avaliar uma migration V3 com estado singleton de ciclo de vida e trigger de
banco para manter a contagem de ADMINs e impedir atomicamente que uma operação
reduza o contador abaixo de 1. Um desenho possível:

- backfill do contador a partir dos usuários existentes e inicialização do
  marcador de bootstrap conforme a contagem;
- trigger para inserção de ADMIN, transição de `CLIENTE` para `ADMIN`,
  transição de `ADMIN` para `CLIENTE` e exclusão de ADMIN;
- decremento condicional/atômico do estado (`admin_count > 1`) antes de aceitar
  rebaixamento ou exclusão; transações concorrentes serializam na mesma linha
  do estado;
- marcador de bootstrap consumido na criação do primeiro ADMIN, na mesma
  transação;
- função de trigger com `SECURITY DEFINER` apenas se necessário, owner
  controlado e `search_path` fixo/qualificado;
- revogação explícita de qualquer grant automático ao runtime sobre a tabela de
  estado.

**Interação com V2:** os default privileges atuais concedem DML ao
`arena_runtime` para toda tabela nova criada por `arena_migrator` em `public`.
Se V3 criar a tabela de estado nesse schema, deve revogar explicitamente os
privilégios runtime nessa tabela, ou escolher um schema interno cuja criação e
grants sejam provisionados deliberadamente.

O trigger pode garantir a invariante “não remover o último ADMIN” mesmo para
DML direto, mas, como todos os requests da aplicação compartilham o mesmo role
PostgreSQL, o banco não sabe por si só qual usuário HTTP iniciou a operação.
A autorização “somente um ADMIN pode promover” continua exigindo controle na
aplicação (ou uma arquitetura explícita de credenciais/procedures por ator).
O trigger não substitui `@PreAuthorize` nem valida identidade de negócio.

Se for escolhido não criar trigger, registrar formalmente que a garantia vale
somente para operações realizadas pelos serviços transacionais da aplicação e
que acesso SQL direto pode contorná-la.

## 9. Migrations e alterações de banco possíveis

Nenhuma migration foi criada nesta auditoria. V1 e V2 foram apenas lidas.

| Opção | Alteração provável | Consequência |
|---|---|---|
| Lock advisory + validação de serviço, sem marcador durável | Nenhuma migration obrigatória; pode persistir estado em configuração/fora do DB | Menor mudança, mas o consumo único e a invariante dependem dos caminhos da aplicação. |
| Bootstrap com estado durável | V3 para marcador de bootstrap e estado/coordenação, com backfill | Permite impedir reuso após commit e tratar bancos com ADMIN existente. |
| Garantia de banco contra último ADMIN | V3 com contador/estado e trigger, inicialização/backfill e grants revogados para runtime | Mais robusto contra DML que contorne o serviço; requer ensaio de migration e testes concorrentes. |

Uma trigger/migration precisa considerar database novo sem ADMIN e database
existente com zero, um ou vários ADMINs. Como não se acessou qualquer database,
este relatório não presume qual desses estados existe em uma instalação real.

## 10. Plano de implementação em etapas pequenas

1. **Decisões de negócio:** aprovar mecanismo bootstrap, política de
   autorremoção/rebaixamento, recuperação e escopo do trigger.
2. **Contrato público:** criar DTO de cadastro sem perfil; manter
   `CLIENTE` imposto pelo serviço; criar DTO específico para mudança de perfil.
3. **Estado de bootstrap:** adicionar comando CLI sem HTTP e operação de serviço
   transacional, com input seguro de senha e marcador de uso único.
4. **Gestão administrativa:** criar operação específica de promoção/redução;
   validar autoridade na camada de serviço e manter auditoria de ator/alvo.
5. **Regra do último ADMIN:** adicionar lock compartilhado e contagem no
   serviço; bloquear rebaixamento/exclusão quando só resta um ADMIN.
6. **Defesa no banco, se aprovada:** migration V3 para estado/trigger,
   backfill e revogação de grants runtime sobre o estado interno.
7. **Testes:** cobrir regras, concorrência e, se V3 aprovada, DML direto no
   PostgreSQL descartável.
8. **Ensaio operacional:** testar bootstrap novo, segunda execução, restart e
   processo de recuperação em ambiente não produtivo. Só então preparar
   implantação aprovada.

Cada etapa deve manter o guard central H-03 para testes PostgreSQL; todos os
testes de banco devem usar somente `arena_flyway_test` descartável, com
credenciais runtime/migrator separadas conforme H-02.

## 11. Plano de testes proposto

| Cenário | Resultado esperado |
|---|---|
| Bootstrap em banco sem ADMIN | Um ADMIN criado; senha persistida somente como hash; marcador consumido na mesma transação. |
| Segundo bootstrap após sucesso | Recusado; nenhum usuário adicional ou mudança de estado. |
| Duas execuções bootstrap simultâneas | Exatamente uma confirma; a outra observa estado consumido/ADMIN existente e falha controladamente. |
| Cadastro público com `perfil=ADMIN` | Resposta/persistência sempre `CLIENTE` (ou rejeição explícita, se esse contrato for aprovado). |
| Promoção `CLIENTE → ADMIN` por ADMIN | Sucesso; perfil passa a ADMIN e a autorização é refletida na próxima requisição autenticada. |
| Promoção/rebaixamento por CLIENTE | `403`; serviço/repositório não altera o perfil. |
| Rebaixar ADMIN com outros ADMINs | Sucesso se política aprovada; contagem final permanece ≥1. |
| Rebaixar o último ADMIN | `409`; linha continua ADMIN. |
| Excluir ADMIN havendo outro ADMIN | Sucesso quando outras regras de exclusão (por exemplo, agendamentos) permitem. |
| Excluir o último ADMIN | `409`; conta permanece. |
| ADMIN tenta rebaixar/excluir a si próprio | Resultado conforme política aprovada; nunca deixa contagem zero. |
| Duas remoções concorrentes com dois ADMINs | No máximo uma operação redutora confirma; após ambas, permanece um ADMIN. |
| Um rebaixamento e uma exclusão concorrentes | Contagem nunca chega a zero; resposta perdedora é conflito. |
| V3: DML direto como runtime tentando remover último ADMIN | Trigger rejeita e transação não deixa estado/usuário divergente. |
| Operação em erro após mexer no contador/marcador | Rollback mantém usuário e estado consistentes. |

Testes HTTP de autorização devem subir a cadeia Spring Security real, usar
JWTs de cliente/admin e o initializer H-03. Testes de corrida precisam usar
barreira/latches, duas transações/conexões e verificar o estado final, não só
os status HTTP.

## 12. Arquivos que provavelmente precisarão de mudança

- `src/main/java/br/com/arena/controller/UsuarioController.java` — rota
  explícita de perfil/bootstrap, se não for somente CLI.
- `src/main/java/br/com/arena/service/UsuarioService.java` — impor cliente no
  cadastro, operações transacionais de perfil/exclusão e regra do último ADMIN.
- `src/main/java/br/com/arena/repository/UsuarioRepository.java` — contagem por
  perfil e, se adotado, queries/locks específicos.
- `src/main/java/br/com/arena/dto/usuario/UsuarioRequestDTO.java` e novos DTOs
  para cadastro/alteração de perfil — remover perfil do signup e separar
  contratos.
- `src/main/java/br/com/arena/mapper/UsuarioMapper.java` — ajustar mapeamento
  conforme DTOs divididos.
- `src/main/java/br/com/arena/security/SecurityConfig.java` e/ou anotações no
  serviço — revisar autorização de toda operação administrativa.
- `src/main/java/br/com/arena/exception/GlobalExceptionHandler.java` —
  reutilizar `ConflictException`/409 para último ADMIN e bootstrap consumido,
  se necessário.
- Uma nova classe de comando, por exemplo
  `src/main/java/br/com/arena/command/BootstrapAdminCommand.java`, sem listener
  HTTP no modo de execução.
- Nova migration Flyway (provavelmente V3) somente se marcador/trigger/estado
  persistido forem aprovados.
- Novos testes de controller/service/repository e integração para usuário,
  autenticação e autorização; se houver V3, teste direto de trigger no
  PostgreSQL descartável.

`Usuario.java`, `Perfil.java` ou `UsuarioResponseDTO.java` podem não precisar
de mudança se os perfis atuais forem suficientes. Não foi alterado nenhum
arquivo nesta auditoria.

## 13. Dúvidas de negócio para aprovação

1. **Bootstrap:** aprova um comando CLI de uso único, fora da API e sem servidor
   HTTP, como recomendação? Quem pode executá-lo e em que etapa do deploy?
2. **Segredo inicial:** senha digitada em prompt oculto, fornecida por secret
   manager/STDIN ou outra política? Qual requisito mínimo de senha do ADMIN?
3. **Uso único:** aprova persistir um marcador irrevogável de bootstrap
   consumido? Quem pode autorizar recuperação caso ele seja marcado
   incorretamente?
4. **Proteção do último ADMIN:** aprova migration V3 com trigger/estado no
   PostgreSQL, além da validação transacional na aplicação? Consequência:
   maior garantia contra DML direto e mais complexidade operacional/migration.
5. **Autorremoção:** se existir outro ADMIN, o administrador atual pode
   rebaixar ou excluir a própria conta, ou essas operações devem ser sempre
   proibidas para a conta autenticada?
6. **Contrato da API:** manter `PUT /usuarios/{id}` compatível ou criar
   endpoint separado `PATCH /usuarios/{id}/perfil`? Recomenda-se separar perfil
   de dados pessoais/senha.
7. **Conflito:** aprova `409 Conflict` quando a operação deixaria zero ADMINs?
8. **Recuperação:** qual procedimento autorizado quando nenhum ADMIN puder
   operar — intervenção DBA auditada, novo comando offline ou outra política?
9. **Semântica de ADMIN ativo:** basta `perfil=ADMIN` (estado atual, pois
   `isEnabled()` sempre retorna `true`) ou haverá estado de bloqueio/desativação
   que também deve entrar na contagem?
10. **Banco e ambiente:** a solução deve garantir o último ADMIN apenas para
    operações da aplicação, ou também contra qualquer DML permitido à conta
    `arena_runtime`? Esta escolha determina se V3 com trigger é obrigatória.

## 14. Limites desta auditoria

A conclusão foi obtida por leitura de código, migrations, testes e documentação.
Nenhum teste foi executado; nenhum banco, Docker ou `.env` foi acessado. Nenhum
código, migration, configuração ou histórico Git foi alterado. O único arquivo
criado nesta etapa é este relatório.
