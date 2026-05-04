# Arquitetura Detalhada — OrderFlow

## Princípios Norteadores

A arquitetura do OrderFlow não é uma coleção arbitrária de tecnologias modernas. Cada decisão decorre de um pequeno conjunto de princípios fundamentais aplicados consistentemente.

O primeiro princípio é que cada microsserviço corresponde a um único contexto delimitado do negócio. Não há serviços organizados por camada técnica (não existe "service-layer-service"); todos são organizados pelo problema de negócio que resolvem. Isso mantém os limites de modificação alinhados com os limites de mudança do negócio.

O segundo princípio é que a comunicação entre serviços é assíncrona por padrão e síncrona apenas por exceção. Acoplamento temporal — onde um serviço precisa que outro esteja respondendo agora — é uma fonte conhecida de fragilidade em sistemas distribuídos. O barramento de eventos Kafka quebra esse acoplamento e permite que cada serviço seja deployado, escalado e falhe independentemente.

O terceiro princípio é polyglot persistence guiada por padrões de acesso. O agregado de pedidos precisa de auditoria completa e replay temporal — vai para event store em PostgreSQL. As consultas dos clientes querem visões denormalizadas com agregações rápidas — vão para MongoDB. Sessões e locks precisam de latência sub-milissegundo — vão para Redis. Forçar tudo em um único banco produz compromissos onde nada fica bom.

O quarto princípio é que segurança e observabilidade são preocupações transversais desenhadas desde o início, não retrofitadas. mTLS, propagação de traceId, validação de entrada, auditoria — tudo está presente desde o primeiro commit, não como esforço de hardening posterior.

## Visão de Componentes

A camada de borda é composta pelo API Gateway (Spring Cloud Gateway) que termina TLS, valida JWTs OAuth2, aplica rate limiting baseado em Redis, e roteia requisições para os serviços apropriados. Atrás do gateway, os cinco microsserviços principais operam de forma independente, cada um com seu próprio ciclo de vida, repositório de código, banco de dados e equipe responsável teórica.

O Order Service é o coração do sistema. Aceita comandos de criação e modificação de pedidos, aplica regras de domínio através do agregado Order event-sourced, persiste eventos no PostgreSQL e os publica no Kafka via padrão Outbox. É construído sobre Spring WebFlux com R2DBC para suportar alta concorrência de forma não-bloqueante.

O Payment Service consome eventos OrderPlaced do Kafka, autoriza pagamentos junto a gateways externos (Stripe, PagSeguro), persiste transações em PostgreSQL com semântica transacional rígida, e emite eventos PaymentAuthorized ou PaymentFailed. Usa Spring MVC bloqueante com virtual threads — a clareza transacional supera o ganho marginal de concorrência reativa neste contexto.

O Inventory Service mantém os níveis de estoque dos produtos e reservas em andamento. Consome eventos PaymentAuthorized, tenta reservar estoque atomicamente (usando locks Redis para evitar overselling em concorrência), e emite InventoryReserved ou InventoryOutOfStock. A reserva tem TTL configurável — se o pedido não for finalizado em N minutos, o estoque é liberado automaticamente.

O Notification Service consome eventos de múltiplos contextos e envia comunicações apropriadas via email (SES), SMS (SNS) e push (FCM). Implementa retry com backoff exponencial e dead letter queues para falhas persistentes. Templates são gerenciados de forma versionada.

O Query Service é a face de leitura do CQRS. Consome todos os eventos de domínio relevantes do Kafka e mantém modelos denormalizados otimizados em MongoDB — visão agregada do pedido com todos os dados de cliente, itens, status de pagamento e envio em um único documento. Serve a maior parte do tráfego de leitura dos clientes (consulta de pedidos, histórico).

## Fluxo de uma Requisição: Criação de Pedido

O fluxo completo de criação de um pedido demonstra como os componentes colaboram.

O cliente envia uma requisição HTTP POST para `/api/orders` no API Gateway, autenticada com JWT. O gateway valida o token contra o Keycloak (com cache de chaves públicas), aplica rate limiting por usuário, propaga um traceId, e encaminha a requisição para o Order Service.

O Order Service recebe o comando de criação, valida estruturalmente o payload (Bean Validation), constrói o agregado Order aplicando regras de domínio (validação de itens, cálculo de total, política de descontos), e gera um evento OrderPlaced. Numa única transação no PostgreSQL, o evento é gravado tanto no event store quanto na tabela outbox. A resposta HTTP retorna 202 Accepted com o orderId.

O Debezium, conectado via CDC ao PostgreSQL, detecta o novo registro na tabela outbox e o publica no tópico Kafka `orders.events`. Esta é a garantia do padrão Outbox: o evento ou é publicado ou não — nunca há divergência entre o estado do banco e o que foi publicado.

O Payment Service consome o evento OrderPlaced, inicia uma autorização junto ao gateway de pagamento externo (com circuit breaker para proteger contra indisponibilidade), e ao receber confirmação, persiste a transação e emite PaymentAuthorized. Em caso de falha não-recuperável, emite PaymentFailed que dispara compensação no Order Service.

O Inventory Service consome PaymentAuthorized, adquire um lock distribuído Redis para os SKUs envolvidos, verifica e debita o estoque atomicamente, persiste a reserva com TTL, e emite InventoryReserved.

O Order Service consome PaymentAuthorized e InventoryReserved, atualiza o estado do agregado (que produz mais eventos OrderPaymentConfirmed, OrderInventoryReserved), e quando ambas as condições são satisfeitas, transiciona para o estado CONFIRMED.

Em paralelo, o Query Service consome todos esses eventos e atualiza progressivamente o modelo de leitura no MongoDB. O cliente, fazendo polling ou recebendo notificações via WebSocket, vê o pedido evoluir através de seus estados.

