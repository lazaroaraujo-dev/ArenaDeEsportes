# doc42 — Correção pontual pós-doc41 (H-04 Parte 4)

**Data:** 2026-11-24
**Objetivo:** corrigir exclusivamente as inconsistências de cobertura/evidência que levaram `doc41` a reprovar o checkpoint da Parte 4 do H-04, preservando os fluxos já avaliados dos Achados A e C, a correção LAC-05, migrations, permissões, roles e arquivos não commitados.
**Resultado das correções:** evidências corrigidas e executadas com sucesso nas seleções abaixo. **Não há novo parecer de checkpoint aprovado**: B1, B12 e a suíte completa continuam pendentes, conforme registrado neste documento.

`doc41-auditoria-final-h04-parte-4.md` permanece como histórico da auditoria que reprovou o checkpoint e **não foi alterado**.

---

## 1. Escopo respeitado

Foram alterados **somente testes**. Não houve alteração nesta etapa em:

- código de produção já avaliado (`UsuarioService`, `AdminLifecycleDbService`, `UsuarioController`, DTOs, mapper, CLI, etc.);
- migrations V3, V3.1 ou V3.2;
- roles, grants, revokes ou regras DB-02;
- V4;
- `doc41` ou qualquer outro documento existente;
- estado do Git (sem `commit`, `push`, `reset`, `clean`, `checkout` destrutivo ou outra operação destrutiva).

---

## 2. Problemas corrigidos

### 2.1 LAC-04 — chamada direta aos métodos legados

**Arquivo:** `src/test/java/br/com/arena/controller/UsuarioCadastroAtualizacaoIntegrationTest.java`

Antes, os testes usavam apenas `POST /usuarios` e `PUT /usuarios/{id}` com `UsuarioCadastroDTO`/`UsuarioUpdateDTO`. Agora há chamada direta aos overloads legados:

- `usuarioService.salvar(UsuarioRequestDTO)` em `metodoLegadoSalvarComRequestDTOForcaPerfilCliente`;
- `usuarioService.atualizar(Long, UsuarioRequestDTO)` em `metodoLegadoAtualizarComRequestDTOIgnoraSenhaENaoAlteraPerfil`.

Não foi inventado campo `perfil` no DTO. `UsuarioRequestDTO` continua com `nome`, `email`, `telefone` e `senha`; a verificação é a do contrato real do serviço:

- criação retorna/persiste `Perfil.CLIENTE`;
- a senha informada é armazenada com hash;
- atualização altera nome e telefone;
- o perfil permanece `CLIENTE`;
- a senha contida no DTO legado **não** é aplicada.

### 2.2 LAC-03 — `NO_OP` observado no nível de serviço

**Arquivo:** `src/test/java/br/com/arena/controller/UsuarioPerfilAdminIntegrationTest.java`

Novo teste `alterarPerfilServicoRetornaNoOpParaMesmoPerfilSemAuditoria`:

- chama `usuarioService.alterarPerfil(...)` com `UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()` para usuário já `ADMIN`;
- assere diretamente `AdminOperationResult.status() == AdminOperationStatus.NO_OP`;
- verifica `message == "Perfil inalterado."`;
- verifica que o `UsuarioResponseDTO` retornado reflete `ADMIN`;
- verifica por conexão independente (`arena_migrator`) que nenhum evento foi gravado em `admin_audit_event`;
- limpa `SecurityContextHolder` em `@AfterEach` para não vazar autenticação entre testes.

Isso elimina a limitação apontada em `doc41`: o controller HTTP não expõe `status()`, portanto a evidência agora está no nível correto.

### 2.3 CLI — verificação real de saída

**Arquivo:** `src/test/java/br/com/arena/cli/AdminCliBootstrapIntegrationTest.java`

O helper `runnerLastErrorContains()`, que sempre retornava `true`, foi removido. Em `bootstrapRejectedInRecoveryRequiredState`:

