# 06 — Auditoria de testes

## Situação atual

Foi encontrado somente:

- `src/test/java/br/com/arena/ArenaApplicationTests.java`: um teste `@SpringBootTest` chamado `contextLoads`, sem assertions de regra de negócio.
- No `pom.xml`, dependências de teste para Spring Data JPA, Validation e WebMVC. Não foi declarada dependência específica `spring-security-test` nem plugin de cobertura.

Não foi executado `mvn test` nem build. O wrapper está configurado para obter Maven e as dependências poderiam ser baixadas; isso foi evitado conforme a instrução de não instalar dependências. Assim, não há resultado de compilação, integração ou cobertura a reportar. Também não se pode concluir que `contextLoads` passa: o repositório não contém configuração datasource/JWT, embora um ambiente externo possa fornecê-la.

## Cobertura observável

| Fluxo/regra | Teste encontrado? | Lacuna |
|---|---:|---|
| Inicialização do contexto | Sim, apenas teste vazio | Depende de banco e propriedades externas; não valida endpoints ou beans funcionalmente. |
| Registro/login, BCrypt e token | Não | Credenciais válidas/inválidas, expiração, formato de token, senha persistida e perfil padrão. |
| Autorização por papel | Não | Cliente versus ADMIN em endpoints administrativos e 401/403. |
| Propriedade de agendamento | Não | Acesso/edição/exclusão de terceiro e troca indevida do `usuarioId` (SEG-01). |
| Datas e intervalos | Não | Horário passado, início/fim inválidos, limite adjacente sem sobreposição. |
| Conflito de reserva | Não | Sobreposição na criação, edição, cancelados e concorrência. |
| Quadra ativa | Não | Recusa ao reservar quadra inativa e filtro da listagem. |
| Usuários e e-mail único | Não | Duplicidade, corrida na unicidade e perfil ausente em update admin. |
| Respostas e exceções | Não | Contratos 201/204/400/401/403/404/409 e consistência do corpo. |
| Persistência/JPA | Não | Relacionamentos, constraints, consultas e comportamento LAZY/N+1. |

Não há base para estimar cobertura percentual. A ausência de relatório de cobertura não é, isoladamente, prova de baixa qualidade, mas neste caso acompanha a falta de testes de comportamento.

## Achados

### TEST-01 — Teste único não cobre comportamento da aplicação

- **Prioridade:** ALTA
- **Situação:** comprovado
- **Arquivo/classe:** `src/test/java/br/com/arena/ArenaApplicationTests.java`.
- **Descrição técnica:** o único teste anotado apenas inicializa o contexto e não verifica resultados, regras, persistência ou segurança.
- **Consequência prática:** falhas graves como mudança de proprietário, conflito em update, exposição de rota ou resposta HTTP incorreta podem passar sem detecção.
- **Solução recomendada:** construir testes em camadas começando pelas regras de autorização e integridade; não tratar `contextLoads` como evidência de cobertura funcional.

### TEST-02 — Reprodutibilidade de testes está indefinida

- **Prioridade:** MÉDIA
- **Situação:** risco decorrente da ausência de configuração no repositório
- **Arquivo/classe:** ausência de `src/test/resources` e de configuração datasource/JWT versionada; `pom.xml`.
- **Descrição técnica:** não foi encontrada configuração de banco de teste, perfil de teste, fixture ou instrução de execução que não dependa de ambiente externo.
- **Consequência prática:** testes de contexto/integrados podem falhar em máquina limpa ou depender acidentalmente de banco local.
- **Solução recomendada:** definir perfil isolado de testes e estratégia de banco (container de teste ou banco local documentado), sem reaproveitar dados de desenvolvimento/produção; injetar segredo JWT descartável apenas no perfil de teste.

## Estratégia recomendada

### 1. Testes unitários de serviços

Usar JUnit/Mockito para regras determinísticas, sem subir o contexto inteiro:

- `AgendamentoService`: intervalos sobrepostos e adjacentes; data passada; quadra inativa; edição sem conflito com o próprio registro; edição que colide com outra reserva; cliente não muda proprietário; ADMIN pode executar a operação explicitamente permitida.
- `UsuarioService`: cadastro sempre `CLIENTE`, senha codificada, e-mail duplicado, atualização própria não altera perfil e update administrativo valida perfil.
- `QuadraService`: atualização de status e comportamento de exclusão com agendamentos.

### 2. Testes de controller/API

Usar MockMvc e suporte Spring Security para verificar:

- rotas públicas e protegidas;
- 401 sem token, 403 para papel errado, acesso permitido ao dono e ADMIN;
- `@Valid`, JSON inválido e esquema de erro;
- códigos 201/204/400/404/409 e contratos OpenAPI relevantes;
- cadastro em `/usuarios` versus caminho anunciado no README.

### 3. Testes de persistência

Testar constraints reais do banco, unicidade de e-mail, FKs, enums e migrações em banco compatível com PostgreSQL. Banco em memória pode diferir em constraints, índices e tipos; escolher a ferramenta de acordo com a fidelidade necessária.

### 4. Teste de concorrência da reserva

Após escolher estratégia de lock/constraint, executar duas transações concorrentes para a mesma quadra/intervalo e afirmar que no máximo uma é aceita. Testar também intervalos sobrepostos, adjacentes, datas diferentes e estados cancelados.

### 5. Evolução contínua

Adicionar cobertura de branches relevantes (sem impor um percentual arbitrário no início), executar testes em CI e manter cada correção de segurança/regra acompanhada por um teste de regressão. Métricas de cobertura ajudam a localizar lacunas, mas não substituem qualidade dos cenários.
