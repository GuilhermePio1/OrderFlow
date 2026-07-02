package com.orderflow.payment.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento {@code OrderCancelled}, publicado pelo contexto
 * Ordering quando um pedido é cancelado — por solicitação do cliente, por falha
 * de pagamento, por falta de estoque, etc. É o gatilho da <em>compensação</em>
 * do pagamento neste contexto (vide {@link OrderEventHandler}), o passo do
 * Payment na saga de compensação coreografada ({@code docs/architecture.md}).
 *
 * <p>DTO de borda da Anti-Corruption Layer ({@code docs/ddd.md}): {@code orderId}
 * chega aninhado como {@code {"value": ...}} (o Ordering não achata value objects
 * ao serializar). {@code reason}/{@code details} são propagados para anexar
 * contexto ao evento de reversão; campos desconhecidos são tolerados pelo
 * {@link InboundEventDeserializer}.
 */
public record OrderCancelledEvent(
        UUID eventId,
        Identifier orderId,
        String reason,
        String details,
        Instant occurredAt,
        int schemaVersion
) {

    /** Referência por identidade — espelha um value object {@code (UUID value)} do Ordering. */
    public record Identifier(UUID value) {
    }
}
