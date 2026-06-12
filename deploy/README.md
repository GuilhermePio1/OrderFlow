# deploy/

Infrastructure for running OrderFlow.

- `docker-compose.yml` — local stack: Kafka (KRaft), PostgreSQL, Debezium Connect, Schema Registry and the observability stack (Prometheus, Grafana, Tempo, Loki). Started from the repository root with `docker compose up -d` (the root `docker-compose.yml` includes this file).
- `postgres/` — init script creating one database + role per service (`orders`, `payments`, `inventory`, `shipping`) with replication privileges for CDC.
- `debezium/` — outbox connector configs (one per producing service); register them with `scripts/register-connectors.sh` once the services' Flyway migrations have created the `outbox` tables.
- `observability/` — Prometheus scrape config, Tempo config, Grafana provisioning, and versioned dashboards (see docs/ARCHITECTURE.md §8).
- `scripts/` — local ops tooling (see docs/OPERATIONS.md §6).
- `k8s/` — Kustomize manifests for cluster deployment. *(to be added)*

## Host ports

| Component | URL / port |
|---|---|
| Kafka broker | `localhost:9092` (in-network: `kafka:29092`) |
| PostgreSQL | `localhost:5432` |
| Schema Registry | http://localhost:8090 (8081 belongs to order-service on the host) |
| Debezium Connect REST | http://localhost:8086 (in-network: `connect:8083`) |
| Kafka UI | http://localhost:8089 |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Tempo | `localhost:3200` query, `localhost:4317/4318` OTLP ingest |
| Loki | http://localhost:3100 |
