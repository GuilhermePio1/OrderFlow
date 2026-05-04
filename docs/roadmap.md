# Roadmap e Plano de Execução

## Estrutura do Plano

O projeto é dividido em quatro fases incrementais, cada uma entregando valor de negócio independentemente. A divisão respeita o princípio de evolução contínua: cada fase produz um sistema funcional em produção, ainda que com escopo limitado, ao invés de exigir um "big bang" final.

## Fase 1 — MVP do Core (Semanas 1 a 8)

O objetivo da primeira fase é estabelecer a fundação arquitetural e o caminho crítico de pedido de ponta a ponta. Ao final, um cliente pode realizar um pedido, ter o pagamento autorizado, ter o estoque reservado, e receber confirmação por email. A telemetria está em pé. CI/CD está em pé. Segurança está em pé.

Entregáveis técnicos da Fase 1: setup do monorepo com módulos esqueleto para os cinco serviços; infraestrutura local via Docker Compose funcionando completamente; pipeline CI executando lint, testes unitários e build; cluster Kubernetes em ambiente de desenvolvimento provisionado via Terraform; Order Service com agregado event-sourced suportando criação, consulta e cancelamento; Payment Service integrado a um gateway sandbox (Stripe test mode) com autorização e estorno; Inventory Service com reserva e liberação de estoque baseada em locks Redis; Notification Service enviando emails via SES; Query Service com projeção básica de pedidos; observabilidade end-to-end (métricas, logs, traces); autenticação OAuth2 funcionando via Keycloak.

Marcos de validação: criação de pedido via API gateway resulta em estado consistente em todos os serviços dentro de 200ms p99; testes de integração Testcontainers passam para todos os serviços; load test demonstra capacidade de 1.000 pedidos/minuto com latência aceitável.

## Fase 2 — Hardening e Escala (Semanas 9 a 14)

A segunda fase fortalece a fundação para suportar carga de produção real. O foco desloca-se de "funciona" para "funciona sob estresse e falha".

Entregáveis técnicos: testes de caos automatizados executando regularmente em staging; SLOs definidos e dashboards de erro budget; auto-scaling configurado com base em consumer lag e latência; deploy canário automatizado via Argo Rollouts com rollback automático; chaos engineering validado para falhas de pod, rede e dependências; testes de carga noturnos com gates no CI; análise de segurança completa (SAST, DAST, SCA) integrada ao pipeline; mTLS via Istio configurado entre todos os serviços; HashiCorp Vault gerenciando todos os segredos; auditoria completa de operações administrativas.

Marcos de validação: load test sustenta 10.000 pedidos/minuto por uma hora sem degradação; chaos drill mata 30% dos pods e o sistema mantém SLO; pen test externo identifica zero vulnerabilidades críticas ou altas; tempo de deploy completo (commit a produção) abaixo de 30 minutos.

## Fase 3 — Diferenciação de Negócio (Semanas 15 a 24)

Com a fundação sólida, a terceira fase entrega capacidades que diferenciam a plataforma. As funcionalidades aqui são tipicamente o que justifica o investimento na arquitetura sofisticada — análises avançadas, ML, customizações.

Entregáveis técnicos: dashboard administrativo (frontend separado, consumindo Query Service); analytics em tempo real via Kafka Streams (taxa de conversão, ticket médio, produtos mais vendidos); detecção de fraude no Payment Service via modelo ML servido por SageMaker, com decisão sub-segundo; sistema de descontos e cupons no contexto Ordering, com regras configuráveis; suporte a múltiplas moedas e regiões fiscais; recomendações personalizadas no Query Service (clientes que compraram X também compraram Y); webhooks para integrações de terceiros.

Marcos de validação: detecção de fraude reduz chargeback em comparação com baseline; analytics em tempo real disponíveis em <5 segundos do evento original; novas features são deployadas sem afetar SLO existente.

## Fase 4 — Multi-região e Maturidade (Semanas 25 a 36)

A quarta fase prepara a plataforma para presença global e ultra-disponibilidade. É a fase mais ambiciosa e arrisca-se justifica apenas se o crescimento de negócio a demandar.

Entregáveis técnicos: replicação ativo-ativo do event store entre regiões via Kafka MirrorMaker; estratégia de resolução de conflitos baseada em CRDTs onde aplicável (counters de estoque, principalmente); roteamento geográfico via AWS Global Accelerator + Route53 latency-based routing; failover regional automatizado, validado em DR drill; suporte a multi-tenancy hard (clientes enterprise com isolamento físico); compliance avançado (SOC 2 Type II, ISO 27001).

