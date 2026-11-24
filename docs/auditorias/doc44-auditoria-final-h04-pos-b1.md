# doc44 — Auditoria final integrada do H-04 Parte 4 (pós-B1)

**Data:** 2026-11-24
**Branch:** `develop`
**HEAD:** `c7c6d53 feat(security): implement H-04 admin lifecycle stages 1-3`
**Modo:** auditoria independente somente de leitura
**Veredito:** **APROVADO COM RESSALVAS**

---

## 1. Conclusão executiva

A H-04 Parte 4 está **tecnicamente coerente, segura e suficientemente testada para um checkpoint local**, desde que três condições sejam cumpridas antes da consolidação:

1. **Corrigir o comentário enganoso da migration V3.2** sobre leitura “sob advisory lock”.
2. **Incluir `V3_2__admin_lifecycle_state_reader.sql` e `V3_3__revoke_legacy_admin_function_signatures.sql`** (e os demais arquivos não rastreados) no conjunto de arquivos do checkpoint.
3. **Registrar explicitamente que a suíte completa não está verde** devido a falhas/erros legados de agendamento, para que o checkpoint não seja interpretado como aprovação geral da suíte.

O B1 foi efetivamente resolvido. As assinaturas antigas de bootstrap e recovery **não são executáveis por `arena_offline_operator` nem por `PUBLIC`**; as novas assinaturas continuam acessíveis, funcionais e auditadas. O CLI agora valida **nome, número e tipos exatos de argumentos**, não apenas nomes de funções. As interações entre Achados A, B, C, D, LAC-01 a LAC-05 e B1 estão coerentes.

Não foram encontradas incompatibilidades funcionais entre as migrations V3/V3.1/V3.2/V3.3, os serviços Java, o CLI e os testes. Também não foram identificadas alterações não documentadas em relação ao HEAD `c7c6d53`.

---

## 2. Evidências por área

### 2.1 B1 — assinaturas antigas revogadas

**Migration:** `src/main/resources/db/migration/V3_3__revoke_legacy_admin_function_signatures.sql:5-21`

- `REVOKE ALL ON FUNCTION public.h04_bootstrap_first_admin(VARCHAR,VARCHAR,VARCHAR,VARCHAR)` de `PUBLIC` e `arena_offline_operator`.
- `REVOKE ALL ON FUNCTION public.h04_recover_admin(BIGINT,VARCHAR,VARCHAR,VARCHAR,VARCHAR)` de `PUBLIC` e `arena_offline_operator`.
- `GRANT EXECUTE` reafirmado somente para as assinaturas novas:
  - bootstrap com 5 argumentos;
  - recovery com 8 argumentos.

As assinaturas antigas foram criadas em `V3:384-445` e `V3:450-540`, e concedidas a `arena_offline_operator` em `V3:637-643`. A `V3_3` revoga exatamente essas permissões.

Como `PUBLIC` é revogado, `arena_runtime` e `arena_admin_writer` também perdem o acesso herdado. O owner `arena_migrator` continua podendo executar as assinaturas antigas por ser dono das funções; isso é esperado e não representa exposição operacional para a aplicação ou para o CLI.

**Testes com evidência direta:**

- `AdminLifecycleStateFreshInstallIntegrationTest.legacyBootstrapSignatureIsDeniedForOfflineOperator` (`:127-143`)
  - invoca a assinatura legada como `arena_offline_operator`;
  - exige `PSQLException`;
  - exige `permission denied for function`.
- `AdminLifecycleStateIntegrationTest.legacyRecoverySignatureIsDeniedForOfflineOperator` (`:96-112`)
  - mesmo padrão para recovery.
- `PostgresRolePrivilegesIntegrationTest.offlineOperatorCanExecuteOnlyNewAdminFunctionSignatures` (`:149-184`)
  - `has_function_privilege` retorna `false` para as duas assinaturas legadas;
  - retorna `true` para as duas novas.

**Conclusão:** B1 resolvido.

---

### 2.2 Assinaturas novas preservadas e CLI endurecido

**Migration V3.1:** `V3_1:8-74` e `:79-178`

- bootstrap novo com `p_actor_ref`;
- recovery novo com `p_actor_ref`, `p_reason` e `p_change_ticket`.

**Migration V3.3:** `V3_3:14-21`

- reafirma os grants apenas das assinaturas novas para `arena_offline_operator`.

**CLI:** `AdminLifecycleCliService.java:54-78`

`v3FunctionsExist()` agora exige:

