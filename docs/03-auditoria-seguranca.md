# 03 — Auditoria de segurança

## Visão geral

O código usa Spring Security em modo stateless, autenticação por JWT, `DaoAuthenticationProvider` e `BCryptPasswordEncoder`. O cadastro público força perfil `CLIENTE`; as operações administrativas são protegidas por `@PreAuthorize`, e operações de agendamento individual verificam propriedade ou papel administrativo. Esses controles formam uma base positiva, mas há uma falha comprovada de autorização na edição de agendamento.

Não foi feita análise dinâmica, execução de testes de penetração, verificação de dependências ou validação de configuração real de produção. Não se afirma que houve exploração de nenhum endpoint.

## Fluxo e proteção atuais

- `POST /auth/login`: chama `AuthenticationManager`; em sucesso emite token com e-mail como `subject`.
- O filtro `JwtAuthenticationFilter` extrai token `Bearer`, valida assinatura/expiração via `JwtService`, carrega usuário atual do repositório e instala autenticação no `SecurityContext`.
- A autorização HTTP libera `/auth/**`, Swagger/OpenAPI e apenas `POST /usuarios`; os demais caminhos exigem autenticação.
- `@EnableMethodSecurity` e `@PreAuthorize` restringem leitura/listagem e mutações administrativas.
- `Usuario.getAuthorities()` converte o perfil em `ROLE_ADMIN` ou `ROLE_CLIENTE`.
- `UsuarioResponseDTO` não inclui a senha. O cadastro ignora perfil recebido e define `CLIENTE`; a edição de `/usuarios/me` também não altera perfil.

## Achados

### SEG-01 — Atualização permite transferir agendamento para outro usuário

- **Prioridade:** ALTA
- **Situação:** comprovado no código
- **Arquivo/classe:** `src/main/java/br/com/arena/service/AgendamentoService.java`, método `atualizar` (linhas 107–126); `AgendamentoRequestDTO.java` contém `usuarioId`.
- **Descrição técnica:** o método verifica que o solicitante é dono do agendamento atual (ou ADMIN), mas depois carrega `dto.getUsuarioId()` e define esse usuário como novo dono. Ao contrário de `salvar`, não substitui o ID pelo usuário autenticado para clientes comuns.
- **Consequência prática:** um cliente que pode editar sua própria reserva consegue atribuí-la a outro usuário conhecido, alterando a associação e removendo do próprio usuário o controle posterior. Isso é escrita não autorizada de propriedade, embora não permita por si só ler a reserva alheia.
- **Solução recomendada:** para cliente, manter imutável o dono obtido da reserva/principal e não aceitar `usuarioId`; permitir troca apenas em fluxo explicitamente administrativo. Criar testes de autorização que tentem trocar o proprietário e afirmem 403/400 sem persistência da mudança.

### SEG-02 — Propriedades obrigatórias do JWT não estão configuradas no repositório

- **Prioridade:** ALTA
- **Situação:** ausência comprovada no repositório; falha de inicialização depende de eventual configuração externa
- **Arquivo/classe:** `security/JwtService.java`, `@Value("${jwt.secret}")` e `@Value("${jwt.expiration}")`; não há `src/main/resources` na árvore encontrada.
- **Descrição técnica:** não há valores padrão para as duas propriedades. `Keys.hmacShaKeyFor` também exige material de chave compatível com o algoritmo escolhido.
- **Consequência prática:** sem injeção externa, a criação do bean pode falhar por placeholder ausente; chave fraca/curta pode falhar ao assinar ou validar. Sem evidência do ambiente, não é possível afirmar se o deploy atual fornece esses valores.
- **Solução recomendada:** fornecer configuração explícita por variáveis/secret manager, exigir chave criptograficamente forte, definir validade e documentar nomes/unidades sem registrar segredo em Git. Validar startup e testes com configuração de teste não produtiva.

