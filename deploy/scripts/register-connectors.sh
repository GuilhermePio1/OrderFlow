#!/usr/bin/env bash
# Registers (or updates) the Debezium outbox connectors from deploy/debezium/.
# Each <name>.json holds only the connector *config*; the connector name is the
# file name, matching the runbook convention orderflow-<svc>-outbox.
#
# Run after each service's Flyway migrations have created its outbox table —
# the connectors create a filtered publication on public.outbox and fail if
# the table does not exist yet.
#
# Usage: deploy/scripts/register-connectors.sh
#   CONNECT_URL overrides the Connect REST endpoint (default localhost:8086,
#   the host mapping of connect:8083).
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8086}"
DEBEZIUM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../debezium" && pwd)"

for file in "${DEBEZIUM_DIR}"/*.json; do
  name="$(basename "${file}" .json)"
  printf 'Registering %s... ' "${name}"
  curl -fsS -X PUT -H 'Content-Type: application/json' \
    --data "@${file}" \
    "${CONNECT_URL}/connectors/${name}/config" > /dev/null
  echo "ok"
done

echo
echo "Connector status:"
curl -fsS "${CONNECT_URL}/connectors?expand=status" | python3 -m json.tool
