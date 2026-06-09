package com.orderflow.payment.adapter.messaging.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contrato de entrada do evento {@code OrderPlaced}, publicado pelo contexto
 * Ordering quando um pedido é confirmado. É um DTO de borda da Anti-Corruption
 * Layer ({@code docs/ddd.md}): o contexto Payment é Customer/Supplier downstream
 * de Ordering e traduz este modelo externo para seu próprio vocabulário (o
 * {@link com.orderflow.payment.application.command.AuthorizePaymentCommand}),
 * sem deixar o modelo do Order vazar para dentro do agregado {@code Payment}.
 *
 * <p>O Ordering é event-sourced e publica o evento de domínio sem achatar seus
 * value objects (vide {@code OrderEventCodec} do order-service): por isso
 * {@code orderId}/{@code customerId} chegam aninhados como {@code {"value": ...}}
 * e {@code totalAmount} como {@code {"amount": ..., "currency": ...}}. Só os
 * campos necessários para autorizar o pagamento são mapeados; os demais
 * (itens, endereço de entrega) são ignorados pela tolerância a propriedades
 * desconhecidas do {@link InboundEventDeserializer}.
 *
 * <p>Note que {@code OrderPlaced} não carrega o meio de pagamento — no contrato
 * atual do Ordering ele não faz parte do evento. O meio é resolvido pela borda
 * de mensageria (vide {@link OrderEventHandler}) a partir de configuração.
 */
public record OrderPlacedEvent(
        UUID eventId,
        Identifier orderId,
        Identifier customerId,
        MoneyPayload totalAmount,
        Instant occurredAt,
        int schemaVersion
) {

    /** Referência por identidade — espelha um value object {@code (UUID value)} do Ordering. */
    public record Identifier(UUID value){
    }

    /** Montante achatado — espelha o {@code Money (amount, currency)} do Ordering. */
    public record MoneyPayload(BigDecimal amount, String currency) {
    }
}
