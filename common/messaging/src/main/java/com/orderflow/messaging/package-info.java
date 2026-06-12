/**
 * Shared messaging infrastructure for all OrderFlow services:
 * transactional outbox (ADR-0003), idempotent consumer support (ADR-0004),
 * trace-context propagation through Kafka headers, and retry/DLT error handling.
 */
package com.orderflow.messaging;