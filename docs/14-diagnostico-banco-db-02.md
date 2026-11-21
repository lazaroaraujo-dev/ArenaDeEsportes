# DB-02 — Etapa 1: diagnóstico read-only do banco

**Resultado: BLOQUEADO — nenhuma conexão PostgreSQL efetiva foi estabelecida.** Não foi possível consultar `current_database()`, contar linhas, inspecionar colunas físicas ou verificar extensões. Não inferi que o banco é de desenvolvimento apenas porque a configuração Compose usa o nome padrão `arena`.

Nenhum comando de escrita, migration, limpeza ou operação de contêiner foi executado. As consultas ao PostgreSQL abaixo são um roteiro para quando uma conexão autorizada estiver disponível; **não foram executadas**.

## 1. Identificação do ambiente conectado

| Verificação | Resultado observado |
|---|---|
| URL/datasource Spring no repositório | Não há `src/main/resources/application.properties`, `application.yml` ou equivalente. |
| Variáveis Spring datasource no processo atual | Nenhuma `SPRING_DATASOURCE_*`/`SPRING_JPA_*` encontrada no ambiente desta sessão. |
| Configuração de execução IntelliJ versionada | Nenhuma referência a `spring.datasource`, `SPRING_DATASOURCE` ou `jdbc:postgresql` foi encontrada em `src`/`.idea`. Uma configuração guardada fora do workspace não foi inspecionada. |
| PostgreSQL no `pom.xml` | Driver PostgreSQL declarado em runtime; isso não configura host, database ou credenciais. |
| `.env` local | Existe e contém chaves `POSTGRES_DB`, `POSTGRES_USER` e `POSTGRES_PASSWORD`. Os valores não foram lidos nem impressos. |
| Uso do `.env` | `.gitignore` ignora `.env`; essas variáveis são usadas pelo Compose, não são automaticamente convertidas em configuração datasource para o processo Spring. |
| Host/porta pretendidos pelo Compose | O arquivo declara bind local `127.0.0.1:5432`; isso é configuração pretendida, não prova de um servidor ativo. |
| Banco/usuário pretendidos | O Compose usa `${POSTGRES_DB:-arena}` e `${POSTGRES_USER}`; o `.env` pode substituir o database padrão. Os valores efetivos não foram revelados. |
| Banco efetivamente conectado | **Nenhum comprovado nesta sessão.** |
| Ambiente confirmado como desenvolvimento | **Não.** Não há identidade de servidor/database obtida por conexão. |

## 2. Ferramentas e disponibilidade

- `docker ps` não pôde consultar o daemon: o executável `docker` não está disponível nesta distribuição WSL (a mensagem indica integração WSL do Docker Desktop indisponível).
- `psql` e `pg_isready` não estão instalados/disponíveis.
- `ss -ltn` não encontrou listener local na porta 5432.
- Nenhuma variável `POSTGRES_*` está exportada no processo atual; os nomes das variáveis presentes no `.env` não configuram uma conexão Spring ativa.
- O Compose existente declara somente configuração; não iniciei, parei ou removi contêineres.
- `docs/09-postgresql-docker.md` registra uma validação Docker/Compose anterior. Ela é histórica e não prova que Docker ou PostgreSQL estejam acessíveis nesta sessão.

**Impedimento:** não há cliente PostgreSQL, daemon Docker acessível, serviço escutando localmente nem configuração Spring datasource no processo. Sem um endpoint/credencial autorizados e uma conexão que confirme o alvo, qualquer consulta de dados seria especulativa.

## 3. Informações do banco e contagens

| Item solicitado | Resultado |
|---|---|
| `current_database()` | Não consultado — sem conexão. |
| Host/endereço e porta do servidor | Não confirmados. `127.0.0.1:5432` é apenas o bind previsto no Compose. |
| `current_user` / usuário conectado | Não consultado. O valor da variável `POSTGRES_USER` não foi exibido. |
| Versão PostgreSQL | Não consultada. `postgres:16` no Compose é imagem pretendida, não evidência do servidor em uso. |
| Quantidade de `agendamento` | Não consultada. |
| Quantidade de `usuario` | Não consultada. |
| Quantidade de `quadra` | Não consultada. |
| Agendamentos por status | Não consultados. Não se presume que o banco já tenha o status `EXPIRADO`. |
| Intervalos inválidos | Não verificados. |
| Sobreposições por quadra nos status bloqueadores | Não verificadas. |
| Colunas/tipos/constraints físicos | Não inspecionados. |
| `btree_gist` instalado/disponível | Não consultado. |

## 4. Schema esperado pelo código — não é schema observado

Pelas entidades JPA (`Agendamento.java`, `Usuario.java`, `Quadra.java`), espera-se:

- `agendamento`: ID gerado, data (`LocalDate`), hora início/fim (`LocalTime`), status persistido como texto (`EnumType.STRING`) e FKs não nulas `usuario_id` e `quadra_id`.
- `usuario` e `quadra`: tabelas relacionadas por essas FKs.
- A entidade atual não tem `criadoEm`; `StatusAgendamento` no código possui `PENDENTE`, `CONFIRMADO` e `CANCELADO`, sem `EXPIRADO`.
- Não há migration versionada detectada no projeto.

