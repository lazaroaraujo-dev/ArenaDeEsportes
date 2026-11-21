# 07 — Roadmap de melhorias

Este roadmap prioriza risco e dependências identificados na auditoria; é uma proposta, não uma implementação. A ordem respeita segurança/integridade antes de expansão funcional. A preparação de configuração e um teste mínimo entram como pré-requisitos para que as correções possam ser verificadas de forma reproduzível.

## Ordem recomendada

| Fase | Prioridade | Melhorias | Dependências | Benefício esperado |
|---|---|---|---|---|
| 0. Base verificável | **ALTA** | Documentar/injetar datasource e propriedades JWT por ambiente; definir perfil de teste; criar teste de regressão mínimo para booking e autorização. | Nenhuma. Necessário para validar fases seguintes. | Aplicação e correções reproduzíveis sem segredos no Git. |
| 1. Segurança e integridade | **ALTA** | Corrigir SEG-01; **[x] DB-01:** validar conflito e quadra ativa também no update; **[ ] DB-02:** definir transação e estratégia de concorrência com constraint/lock; tratar conflitos como 409; revisar política de senha e credenciais. | Fase 0; estratégia depende do schema PostgreSQL. | Evita alterações de proprietário não autorizadas, overbooking e gravações inválidas. |
| 2. Arquitetura e regras de negócio | **ALTA/MÉDIA** | Centralizar regras compartilhadas de reserva; tornar identidade atual explícita; definir estados/transições e semântica de cancelamento; validar `perfil` e demais campos obrigatórios. | Fase 1 e testes de regressão. | Regras consistentes em todos os caminhos e mais fáceis de testar. |
| 3. Contratos da API | **MÉDIA** | Alinhar README/OpenAPI; decidir listagem de quadras ativas; padronizar 400/401/403/404/409; paginação/ordenação; separar comandos de cliente/admin; configurar documentação pública por ambiente. | Regras e autorização da fase 1/2. | Contratos previsíveis e integração mais simples para consumidores. |
| 4. Testes e confiabilidade | **ALTA antes de produção** | Cobrir serviços, controllers, persistência PostgreSQL e concorrência; automatizar testes em CI; monitorar regressões e falhas. | Base fase 0; executar junto às fases 1–3, não somente ao final. | Evidência de que regras críticas continuam corretas. |
| 5. Desempenho e operação | **MÉDIA** | Medir consultas; índices seletivos; projeções/fetch planejado; limites de paginação; logs estruturados, health checks e métricas conforme ambiente de implantação. | Contratos de coleção e volume conhecido. | Menos consultas redundantes, resposta previsível e diagnóstico operacional. |
| 6. Evolução do produto | **MÉDIA/BAIXA conforme escopo** | Consulta de reservas próprias e disponibilidade; fluxo de confirmação/cancelamento; horários de funcionamento; notificações; preços/pagamento apenas se fizerem parte do modelo comercial. | Fases 1–4: integridade, estados e testes. | Experiência completa de reserva sem introduzir regras antes de garantir consistência. |

## Cinco melhorias de maior prioridade

1. **Impedir troca de proprietário em `PUT /agendamentos/{id}`**: para cliente, conservar o dono do registro/principal; atribuição a terceiro somente por operação administrativa explícita.
2. **Aplicar as regras de reserva em criação e atualização**: **[x] DB-01** — no update, validar sobreposição ignorando o próprio ID e exigir quadra ativa; **[ ] DB-02** — proteger a garantia contra concorrência. A filtragem de estados continua pendente conforme DB-03.
3. **Garantir exclusividade do horário sob concorrência**: desenhar transação e mecanismo PostgreSQL de serialização/integridade; uma consulta seguida de insert não é garantia suficiente.
4. **Tornar execução e segredo JWT reproduzíveis e seguros**: fornecer configuração externa documentada e validar presença/força; verificar também o datasource requerido para subir a aplicação.
5. **Adicionar testes de regressão de segurança e booking**: testar proprietário/admin, colisões em criação/update e concorrência antes de ampliar funcionalidades.

## Dependências e decisões a tomar

- **Propriedade do agendamento:** determinar se ADMIN pode reatribuir reservas; não misturar essa permissão com edição de cliente.
- **Conflitos:** definir se `PENDENTE` bloqueia horário, se `CANCELADO` libera e quais transições são permitidas.
- **Concorrência:** escolher implementação compatível com PostgreSQL e com o modelo de datas/horas; validar comportamento real em teste concorrente.
- **Desativação de quadra:** decidir se reservas existentes permanecem válidas, se desativação bloqueia apenas novas reservas e como a lista pública representa a quadra.
- **Histórico:** decidir retenção, cancelamento e auditoria antes de optar por exclusão física/soft delete.
- **Clientes browser:** definir origens CORS e armazenamento do token; manter CSRF desabilitado somente se credencial continuar não ambientemente enviada.
- **Operação:** indicar ambiente-alvo antes de escolher Docker, métricas, limites ou mecanismo de rate limiting; não adotar essas peças apenas por popularidade.

## Funcionalidades consideradas, mas não assumidas como obrigatórias

- **Listar reservas próprias e disponibilidade:** lacunas claras para experiência de cliente, recomendadas se o objetivo for gerenciamento real de reservas.
- **Confirmação/notificação por e-mail:** está no roadmap do README como pendente; requer decisão sobre estados, entrega e falhas antes da implementação.
- **Horário de funcionamento, duração mínima/máxima, preço, pagamento e múltiplas arenas:** não foram encontrados no código nem especificados como requisito fechado. São possibilidades de produto, não problemas comprovados; priorizar somente após definição operacional.
- **Docker/Compose:** README lista como pendente. Ajuda onboarding/reprodutibilidade, mas não corrige as falhas de autorização ou integridade e depende da estratégia de configuração.
- **Migrações versionadas:** recomendadas antes de ambiente persistente/produção; evitar `ddl-auto=update` como processo de evolução de schema.
