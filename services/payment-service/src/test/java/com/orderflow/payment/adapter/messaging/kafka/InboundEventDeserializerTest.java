package com.orderflow.payment.adapter.messaging.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InboundEventDeserializer")
class InboundEventDeserializerTest {

    private final InboundEventDeserializer deserializer = InboundEventDeserializer.withDefaultObjectMapper();

    @Test
    @DisplayName("desserializa OrderPlaced preservando identidades, total e timestamp ISO-8601")
    void deserializesOrderPlaced() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        byte[] json = orderPlacedJson(eventId, orderId, customerId);

        OrderPlacedEvent event = deserializer.deserialize(json, OrderPlacedEvent.class);

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.orderId().value()).isEqualTo(orderId);
        assertThat(event.customerId().value()).isEqualTo(customerId);
        assertThat(event.totalAmount().amount()).isEqualByComparingTo(new BigDecimal("149.90"));
        assertThat(event.totalAmount().currency()).isEqualTo("BRL");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2025-01-15T10:00:00Z"));
        assertThat(event.schemaVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("tolera propriedades desconhecidas (itens, endereço — compatibilidade aditiva)")
    void toleratesUnknownProperties() {
        byte[] json = """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "customerId": {"value": "%s"},
                  "items": [
                    {"productId": {"value": "%s"}, "quantity": {"value": 2}, "unitPrice": {"amount": 74.95, "currency": "BRL"}}
                  ],
                  "totalAmount": {"amount": 149.90, "currency": "BRL"},
                  "shippingAddress": {"street": "Rua das Flores", "number": "100", "city": "São Paulo"},
                  "occurredAt": "2025-01-15T10:00:00Z",
                  "schemaVersion": 1
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                .getBytes(StandardCharsets.UTF_8);

        OrderPlacedEvent event = deserializer.deserialize(json, OrderPlacedEvent.class);

        assertThat(event.orderId().value()).isNotNull();
        assertThat(event.customerId().value()).isNotNull();
        assertThat(event.totalAmount().amount()).isEqualByComparingTo(new BigDecimal("149.90"));
    }

    @Test
    @DisplayName("JSON malformado lança InboundEventDeserializationException")
    void malformedJsonThrows() {
        assertThatThrownBy(() -> deserializer.deserialize(
                "{not-json".getBytes(StandardCharsets.UTF_8), OrderPlacedEvent.class))
                .isInstanceOf(InboundEventDeserializationException.class)
                .hasMessageContaining("OrderPlacedEvent")
                .hasCauseInstanceOf(Throwable.class);
    }

    static byte[] orderPlacedJson(UUID eventId, UUID orderId, UUID customerId) {
        return """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "customerId": {"value": "%s"},
                  "totalAmount": {"amount": 149.90, "currency": "BRL"},
                  "occurredAt": "2025-01-15T10:00:00Z",
                  "schemaVersion": 1
                }
                """.formatted(eventId, orderId, customerId).getBytes(StandardCharsets.UTF_8);
    }
}
