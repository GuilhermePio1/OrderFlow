package com.orderflow.order.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento publicado pelo contexto de Payment quando
 * a autorização do pagamento é bem-sucedida. É um DTO de borda (vocabulário
 * externo, identidades como {@link UUID} cruas) que a camada de mensageria
 * traduz para o comando de domínio — a Anti-Corruption Layer descrita em
 * {@code docs/ddd.md}: o modelo externo não vaza para dentro do agregado.
 */
public record PaymentAuthorizedEvent(
        UUID eventId,
        UUID orderId,
        UUID paymentId,
        Instant occurredAt
) {
}
