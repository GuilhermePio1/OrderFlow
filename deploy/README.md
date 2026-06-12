# deploy/

Infrastructure for running OrderFlow.

- `docker-compose.yml` — local stack: Kafka (KRaft), PostgreSQL, Debezium Connect, Schema Registry and the observability stack (Prometheus, Grafana, Tempo, Loki). *(to be added)*
- `k8s/` — Kustomize manifests for cluster deployment. *(to be added)*
- `observability/` — versioned dashboards and alert rules (see docs/ARCHITECTURE.md §8). *(to be added)*