- `proname` exato;
- `pronargs` igual a 5 ou 8;
- tipos exatos via `array_to_string(proargtypes::regtype[], ',')`.

Isso impede que o CLI aceite apenas pelo nome quando as assinaturas corretas não existem.

**Observação:** o guard valida a existência das assinaturas novas, não o estado de ACL. Como o Flyway aplica as versões em ordem, um banco parcialmente migrado manualmente poderia ser um caso residual, mas não ocorre no fluxo normal de migração.

**Conclusão:** CLI e assinaturas novas compatíveis e verificáveis.

---

### 2.3 Interação dos Achados A, B, C e D

A interação está coerente:

1. **Ordem lock → revalidação → validação de estado** em ambos os fluxos:
   - `UsuarioService.alterarPerfil` (`:154-157`);
   - `UsuarioService.deletarAdministrativo` (`:272-275`).

2. **Revalidação do operador sob lock:**
   - `revalidarOperadorAdminOuFalhar` (`UsuarioService.java:131-145`) recarrega o operador dentro da transação.

3. **Proteção do último ADMIN:**
   - rebaixamento rejeitado com `LAST_ADMIN` e auditoria `REJECTED`;
   - exclusão rejeitada com `LAST_ADMIN` e auditoria `REJECTED`.

4. **Exceção legada `deletar(Long)`:**
   - mantém `@Transactional(noRollbackFor = LastAdminException.class)`;
   - delega para `deletarAdministrativo`;
   - os dois testes (`UsuarioServiceLegadoDeletarIntegrationTest:238-264` e `:272-307`) documentam corretamente o comportamento real:
     - exceção capturada dentro do callback → auditoria comitada;
     - exceção propagada pelo limite externo → rollback da auditoria.

5. **Exclusão de CLIENTE:**
   - validada antes da mutação (`UsuarioService.java:346-352`);
   - testada em `UsuarioClienteExclusaoConsistenciaIntegrationTest`.

6. **DTOs legados:**
   - `UsuarioRequestDTO` não possui campo `perfil`;
   - `UsuarioService.salvar(UsuarioRequestDTO)` força `CLIENTE`;
   - `UsuarioService.atualizar(Long, UsuarioRequestDTO)` não altera perfil nem senha;
   - `UsuarioCadastroAtualizacaoIntegrationTest:373-431` chama diretamente os dois métodos.

7. **Auditoria e rollback:**
   - `AdminLifecycleDbService.registrarAuditoria` usa `h04_append_audit_event`;
   - `rollbackTecnicoPreservaPerfisEAuditoriaConsistente` comprova reversão de perfil, auditoria e liberação do lock.

**Conclusão:** A, B, C e D estão integrados de forma consistente.

---

### 2.4 LAC-01 a LAC-05

| LAC | Comportamento esperado | Evidência |
|---|---|---|
| **LAC-01** | Exclusões concorrentes dos últimos dois ADMIN não podem deixar o sistema sem ADMIN | `UsuarioPerfilAdminIntegrationTest:684-750` |
| **LAC-02** | ID inexistente retorna 404 e não grava auditoria de mutação | `UsuarioPerfilAdminIntegrationTest:560-595` |
| **LAC-03** | Mesmo perfil retorna `NO_OP` sem auditoria | `UsuarioPerfilAdminIntegrationTest:601-678` |
| **LAC-04** | Métodos legados não alteram perfil/senha indevidamente | `UsuarioCadastroAtualizacaoIntegrationTest:373-431` |
| **LAC-05** | Rollback técnico preserva perfil, auditoria e estado | `UsuarioServiceLegadoDeletarIntegrationTest:351-443` |

**Conclusão:** todos os LACs exercitam efetivamente os comportamentos descritos.

---

### 2.5 Permissões das roles

**Provisionamento:** `scripts/postgres/provision-arena-roles.sql:40-100`

- `arena_runtime`: `NOINHERIT`, sem atributos administrativos, `CONNECT`, `USAGE` em `public`, sem `CREATE`.
- `arena_migrator`: `NOINHERIT`, sem atributos administrativos, `CONNECT`, `USAGE` e `CREATE` em `public`; após instalar `btree_gist`, o `CREATE` em database é revogado.
- `arena_admin_writer`: `NOINHERIT`, sem atributos administrativos, `CONNECT`, `USAGE` em `public`.
- `arena_offline_operator`: `NOINHERIT`, sem atributos administrativos, `CONNECT`, `USAGE` em `public`.

**V2:** `V2:1-18`

