# 05 — Auditoria da API REST

## Endpoints encontrados

As proteções abaixo combinam regras HTTP em `SecurityConfig`, anotações de controller e validações de propriedade no serviço.

| Método | Caminho | Acesso encontrado | Comportamento |
|---|---|---|---|
| `POST` | `/auth/login` | Público | Autentica e devolve JWT (200). |
| `POST` | `/usuarios` | Público | Cadastro (201); perfil é forçado para `CLIENTE`. |
| `GET` | `/usuarios` | ADMIN | Lista usuários sem paginação. |
| `GET` | `/usuarios/me` | Autenticado | Consulta perfil do principal. |
| `PUT` | `/usuarios/me` | Autenticado | Atualiza dados do principal, sem alterar perfil. |
| `GET` | `/usuarios/{id}` | ADMIN | Consulta usuário. |
| `PUT` | `/usuarios/{id}` | ADMIN | Atualiza dados e perfil. |
| `DELETE` | `/usuarios/{id}` | ADMIN | Exclui se não possuir agendamentos (204). |
| `GET` | `/quadras` | Autenticado | Lista todas as quadras, inclusive inativas. |
| `GET` | `/quadras/{id}` | Autenticado | Consulta quadra por ID. |
| `POST` | `/quadras` | ADMIN | Cria quadra (201). |
| `PUT` | `/quadras/{id}` | ADMIN | Substitui dados da quadra (200). |
| `PATCH` | `/quadras/{id}/status?ativa={boolean}` | ADMIN | Altera estado ativo (200). |
| `DELETE` | `/quadras/{id}` | ADMIN | Exclui se não possuir agendamentos (204). |
| `GET` | `/agendamentos` | ADMIN | Lista todos sem paginação. |
| `GET` | `/agendamentos/{id}` | Dono ou ADMIN | Consulta individual. |
| `POST` | `/agendamentos` | Autenticado | Cria reserva (201); cliente é derivado do principal. |
| `PUT` | `/agendamentos/{id}` | Dono ou ADMIN | Atualiza reserva; apresenta os problemas DB-01 e SEG-01. |
| `DELETE` | `/agendamentos/{id}` | Dono ou ADMIN | Remove fisicamente (204). |

Não existe `POST /auth/register`; o registro está em `/usuarios`. Não foi encontrado endpoint para consultar a coleção de agendamentos do usuário autenticado, disponibilidade por quadra/data, transicionar para cancelado ou confirmar reserva.

## Achados

### API-01 — Lista de quadras contradiz o contrato documentado

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** `controller/QuadraController.listarTodas`; `service/QuadraService.listarTodas`; README, endpoint `/quadras`.
- **Descrição técnica:** a implementação usa `quadraRepository.findAll()` e não filtra `ativa`, apesar de o README descrever “lista todas as quadras ativas”.
- **Consequência prática:** clientes recebem quadras desativadas como opções e podem interpretar incorretamente sua disponibilidade. A criação já rejeita quadra inativa, mas isso não corrige o contrato de leitura.
- **Solução recomendada:** definir se endpoint público representa catálogo ativo ou inventário administrativo; filtrar/parametrizar e refletir o contrato no OpenAPI/README.

### API-02 — Contrato de reserva mistura dados do cliente e do administrador

- **Prioridade:** ALTA por contribuir para SEG-01; correção descrita na auditoria de segurança
- **Situação:** comprovado
- **Arquivo/classe:** `dto/agendamento/AgendamentoRequestDTO.java`; `service/AgendamentoService.salvar` e `atualizar`.
- **Descrição técnica:** `usuarioId` é obrigatório para todos, embora criação por cliente ignore o valor e derive o usuário do token; edição utiliza o valor recebido sem restringi-lo ao administrador.
- **Consequência prática:** contrato exige informação inútil para cliente e expõe possibilidade de alterar proprietário. Também dificulta entender quem é autoritativo para cada campo.
- **Solução recomendada:** DTO de cliente sem `usuarioId`; derivar proprietário do principal; criar operação administrativa separada se atribuição a outro usuário for requisito.

### API-03 — Não há consulta de coleção própria nem disponibilidade

- **Prioridade:** MÉDIA
- **Situação:** funcionalidade ausente
- **Arquivo/classe:** `AgendamentoController` e `AgendamentoService`.
- **Descrição técnica:** `GET /agendamentos` é exclusivamente ADMIN. Não há rota `/agendamentos/me` ou consulta filtrada por usuário, nem rota para visualizar horários disponíveis antes de criar.
- **Consequência prática:** o consumidor cliente não consegue descobrir suas reservas por uma operação de coleção e precisa tentar reservar para descobrir conflito.
- **Solução recomendada:** considerar endpoints próprios com identidade derivada do token e consulta de disponibilidade com filtros/data; paginar resultados e respeitar status que bloqueiam horário.

### API-04 — Cancelamento anunciado é exclusão física, não transição de estado

