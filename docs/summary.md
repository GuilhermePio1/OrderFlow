# OrderFlow — Plataforma Distribuída de Gestão de Pedidos

## Visão Geral

OrderFlow é uma plataforma de gestão de pedidos para e-commerce projetada para demonstrar a aplicação prática dos princípios mais avançados de engenharia de software corporativa: arquitetura de microsserviços nativa em nuvem, Domain-Driven Design, Event Sourcing com CQRS, programação reativa, e práticas rigorosas de qualidade e segurança.

A plataforma processa o ciclo de vida completo de um pedido — desde a confirmação do carrinho, autorização de pagamento, reserva de estoque, despacho do envio, até as notificações pós-venda. Foi dimensionada para suportar mais de 10 mil pedidos por minuto em pico, mantendo consistência forte onde importa (pagamentos, estoque) e consistência eventual onde escala (modelos de leitura, analytics).

## Contexto de Negócio

O cenário escolhido — gestão de pedidos — não é arbitrário. É um domínio que naturalmente exige tudo que o desafio técnico pede: múltiplos contextos delimitados com regras distintas, transações distribuídas (saga), auditoria regulatória (event sourcing é um ajuste perfeito), picos de tráfego (programação reativa), e dados sensíveis (segurança rigorosa). Cada decisão arquitetural neste projeto é justificada por uma necessidade de negócio concreta, não por modismo técnico.

## Objetivos do Projeto

O projeto foi construído para evidenciar, simultaneamente, os três pilares do desafio:

Arquitetar e construir sistemas escaláveis e robustos é demonstrado através de uma arquitetura de microsserviços comunicando-se de forma assíncrona via Apache Kafka, empacotados em containers Docker, orquestrados por Kubernetes na AWS, com infraestrutura provisionada como código via Terraform.

Resolver problemas complexos é evidenciado pela aplicação de Domain-Driven Design para modelar os contextos delimitados, Event Sourcing para o agregado de pedidos, CQRS para separar fluxos de escrita e leitura, e programação reativa não-bloqueante com Project Reactor para serviços de alta concorrência.

Garantir qualidade é mantida através de uma pirâmide de testes rigorosa (unitários, de arquitetura, de integração com Testcontainers, de contrato, end-to-end, de carga e de caos), gates de cobertura no CI/CD, varreduras de segurança automatizadas (SAST, DAST, SCA), e práticas como mTLS entre serviços e gestão centralizada de segredos.

## Arquitetura em Alto Nível

A plataforma é composta por cinco microsserviços principais, cada um mapeado a um contexto delimitado do DDD, comunicando-se predominantemente de forma assíncrona através de um barramento de eventos Kafka. Um API Gateway unifica o acesso externo, aplicando autenticação OAuth2 e roteamento. Cada serviço possui sua própria persistência — uma estratégia deliberada de polyglot persistence que escolhe a tecnologia certa para cada padrão de acesso ao invés de forçar um único banco a fazer tudo.

A representação textual do fluxo é a seguinte: clientes chegam ao API Gateway, que distribui requisições para os serviços de Order, Payment, Inventory, Notification e Query. Estes serviços não se comunicam diretamente entre si — em vez disso, publicam eventos de domínio no Kafka, que outros serviços consomem de forma assíncrona. Os dados persistem em PostgreSQL (event store e dados transacionais), MongoDB (modelos de leitura denormalizados) e Redis (cache, locks distribuídos, sessões).

Decisões arquiteturais centrais incluem: comunicação assíncrona orientada a eventos como padrão (REST síncrono apenas para leituras voltadas ao cliente); padrão Saga com coreografia para transações distribuídas; padrão Outbox para garantir publicação confiável de eventos sem dual-writes; e Circuit Breakers via Resilience4j para degradação graciosa quando serviços a jusante falham.

## Stack Tecnológica

