package com.orderflow.order.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento publicado pelo contexto de Inventory quando
 * o estoque é reservado com sucesso. Traduzido para o comando de domínio
 * que registra a reserva no agregado — quando pagamento e estoque convergem,
 * o próprio agregado emite {@code OrderConfirmed} ({@code docs/architecture.md}).
 */
public record InventoryReservedEvent(
        UUID eventId,
        UUID orderId,
        UUID reservationId,
        Instant occurredAt
) {
}
