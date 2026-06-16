# OrderFlow

**Plataforma distribuída de gestão de pedidos** para e-commerce, construída como uma referência prática de arquitetura de microsserviços com Domain-Driven Design, Event Sourcing, CQRS, programação reativa e segurança ponta a ponta.

O sistema cobre o ciclo de vida de um pedido — confirmação, autorização de pagamento, reserva de estoque, envio e notificação — usando comunicação assíncrona via Kafka, com cada serviço mapeado a um contexto delimitado do DDD e dono da própria persistência.

> Para a visão completa do produto, motivação de negócio e justificativa de cada decisão arquitetural, veja [`docs/summary.md`](docs/summary.md).

---

## Estado da implementação

Este repositório está em construção incremental. Nem tudo descrito na documentação de visão já existe em código — a tabela abaixo reflete o que está implementado hoje.

| Serviço | Contexto (DDD) | Stack | Estado |
| --- | --- | --- | --- |
| **order-service** | Ordering (core) | Spring WebFlux · R2DBC · Kafka | ✅ Implementado — agregado event-sourced, REST, saga, outbox, segurança OAuth2 |
| **payment-service** | Payment (supporting) | Spring MVC · Virtual Threads · JPA | ✅ Implementado — autorização, gateway Stripe (ACL), outbox, consumo da saga |
| **inventory-service** | Inventory (supporting) | Spring Boot | 🚧 Esqueleto (apenas a aplicação) |
| **notification-service** | Notification (generic) | Spring Boot | 🚧 Esqueleto (apenas a aplicação) |
| **query-service** | Query (read-side CQRS) | Spring Boot | 🚧 Esqueleto (apenas a aplicação) |

A infraestrutura local (PostgreSQL, MongoDB, Redis, Kafka, Debezium, Keycloak) já está totalmente definida via Docker Compose, e o pipeline de CI executa build e testes em cada push/PR.

---

## Arquitetura em alto nível

- **Microsserviços por contexto delimitado.** Cada serviço resolve um problema de negócio, não uma camada técnica.
- **Assíncrono por padrão.** Os serviços não se chamam diretamente; publicam e consomem eventos de domínio no Kafka. REST síncrono fica reservado para leituras voltadas ao cliente.
- **Saga coreografada.** Transações distribuídas (pedido → pagamento → estoque) avançam e compensam reagindo a eventos, sem orquestrador central.
- **Padrão Outbox + CDC.** Eventos são gravados na mesma transação do estado de domínio e publicados no Kafka pelo Debezium, evitando dual-writes.
- **Event Sourcing no Ordering.** O agregado `Order` é reconstruído a partir do stream de eventos imutáveis no PostgreSQL, habilitando auditoria completa e replay.
- **Polyglot persistence.** PostgreSQL (event store e ledger transacional), MongoDB (modelos de leitura) e Redis (cache e locks) — cada tecnologia escolhida pelo padrão de acesso.
- **Arquitetura hexagonal.** Domínio puro no centro, ports/adapters em volta; regras de fronteira garantidas por testes ArchUnit.

```
Cliente ──HTTP──> order-service ──(OrderPlaced)──> Kafka
                                                     │
                          payment-service <──────────┤
                                 │ (PaymentAuthorized / PaymentFailed)
                                 └──────────> Kafka ──> order-service (confirma/cancela)
```

## Stack tecnológica

- **Linguagem:** Java 21 (records, sealed interfaces, virtual threads)
- **Frameworks:** Spring Boot 4.0, Spring WebFlux, Spring MVC, Spring Cloud Stream
- **Reativo:** Project Reactor, R2DBC
- **Mensageria:** Apache Kafka + Debezium (CDC)
- **Persistência:** PostgreSQL 18, MongoDB 8, Redis 8 (migrações via Flyway)
- **Segurança:** OAuth2/OIDC com Keycloak, Spring Security (JWT RS256)
- **Testes:** JUnit 5, Testcontainers, WireMock, ArchUnit
- **Build:** Maven (multi-módulo)

## Estrutura do projeto

```
OrderFlow/
├── pom.xml                      # POM pai (multi-módulo)
├── services/
│   ├── order-service/           # Ordering — event sourcing, WebFlux, R2DBC
│   ├── payment-service/         # Payment — Spring MVC, JPA, gateway Stripe
│   ├── inventory-service/       # Inventory (esqueleto)
│   ├── notification-service/    # Notification (esqueleto)
│   └── query-service/           # Query / CQRS read-side (esqueleto)
├── infrastructure/
│   └── docker/                  # docker-compose + Debezium + Keycloak realm
├── docs/                        # Documentação de arquitetura (ver abaixo)
└── .github/workflows/ci.yml     # Build & testes
```

Cada serviço implementado segue a mesma estrutura hexagonal interna:

```
domain/        # modelo, value objects, eventos, exceções, ports de repositório (sem framework)
application/   # casos de uso, comandos, ports
adapter/       # rest, persistence, messaging, config, gateways
```

## Como executar localmente

**Pré-requisitos:** Docker, Docker Compose, Java 21 e Maven 3.9+.

### 1. Subir a infraestrutura

```bash
cd infrastructure/docker
docker compose up -d
```

Isso inicia PostgreSQL, MongoDB, Redis, Kafka, Debezium (com os conectores de outbox de order e payment) e Keycloak (com o realm `orderflow` pré-configurado).

### 2. Rodar os serviços

A partir da raiz do projeto:

```bash
# order-service  (porta 8081)
./mvnw -pl services/order-service spring-boot:run

# payment-service (porta 8082)
./mvnw -pl services/payment-service spring-boot:run
```

### Portas

| Componente | Porta |
| --- | --- |
| order-service | 8081 |
| payment-service | 8082 |
| Keycloak | 8080 |
| PostgreSQL | 5433 |
| MongoDB | 27017 |
| Redis | 6379 |
| Kafka | 9092 |
| Kafka Connect (Debezium) | 8083 |

## Testes

O CI (`.github/workflows/ci.yml`) roda `mvn -B -ntp verify` em cada push e PR para `main`, publicando relatórios de cobertura JaCoCo.

```bash
# todos os módulos
mvn verify

# um serviço específico
mvn -pl services/order-service verify
```

A suíte cobre as camadas da pirâmide: testes unitários de domínio (sem contexto Spring), testes de arquitetura (ArchUnit — fronteiras hexagonais e convenções de nomenclatura), integração com Testcontainers (PostgreSQL, Kafka) e contratos de gateway externo com WireMock.

## Documentação

A pasta [`docs/`](docs/) detalha cada pilar da arquitetura:

| Documento | Conteúdo |
| --- | --- |
| [`summary.md`](docs/summary.md) | Visão geral completa do projeto, contexto de negócio e mapeamento de requisitos |
| [`architecture.md`](docs/architecture.md) | Arquitetura detalhada, fluxos de requisição e sagas |
| [`ddd.md`](docs/ddd.md) | Domain-Driven Design, mapa de contextos e agregados |
| [`event-sourcing.md`](docs/event-sourcing.md) | Event Sourcing e CQRS |
| [`reactive-programming.md`](docs/reactive-programming.md) | Programação reativa com Project Reactor |
| [`security.md`](docs/security.md) | Modelo de segurança e defesa em profundidade |
| [`testing.md`](docs/testing.md) | Estratégia e pirâmide de testes |
| [`infrastructure.md`](docs/infrastructure.md) | Cloud, containers e orquestração |
| [`roadmap.md`](docs/roadmap.md) | Fases de execução e evolução |
</content>
</invoke>