### SEG-03 — Política de senha mínima é fraca para uma conta de acesso

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** `dto/usuario/UsuarioRequestDTO.java`, campo `senha` (`@Size(min = 6)`); aplicação em `UsuarioController`.
- **Descrição técnica:** senha de seis caracteres é aceita; não há limite superior explícito nem política documentada de bloqueio/rate-limit para tentativas de login. BCrypt é um hash adequado como base, mas não compensa senha fraca ou abuso de tentativas.
- **Consequência prática:** aumenta a probabilidade de credenciais adivinháveis e de abuso de autenticação, especialmente com cadastro/login expostos publicamente.
- **Solução recomendada:** elevar o mínimo com uma política orientada a passphrases, permitir gerenciador de senhas, aplicar limite razoável de tamanho compatível com BCrypt e implementar limitação de tentativas no perímetro/aplicação. Evitar regras arbitrárias de composição sem justificativa.

### SEG-04 — Token permanece válido após alteração de senha até expirar

- **Prioridade:** MÉDIA
- **Situação:** risco comprovado pelo desenho stateless
- **Arquivo/classe:** `security/JwtService.java`; `UsuarioService.atualizarProprioPerfil` e `atualizar`.
- **Descrição técnica:** o token contém subject/tempo, sem mecanismo de revogação ou versão de credencial. O filtro recarrega o usuário, o que atualiza papéis, mas não compara token com versão de senha/sessão.
- **Consequência prática:** um token já emitido continua autenticando após troca de senha, até a expiração. O impacto depende da validade configurada, que não está presente no repositório.
- **Solução recomendada:** definir validade curta adequada ao produto e, se revogação imediata for requisito, introduzir mecanismo deliberado (por exemplo, versão de credencial ou lista de revogação). Considerar também revogação/invalidade após desativação de conta.

### SEG-05 — Documentação Swagger e OpenAPI é liberada sem autenticação

- **Prioridade:** BAIXA em desenvolvimento; MÉDIA se publicada em produção sem necessidade
- **Situação:** comprovado; impacto condicionado ao ambiente
- **Arquivo/classe:** `security/SecurityConfig.java`, matchers de Swagger/OpenAPI com `permitAll()`.
- **Descrição técnica:** UI e documentos OpenAPI são públicos por configuração. `OpenApiConfig` também declara bearer security global, inclusive para operações que o Spring libera anonimamente.
- **Consequência prática:** em produção, visitantes podem enumerar rotas e contratos. Isso não é vulnerabilidade por si só, mas amplia descoberta da superfície e pode confundir consumidores sobre operações públicas.
- **Solução recomendada:** decidir política por ambiente; restringir documentação em produção se necessário e marcar corretamente operações públicas/seguras na especificação.

### SEG-06 — Erros produzidos antes do MVC podem não seguir o formato global

- **Prioridade:** MÉDIA
- **Situação:** risco arquitetural comprovado pela ausência de handlers de segurança dedicados
- **Arquivo/classe:** `security/SecurityConfig.java`; `security/JwtAuthenticationFilter.java`; `exception/GlobalExceptionHandler.java`.
- **Descrição técnica:** `@RestControllerAdvice` trata exceções do MVC, mas rejeições da cadeia Spring Security (não autenticado/negado no filtro) ocorrem fora do fluxo normal de controller. Não há `AuthenticationEntryPoint` ou `AccessDeniedHandler` próprios. O filtro ainda captura qualquer `Exception`, limpa o contexto e continua anonimamente.
- **Consequência prática:** 401/403 podem ter corpo diferente do `ErrorResponse`; falhas de carregamento de usuário no filtro também podem ser mascaradas como token inválido, dificultando diagnóstico.
- **Solução recomendada:** configurar entry point e denied handler consistentes; tratar separadamente token inválido de falha operacional, sem devolver detalhes internos ao cliente e sem suprimir logs diagnósticos necessários.

### SEG-07 — `toString` gerado para usuário inclui campos sensíveis

