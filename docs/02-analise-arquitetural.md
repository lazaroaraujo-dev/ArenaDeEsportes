# 02 — Análise arquitetural

## Estrutura atual

O código está sob `br.com.arena`, com organização principalmente por camada:

```text
auth/         fluxo de login e DTOs relacionados
config/       configuração OpenAPI
controller/   endpoints HTTP
dto/          contratos de entrada e saída por recurso
enumerate/    perfis, tipos de quadra e estados de agendamento
exception/    exceções próprias e advice global
mapper/       conversão manual entre entidades e DTOs
model/        entidades JPA (Usuario também é UserDetails)
repository/   interfaces Spring Data JPA
security/     filtro JWT, serviço JWT, configuração e utilitário
service/      regras de negócio e coordenação de persistência
```

A separação Controller → Service → Repository é visível. Os controllers delegam a lógica e os repositórios são interfaces pequenas. Os mapeadores evitam expor entidades diretamente, embora sejam estáticos e sem abstração compartilhada — algo aceitável para o tamanho atual, não uma deficiência por si só.

## Responsabilidades observadas

- **Controllers:** rotas, binding/validação HTTP, status de criação/exclusão e anotações de autorização.
- **Services:** regras de usuário, quadra e reserva; `AgendamentoService` coordena também os serviços de usuário e quadra.
- **Repositories:** operações CRUD e busca de usuário por e-mail / agendamento por quadra e data.
- **Models:** estado persistente e relacionamentos; `Usuario` também adapta o domínio à interface de segurança Spring.
- **DTOs e mappers:** validam dados recebidos e projetam respostas simples. `UsuarioResponseDTO` não inclui senha.
- **Security:** `SecurityConfig` define política stateless, caminhos públicos e filtro; `JwtAuthenticationFilter` carrega o usuário para cada token.
- **Exceptions:** `GlobalExceptionHandler` converte parte das exceções MVC para `ErrorResponse`.

## Avaliação de princípios e acoplamento

- **Responsabilidade única:** em geral razoável nas classes de camada. `Usuario` acumula persistência, domínio e autenticação, e `SecurityUtils` combina acesso ao contexto global com consulta de papel.
- **Aberto/fechado:** regras estão concentradas em serviços, mas regras de agendamento duplicadas ou incompletas não são extraídas para uma política testável. Não há necessidade atual de interfaces para cada serviço.
- **Substituição de Liskov / segregação de interfaces:** pouca evidência para avaliação aprofundada; não foi encontrada hierarquia própria relevante. `UserDetails` é uma interface externa implementada pela entidade.
- **Inversão de dependência:** controllers recebem serviços por construtor, o que é positivo. `AgendamentoService` depende de `UsuarioService` e `QuadraService`, além de um utilitário estático global; isso torna dependências de segurança e transação menos explícitas.
- **Coesão:** pacotes seguem responsabilidades entendíveis; organização por camada é simples e adequada ao MVP. Não há justificativa para introduzir arquitetura mais complexa apenas por escala futura.

## Achados arquiteturais

### ARQ-01 — Acesso à autenticação por utilitário estático e global

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** `src/main/java/br/com/arena/security/SecurityUtils.java`; usos em `AgendamentoService` e `UsuarioController`.
- **Descrição técnica:** serviços chamam `SecurityUtils.getUsuarioLogado()`/`isAdmin()`, que lê diretamente `SecurityContextHolder` e faz cast do principal para `Usuario`. A dependência de identidade não aparece na assinatura do caso de uso.
- **Consequência prática:** testes unitários precisam preparar estado global de segurança; chamadas fora de uma requisição autenticada podem causar cast/null errors; lógica de autorização fica menos explícita e reutilizável.
- **Solução recomendada:** tornar o usuário/ator atual uma dependência explícita do caso de uso (por exemplo, serviço de identidade autenticada injetável ou principal resolvido na camada web), mantendo a decisão de acesso centralizada e testável.

### ARQ-02 — Entidade de domínio implementa interface de segurança

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** `src/main/java/br/com/arena/model/Usuario.java`, declaração `implements UserDetails`.
- **Descrição técnica:** o modelo persistente depende de tipos Spring Security (`GrantedAuthority`, `UserDetails`) e define ali as flags de conta.
- **Consequência prática:** acopla domínio e persistência a um framework; mudanças de autenticação contaminam a entidade e tornam testes/modelagem menos isolados.
- **Solução recomendada:** avaliar um adaptador `UserDetails` separado que envolva `Usuario`, especialmente se regras de conta/identidade crescerem. Para um MVP, registrar como melhoria de baixo risco, não como bloqueio funcional.

