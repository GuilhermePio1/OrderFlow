-- Database-per-service (ADR-0005): one PostgreSQL instance locally,
-- one database + owner role per service. REPLICATION is required so the
-- Debezium outbox connectors (ADR-0003) can open a logical replication slot
-- and create their filtered publication on the service's outbox table.

CREATE ROLE orders WITH LOGIN REPLICATION PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;

CREATE ROLE payments WITH LOGIN REPLICATION PASSWORD 'payments';
CREATE DATABASE payments OWNER payments;

CREATE ROLE inventory WITH LOGIN REPLICATION PASSWORD 'inventory';
CREATE DATABASE inventory OWNER inventory;

CREATE ROLE shipping WITH LOGIN REPLICATION PASSWORD 'shipping';
CREATE DATABASE shipping OWNER shipping;