A linguagem principal é Java 21, aproveitando virtual threads, records e pattern matching modernos. O framework é Spring Boot 4.0 com Spring WebFlux para os serviços reativos e Spring Cloud para integração distribuída. A camada reativa usa Project Reactor e R2DBC para acesso não-bloqueante ao PostgreSQL. Mensageria é tratada por Apache Kafka via Spring Cloud Stream.

Para persistência, PostgreSQL 18 atua como event store e armazena dados transacionais, MongoDB 8 mantém modelos de leitura, e Redis 8 cuida de cache e locks distribuídos. A camada de containers usa Docker para empacotamento, Docker Compose para desenvolvimento local, e Kubernetes (AWS EKS) em produção. A infraestrutura é gerenciada por Terraform.

CI/CD é implementado com GitHub Actions e ArgoCD seguindo princípios GitOps. Observabilidade combina OpenTelemetry, Prometheus, Grafana, Loki e Jaeger. Segurança é construída sobre OAuth2/OIDC com Keycloak, Spring Security e HashiCorp Vault. Para testes, JUnit 5, Testcontainers, WireMock, Gatling e ArchUnit cobrem todas as camadas da pirâmide.

## Contextos Delimitados (DDD)

Cada microsserviço corresponde a um único contexto delimitado com sua própria linguagem ubíqua. A comunicação entre contextos acontece exclusivamente através de eventos de domínio no Kafka — nunca através de bancos de dados compartilhados, o que preservaria acoplamento indesejado.

O contexto de Ordering é responsável pelo ciclo de vida do pedido e regras de precificação, com agregados Order e OrderItem. O contexto de Payment cuida de autorização, captura e reembolso, com agregados Payment e Transaction. Inventory gerencia níveis de estoque e reservas através dos agregados Product e StockReservation. Notification entrega comunicações por email, SMS e push, modelando Notification e Template. Query é um contexto puro de leitura, sem escrita — apenas projeções denormalizadas otimizadas para consultas dos clientes.

O mapa de contexto detalha as relações entre contextos: Ordering atua como Customer/Supplier upstream para Payment e Inventory; Notification consome eventos de todos os contextos através de uma Anti-Corruption Layer; Query implementa um Open Host Service publicando uma API de leitura unificada.

## Event Sourcing e CQRS

O agregado Order é completamente event-sourced. Em vez de persistir o estado atual, toda mudança de estado é anexada como um evento de domínio imutável ao PostgreSQL. Isso traz benefícios concretos: trilha de auditoria completa (requisito regulatório para fluxos financeiros); consultas temporais — capacidade de reconstruir o estado do pedido em qualquer momento histórico; reconstrução de modelos de leitura sob demanda quando os requisitos de consulta mudam; e encaixe natural com o padrão saga.

O fluxo de eventos funciona assim: um comando chega ao agregado, que valida invariantes e produz um evento de domínio; o evento é persistido no event store no PostgreSQL e simultaneamente em uma tabela outbox; o Debezium captura mudanças nesta tabela via CDC e publica no Kafka; consumidores incluem o projetor de modelos de leitura (que atualiza o MongoDB) e outros serviços que reagem ao evento.

CQRS separa fisicamente o caminho de escrita (event-sourced, no PostgreSQL) do caminho de leitura (modelos denormalizados no MongoDB), permitindo otimizar cada um independentemente. O Query Service serve apenas leituras e pode ser escalado horizontalmente sem tocar no caminho transacional.

## Programação Reativa

Os serviços de Order e Query são totalmente não-bloqueantes, construídos sobre Spring WebFlux, Project Reactor e R2DBC. Isso desbloqueia maior throughput por pod — uma única instância suporta mais de 5 mil conexões concorrentes com threads limitadas; propagação de backpressure de ponta a ponta (HTTP até banco de dados e Kafka); e pipelines assíncronos componíveis sem callback hell.