### ARQ-03 — `@Data` em entidades com relacionamentos bidirecionais

- **Prioridade:** MÉDIA
- **Situação:** comprovado; impactos dependem do uso de `equals`, `hashCode` e `toString` gerados.
- **Arquivo/classe:** `model/Usuario.java`, `model/Quadra.java`, `model/Agendamento.java`.
- **Descrição técnica:** Lombok `@Data` gera `equals`, `hashCode` e `toString` abrangendo campos, inclusive coleções/relacionamentos. `Usuario` e `Quadra` referenciam listas de agendamentos, e `Agendamento` referencia de volta usuário e quadra.
- **Consequência prática:** risco de recursão, consultas LAZY incidentais, custo elevado e comportamento inadequado de igualdade em entidades gerenciadas/proxies JPA.
- **Solução recomendada:** usar getters/setters explícitos ou anotações Lombok mais restritas; definir igualdade baseada em identidade persistente com cuidado e excluir associações de `toString`/`equals`/`hashCode`.

### ARQ-04 — Fronteiras transacionais não aparecem nos serviços

- **Prioridade:** ALTA para reservas; MÉDIA como padrão arquitetural
- **Situação:** comprovado
- **Arquivo/classe:** `service/AgendamentoService.java`; também `QuadraService` e `UsuarioService`.
- **Descrição técnica:** operações compostas de leitura, validação e gravação não têm `@Transactional` nos serviços. As transações padrão dos métodos de repositório não abrangem necessariamente toda a regra de negócio.
- **Consequência prática:** a verificação e gravação de reserva ficam separadas e concorrentes; alterações em várias entidades podem não ser atômicas. Relaciona-se a DB-01/DB-02 em `04-auditoria-banco-de-dados.md`.
- **Solução recomendada:** declarar transação na fronteira do caso de uso e complementar com restrição/estratégia de concorrência no banco. `@Transactional` isoladamente não garante que duas reservas sobrepostas sejam serializadas.

### ARQ-05 — Mapeamento manual estático e DTO de reserva acoplado a papéis

- **Prioridade:** BAIXA (melhoria opcional) / MÉDIA para o contrato de reserva
- **Situação:** comprovado
- **Arquivo/classe:** `mapper/*Mapper.java`; `dto/agendamento/AgendamentoRequestDTO.java`.
- **Descrição técnica:** mapeadores manuais pequenos são simples e adequados ao volume atual. Porém, o DTO de agendamento combina `usuarioId` (necessário a operações administrativas) com criação/edição feita por cliente comum.
- **Consequência prática:** a aplicação precisa lembrar de ignorar o campo em alguns caminhos e validá-lo noutros; esse desenho contribuiu para a troca indevida do proprietário no update (SEG-01).
- **Solução recomendada:** separar comandos de cliente e de administrador ou derivar sempre o dono do principal autenticado. Não adicionar MapStruct/ModelMapper sem volume/complexidade que justifique.

## Configuração, dependências e Java

- `pom.xml` define Java 21, Spring Boot 4.0.7, Springdoc 3.0.3 e JJWT 0.12.7. O README ainda anuncia Boot 3.x, Auth0 JWT e ModelMapper/MapStruct.
- `spring-boot-starter-webmvc`, JPA, Validation, Security e drivers PostgreSQL estão declarados. As dependências de teste Spring Boot estão presentes, mas não há `spring-security-test` declarado.
- Não foi encontrada configuração em `src/main/resources` nem propriedades de datasource/JWT versionadas. `JwtService` exige `jwt.secret` e `jwt.expiration`; propriedades do datasource também devem ser fornecidas por ambiente externo.
- A classe de aplicação é mínima e corretamente posicionada no pacote raiz `br.com.arena`, permitindo o component scan esperado.
- Uso de Java 21 é compatível com `Optional`, collections e streams empregados. Os `.collect(Collectors.toList())` são funcionais; trocar por `.toList()` é apenas escolha de estilo e pode alterar mutabilidade, portanto não é recomendação prioritária.

## Recomendações arquiteturais

1. Testar regras de negócio por serviços com dependências explícitas e usuário autenticado controlável.
2. Definir transações nos casos de uso, especialmente criação/edição/cancelamento de reservas.
3. Separar modelo de autenticação do modelo JPA quando a aplicação crescer; remover geração indiscriminada de métodos de entidade.
4. Manter os mapeadores simples até existir necessidade concreta de geração automática.
5. Documentar e externalizar configuração de ambiente; adotar migrações versionadas antes de persistir dados de produção.