- `arena_runtime` recebe DML apenas em `usuario`, `quadra` e `agendamento`;
- recebe `USAGE/SELECT` em sequences;
- não recebe acesso a `flyway_schema_history`.

**V3:** `V3:620-662`

- revoga acesso de `arena_runtime` às tabelas internas de lifecycle/auditoria;
- concede apenas as funções necessárias:
  - `arena_runtime` e `arena_admin_writer`: triggers e auditoria;
  - `arena_offline_operator`: bootstrap/recovery/reconcile/append.

**V3.2:** `V3_2:34-37`

- `h04_get_admin_lifecycle_state()` revogado de `PUBLIC`;
- concedido a `arena_runtime` e `arena_admin_writer`.

**V3.3:** `V3_3:5-21`

- assinaturas antigas revogadas de `PUBLIC` e `arena_offline_operator`;
- assinaturas novas preservadas apenas para `arena_offline_operator`.

**Testes:** `PostgresRolePrivilegesIntegrationTest:27-325`

- confirma separação de roles;
- confirma ausência de memberships;
- confirma ausência de DDL/DML direto por writer/offline;
- confirma que apenas as assinaturas novas são executáveis pelo operador offline.

**Conclusão:** modelo de permissões consistente com DB-02.

---

## 3. Migrations V3, V3.1, V3.2 e V3.3 em conjunto

- **V3:** cria estado singleton, triggers, funções administrativas e permissões base.
- **V3.1:** adiciona contexto de auditoria às assinaturas novas.
- **V3.2:** cria o reader `h04_get_admin_lifecycle_state()`.
- **V3.3:** revoga as assinaturas antigas e preserva as novas.

Não há conflito de versões. O Flyway aplica a cadeia em ordem e a V3.3 depende apenas das funções já criadas por V3/V3.1.

**Presença atual no Git:**

- `V3_2__admin_lifecycle_state_reader.sql` — presente, **untracked**.
- `V3_3__revoke_legacy_admin_function_signatures.sql` — presente, **untracked**.

Hashes de integridade:

- `V3_2`: `775e016d6ad6ca31c26566ab44fa6b5d206fe5dbd5842f3c43e6f3a0664c4b56`
- `V3_3`: `d9f0d14be4aa068837b3cc0e022dfc0e177536afd92337e375a083282fa2bb1e`

**Atenção:** ambas precisam entrar no checkpoint. Sem `V3_2`, `AdminLifecycleDbService.obterEstadoSingleton()` falha porque a função não existirá.

---

## 4. Estado do Git e arquivos alterados

**Branch:** `develop`
**HEAD:** `c7c6d53`
**Working tree:** 9 arquivos rastreados modificados e 23 arquivos não rastreados.

### 4.1 Arquivos rastreados modificados

1. `src/main/java/br/com/arena/cli/AdminLifecycleCliService.java`
2. `src/main/java/br/com/arena/controller/UsuarioController.java`
3. `src/main/java/br/com/arena/dto/usuario/UsuarioRequestDTO.java`
4. `src/main/java/br/com/arena/mapper/UsuarioMapper.java`
5. `src/main/java/br/com/arena/service/UsuarioService.java`
6. `src/test/java/br/com/arena/cli/AdminCliBootstrapIntegrationTest.java`
7. `src/test/java/br/com/arena/testsupport/AdminLifecycleStateFreshInstallIntegrationTest.java`
8. `src/test/java/br/com/arena/testsupport/AdminLifecycleStateIntegrationTest.java`
9. `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`

### 4.2 Arquivos não rastreados

**Documentação (10):**

1. `docs/34-implementacao-h04-etapa-4-gerenciamento-perfis.md`
2. `docs/auditorias/doc35-auditoria-tecnica-h04-parte4.md`
3. `docs/auditorias/doc36-correcao-h04-achado-a.md`
4. `docs/auditorias/doc37-correcao-h04-achado-b.md`
5. `docs/auditorias/doc38-correcao-h04-achado-c.md`
6. `docs/auditorias/doc39-investigacao-h04-achado-d.md`
7. `docs/auditorias/doc40-correcao-h04-achado-d.md`
8. `docs/auditorias/doc41-auditoria-final-h04-parte-4.md`
9. `docs/auditorias/doc42-correcao-pontual-pos-doc41.md`
10. `docs/auditorias/doc43-correcao-b1-h04.md`

**Código novo (7):**

