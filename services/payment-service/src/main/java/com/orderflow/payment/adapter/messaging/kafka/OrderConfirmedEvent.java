package com.orderflow.payment.adapter.messaging.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento {@code OrderConfirmed}, publicado pelo contexto
 * Ordering quando um pedido alcança o estado confirmado (pagamento autorizado e
 * estoque reservado). É o gatilho da <em>captura</em> do pagamento neste
 * contexto (vide {@link OrderEventHandler}).
 *
 * <p>DTO de borda da Anti-Corruption Layer ({@code docs/ddd.md}): o Ordering é
 * event-sourced e publica o evento sem achatar seus value objects — por isso
 * {@code orderId} chega aninhado como {@code {"value": ...}}, espelhando o
 * {@code OrderId (UUID value)} do produtor. Só {@code orderId} é necessário para
 * localizar o pagamento; os demais campos são tolerados e ignorados pelo
 * {@link InboundEventDeserializer}.
 */
public record OrderConfirmedEvent(
        UUID eventId,
        Identifier orderId,
        Instant occurredAt,
        int schemaVersion
) {

    /** Referência por identidade — espelha um value object {@code (UUID value)} do Ordering. */
    public record Identifier(UUID value) {
    }
}
