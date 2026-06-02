package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.event.PaymentEvent;
import com.orderflow.payment.domain.event.PaymentFailed;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que o payload publicado na outbox respeita o contrato de borda
 * esperado pelos consumidores a jusante (ex.: {@code PaymentAuthorizedEvent}
 * do Order Service): identidades como UUIDs crus no topo do JSON, sem os
 * value objects aninhados do modelo interno.
 */
@DisplayName("PaymentEventCodec")
class PaymentEventCodecTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

    private final PaymentEventCodec codec = PaymentEventCodec.withDefaultObjectMapper();
    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("PaymentAuthorized achata os value objects em escalares de topo")
    void authorizedPayloadIsFlat() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Payment payment = Payment.initiate(
                PaymentId.generate(),
                OrderId.of(orderId),
                CustomerId.of(customerId),
                Money.of("100.00", "BRL"),
                PaymentMethod.CREDIT_CARD,
                CLOCK
        );
        payment.authorize(GatewayTransactionId.of("ch_123"), AuthorizationCode.of("A1B2C3"));
        PaymentEvent event = payment.pullUncommittedEvents().getFirst();

        assertThat(codec.eventTypeOf(event)).isEqualTo("PaymentAuthorized");

        JsonNode node = json.readTree(codec.serialize(event));
        assertThat(node.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(node.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(node.get("paymentId").asString()).isEqualTo(payment.id().value().toString());
        assertThat(node.get("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(node.get("gatewayTransactionId").asString()).isEqualTo("ch_123");
        assertThat(node.get("authorizationCode").asString()).isEqualTo("A1B2C3");
        assertThat(node.get("currency").asString()).isEqualTo("BRL");
        assertThat(node.get("method").asString()).isEqualTo("CREDIT_CARD");
        assertThat(node.get("occurredAt").asString()).isEqualTo("2026-01-15T10:00:00Z");
        assertThat(node.get("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("PaymentFailed expõe reason como string plana e mantém detalhes")
    void failedPayloadIsFlat() {
        UUID orderId = UUID.randomUUID();
        Payment payment = Payment.initiate(
                PaymentId.generate(),
                OrderId.of(orderId),
                CustomerId.of(UUID.randomUUID()),
                Money.of("50.00", "BRL"),
                PaymentMethod.PIX,
                CLOCK
        );
        payment.fail(PaymentFailed.FailureReason.CARD_DECLINED, "issuer declined");
        PaymentEvent event = payment.pullUncommittedEvents().getFirst();

        assertThat(codec.eventTypeOf(event)).isEqualTo("PaymentFailed");

        JsonNode node = json.readTree(codec.serialize(event));
        assertThat(node.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(node.get("reason").asString()).isEqualTo("CARD_DECLINED");
        assertThat(node.get("details").asString()).isEqualTo("issuer declined");
    }
}