11. `src/main/java/br/com/arena/dto/usuario/AdminOperationResult.java`
12. `src/main/java/br/com/arena/dto/usuario/AdminOperationStatus.java`
13. `src/main/java/br/com/arena/dto/usuario/UsuarioCadastroDTO.java`
14. `src/main/java/br/com/arena/dto/usuario/UsuarioPerfilDTO.java`
15. `src/main/java/br/com/arena/dto/usuario/UsuarioUpdateDTO.java`
16. `src/main/java/br/com/arena/exception/LastAdminException.java`
17. `src/main/java/br/com/arena/service/AdminLifecycleDbService.java`

**Migrations (2):**

18. `src/main/resources/db/migration/V3_2__admin_lifecycle_state_reader.sql`
19. `src/main/resources/db/migration/V3_3__revoke_legacy_admin_function_signatures.sql`

**Testes novos (4):**

20. `src/test/java/br/com/arena/controller/UsuarioCadastroAtualizacaoIntegrationTest.java`
21. `src/test/java/br/com/arena/controller/UsuarioClienteExclusaoConsistenciaIntegrationTest.java`
22. `src/test/java/br/com/arena/controller/UsuarioPerfilAdminIntegrationTest.java`
23. `src/test/java/br/com/arena/controller/UsuarioServiceLegadoDeletarIntegrationTest.java`

**Alterações não documentadas:** nenhuma identificada. Os arquivos acima correspondem aos conjuntos registrados em `doc34`–`doc43`.

`git diff --check` não retornou erros de whitespace.

---

## 5. Testes realmente executados

### 5.1 Compilação

Executada nesta auditoria:

```
./mvnw -DskipTests test-compile
```

Resultado:

- `BUILD SUCCESS`

### 5.2 Testes H-04 selecionados

A reexecução nesta auditoria não foi possível porque o Docker Desktop não estava disponível no ambiente WSL. A última execução real nesta mesma árvore de código, imediatamente antes da auditoria, registrou:

- **95/95 testes H-04 selecionados**
- `BUILD SUCCESS`
- tempo total: `01:12 min`

Distribuição:

| Classe | Testes | Falhas | Erros |
|---|---:|---:|---:|
| `AdminCliBootstrapIntegrationTest` | 5 | 0 | 0 |
| `AdminCliRecoveryIntegrationTest` | 5 | 0 | 0 |
| `AdminCliSecurityTest` | 4 | 0 | 0 |
| `UsuarioCadastroAtualizacaoIntegrationTest` | 11 | 0 | 0 |
| `UsuarioClienteExclusaoConsistenciaIntegrationTest` | 6 | 0 | 0 |
| `UsuarioPerfilAdminIntegrationTest` | 18 | 0 | 0 |
| `UsuarioServiceLegadoDeletarIntegrationTest` | 9 | 0 | 0 |
| `AdminLifecycleStateFreshInstallIntegrationTest` | 4 | 0 | 0 |
| `AdminLifecycleStateIntegrationTest` | 16 | 0 | 0 |
| `PostgresRolePrivilegesIntegrationTest` | 7 | 0 | 0 |
| `TestPostgresSettingsTest` | 10 | 0 | 0 |
| **Total** | **95** | **0** | **0** |

Como nenhum arquivo de código foi alterado após essa execução, o resultado permanece aplicável ao estado auditado.

### 5.3 Suíte completa

**Não foi reexecutada nesta auditoria** e, portanto, **não é declarada aprovada**.

Falha anterior conhecida:

- `AgendamentoCreatedAtIntegrationTest.flywayV1ExisteEConstraintDeSobreposicaoFoiCriada`
  - espera exatamente **2** migrations aplicadas;
  - a cadeia atual chega a **6** versões: V1, V2, V3, V3.1, V3.2 e V3.3.

Erros anteriores conhecidos:

- 33 erros legados em testes de agendamento, relacionados a fixtures que criam ADMIN em estado `RECOVERY_REQUIRED`.

Essas falhas não são regressão da Parte 4 do H-04, mas impedem qualquer declaração de suíte completa verde.

---

## 6. Problemas remanescentes