Marcos de validação: failover de região completo dentro de RTO de 5 minutos; chaos drill regional bem-sucedido; auditoria SOC 2 sem findings significativos.

## Equipe e Skills

A composição ideal da equipe para o projeto inclui:

- **2 Senior Backend Engineers** com 5+ anos de Java, experiência prévia com microsserviços, Spring Boot, e idealmente alguma exposição a DDD/event sourcing.
- **1 Tech Lead / Staff Engineer** com experiência arquitetural ampla, capaz de tomar decisões transversais e mentorar a equipe nos paradigmas avançados.
- **1 DevOps / SRE Engineer** com profundidade em Kubernetes, Terraform, AWS e observabilidade.
- **1 Frontend Engineer** (a partir da Fase 3) para o dashboard administrativo.
- **1 Product Manager** definindo prioridades de negócio e mantendo backlog.
- **1 QA Engineer** focado em automação de testes E2E e de regressão.

Skills que precisam ser desenvolvidas durante o projeto pela equipe: programação reativa com Project Reactor (curva de aprendizado real); event sourcing e suas implicações operacionais; observabilidade distribuída (interpretar traces, definir SLOs sãos). Mentoring interno e dedicação de tempo para aprendizado nas primeiras semanas é crítico.

## Riscos e Mitigações

Cada risco identificado tem mitigação documentada no projeto.

**Risco: complexidade operacional do event sourcing supera benefícios para a equipe.** Mitigação: aplicar event sourcing apenas no contexto Ordering, não em todos. Investir em ferramental de visualização de event store (Event Store UI customizado). Documentação detalhada e workshops internos.

**Risco: tempo de aprendizado de programação reativa atrasa entregas.** Mitigação: usar reactive apenas onde os benefícios são claros (Order, Query). Demais serviços usam Spring MVC com virtual threads, mais familiar. Treinamento concentrado nas primeiras semanas; pair programming nas primeiras features reativas.

**Risco: custo de cloud excede orçamento.** Mitigação: budget alerts com threshold em 80% do orçamento mensal; revisão semanal de custos por serviço; ambientes não-produtivos com auto-shutdown fora do horário comercial; uso intensivo de spot instances onde aplicável.

**Risco: dependências externas (gateway de pagamento) instáveis.** Mitigação: circuit breakers, fallback para gateway secundário quando aplicável, modo degradado documentado; monitoring específico por gateway com alertas independentes.

**Risco: incidente de segurança em produção.** Mitigação: defesa em profundidade documentada; pen tests trimestrais; treinamento de segurança da equipe; runbooks de resposta a incidentes exercitados em drills.

## Métricas de Sucesso

O projeto define métricas claras para validar sucesso, alinhadas aos pilares do desafio:

**Pilar arquitetural** — disponibilidade do sistema acima de 99.9% mensal; capacidade de processamento de 10.000+ pedidos/minuto; tempo médio de deploy abaixo de 30 minutos; recuperação de incidente abaixo de 15 minutos para 95% dos casos.

**Pilar de complexidade técnica** — todos os contextos delimitados modelados explicitamente; event sourcing aplicado e operacional no contexto crítico; CQRS funcionando com latência de projeção <100ms p99; serviços reativos demonstrando 3x throughput em comparação com bloqueante equivalente.

**Pilar de qualidade** — cobertura de testes acima de 85% em domínio; mutation score acima de 80% em domínio; zero vulnerabilidades críticas em produção; tempo de execução do CI abaixo de 30 minutos.

## Continuidade Pós-Lançamento

Após a entrega da Fase 4, o projeto entra em modo de operação contínua com investimento dedicado a:

- **Manutenção evolutiva**: ajustes de SLO conforme padrões de uso evoluem; otimizações de custo identificadas via FinOps; refatoração contínua de áreas com débito técnico identificado.
- **Capacidade**: planejamento trimestral de capacidade baseado em projeções de crescimento; load tests regulares contra limites projetados.
- **Inovação**: 20% do tempo de engenharia dedicado a iniciativas de melhoria interna (developer experience, ferramental, automação de operações repetitivas).
- **Compliance contínuo**: auditorias renovadas anualmente; atualização de práticas conforme regulamentações evoluem.
