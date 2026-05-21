package com.orderflow.order.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento de falha de pagamento publicado pelo
 * contexto de Payment. Dispara a compensação da saga ({@code docs/architecture.md}):
 * o Ordering cancela o pedido com motivo {@code PAYMENT_FAILED}.
 */
public record PaymentFailedEvent(
        UUID eventId,
        UUID orderId,
        String reason, // Garante que não venha um motivo vazio
        Instant occurredAt
) {
}
