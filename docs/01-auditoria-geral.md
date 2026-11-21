# 01 — Auditoria geral

## Escopo e método

Esta auditoria foi feita por inspeção estática do README, do `pom.xml` e de todos os arquivos Java encontrados em `src/main/java` e `src/test/java`. O repositório não contém `src/main/resources` nem configuração de aplicação versionada. Não executei build ou testes: isso poderia baixar wrapper/dependências, operação que foi vedada. Portanto, conclusões sobre inicialização e comportamento em runtime são identificadas como condicionais quando dependem de configuração externa.

Os achados detalhados estão nos documentos 02 a 06. Cada achado usa um identificador, prioridade, evidência, impacto e recomendação. “Comprovado” significa diretamente observável no código/documentação; “risco” indica consequência plausível ainda dependente do ambiente; “ausente” indica funcionalidade não encontrada no escopo analisado.

## Resumo executivo

A Arena Esportiva é um MVP pequeno, organizado em camadas, com autenticação JWT, papéis `ADMIN`/`CLIENTE`, DTOs, validação de entrada, repositórios Spring Data e regras de agendamento implementadas em serviços. Há uma base didática razoável e alguns controles importantes — por exemplo, cadastro público sempre cria `CLIENTE`, senhas passam por BCrypt e leitura/alteração/exclusão de agendamento verificam proprietário ou administrador.

O projeto ainda não demonstra integridade suficiente para operar reservas reais. A atualização de agendamento não repete a verificação de conflito, não verifica se a quadra está ativa e aceita um `usuarioId` diferente do proprietário autorizado. A verificação de conflito na criação também é uma consulta seguida de gravação sem transação/invariante no banco, permitindo colisão sob concorrência. A configuração necessária para JWT e banco não está no repositório; sem fornecimento externo, a aplicação não tem configuração suficiente para inicializar.

**Diagnóstico:** protótipo/MVP em estágio inicial; adequado como base de aprendizado, mas não recomendado para produção antes de corrigir autorização e consistência de reservas, tornar a execução reproduzível e acrescentar testes automatizados dos fluxos críticos.

## Maturidade técnica

| Área | Avaliação resumida |
|---|---|
| Organização | Camadas reconhecíveis e controllers relativamente finos; fronteiras de segurança e domínio ainda acopladas. |
| Segurança | BCrypt, JWT stateless e autorização por papel/proprietário presentes; regra de propriedade falha na atualização de reserva. |
| Persistência | Entidades e chaves estrangeiras JPA básicas; faltam garantia atômica de não sobreposição, limites de transação de negócio e estratégia versionada de schema. |
| API | Endpoints CRUD básicos e validação; paginação, consulta de disponibilidade e padronização completa de erros estão ausentes. |
| Testes | Um teste de carregamento de contexto, sem testes de regras, API, segurança ou concorrência. |
| Operação | Não há configuração de runtime, migrações, conteinerização ou evidência de observabilidade no repositório. |

## Pontos fortes

- Separação inicial entre `controller`, `service`, `repository`, `model`, `dto`, `mapper` e `security`.
- Injeção de dependências predominantemente por construtor.
- Senha não aparece no `UsuarioResponseDTO`; o cadastro força `Perfil.CLIENTE` e o endpoint de perfil próprio não aplica o campo `perfil` recebido.
- Autenticação stateless, verificação de assinatura/expiração do JWT pela biblioteca e BCrypt para armazenamento de senhas.
- DTOs de entrada e `@Valid` nos endpoints de escrita; exceções de recurso inexistente e regras de negócio têm tratamento central.
- Mapeamento explícito para respostas, evitando serializar diretamente as entidades JPA.
- `POST` de criação retorna 201 e exclusões retornam 204.

## Principais problemas — visão consolidada

| ID | Prioridade | Situação | Resumo |
|---|---|---|---|
| SEG-01 | **ALTA** | Comprovado | `PUT /agendamentos/{id}` autoriza pelo dono atual, mas depois permite substituir o `usuarioId` pelo de outra pessoa. |
| DB-01 | **ALTA** | Comprovado | Atualização de agendamento não valida conflito nem quadra ativa. |
| DB-02 | **ALTA** | Risco comprovado pela implementação | Checagem e gravação de conflito não são atômicas; duas requisições concorrentes podem reservar o mesmo horário. |
| SEG-02 | **ALTA** | Dependente de configuração externa | `jwt.secret` e `jwt.expiration` são obrigatórios, mas não há configuração de aplicação versionada no projeto. |
| TEST-01 | **ALTA** | Comprovado | Não há testes de regras centrais, autorização, persistência ou concorrência; existe apenas `contextLoads`. |
| DB-03 | **MÉDIA** | Comprovado | Não há índices explícitos para a consulta de disponibilidade nem migrações versionadas. |
| ARQ-01 | **MÉDIA** | Comprovado | Serviços não definem fronteiras `@Transactional`; utilitário estático de segurança e entidade ligada ao Spring Security dificultam isolamento/testabilidade. |
| API-01 | **MÉDIA** | Comprovado | Conflitos de negócio são convertidos em 400; não existe representação 409 específica. |
| API-02 | **MÉDIA** | Comprovado | A listagem de quadras inclui inativas, embora o README prometa apenas quadras ativas. |
| API-03 | **MÉDIA** | Funcionalidade ausente | O cliente não tem endpoint de coleção para listar seus próprios agendamentos; não há endpoint de disponibilidade nem fluxo de cancelamento por status. |

## Divergências entre README e implementação

| README | Implementação encontrada |
|---|---|
| Spring Boot 3.x | `pom.xml` declara Spring Boot **4.0.7**. |
| Auth0 Java JWT | A implementação usa **JJWT (`io.jsonwebtoken`) 0.12.7**. |
| ModelMapper / MapStruct | Mapeadores são classes próprias com métodos estáticos (`mapper/`). |
| Registro em `POST /auth/register` | Cadastro está em `POST /usuarios`; `/auth/**` contém o login. |
| `GET /quadras` lista quadras ativas | `QuadraService.listarTodas()` chama `findAll()` sem filtrar `ativa`. |
| CORS e beans na configuração | `OpenApiConfig` documenta OpenAPI; não foi encontrada configuração explícita de CORS. |
| Swagger/OpenAPI “em andamento” | Dependência e `OpenApiConfig` já estão presentes; a disponibilidade em runtime não foi testada. |
| Prevenção de sobreposição | Existe verificação na criação, mas não na atualização e sem proteção atômica contra concorrência. |
| CRUD “completo” de agendamentos | As rotas existem, mas `DELETE` remove fisicamente; o estado `CANCELADO` não é aplicado por fluxo API. |
| `application.properties` de exemplo | O arquivo e o diretório `src/main/resources` não aparecem no repositório. |
| Testes, paginação, e-mail e Docker no roadmap como pendentes | A inspeção confirma ausência de testes além de `contextLoads`, ausência de paginação, Docker e fluxo de e-mail. |

## Conclusão

A estrutura é útil para evoluir didaticamente, mas as alegações de “prevenção de conflitos” e de operação completa precisam ser qualificadas. O risco principal não é a falta de uma tecnologia específica: é a ausência de garantias de autorização e integridade no caminho de atualização e concorrência, somada à falta de testes que detectem regressões. Recomenda-se começar por configuração reproduzível, propriedade dos agendamentos e consistência transacional/relacional; depois padronizar contratos e ampliar testes antes de acrescentar funcionalidades de produto.