- **Prioridade:** MÉDIA
- **Situação:** comprovado; fluxo de cancelamento por status ausente
- **Arquivo/classe:** `AgendamentoController.deletar`, `AgendamentoService.deletar`, `StatusAgendamento`.
- **Descrição técnica:** `DELETE` apaga a linha; `CANCELADO` existe como enum, mas não é definido em operação de API. Isso difere do conceito de cancelamento de reserva e apaga trilha histórica.
- **Consequência prática:** não há histórico do cancelamento e políticas de prazo, motivo ou notificação não podem ser aplicadas.
- **Solução recomendada:** decidir semântica de DELETE e, se o domínio exigir histórico, oferecer ação explícita de cancelar com transição de estado e regra de autorização.

### API-05 — Respostas de negócio e segurança não têm contrato uniforme completo

- **Prioridade:** MÉDIA
- **Situação:** comprovado em parte; resposta de filtro depende do Spring Security
- **Arquivo/classe:** `exception/GlobalExceptionHandler.java`, `ErrorResponse.java`, `SecurityConfig.java`.
- **Descrição técnica:** `BusinessException` sempre vira 400, inclusive conflito de horário; só a primeira mensagem de validação é devolvida. O advice não contém handlers específicos para erros da cadeia de segurança, cuja resposta pode ser diferente.
- **Consequência prática:** cliente precisa inferir tipos de erro por texto; conflito semântico não é distinguido de entrada inválida; corpo de 401/403 pode variar.
- **Solução recomendada:** mapear conflito para 409, validação para 400 e não autenticado/negado para 401/403 com esquema estável, códigos de erro e mensagens adequadas. Preservar detalhe técnico somente nos logs.

### API-06 — Listagens sem paginação/ordenação e sem limites

- **Prioridade:** MÉDIA
- **Situação:** comprovado
- **Arquivo/classe:** controllers/services de usuários, quadras e agendamentos; repositórios Spring Data.
- **Descrição técnica:** operações de coleção usam `findAll()` e retornam listas completas, sem `Pageable`, ordenação ou filtros documentados.
- **Consequência prática:** volume de resposta e trabalho de banco crescem com os dados; consumidores não podem navegar incrementalmente.
- **Solução recomendada:** adicionar paginação e ordenação explícitas onde o conjunto puder crescer, com limites máximos e filtros compatíveis com os casos de uso.

### API-07 — Especificação OpenAPI pode declarar autenticação global incorretamente

- **Prioridade:** BAIXA
- **Situação:** comprovado
- **Arquivo/classe:** `config/OpenApiConfig.java`; rotas públicas em `SecurityConfig`.
- **Descrição técnica:** `addSecurityItem` aplica requisito bearer no documento como padrão geral, embora login e cadastro sejam públicos; não foram encontrados ajustes por operação.
- **Consequência prática:** Swagger pode sugerir token obrigatório em chamadas públicas ou contrato divergente da política real.
- **Solução recomendada:** configurar esquema global apenas para endpoints protegidos e remover o requisito das operações públicas; manter especificação alinhada às rotas reais.

## DTOs e validação

- `UsuarioRequestDTO`: nome obrigatório com tamanho 3–100, e-mail validado, telefone por expressão regular e senha obrigatória com mínimo de seis caracteres. `perfil` não tem `@NotNull`; atualização administrativa pode tentar persistir nulo.
- `QuadraRequestDTO`: nome tem `@NotBlank`; `tipo` não tem `@NotNull`; `ativa` primitivo assume `false` se omitido no JSON. Confirmar se criação inativa por omissão é intencional.
- `AgendamentoRequestDTO`: campos de data/horário/IDs têm `@NotNull`; regras de ordem/futuro ficam no serviço. Não há restrições de duração, janela de funcionamento ou valor positivo de IDs.
- `LoginRequestDTO`: e-mail e senha não vazios, e-mail validado.
- `UsuarioResponseDTO` não expõe senha; outros DTOs também projetam nomes em vez de serializar relações JPA.
- A validação de DTO funciona quando controller usa `@Valid`, como nos endpoints de escrita examinados. Validação de domínio continua necessária para conflitos e autorização.

## Códigos HTTP, Swagger e convenções

Criações retornam 201 e exclusões 204; leituras e atualizações usam o padrão 200. Login devolve 200 com token. Não há paginação, versionamento de rota ou envelope uniforme de sucesso — nenhum desses itens é automaticamente obrigatório, mas devem ser decisões documentadas. OpenAPI tem configuração, título, versão, contato e esquema bearer; endpoints públicos e protegidos precisam de anotação precisa. A configuração permite documentação anonimamente.

## Divergências documentais adicionais

- README publica `POST /auth/register`, enquanto o caminho real de cadastro é `POST /usuarios`.
- README promete apenas quadras ativas, mas o serviço não filtra.
- README cita ModelMapper/MapStruct, mas mapeamento é manual.
- README descreve prevenção de conflito sem ressalvar atualização/concorrência.
- Swagger consta como “em andamento”, embora dependência e bean estejam presentes; não foi verificado se a aplicação inicia/serve a UI.
