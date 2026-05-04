# Infraestrutura: Cloud, Containers e Orquestração

## Visão Geral

A infraestrutura do OrderFlow é desenhada para satisfazer simultaneamente quatro objetivos: escalabilidade horizontal nativa, portabilidade entre ambientes (dev local idêntico a produção), resiliência através de redundância em múltiplas zonas, e custo operacional racional pago apenas pelo que usa.

A escolha por containers e Kubernetes não é gratuita — traz complexidade real. É justificada pelos benefícios concretos: deploys rolling com zero downtime, autoscaling reativo a métricas de negócio, isolamento de processos em uma mesma máquina, e ecosistema rico de ferramentas operacionais. Para um sistema com cinco serviços comunicando-se através de mensageria assíncrona, é o ferramental adequado.

## Provedor de Cloud: AWS

A AWS foi escolhida pelo ecossistema maduro de serviços gerenciados que mitigam a complexidade operacional. Cada componente da infraestrutura tem um equivalente gerenciado, reduzindo trabalho de manutenção:

- **EKS** (Elastic Kubernetes Service) provê o control plane do Kubernetes gerenciado. AWS atualiza, aplica patches, e mantém alta disponibilidade do master, deixando a equipe focada nos workloads.
- **RDS for PostgreSQL** opera o event store e bancos transacionais com backups automatizados, multi-AZ failover, e read replicas sob demanda.
- **DocumentDB** (compatível com MongoDB) mantém os modelos de leitura, com escalabilidade horizontal via sharding gerenciado.
- **ElastiCache for Redis** provê cache e locks distribuídos em modo cluster.
- **MSK** (Managed Streaming for Kafka) atua como barramento de eventos sem operação manual de Zookeeper, broker tuning, ou manutenção de versões.
- **S3** armazena backups, logs de longo prazo e snapshots de event store.
- **CloudWatch + X-Ray** complementam observabilidade nativa AWS, integrados com Prometheus/Jaeger.
- **Secrets Manager + KMS** suportam o Vault para certas necessidades de criptografia gerenciada.

A arquitetura permanece relativamente portável apesar da escolha AWS. A maior parte do código é cloud-agnostic; pontos de aderência são abstraídos atrás de interfaces. Em teoria, migração para GCP ou Azure exigiria reescrever os módulos Terraform e adaptar alguns clientes específicos — não reescrever a aplicação.

## Containers

Cada microsserviço é empacotado como uma imagem Docker. As imagens seguem práticas que minimizam tamanho e superfície de ataque:

- Base images distroless do Google quando possível, ou Alpine quando distroless não cobre o caso. Imagens base sem shell, sem package manager, sem ferramentas extras.
- Multi-stage builds: estágio de build com JDK completo, Maven, dependências de teste; estágio final apenas com JRE 21 e o JAR da aplicação.
- Layered JARs do Spring Boot 3 separam dependências (raramente mudam) do código de aplicação (muda frequentemente). Cache de layers Docker acelera builds e reduz pull time de imagens em deploys.
- Containers rodam como UID não-root com filesystem read-only. Volumes nomeados expõem apenas os diretórios graváveis necessários.
- Imagens são assinadas via cosign, com verificação no admission controller do Kubernetes — apenas imagens assinadas pela equipe são deployadas.

Tamanhos resultantes são da ordem de 80-150MB por imagem, comparados aos 500MB+ comuns em imagens não otimizadas. Isso impacta tempo de pull em scaling events, bandwidth no registry, e tempo de cold start em geral.

## Kubernetes

O cluster EKS roda em três Availability Zones para alta disponibilidade. Node groups são configurados em grupos: um grupo on-demand para workloads críticos garantindo capacidade, e grupos spot instances para batch e tarefas tolerantes a interrupção, reduzindo custo significativamente.

Cada serviço tem manifestos Kubernetes próprios incluindo:

- **Deployment** com strategy `RollingUpdate`, `maxSurge: 25%` e `maxUnavailable: 0` para zero downtime durante deploys.
- **HorizontalPodAutoscaler** baseado em CPU, memória e métricas customizadas (consumer lag do Kafka, latência p99). Para serviços orientados a eventos, escalar por consumer lag é essencial — CPU é tardio demais.
- **PodDisruptionBudget** garantindo número mínimo de pods saudáveis durante operações voluntárias (drain de node, upgrade).
- **NetworkPolicy** restringindo comunicação ao mínimo necessário (default deny, permit por exceção).
- **ServiceMonitor** expondo métricas para o Prometheus operator descobrir automaticamente.
- **Liveness, readiness e startup probes** específicos por serviço. Startup probe é crucial para apps Spring Boot que demoram a iniciar — sem ele, a liveness probe mata o pod antes de inicializar.
- **Resource requests e limits** definidos com base em medições reais de produção, não palpites. Subestimar produz instabilidade; superestimar desperdiça recursos.

Recursos de plataforma como Istio (service mesh), cert-manager, external-dns, ArgoCD são instalados como add-ons, com versões pinned e atualizações controladas.

