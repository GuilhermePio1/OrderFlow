package com.orderflow.order.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento publicado pelo contexto de Inventory quando
 * não há estoque suficiente. Dispara a compensação da saga
 * ({@code docs/architecture.md}): o Ordering cancela o pedido com motivo
 * {@code OUT_OF_STOCK}.
 */
public record InventoryOutOfStockEvent(
        UUID eventId,
        UUID orderId,
        String details, // Opcional: pode trazer o SKU ou ID do produto que faltou
        Instant occurredAt
) {
}