Os serviços de Payment e Inventory, por outro lado, usam Spring MVC tradicional bloqueante com virtual threads do Java 21. Eles têm requisitos transacionais mais fortes onde a complexidade reativa não compensa. Esta é uma escolha deliberada e dirigida pelo contexto, não uniformidade pela uniformidade — cada serviço usa o paradigma adequado ao seu problema.

## Estratégia de Dados: SQL e NoSQL

PostgreSQL serve como event store e ledger de pagamentos, escolhido pelas suas garantias ACID, ferramental maduro, e suporte a JSONB para payloads de eventos. MongoDB armazena modelos de leitura como visões de pedidos e histórico de clientes, aproveitando esquema flexível, agregações rápidas e escalabilidade horizontal. Redis fornece cache, locks distribuídos e rate limiting com latência sub-milissegundo, operações atômicas e pub/sub.

Esta abordagem polyglot é justificada por padrões de acesso distintos. Forçar todos os dados em um único tipo de banco resultaria em compromissos onde nenhum padrão de acesso é otimizado. A complexidade operacional de manter três tecnologias é mitigada pela maturidade dos serviços gerenciados na AWS (RDS, DocumentDB-compatible, ElastiCache).

## Segurança

Autenticação é gerenciada via OAuth2/OIDC através de Keycloak, emitindo JWTs com RS256, TTLs curtos e rotação automática de chaves. Autorização é baseada em papéis e atributos, aplicada no gateway e revalidada em cada serviço a jusante — princípio de defesa em profundidade.

Comunicação entre serviços usa mTLS através do service mesh Istio. Segredos são gerenciados pelo HashiCorp Vault, jamais como variáveis de ambiente em produção. Validação de entrada combina Bean Validation com validadores customizados para regras de domínio. Acesso a SQL usa exclusivamente queries parametrizadas via R2DBC; acesso a NoSQL usa queries tipadas para evitar injeção.

A OWASP Top 10 é endereçada continuamente através do pipeline: varreduras de dependências com Trivy, análise estática com SonarQube, e análise dinâmica com OWASP ZAP. Dados pessoalmente identificáveis são criptografados em repouso com AES-256, em trânsito com TLS 1.3, e tokenizados nos logs. Todas as ações administrativas emitem eventos de auditoria para um log append-only, garantindo rastreabilidade completa.

## Estratégia de Testes

A pirâmide de testes é tratada como cidadã de primeira classe da arquitetura. Na base, testes unitários cobrem a lógica de domínio sem inicializar o contexto Spring, oferecendo feedback sub-segundo, com mutation testing via PIT para verificar a qualidade dos próprios testes. Testes de arquitetura usando ArchUnit garantem que as fronteiras hexagonais, convenções de nomenclatura e regras de dependência permaneçam intactas conforme o código evolui.

Testes de integração usam Testcontainers para PostgreSQL, MongoDB e Kafka reais — sem mocks de infraestrutura, eliminando a divergência entre ambientes. Testes de contrato com Spring Cloud Contract e Pact garantem que mudanças em APIs entre serviços sejam detectadas no produtor antes de quebrar consumidores.

No topo, testes end-to-end cobrem o caminho feliz e sagas críticas, executados contra um namespace Kubernetes efêmero a cada pull request. Testes de carga com Gatling rodam noturnamente, e SLOs de latência fazem parte do build — uma regressão de performance falha o pipeline. Testes de caos com Chaos Mesh injetam falhas de pod, atrasos de rede e particionamento em staging para validar que a arquitetura se comporta sob falha.

O gate de cobertura exige 85% de cobertura de linhas e 80% de branches em módulos de domínio. Adaptadores não têm gate de cobertura — confiamos nos testes de integração para validá-los, e cobrir adaptadores fica artificial.

## Observabilidade

