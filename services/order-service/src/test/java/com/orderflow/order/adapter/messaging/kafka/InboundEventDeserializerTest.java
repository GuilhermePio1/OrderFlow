package com.orderflow.order.adapter.messaging.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InboundEventDeserializer")
class InboundEventDeserializerTest {

    private final InboundEventDeserializer deserializer = InboundEventDeserializer.withDefaultObjectMapper();

    @Test
    @DisplayName("desserializa PaymentAuthorized preservando identidades e timestamp ISO-8601")
    void deserializesPaymentAuthorized() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        byte[] json = """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "paymentId": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z"
                }
                """.formatted(eventId, orderId, paymentId).getBytes(StandardCharsets.UTF_8);

        PaymentAuthorizedEvent event = deserializer.deserialize(json, PaymentAuthorizedEvent.class);

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.paymentId()).isEqualTo(paymentId);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2025-01-15T10:00:00Z"));
    }

    @Test
    @DisplayName("tolera propriedades desconhecidas (compatibilidade aditiva de schema)")
    void toleratesUnknownProperties() {
        byte[] json = """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "reservationId": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z",
                  "warehouseId": "added-by-upstream-later"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);

        InventoryReservedEvent event = deserializer.deserialize(json, InventoryReservedEvent.class);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.orderId()).isNotNull();
        assertThat(event.reservationId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("JSON malformado lança InboundEventDeserializationException")
    void malformedJsonThrows() {
        assertThatThrownBy(() -> deserializer.deserialize(
                "{not-json".getBytes(StandardCharsets.UTF_8), PaymentFailedEvent.class))
                .isInstanceOf(InboundEventDeserializationException.class)
                .hasMessageContaining("PaymentFailedEvent")
                .hasCauseInstanceOf(Throwable.class);
    }
}
