# OrderFlow Operations Runbook

How to run, observe, and fix OrderFlow in production. Written for the on-call engineer at 3 a.m. — every alert links to a procedure here.

---

## 1. Golden Signals & Dashboards

Grafana folder **OrderFlow** (provisioned from `deploy/observability/dashboards/`):

| Dashboard | What it answers |
|---|---|
| **Order Flow Overview** | Orders/sec, saga completion rate, completion latency p50/p99, cancellation reasons breakdown |
| **Kafka Health** | Consumer lag per group, under-replicated partitions, DLT depth, broker disk |
| **Outbox & CDC** | `outbox_lag_seconds` per service, Debezium connector status, replication slot WAL retention |
| **Service RED** | Rate/Errors/Duration per endpoint per service, JVM (heap, GC pauses, virtual thread pinning) |
| **Saga Inspector** | Active sagas by state, stuck sagas (no transition > 5 min), compensation rate |

Every dashboard panel links to Tempo traces filtered by the relevant `orderId`/`traceId`.

## 2. Alert Catalog

| Alert | Threshold | Severity | Procedure |
|---|---|---|---|
| `SagaStuck` | saga without transition > 5 min, count > 0 | page | [§4.1](#41-sagastuck) |
| `ConsumerLagHigh` | lag > 10k msgs or growing 5 min | page | [§4.2](#42-consumerlaghigh) |
| `OutboxLagHigh` | `outbox_lag_seconds` > 60 | page | [§4.3](#43-outboxlaghigh) |
| `DLTNotEmpty` | any DLT depth > 0 | ticket (page if > 100) | [§4.4](#44-dlt-replay) |
| `ReplicationSlotBloat` | slot retained WAL > 5 GB | page | [§4.3](#43-outboxlaghigh) |
| `PaymentDeclineRateAnomaly` | declines > 3× 7-day baseline | ticket | likely PSP issue; check PSP status page first |
| `OrderApiP99High` | p99 > 200 ms for 10 min | ticket | check Service RED dashboard, GC pauses, DB connections |
| `ReconciliationMismatch` | nightly job found orphans | page | [§4.5](#45-reconciliationmismatch) |

## 3. Routine Operations

### Deploying
Rolling deploys via GitHub Actions → Kustomize. Consumers use cooperative rebalancing, so partial rollouts don't stop the world. **Order matters for schema changes:** consumers first, producers second (BACKWARD compatibility). CI blocks incompatible schemas, but the deploy order is on you.

### Scaling
- Stateless services: scale on CPU (HPA configured). Consumer parallelism is capped by partition count (12) per topic — scaling a consumer group beyond 12 pods adds nothing.
- Increasing partitions is a **breaking-ish** operation (key→partition mapping changes; per-order ordering holds only for new messages). Procedure: drain sagas (stop accepting orders, wait for active sagas < threshold), then repartition. Avoid unless sustained lag at max parallelism.

### Certificate / credential rotation
SASL/SCRAM credentials per service live in sealed secrets; rotate by adding the new credential, rolling pods, then deleting the old one. Topic ACLs are declarative in `deploy/kafka/acls.yaml`.

## 4. Incident Procedures

### 4.1 SagaStuck

1. Open **Saga Inspector**, get the stuck `orderId`(s) and the state they're stuck in.
2. Open the trace (one click) — find the last completed hop.
3. Three usual causes:
   - **Participant down / lagging** → check that service's consumer lag; if the service is healthy and lag is draining, the saga will self-heal. Confirm and watch.
   - **Reply landed in DLT** (poison message) → go to [§4.4](#44-dlt-replay).
   - **Step timeout already fired and compensation also stuck** → check the saga DLQ (`orderflow.orders.saga-dlq.v1`).
4. Saga DLQ entries require manual resolution via the admin endpoint (`POST /internal/sagas/{id}/resolve` with `action=RETRY_STEP | FORCE_COMPENSATE | MARK_RESOLVED`). `MARK_RESOLVED` requires a linked ticket — it asserts a human fixed reality out-of-band (e.g., manual refund in the PSP console).

### 4.2 ConsumerLagHigh

1. Identify the group on **Kafka Health**. Lag on *one* group = that service's problem; lag on *all* groups = broker problem.
2. Service-side: check RED dashboard — usually slow DB (connection pool exhaustion shows as high `hikaricp_connections_pending`) or repeated processing errors (check `*.retry` topic throughput).
3. If processing is healthy but slow: scale the deployment (up to partition count).
4. Broker-side: check under-replicated partitions; if a broker is down, Kafka self-heals on restart — verify ISR recovery, don't reassign partitions in panic.

### 4.3 OutboxLagHigh

Events are **not lost** — they're sitting in the outbox/WAL. Customers see stalled order progress.

1. Check Debezium connector status: `curl connect:8083/connectors/orderflow-<svc>-outbox/status`.
2. `FAILED` task → read the trace in the status output; the dominant cause is a poison record after a manual schema change. Fix config, then `POST /connectors/.../tasks/0/restart`.
3. Connector RUNNING but lag growing → PostgreSQL replication slot issue. Check `pg_replication_slots` for the slot's `restart_lsn` advancing.
4. **WAL bloat warning:** a dead connector holds its replication slot, and PostgreSQL retains WAL indefinitely for it. If `ReplicationSlotBloat` fired and the connector can't be revived quickly, escalate before the disk fills — dropping the slot is a data-loss decision (events still in WAL-only would be lost) and requires the reconciliation procedure afterwards.

### 4.4 DLT Replay

1. Inspect: `deploy/scripts/dlt-inspect.sh <topic>.dlt` prints each record's `x-exception`, `x-trace-id`, key, and payload (Avro-decoded).
2. Classify:
   - **Transient at the time** (downstream outage since resolved) → replay as-is.
   - **Bug in consumer** → fix, deploy, then replay.
   - **Genuinely invalid message** (should be rare — producer bug) → fix producer; for the bad records, decide per-record: discard with a ticket, or transform-and-replay.
3. Replay: `deploy/scripts/dlt-replay.sh <topic>.dlt --from <offset> --to <offset> [--dry-run]` republishes to the original topic with the original key (ordering caveat: replayed records are *appended*, they do not reinsert into historical order — consumers' idempotency and the saga's state checks make this safe; that's not luck, it's ADR-0004).
4. Confirm DLT depth returns to 0 and the affected sagas resume (Saga Inspector).

### 4.5 ReconciliationMismatch

The nightly job replays state topics and cross-checks: every `COMPLETED` order has exactly one capture; every `CANCELLED`-after-payment has exactly one refund; every reservation is either consumed or released.

1. The job's report (artifact in the job's pod logs / S3) lists each mismatch with `orderId` and the violated invariant.
2. Mismatches have historically (in chaos testing) meant: operator dropped a replication slot (§4.3), or a `MARK_RESOLVED` was used without the out-of-band fix actually happening.
3. Resolution is financial-grade: open an incident, fix via PSP/inventory admin actions, re-run the job to verify zero. Never "fix" by editing service databases directly.

## 5. Disaster Recovery

- **PostgreSQL:** WAL archiving + nightly base backups (PITR). RPO ≤ 5 min, RTO ≤ 30 min per service. Restore procedure in `deploy/dr/restore-postgres.md`.
- **Kafka:** replication factor 3, `min.insync.replicas=2`. Topic configs are declarative (`deploy/kafka/topics.yaml`) and can rebuild an empty cluster; *data* recovery relies on source databases + outbox re-publication for recent windows, plus the compacted state topics restored from snapshots.
- **Loss of an entire service's DB beyond PITR:** state topics are the rebuild source (replay into a fresh schema via the service's `--rebuild` mode). This is tested quarterly in staging — the test script is `deploy/dr/drill.sh`.

## 6. Local Ops Cheat Sheet

```bash
docker compose ps                                  # what's running
deploy/scripts/kafka-lag.sh                        # lag per group, one screen
deploy/scripts/saga-inspect.sh <orderId>           # full saga state + history + trace link
deploy/scripts/dlt-inspect.sh orderflow.payments.events.v1.dlt
docker compose logs -f connect                     # Debezium logs
```