Os três pilares são correlacionados por traceId propagado de ponta a ponta, inclusive através de cabeçalhos Kafka. Métricas são coletadas via Micrometer, expostas para Prometheus e visualizadas no Grafana, com dashboards RED (Rate, Errors, Duration) e USE (Utilization, Saturation, Errors) por serviço. Logs são estruturados em JSON e enviados para Loki, indexados por correlationId. Traces distribuídos passam por OpenTelemetry e são visualizados no Jaeger, mostrando o caminho completo de uma requisição através de chamadas HTTP e mensagens Kafka.

SLOs são definidos por serviço — disponibilidade, latência p99, taxa de erros — e rastreados com error budgets que governam a velocidade de deploy.

## Estrutura do Projeto

O repositório organiza-se em diretórios funcionais. O diretório `services/` contém os cinco microsserviços principais, cada um como módulo Maven independente com sua própria estrutura hexagonal interna (domain, application, infrastructure). O diretório `infrastructure/` agrupa configurações Docker para desenvolvimento local, manifestos Kubernetes para implantação em produção, e módulos Terraform para provisionamento da infraestrutura AWS. O diretório `docs/` contém documentação arquitetural detalhada, incluindo o mapa de contextos DDD, modelo de ameaças de segurança, ADRs (Architecture Decision Records) e diagramas C4. O diretório `.github/workflows/` define os pipelines de CI/CD.

## Ciclo de Desenvolvimento Local

O ambiente de desenvolvimento é construído para iniciar em minutos. Pré-requisitos são apenas Docker, Docker Compose, Java 21 e Maven 3.9 ou superior. Um único comando `docker compose up` inicia toda a infraestrutura localmente — PostgreSQL, MongoDB, Redis, Kafka, Keycloak — com configuração pré-populada. Cada serviço pode então ser executado individualmente com `./mvnw spring-boot:run` no perfil `local`, com hot reload via Spring DevTools. O comando `./mvnw verify` executa toda a pirâmide de testes, levando aproximadamente cinco minutos para o monorepo completo.

## Implantação

Produção roda em AWS EKS com GitOps via ArgoCD. Cada merge para a branch main dispara o seguinte fluxo: o CI constrói, testa, faz varredura de segurança e empurra imagens de container para o ECR. Em seguida, atualiza a tag da imagem no repositório GitOps separado. ArgoCD detecta a mudança e inicia o rollout no cluster. Argo Rollouts executa um deploy canário progressivo (5%, depois 25%, depois 100%) com análise automatizada baseada em métricas Prometheus — taxa de erros, latência p99, saturação. Rollback automático é acionado em qualquer violação de SLO durante o canário.

## Roadmap e Evolução

A primeira fase entrega o caminho crítico: criação de pedido, pagamento, reserva de estoque e notificação básica. A segunda fase adiciona analytics em tempo real através de Kafka Streams, dashboard administrativo e mais canais de notificação. A terceira fase introduz machine learning para detecção de fraude no contexto de pagamento e recomendações personalizadas no Query Service. A quarta fase expande para multi-região com replicação ativo-ativo do event store e estratégias de resolução de conflitos baseadas em CRDTs.

## Mapeamento aos Requisitos do Desafio

Cada requisito explícito do desafio é endereçado por elementos concretos do projeto. Cinco anos de experiência com Java é demonstrado pelo uso idiomático de Java 21 moderno, padrões avançados como agregados imutáveis e sealed interfaces para eventos, e conhecimento profundo de Spring Boot e Project Reactor. A experiência com SQL e NoSQL aparece na estratégia deliberada de polyglot persistence, com papéis distintos e justificados para PostgreSQL, MongoDB e Redis. A familiaridade com microsserviços, nuvem e containers materializa-se em cinco serviços em containers Docker orquestrados por Kubernetes na AWS. Domain-Driven Design estrutura todos os contextos delimitados; Event Sourcing é o coração do contexto de pedidos; programação reativa governa os serviços de Order e Query; testes automatizados cobrem todas as camadas da pirâmide; e segurança é tratada como preocupação transversal desde o desenho até o pipeline.