Esses pontos descrevem o mapeamento esperado, não confirmam nomes, tipos, defaults, constraints, cascades ou dados físicos do banco. A compatibilidade com a migration DB-02 só pode ser determinada após introspecção read-only do PostgreSQL correto.

## 5. Roteiro de consultas read-only

Executar somente depois de obter uma conexão aprovada e confirmar visualmente que ela aponta ao ambiente de desenvolvimento. Primeiro identificar a conexão:

```sql
SELECT current_database() AS database_name,
       current_user AS connected_user,
       inet_server_addr() AS server_address,
       inet_server_port() AS server_port,
       version() AS server_version;
```

Inspecionar colunas reais antes de executar consultas dependentes dos nomes físicos:

```sql
SELECT table_schema, table_name, column_name, data_type, udt_name,
       is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('agendamento', 'usuario', 'quadra')
ORDER BY table_name, ordinal_position;
```

Depois de confirmar a existência das três tabelas:

```sql
SELECT 'agendamento' AS tabela, count(*) AS quantidade FROM agendamento
UNION ALL
SELECT 'usuario', count(*) FROM usuario
UNION ALL
SELECT 'quadra', count(*) FROM quadra;

SELECT status, count(*) AS quantidade
FROM agendamento
GROUP BY status
ORDER BY status;
```

Verificar intervalos inválidos antes de construir qualquer range:

```sql
SELECT id, quadra_id, data, hora_inicio, hora_fim, status
FROM agendamento
WHERE hora_fim <= hora_inicio
ORDER BY data, hora_inicio, id;
```

Somente após confirmar que não há intervalos inválidos, verificar overlaps usando os status aprovados:

```sql
WITH bloqueantes AS (
    SELECT id, quadra_id, data, status,
           tsrange(data + hora_inicio, data + hora_fim, '[)') AS periodo
    FROM agendamento
    WHERE status IN ('PENDENTE', 'CONFIRMADO')
)
SELECT a.id AS agendamento_a,
       b.id AS agendamento_b,
       a.quadra_id,
       a.data,
       a.status AS status_a,
       b.status AS status_b
FROM bloqueantes a
JOIN bloqueantes b
  ON a.quadra_id = b.quadra_id
 AND a.data = b.data
 AND a.id < b.id
 AND a.periodo && b.periodo
ORDER BY a.data, a.quadra_id, a.id, b.id;
```

Verificar a extensão:

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname = 'btree_gist';

SELECT name, default_version, installed_version
FROM pg_available_extensions
WHERE name = 'btree_gist';
```

As consultas acima são apenas SELECTs. Não foram executadas nesta sessão. Se os nomes físicos diferirem, adaptar somente após inspecionar `information_schema`; não executar a consulta de overlaps antes de confirmar os tipos e validar intervalos.

## 6. Compatibilidade com a migration DB-02

**Status: não determinável sem acesso ao banco.** A migration planejada pressupõe coluna de data/hora compatível com `tsrange`, coluna de quadra comparável por GiST/`btree_gist`, status texto compatível com `PENDENTE`/`CONFIRMADO`, ausência de overlaps ativos e permissão para instalar `btree_gist`.

Também é necessário confirmar as FKs e constraints reais. A limpeza futura deve atingir apenas `agendamento` e preservar usuários/quadras; não há evidência física nesta sessão para afirmar a política `ON DELETE`, cascades, schema ou tabela efetivamente conectados.

## 7. Recomendação para a limpeza controlada

1. **Não limpar agora:** o ambiente conectado não pôde ser identificado.
2. Restaurar uma rota de conexão autorizada (por exemplo, Docker Desktop integrado ao WSL ou um datasource de desenvolvimento explicitamente fornecido). Não iniciar contêiner como parte deste diagnóstico.
3. Executar primeiro `current_database()`, endereço/porta e `current_user`; comparar os resultados com a configuração de desenvolvimento esperada. O nome `arena` sozinho não é prova.
4. Mostrar contagens/status de `agendamento`, `usuario` e `quadra` e o resultado de invalid ranges/overlaps. Parar se host/database não forem inequivocamente de desenvolvimento.
5. Antes de qualquer etapa destrutiva futura, obter confirmação explícita do alvo; então planejar uma transação que remova somente agendamentos, registre contagens antes/depois e valide que usuários e quadras não mudaram. Não usar `TRUNCATE ... CASCADE`.
6. Só depois, preparar/aplicar migrations aprovadas em etapa separada. Nenhuma migration ou exclusão foi executada aqui.

## 8. Resultado desta etapa

O diagnóstico não alcançou o PostgreSQL: não há conexão efetiva comprovada, portanto contagens, status, overlaps, estrutura física e `btree_gist` permanecem **desconhecidos**, e a conexão não pode ser classificada como desenvolvimento. O próximo passo é obter acesso autorizado e repetir as consultas read-only acima.

**Confirmação explícita:** nenhuma alteração foi realizada no banco; nenhum registro, usuário ou quadra foi inserido, atualizado ou excluído. Nenhum container foi iniciado, parado ou removido.