| ID | Severidade | Descrição | Ação |
|---|---|---|---|
| **P1** | Médio | `V3_2__admin_lifecycle_state_reader.sql:1-4` ainda diz que a leitura ocorre “sob o advisory lock”, mas a função faz apenas `SELECT` simples (`:20-22`). O lock é garantido pelos chamadores (`UsuarioService:154-157,272-275`), não pela própria função | Corrigir o comentário antes do checkpoint |
| **P2** | Alto (para checkpoint futuro) | `V3_2` e `V3_3` estão untracked. Um commit sem elas quebra a aplicação e o B1 | Incluir no commit do checkpoint |
| **P3** | Médio | Suíte completa não está verde: 1 falha + 33 erros legados | Registrar explicitamente; corrigir em etapa separada |
| **P4** | Baixo | `AdminCliApplication.main()` usa nomes antigos de exclusões Spring Boot 3 (`:30-35`) que são silenciosamente ignorados no Boot 4.0.7; as exclusões reais corretas estão na anotação (`:18-22`) e em `application-cli.properties` | Limpeza cosmética em V4/V5 |
| **P5** | Baixo | `h04_reconcile_admin_state(TEXT)` continua com auditoria empobrecida (`actor_ref` fixo, sem ticket) | V4/V5 |
| **P6** | Baixo | Testes de fresh install não exercitam o backfill real de `v_initial_install` | V4/V5 |
| **P7** | Baixo | `doc37` e `doc40` permanecem como registros históricos superados por `doc42`; não foram editados | Higiene documental opcional |

---

## 7. Itens obrigatórios antes do checkpoint

1. **Corrigir o comentário de `V3_2`** para refletir que:
   - a função apenas lê o estado;
   - a aquisição do advisory lock é responsabilidade dos chamadores;
   - os chamadores de produção já adquirem o lock antes da leitura.
2. **Incluir no checkpoint**:
   - `V3_2__admin_lifecycle_state_reader.sql`
   - `V3_3__revoke_legacy_admin_function_signatures.sql`
   - os demais arquivos não rastreados listados na seção 4.
3. **Registrar no checkpoint** que a suíte completa não está verde devido às falhas legadas de agendamento.
4. **Reexecutar a seleção H-04 de 95 testes** após a correção do comentário, mesmo sendo uma alteração apenas documental, para manter a evidência atualizada.

---

## 8. Riscos que podem aguardar V4/V5

- Remover DML direto de `arena_runtime` sobre `perfil`/`senha`, substituindo por funções `SECURITY DEFINER` e datasource `arena_admin_writer`.
- Enriquecer a auditoria de `h04_reconcile_admin_state(TEXT)` com operador e ticket.
- Avaliar contenção do advisory lock global em escala.
- Formalizar runbook de provisionamento (`btree_gist` antes do Flyway).
- Criar teste real de fresh install com `TEST_H04_INITIAL_INSTALL=true`.
- Limpar nomes de exclusões obsoletos em `AdminCliApplication.main()`.
- Redesenhar os testes legados DB-02 de agendamento.
- Mapear `LastAdminException` diretamente para 409 se o método legado vier a ser reexposto.
- Decidir se `CLIENTE_DELETE`, `NO_OP` e `TARGET_NOT_FOUND` devem gerar auditoria explícita.
- Remover código morto identificado:
  - `UsuarioMapper.toEntity(UsuarioRequestDTO)`;
  - `UsuarioService.atualizarProprioPerfil`.

---

## 9. Confirmação de preservação

Hashes verificados nesta auditoria:

- `doc41-auditoria-final-h04-parte-4.md`
  `3285c5e88a7e34a882ee3664aeaec1972061ecca3fe9da5c3a7d0a2c793d8b31`
- `doc42-correcao-pontual-pos-doc41.md`
  `9055f02c240da2b7e80ac9f681727f1674e0a13941a111a9eb1e0975b9cf0c83`
- `doc43-correcao-b1-h04.md`
  `c26cb1f06cf33eb583170f757419c84434ed448b2a99937db2ae8c074a52ff11`
- `V3_2__admin_lifecycle_state_reader.sql`
  `775e016d6ad6ca31c26566ab44fa6b5d206fe5dbd5842f3c43e6f3a0664c4b56`
- `V3_3__revoke_legacy_admin_function_signatures.sql`
  `d9f0d14be4aa068837b3cc0e022dfc0e177536afd92337e375a083282fa2bb1e`

Nenhum arquivo foi alterado nesta auditoria. Nenhum `commit`, `push`, `reset`, `clean` ou `checkout` destrutivo foi executado.

---

## 10. Veredito final

**APROVADO COM RESSALVAS.**

A H-04 Parte 4 pode ser aprovada para um checkpoint local, desde que o comentário da V3.2 seja corrigido antes da consolidação, as migrations V3.2 e V3.3 sejam incluídas no conjunto de arquivos do checkpoint e o estado não verde da suíte completa permaneça formalmente registrado.

Aprovado **não significa** suíte completa verde. Aprova-se a Parte 4 do H-04 com base na coerência funcional, nas permissões, na cobertura dos LACs e na resolução do B1.