O Notification Service consome OrderConfirmed e dispara a confirmação por email para o cliente.

Cada passo deste fluxo é observável: traces no Jaeger mostram a latência de cada etapa, métricas no Grafana revelam taxa de sucesso e throughput, logs em Loki permitem investigação detalhada de qualquer pedido específico.

## Saga de Compensação

Quando algo falha, a saga executa compensações na ordem inversa. Se o pagamento foi autorizado mas o estoque está esgotado, o Inventory Service emite InventoryOutOfStock; o Order Service consome e transiciona para o estado CANCELLING; o Payment Service consome a transição e estorna o pagamento; o Order Service consome o estorno e finaliza no estado CANCELLED; o Notification Service envia o aviso ao cliente.

Esta coreografia (cada serviço reagindo a eventos) é preferida sobre orquestração explícita (um serviço dirigindo o fluxo) porque preserva o desacoplamento. Nenhum serviço precisa conhecer a saga inteira — cada um conhece apenas suas reações locais.

## Persistência: Padrão Outbox e CDC

O padrão Outbox resolve um problema clássico em sistemas distribuídos: como garantir que uma mudança no banco de dados e a publicação de uma mensagem aconteçam atomicamente. Sem este padrão, há cenários em que o banco é atualizado mas a publicação falha (ou vice-versa), produzindo divergência.

A solução é gravar eventos em uma tabela `outbox` na mesma transação local que persiste a mudança principal. Um processo separado — Debezium, neste caso — lê o write-ahead log do PostgreSQL, captura inserções na tabela outbox, e as publica no Kafka. Se a publicação falhar, o Debezium reenviará — garantia "at least once". Os consumidores são idempotentes, lidando com possíveis duplicatas através do eventId.

## Modelos de Leitura: Projeções no MongoDB

O Query Service mantém múltiplas projeções, cada uma otimizada para um padrão de consulta específico. A projeção `order_summary` agrega dados básicos de pedido para a tela de listagem. A projeção `order_detail` denormaliza tudo (itens, pagamento, envio, cliente) em um único documento, eliminando joins. A projeção `customer_history` agrega gastos por cliente e período. Novas projeções podem ser adicionadas a qualquer momento — basta criar um novo consumidor que processa o stream de eventos desde o início e gradualmente fica em dia com o presente.

Esta capacidade de criar novas visões a partir do histórico completo é uma das vantagens definidoras do event sourcing. Em sistemas tradicionais com persistência de estado atual, mudanças no modelo de leitura exigem migrações dolorosas; aqui, são apenas novos consumidores.

## Estratégias de Resiliência

Cada chamada externa é protegida por circuit breaker (Resilience4j). Após N falhas consecutivas, o circuito abre e chamadas falham rapidamente sem sobrecarregar o serviço a jusante. Após um período de cool-down, o circuito entra em estado half-open e tenta uma chamada de teste; se bem-sucedida, fecha; se não, volta ao estado open.

Retries são aplicados com cuidado — apenas para falhas idempotentes ou em operações que possuem chaves de idempotência explícitas. Backoff exponencial com jitter evita cascatas thundering-herd quando um serviço se recupera após falha massiva.

Bulkheads limitam o número de threads ou conexões dedicadas a cada dependência externa, garantindo que uma dependência lenta não consuma todos os recursos do serviço.

Timeouts explícitos são definidos em cada chamada — nunca confiar em timeouts padrão de bibliotecas de cliente, que frequentemente são longos demais ou inexistentes.

Mensagens consumidas que falham repetidamente são enviadas para uma Dead Letter Queue (DLQ) após N tentativas, evitando bloqueio do consumer e permitindo investigação posterior por um operador.

## Escalabilidade Horizontal

Cada serviço é stateless — todo estado vive em PostgreSQL, MongoDB ou Redis. Isso permite escalar horizontalmente adicionando réplicas sem coordenação especial. O Kubernetes HPA (Horizontal Pod Autoscaler) ajusta o número de pods baseado em métricas customizadas: utilização de CPU, latência p99, e profundidade do consumer lag no Kafka.

Tópicos Kafka são particionados pela orderId (ou customerId, dependendo do tópico), garantindo que eventos relacionados ao mesmo pedido sejam processados em ordem por um único consumer dentro do consumer group. Aumentar o paralelismo é apenas questão de adicionar partições e réplicas de consumers.

PostgreSQL é escalado verticalmente para escritas e horizontalmente para leituras através de réplicas. MongoDB é escalado horizontalmente via sharding pelo customerId. Redis usa cluster mode para distribuição de chaves.

## Multi-tenancy e Isolamento

Embora o projeto inicial seja single-tenant, a arquitetura é desenhada considerando multi-tenancy futuro. Cada agregado carrega um tenantId; queries são automaticamente filtradas; chaves de cache incluem o tenantId no prefixo; tópicos Kafka podem ser segregados por tenant em deployments dedicados quando o isolamento exigir.

## Decisões Arquiteturais Registradas

Decisões importantes são documentadas como ADRs (Architecture Decision Records) no diretório `docs/adr/`. Cada ADR captura o contexto da decisão, as alternativas consideradas, a decisão tomada e suas consequências. Exemplos: ADR-001 (Escolha de Spring WebFlux para Order Service), ADR-002 (Coreografia vs Orquestração de Sagas), ADR-003 (Padrão Outbox para Publicação Confiável de Eventos), ADR-004 (PostgreSQL como Event Store), ADR-005 (Coreografia para Sagas), ADR-006 (Java 21 Virtual Threads em Serviços Não-Reativos).

ADRs servem três propósitos: forçam pensamento explícito sobre alternativas, documentam o contexto histórico para futuros desenvolvedores, e comunicam a intencionalidade da arquitetura para stakeholders técnicos.
