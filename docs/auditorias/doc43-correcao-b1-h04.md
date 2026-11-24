# doc43 — Resolução do B1 (H-04 Parte 4)

**Data:** 2026-11-24
**Escopo:** eliminar o acesso às assinaturas SQL legadas de bootstrap e recovery, mantendo apenas as versões novas com contexto completo de auditoria.
**Resultado:** B1 resolvido em migration e testes. As assinaturas antigas continuam existindo, mas **não são executáveis por `arena_offline_operator`**; as novas continuam executáveis, funcionais e auditadas.

---

## 1. Assinaturas tratadas

### Legadas — revogadas

- `public.h04_bootstrap_first_admin(VARCHAR, VARCHAR, VARCHAR, VARCHAR)`
- `public.h04_recover_admin(BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR)`

### Novas — preservadas

- `public.h04_bootstrap_first_admin(VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR)`
- `public.h04_recover_admin(BIGINT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, VARCHAR)`

---

## 2. Migration criada

**Arquivo novo:** `src/main/resources/db/migration/V3_3__revoke_legacy_admin_function_signatures.sql`

Ação executada:

- `REVOKE ALL ON FUNCTION` das duas assinaturas legadas **de `PUBLIC` e `arena_offline_operator`**;
- `GRANT EXECUTE` reafirmado apenas para as assinaturas novas em favor de `arena_offline_operator`;
- nenhum `DROP FUNCTION`;
- nenhuma alteração em `V3__admin_lifecycle_state_and_triggers.sql`;
- nenhuma alteração em `V3_1__admin_recovery_audit_context.sql`;
- nenhuma alteração em `V3_2__admin_lifecycle_state_reader.sql`;
- nenhuma alteração em roles, DB-02, V4 ou V5.

SHA-256 do arquivo novo:

`d9f0d14be4aa068837b3cc0e022dfc0e177536afd92337e375a083282fa2bb1e`

---

## 3. Guard do CLI endurecido

**Arquivo:** `src/main/java/br/com/arena/cli/AdminLifecycleCliService.java`

O método `v3FunctionsExist()` deixou de contar apenas nomes distintos em `pg_proc`. Agora valida:

- nome da função;
- número exato de argumentos (`pronargs`);
- tipos reais dos argumentos, via `array_to_string(proargtypes::regtype[], ',')`.

Isso garante que o CLI só aceita as assinaturas novas:

- bootstrap com 5 `character varying`;
- recovery com `bigint`, 5 `character varying`, `text` e 1 `character varying`.

A compatibilidade do CLI foi preservada: `AdminCliBootstrapIntegrationTest` e `AdminCliRecoveryIntegrationTest` continuam verdes.

---

## 4. Testes atualizados

### 4.1 `AdminLifecycleStateFreshInstallIntegrationTest`

- Chamadas legadas de bootstrap migradas para a assinatura nova com `actor_ref`.
- Auditoria passou a verificar `actor_ref == "test-operator"`.
- Novo teste `legacyBootstrapSignatureIsDeniedForOfflineOperator`:
  - invoca diretamente a assinatura legada;
  - exige `PSQLException`;
  - exige `permission denied for function` na causa raiz.

### 4.2 `AdminLifecycleStateIntegrationTest`

- Bootstrap em estado `RECOVERY_REQUIRED` migrado para a assinatura nova.
- Recovery migrado para a assinatura nova com `actor_ref`, `reason` e `change_ticket`.
- Auditoria passou a verificar os três campos de contexto.
- Novo teste `legacyRecoverySignatureIsDeniedForOfflineOperator`:
  - invoca diretamente a assinatura legada;
  - exige `PSQLException`;
  - exige `permission denied for function` na causa raiz.

### 4.3 `PostgresRolePrivilegesIntegrationTest`

Novo teste `offlineOperatorCanExecuteOnlyNewAdminFunctionSignatures`:

- `has_function_privilege` retorna `false` para as duas assinaturas legadas;
- retorna `true` para as duas assinaturas novas.

### 4.4 Arquivos não alterados nesta etapa

- Achados A, B e C;
- LAC-01 a LAC-05;
- V3, V3.1 e V3.2;
- regras DB-02;
- `doc41` e `doc42`.

---

## 5. Validação executada

Ambiente:

- PostgreSQL `16.15 (Debian 16.15-1.pgdg13+2)`;
- container descartável `arena_h04_b1_36069`;
- porta aleatória `62262` em `127.0.0.1`;
- `tmpfs` de 1 GiB (`rw,noexec,nosuid,size=1g`);
- `Mounts=[]`, ou seja, **sem volume persistente**;
- containers/bancos existentes não acessados;
- nenhum container `arena_h04_b1_*` permaneceu ativo após a execução.

### 5.1 Compilação

- `./mvnw -DskipTests test-compile`
- `BUILD SUCCESS`
- tempo final: `30.152 s`

### 5.2 Bootstrap

Seleção:

```
-Dtest=AdminCliBootstrapIntegrationTest,AdminLifecycleStateFreshInstallIntegrationTest#bootstrapCreatesFirstAdminAndConsumesMarker+secondBootstrapFailsAfterMarkerConsumed+legacyBootstrapSignatureIsDeniedForOfflineOperator
```