## GitOps com ArgoCD

A configuração de cada ambiente vive em um repositório Git separado do código de aplicação (separation of concerns). ArgoCD observa este repositório e aplica continuamente as mudanças ao cluster.

O fluxo completo de deploy é: desenvolvedor faz merge para `main` no repositório de aplicação; CI builda, testa e publica a imagem com tag única (commit SHA); CI atualiza a imagem tag no repositório GitOps; ArgoCD detecta a mudança e inicia o rollout.

Argo Rollouts substitui o Deployment padrão para suportar canary deployment progressivo: 5% do tráfego para a nova versão, análise automatizada de métricas (taxa de erros, latência p99) por N minutos, então 25%, então 50%, então 100%. Em qualquer ponto, se a análise detectar regressão, rollback é automático.

A vantagem desta abordagem é dupla: declarativa (o estado desejado está em Git, fonte única de verdade), auditável (toda mudança no ambiente tem commit Git associado), e reversível (rollback é um revert no Git).

## Infraestrutura como Código

Toda infraestrutura AWS é declarada em Terraform. O código está em módulos reutilizáveis: módulo de VPC, módulo de EKS, módulo de RDS, etc. Ambientes (dev, staging, prod) são instâncias destes módulos com parâmetros distintos.

State do Terraform é armazenado remotamente no S3 com locking via DynamoDB. Mudanças seguem fluxo de PR — `terraform plan` é executado em PR, comentado para revisão, e `terraform apply` apenas após aprovação e merge.

Mudanças em infra rodam pelo mesmo pipeline de qualidade que código de aplicação: lint (tflint), format (terraform fmt), validação (terraform validate), análise de segurança (Checkov, tfsec). Configurações inseguras (security group aberto, S3 público) falham o build.

## Estratégia de Ambientes

Três ambientes principais existem: development, staging, production. Cada um isolado em uma conta AWS separada (limita blast radius e simplifica billing).

Development é compartilhado pela equipe, com dados sintéticos. Staging é uma réplica fiel de produção em escala reduzida, usado para testes finais de integração, load tests, e chaos. Produção é o ambiente cliente-facing, com isolamento estrito e change management formal.

Adicionalmente, ambientes efêmeros são criados sob demanda para cada pull request: um namespace Kubernetes em um cluster compartilhado, com infra leve (sem RDS gerenciado — usa PostgreSQL em pod, idem para MongoDB e Kafka). Estes ambientes existem pelo tempo de vida da PR e são destruídos no merge ou close. Permitem testar mudanças num ambiente real sem competir pelo staging.

## Observabilidade

A stack de observabilidade é composta por componentes open-source rodando no próprio cluster:

- **Prometheus** coleta métricas via service discovery automático. Retenção curta no cluster (15 dias), longa via Thanos no S3.
- **Grafana** visualiza métricas e logs. Dashboards são versionados em Git e provisionados via Grafana Operator.
- **Loki** agrega logs estruturados de todos os pods. Queries por correlationId tornam investigação trivial.
- **Jaeger** armazena e visualiza traces distribuídos. OpenTelemetry instrumenta os serviços, exportando para Jaeger.
- **Alertmanager** roteia alertas para canais apropriados — PagerDuty para críticos, Slack para informativos.

SLOs definidos por serviço alimentam dashboards de erro budget. Quando o budget está esgotando, deploys são desacelerados ou pausados — preserva-se confiabilidade ao custo de velocidade temporariamente.

## Custos e FinOps

Custo de infraestrutura é monitorado e otimizado continuamente. Tags em todos os recursos AWS permitem alocação por serviço e ambiente. Dashboards de custo identificam tendências e anomalias.

Práticas de otimização incluem: instâncias spot para workloads tolerantes; auto-scaling agressivo em baixo tráfego (scale to zero quando possível para ambientes não-produtivos); reserved instances para baseline previsível; right-sizing baseado em métricas reais; lifecycle policies em S3 para mover dados antigos para tiers mais baratos.

Budget alerts disparam em desvios significativos, permitindo investigação antes que o custo escape. Custos são apresentados à equipe regularmente — engenheiros tomando decisões cientes do custo tendem a optar por arquiteturas eficientes.

## Disaster Recovery

DR plan documenta RTO (Recovery Time Objective) e RPO (Recovery Point Objective) para cada componente. Eventos de pagamento têm RPO próximo de zero (multi-AZ replication síncrona); modelos de leitura têm RPO de minutos (podem ser reconstruídos do event store).

Backups são automatizados: RDS snapshots diários com retenção de 35 dias, plus snapshots semanais arquivados por 1 ano. DocumentDB segue padrão similar. S3 tem versioning habilitado. O event store, sendo a fonte de verdade canônica, tem replicação cross-region para o caso extremo de perda total da região primária.

DR drills são executadas semestralmente — simulações de perda de uma AZ, depois de uma região completa. Verificam que a recuperação acontece dentro do RTO documentado e ajustam o processo conforme necessário.