- **Prioridade:** BAIXA (risco condicionado ao uso em logs/debug)
- **Situação:** comportamento gerado comprovado; exposição efetiva não foi observada
- **Arquivo/classe:** `model/Usuario.java`, anotação Lombok `@Data`.
- **Descrição técnica:** `@Data` gera `toString()` incluindo campos de instância, entre eles `senha` (hash) e relacionamentos. Não foi encontrado uso explícito de `toString()` em log no código examinado.
- **Consequência prática:** se a entidade for incluída em log ou mensagem de diagnóstico, o hash de senha e dados relacionados podem ser gravados; relacionamentos também podem provocar carregamentos/recursão.
- **Solução recomendada:** excluir explicitamente senha e associações de `toString` ou não gerar esse método para a entidade; nunca registrar entidade/credencial integralmente.

## Autorização por endpoint e controle de propriedade

| Operação | Proteção encontrada |
|---|---|
| `POST /usuarios` | Pública; o serviço força `CLIENTE`. |
| `GET /usuarios`, `GET /usuarios/{id}`, `PUT /usuarios/{id}`, `DELETE /usuarios/{id}` | `ADMIN`. |
| `GET /usuarios/me`, `PUT /usuarios/me` | Autenticado; ID vem do principal. |
| `GET /quadras`, `GET /quadras/{id}` | Autenticado pela regra geral; sem papel específico. |
| Escritas de quadra | `ADMIN`. |
| `GET /agendamentos` | `ADMIN`. |
| `GET /agendamentos/{id}`, `PUT`, `DELETE` | Serviço valida proprietário ou `ADMIN`; update tem a falha SEG-01 ao trocar o proprietário. |
| `POST /agendamentos` | Autenticado; para cliente o usuário é derivado do principal, para ADMIN usa o ID enviado. |

Não foi encontrada evidência de que um cliente consiga listar todos os agendamentos, acessar uma reserva de outro ID ou excluir a reserva de terceiro pelos fluxos normais: `listarTodos` requer ADMIN e os métodos por ID validam propriedade. A falha comprovada é a alteração da associação do próprio agendamento descrita em SEG-01.

## Senhas, JWT, CSRF e CORS

- **Armazenamento:** senha recebida é codificada com `BCryptPasswordEncoder` e resposta não expõe hash. Bom controle básico.
- **Validação de token:** JJWT verifica assinatura de token assinado e expiração ao fazer parse; token usa e-mail como `subject`. Validade/segredo dependem de configuração externa ausente do repositório.
- **Papéis:** authorities são carregadas do usuário do banco em cada requisição autenticada; isso evita depender de role antiga embutida no token. O perfil deve permanecer não nulo — o DTO de atualização administrativa não valida `perfil` como obrigatório.
- **CSRF:** desabilitado. É uma decisão coerente se autenticação usar exclusivamente `Authorization: Bearer` e o browser não enviar credenciais automaticamente por cookie. Reavaliar se houver cookie de sessão/refresh ou outra credencial ambientemente enviada.
- **CORS:** não foi encontrada configuração explícita. Isso não prova exposição cross-origin permissiva; significa que origens e headers permitidos não estão documentados/configurados no código examinado. Definir allowlist se consumidores browser em outras origens forem requisito; não usar curingas indiscriminados com credenciais.
- **HTTPS/segredos:** não se pode verificar proxy/TLS/deploy neste repositório. JWT e credenciais devem trafegar somente em HTTPS e segredos devem ficar fora do controle de versão.

## Recomendações por ordem

1. Corrigir SEG-01 e cobrir cenários de proprietário/admin com testes.
2. Fornecer configuração segura/reproduzível de JWT e banco; revisar política de segredo e expiração.
3. Adicionar limites de tentativas e fortalecer a política de senha conforme o contexto de produção.
4. Definir tratamento 401/403 uniforme e política de publicação do Swagger.
5. Documentar explicitamente modelo de credencial; manter CSRF desligado somente enquanto a autenticação continuar estritamente bearer-header.