Resultado:

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `AdminCliBootstrapIntegrationTest` | 5 | 0 | 0 | 32,72 s |
| `AdminLifecycleStateFreshInstallIntegrationTest` | 3 | 0 | 0 | 0,250 s |
| **Total** | **8** | **0** | **0** | `43.613 s` |

`BUILD SUCCESS`.

### 5.3 Recovery

Seleção:

```
-Dtest=AdminCliRecoveryIntegrationTest,AdminLifecycleStateIntegrationTest#recoveryCreatesAdminAndClearsRecoveryFlag+bootstrapIsRejectedInRecoveryRequiredState+legacyRecoverySignatureIsDeniedForOfflineOperator
```

Resultado:

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `AdminCliRecoveryIntegrationTest` | 5 | 0 | 0 | 44,70 s |
| `AdminLifecycleStateIntegrationTest` | 3 | 0 | 0 | 0,287 s |
| **Total** | **8** | **0** | **0** | `57.303 s` |

`BUILD SUCCESS`.

### 5.4 Permissões

Seleção:

```
-Dtest=PostgresRolePrivilegesIntegrationTest
```

Resultado:

| Classe | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `PostgresRolePrivilegesIntegrationTest` | 7 | 0 | 0 | 47,47 s |

`BUILD SUCCESS`.

### 5.5 Regressão H-04 ampliada

Seleção:

```
-Dtest=UsuarioPerfilAdminIntegrationTest,UsuarioCadastroAtualizacaoIntegrationTest,UsuarioClienteExclusaoConsistenciaIntegrationTest,UsuarioServiceLegadoDeletarIntegrationTest,AdminCliSecurityTest,AdminCliBootstrapIntegrationTest,AdminCliRecoveryIntegrationTest,AdminLifecycleStateFreshInstallIntegrationTest,AdminLifecycleStateIntegrationTest,PostgresRolePrivilegesIntegrationTest,TestPostgresSettingsTest
```

Resultado:

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

`BUILD SUCCESS`; tempo total `01:12 min`.

### 5.6 Executados x existentes

- **Executados nesta etapa:** compilação, bootstrap, recovery, permissões e regressão H-04 ampliada — todos verdes.
- **Suíte completa:** não foi executada nesta etapa e **não é declarada aprovada**.

---

## 6. Estado do Git

- branch: `develop`;
- HEAD: `c7c6d53 feat(security): implement H-04 admin lifecycle stages 1-3`;
- arquivos modificados nesta etapa:
  - `src/main/java/br/com/arena/cli/AdminLifecycleCliService.java`
  - `src/test/java/br/com/arena/testsupport/AdminLifecycleStateFreshInstallIntegrationTest.java`
  - `src/test/java/br/com/arena/testsupport/AdminLifecycleStateIntegrationTest.java`
  - `src/test/java/br/com/arena/testsupport/PostgresRolePrivilegesIntegrationTest.java`
- arquivo novo nesta etapa:
  - `src/main/resources/db/migration/V3_3__revoke_legacy_admin_function_signatures.sql`
- nenhum `commit`, `push`, `reset`, `clean` ou `checkout` destrutivo foi executado.

---

## 7. Integridade preservada

Hashes confirmados após a etapa:

- `docs/auditorias/doc41-auditoria-final-h04-parte-4.md`:
  `3285c5e88a7e34a882ee3664aeaec1972061ecca3fe9da5c3a7d0a2c793d8b31`
- `docs/auditorias/doc42-correcao-pontual-pos-doc41.md`:
  `9055f02c240da2b7e80ac9f681727f1674e0a13941a111a9eb1e0975b9cf0c83`
- `src/main/resources/db/migration/V3_2__admin_lifecycle_state_reader.sql`:
  `775e016d6ad6ca31c26566ab44fa6b5d206fe5dbd5842f3c43e6f3a0664c4b56`
- `src/main/resources/db/migration/V3_3__revoke_legacy_admin_function_signatures.sql`:
  `d9f0d14be4aa068837b3cc0e022dfc0e177536afd92337e375a083282fa2bb1e`

---

## 8. Riscos restantes

| Item | Situação | Impacto |
|---|---|---|
| B12 — V3.2 untracked | Preservada, mas ainda precisa entrar no checkpoint | Alto |
| V3.3 untracked | Criada nesta etapa e precisa entrar no checkpoint | Alto |
| Suíte completa | Não executada nesta etapa | Não declarada aprovada |
| Owner `arena_migrator` | Continua podendo executar as assinaturas legadas como dono | Esperado e não acessível à aplicação/offline |
| V4/V5 | Não implementados nesta etapa | Fora do escopo |

---

## 9. Conclusão

B1 está resolvido no plano aplicável a `arena_offline_operator`:

- assinaturas legadas sem `EXECUTE`;
- assinaturas novas com `EXECUTE`;
- CLI funcional e validando assinaturas exatas;
- bootstrap e recovery funcionais com contexto de auditoria completo;
- regressão H-04 ampliada verde.

O trabalho permanece **sem commit e sem push**, aguardando revisão.