- `System.out` e `System.err` são capturados **antes** de construir `CliRunner`, pois o runner cria seu `PrintWriter` no construtor;
- a captura é restaurada em `finally`;
- a asserção exige:
  - `CliRunner.EXIT_BUSINESS_ERROR`;
  - prefixo `Bootstrap rejeitado:`;
  - mensagem SQL completa `H-04 cannot bootstrap while recovery is required`;
  - `countAdmins() == 0`.

### 2.4 Achado B — comportamento transacional externo da `LastAdminException`

**Arquivo:** `src/test/java/br/com/arena/controller/UsuarioServiceLegadoDeletarIntegrationTest.java`

O teste anterior capturava a exceção dentro do callback do `TransactionTemplate` e por isso não provava propagação pelo limite externo. Ele foi substituído por dois cenários explícitos:

1. `lastAdminExceptionCapturadaPeloChamadorExternoComitaAuditoria`
   A exceção é capturada dentro do callback. Nesse caso, `status.isRollbackOnly()` continua `false` e a auditoria `LAST_ADMIN/REJECTED` é comitada. É o comportamento que o teste antigo realmente demonstrava.

2. `lastAdminExceptionAtravessaLimiteTransacionalExternoEProvocaRollbackDaAuditoria`
   A `LastAdminException` é relançada e atravessa o limite externo. A asserção dentro do callback confirma que a participação interna não foi marcada como rollback-only, mas, ao propagar, a política padrão do `TransactionTemplate` externo prevalece e provoca rollback:
   - a contagem de auditoria por conexão independente permanece igual;
   - o último ADMIN continua persistido.

**Interpretação técnica:** `@Transactional(noRollbackFor = LastAdminException.class)` em `deletar(Long)` atua somente na transação/interceptor interna. Ele não impõe `noRollbackFor` a um chamador externo com regras padrão. Portanto, a afirmação anterior de que “a transação externa sempre comita” só é válida quando o próprio chamador captura a exceção. Não existe chamador de produção desse método legado, o que limita o impacto atual, mas a documentação/teste agora refletem o comportamento real.

### 2.5 `pg_locks` identificado por PID das sessões sob teste

**Arquivos:** Achados A, B e C:

- `UsuarioPerfilAdminIntegrationTest.java`
- `UsuarioServiceLegadoDeletarIntegrationTest.java`
- `UsuarioClienteExclusaoConsistenciaIntegrationTest.java`

As verificações de bloqueio agora filtram por `pid = ?`, obtido com `SELECT pg_backend_pid()` na sessão relevante, além de `locktype = 'advisory'`, chave `7409395520021` e `objsubid = 1`:

- sessão A: `granted = true`, confirmando quem detém o lock;
- sessão B: `NOT granted`, confirmando o PID exato que aguarda o lock.

Para a thread B, o PID é capturado dentro do próprio `adquirirLock()` por `@MockitoSpyBean(reset = MockReset.AFTER)` com `invocation.callRealMethod()`. Isso identifica a conexão usada pelo serviço sem introduzir uma transação externa ao redor da requisição; essa transação externa, quando tentada, alterou o cache JPA e fez o operador parecer ADMIN antes da revalidação sob lock, causando regressão no Achado A. O fluxo original dos testes foi preservado.

A verificação final de LAC-05 de que não restam locks retidos continua global e correta para estado pós-rollback; não foi alterada.

---

## 3. Ambiente de validação

Todas as execuções abaixo usaram PostgreSQL descartável:

- imagem `postgres:16`;
- versão efetiva `16.15 (Debian 16.15-1.pgdg13+2)`;
- porta aleatória em `127.0.0.1`, publicada por Docker;
- banco `arena_flyway_test`;
- diretório de dados em `tmpfs` de 1 GiB (`rw,noexec,nosuid,size=1g`);
- `Mounts=[]`, ou seja, **nenhum volume persistente**;
- roles criadas por `scripts/postgres/provision-arena-roles.sql` com senhas aleatórias geradas na execução;
- apenas variáveis `TEST_*` exportadas para os testes;
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_MS` e `H04_INITIAL_INSTALL` removidas com `env -u`;
- containers nomeados `arena_h04_doc42_*`, criados apenas para esta validação e removidos ao final;
- containers/bancos existentes, incluindo `arena_pg16_achado_d` e `arenadeesportes-postgres-1`, não foram acessados nem modificados.

Execução final: container `arena_h04_doc42_29868`, porta `52561`. Nenhum container `arena_h04_doc42_*` permaneceu ativo após a execução.

Logs mantidos fora do repositório em `/tmp/opencode/h04-doc42-fix-logs/`.

---

## 4. Resultados executados

### 4.1 Compilação

- `./mvnw -DskipTests test-compile`
- `BUILD SUCCESS`
- tempo final: `4.324 s`

### 4.2 Testes corrigidos

Comando Maven com seleção nominal dos testes corrigidos:

```
-Dtest=UsuarioCadastroAtualizacaoIntegrationTest#metodoLegadoSalvarComRequestDTOForcaPerfilCliente+metodoLegadoAtualizarComRequestDTOIgnoraSenhaENaoAlteraPerfil,UsuarioPerfilAdminIntegrationTest#alterarPerfilServicoRetornaNoOpParaMesmoPerfilSemAuditoria,AdminCliBootstrapIntegrationTest#bootstrapRejectedInRecoveryRequiredState,UsuarioServiceLegadoDeletarIntegrationTest#lastAdminExceptionAtravessaLimiteTransacionalExternoEProvocaRollbackDaAuditoria+lastAdminExceptionCapturadaPeloChamadorExternoComitaAuditoria
```

Resultado:

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `AdminCliBootstrapIntegrationTest` | 1 | 0 | 0 | 49,92 s |
| `UsuarioCadastroAtualizacaoIntegrationTest` | 2 | 0 | 0 | 8,103 s |
| `UsuarioPerfilAdminIntegrationTest` | 1 | 0 | 0 | 9,128 s |
| `UsuarioServiceLegadoDeletarIntegrationTest` | 2 | 0 | 0 | 6,981 s |
| **Total** | **6** | **0** | **0** | `01:42 min` |

`BUILD SUCCESS`.

### 4.3 Regressão A/B/C

```
-Dtest=UsuarioPerfilAdminIntegrationTest,UsuarioServiceLegadoDeletarIntegrationTest,UsuarioClienteExclusaoConsistenciaIntegrationTest
```

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `UsuarioClienteExclusaoConsistenciaIntegrationTest` | 6 | 0 | 0 | 46,19 s |
| `UsuarioPerfilAdminIntegrationTest` | 18 | 0 | 0 | 10,45 s |
| `UsuarioServiceLegadoDeletarIntegrationTest` | 9 | 0 | 0 | 0,990 s |
| **Total** | **33** | **0** | **0** | `01:11 min` |

`BUILD SUCCESS`.

### 4.4 LAC-01 a LAC-05 efetivamente aplicáveis

```
-Dtest=UsuarioPerfilAdminIntegrationTest,UsuarioCadastroAtualizacaoIntegrationTest,UsuarioServiceLegadoDeletarIntegrationTest
```

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `UsuarioCadastroAtualizacaoIntegrationTest` | 11 | 0 | 0 | 37,86 s |
| `UsuarioPerfilAdminIntegrationTest` | 18 | 0 | 0 | 11,51 s |
| `UsuarioServiceLegadoDeletarIntegrationTest` | 9 | 0 | 0 | 7,906 s |
| **Total** | **38** | **0** | **0** | `01:06 min` |

`BUILD SUCCESS`.

Cobertura efetiva verificada:

- **LAC-01:** exclusões concorrentes dos últimos dois ADMIN, com estado final de exatamente 1 ADMIN;
- **LAC-02:** ausência/404 e ausência de auditoria para alvo inexistente;
- **LAC-03:** `NO_OP` direto no serviço, sem auditoria;
- **LAC-04:** chamadas diretas a `salvar(UsuarioRequestDTO)` e `atualizar(Long, UsuarioRequestDTO)`;
- **LAC-05:** rollback técnico real em `alterarPerfil()`, com spy chamando o método real, mutação e auditoria antes da falha, verificação por conexão independente e zero locks retidos.

### 4.5 Seleção H-04 ampliada

```
-Dtest=UsuarioPerfilAdminIntegrationTest,UsuarioCadastroAtualizacaoIntegrationTest,UsuarioClienteExclusaoConsistenciaIntegrationTest,UsuarioServiceLegadoDeletarIntegrationTest,AdminCliSecurityTest,TestPostgresSettingsTest,AdminCliBootstrapIntegrationTest,AdminCliRecoveryIntegrationTest,AdminLifecycleStateFreshInstallIntegrationTest,AdminLifecycleStateIntegrationTest,PostgresRolePrivilegesIntegrationTest
```

| Classe | Testes | Falhas | Erros |
|---|---:|---:|---:|
| `AdminCliBootstrapIntegrationTest` | 5 | 0 | 0 |
| `AdminCliRecoveryIntegrationTest` | 5 | 0 | 0 |
| `AdminCliSecurityTest` | 4 | 0 | 0 |
| `UsuarioCadastroAtualizacaoIntegrationTest` | 11 | 0 | 0 |
| `UsuarioClienteExclusaoConsistenciaIntegrationTest` | 6 | 0 | 0 |
| `UsuarioPerfilAdminIntegrationTest` | 18 | 0 | 0 |
| `UsuarioServiceLegadoDeletarIntegrationTest` | 9 | 0 | 0 |
| `AdminLifecycleStateFreshInstallIntegrationTest` | 3 | 0 | 0 |
| `AdminLifecycleStateIntegrationTest` | 15 | 0 | 0 |
| `PostgresRolePrivilegesIntegrationTest` | 6 | 0 | 0 |
| `TestPostgresSettingsTest` | 10 | 0 | 0 |
| **Total** | **92** | **0** | **0** |

`BUILD SUCCESS`; tempo total `01:40 min`.

### 4.6 Executados x existentes

- **Executados nesta etapa:** compilação, 6 testes corrigidos, 33 testes de regressão A/B/C, 38 testes LAC e 92 testes na seleção H-04 ampliada — todos verdes na execução final.
- **Existentes, mas não executados nesta etapa:** os 47 testes restantes da suíte completa de 139 métodos `@Test`.
- **Suíte completa:** não foi reexecutada nesta etapa e **não é declarada aprovada**. `doc41` registrou `1` falha e `33` erros legados; esse histórico permanece. Não há evidência nova que autorize alterar esse parecer.

---

## 5. Recomendação técnica para B1 (não executada)

B1 continua em aberto: as assinaturas antigas permanecem com `EXECUTE` para `arena_offline_operator` e gravam `actor_ref` fixo, sem identidade real do operador, justificativa e ticket.

Recomendação para uma etapa futura, **fora desta correção pontual**:

1. Migrar testes e qualquer ferramenta offline que ainda chame as assinaturas antigas:
   - `public.h04_bootstrap_first_admin(VARCHAR,VARCHAR,VARCHAR,VARCHAR)` — 4 argumentos;
   - `public.h04_recover_admin(BIGINT,VARCHAR,VARCHAR,VARCHAR,VARCHAR)` — 5 argumentos.
2. Criar nova migration incrementando a cadeia atual, sem reescrever V3/V3.1, com:
   - `REVOKE ALL ON FUNCTION ... FROM PUBLIC, arena_offline_operator;` para as duas assinaturas antigas;
   - manutenção explícita dos grants apenas nas assinaturas novas de 5 e 8 argumentos.
3. Endurecer `v3FunctionsExist()` para validar assinaturas por `pg_get_function_identity_arguments()`/argumentos, não apenas `proname`.
4. Reexecutar testes CLI, guards de função e regressão de grants após a migration.
5. Somente após isso, reavaliar o risco B1 no checkpoint.

Nenhuma dessas ações foi executada aqui: migrations, permissões e roles foram preservados.

---

## 6. Arquivos modificados nesta etapa

Somente arquivos de teste:

1. `src/test/java/br/com/arena/controller/UsuarioCadastroAtualizacaoIntegrationTest.java` — LAC-04 com chamadas diretas aos métodos legados.
2. `src/test/java/br/com/arena/controller/UsuarioPerfilAdminIntegrationTest.java` — LAC-03 com `NO_OP` direto, limpeza de `SecurityContextHolder` e PID por spy em `adquirirLock()`.
3. `src/test/java/br/com/arena/cli/AdminCliBootstrapIntegrationTest.java` — captura real de saída; remoção do stub.
4. `src/test/java/br/com/arena/controller/UsuarioServiceLegadoDeletarIntegrationTest.java` — propagação externa/rollback da `LastAdminException`; PID por spy; LAC-05 preservado.
5. `src/test/java/br/com/arena/controller/UsuarioClienteExclusaoConsistenciaIntegrationTest.java` — PID por spy na verificação de lock do Achado C.

Criado nesta etapa:

- `docs/auditorias/doc42-correcao-pontual-pos-doc41.md`.

Não modificados nesta etapa, mas já presentes no estado de trabalho:

- `src/main/java/br/com/arena/controller/UsuarioController.java`;
- `src/main/java/br/com/arena/dto/usuario/UsuarioRequestDTO.java`;
- `src/main/java/br/com/arena/mapper/UsuarioMapper.java`;
- `src/main/java/br/com/arena/service/UsuarioService.java`.

---

## 7. Estado do Git e integridade

Estado observado ao final:

- branch: `develop`;
- HEAD: `c7c6d53 feat(security): implement H-04 admin lifecycle stages 1-3`;
- `4` arquivos de produção rastreados modificados, existentes antes desta etapa;
- `1` arquivo de teste rastreado modificado nesta etapa (`AdminCliBootstrapIntegrationTest.java`);
- `4` arquivos de teste untracked alterados nesta etapa;
- `V3_2__admin_lifecycle_state_reader.sql` continua untracked, sem renomeação ou recriação;
- `doc41` continua untracked e intacto.

Hashes de integridade:

- `docs/auditorias/doc41-auditoria-final-h04-parte-4.md`:
  `3285c5e88a7e34a882ee3664aeaec1972061ecca3fe9da5c3a7d0a2c793d8b31`
- `src/main/resources/db/migration/V3_2__admin_lifecycle_state_reader.sql`:
  `775e016d6ad6ca31c26566ab44fa6b5d206fe5dbd5842f3c43e6f3a0664c4b56`

Nenhum `commit` ou `push` foi realizado.

---

## 8. Riscos pendentes

| Item | Situação | Impacto |
|---|---|---|
| B1 — assinaturas SQL antigas | Não corrigido; apenas recomendado neste documento | Alto: credencial offline pode gerar auditoria empobrecida |
| B12 — V3.2 untracked | Preservada, mas ainda precisa entrar no checkpoint | Alto: build sem ela não cria `h04_get_admin_lifecycle_state()` |
| Suíte completa | Não executada nesta etapa; histórico de `1` falha + `33` erros em `doc41` | Bloqueante para declarar aprovação geral |
| Backfill de “fresh install” | Testes passaram, mas não exercitam o mecanismo real de backfill da migration, conforme `doc41` | Médio: lacuna de evidência permanece |
| `LastAdminException` legado | Sem chamador de produção; se exposto, pode mapear para 400 | Baixo/controlado |
| B10/B11 — lock global, custo e pré-condição `btree_gist` | Não abordados nesta etapa | V4/runbook |

---

## 9. Conclusão operacional

As quatro inconsistências de evidência apontadas por `doc41` foram corrigidas nos testes e executadas com sucesso em PostgreSQL descartável. As verificações de `pg_locks` agora identificam a sessão por PID, sem alterar os fluxos aprovados dos Achados A e C. LAC-05 e a migration V3.2 foram preservados integralmente.

Esta correção **não altera o parecer final de `doc41`**: B1, B12 e a suíte completa ainda exigem decisão/execução em etapa separada. O trabalho permanece sem commit e aguarda revisão.
