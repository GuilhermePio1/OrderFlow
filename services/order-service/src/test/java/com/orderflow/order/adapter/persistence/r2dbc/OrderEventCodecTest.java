package com.orderflow.order.adapter.persistence.r2dbc;

import com.orderflow.order.domain.event.*;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários do {@link OrderEventCodec}.
 *
 * Foco: garantir que cada {@link OrderEvent} sobrevive ao round-trip
 * JSON sem perda de informação, que o {@code event_type} salvo na
 * coluna é estável (refactorings de pacote não quebram leituras
 * históricas), e que falhas de serialização/tipo desconhecido produzem
 * exceções específicas do adapter.
 */
@DisplayName("OrderEventCodec — round-trip e mapeamento de event_type")
class OrderEventCodecTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2025-01-15T10:00:00Z");

    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", "Apto 12", "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private final OrderEventCodec codec = OrderEventCodec.withDefaultObjectMapper();

    @Nested
    @DisplayName("eventTypeOf")
    class EventTypeOf {

        @Test
        @DisplayName("mapeia cada evento concreto para seu identificador estável")
        void mapsConcreteEventsToStableIdentifier() {
            assertThat(codec.eventTypeOf(orderPlaced())).isEqualTo("OrderPlaced");
            assertThat(codec.eventTypeOf(orderPaymentConfirmed())).isEqualTo("OrderPaymentConfirmed");
            assertThat(codec.eventTypeOf(orderInventoryReserved())).isEqualTo("OrderInventoryReserved");
            assertThat(codec.eventTypeOf(orderConfirmed())).isEqualTo("OrderConfirmed");
            assertThat(codec.eventTypeOf(orderShipped())).isEqualTo("OrderShipped");
            assertThat(codec.eventTypeOf(orderDelivered())).isEqualTo("OrderDelivered");
            assertThat(codec.eventTypeOf(orderCancelled())).isEqualTo("OrderCancelled");
        }
    }

    @Nested
    @DisplayName("round-trip serialize/deserialize")
    class RoundTrip {

        @Test
        @DisplayName("OrderPlaced preserva itens, total, endereço e identidades")
        void orderPlacedRoundTrip() {
            OrderPlaced original = orderPlaced();

            OrderEvent decoded = codec.deserialize("OrderPlaced", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderPlaced.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderPaymentConfirmed preserva paymentId e timestamp")
        void orderPaymentConfirmedRoundTrip() {
            OrderPaymentConfirmed original = orderPaymentConfirmed();

            OrderEvent decoded = codec.deserialize("OrderPaymentConfirmed", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderPaymentConfirmed.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderInventoryReserved preserva reservationId e timestamp")
        void orderInventoryReservedRoundTrip() {
            OrderInventoryReserved original = orderInventoryReserved();

            OrderEvent decoded = codec.deserialize("OrderInventoryReserved", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderInventoryReserved.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderConfirmed preserva orderId e timestamp")
        void orderConfirmedRoundTrip() {
            OrderConfirmed original = orderConfirmed();

            OrderEvent decoded = codec.deserialize("OrderConfirmed", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderConfirmed.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderShipped preserva trackingNumber e carrier")
        void orderShippedRoundTrip() {
            OrderShipped original = orderShipped();

            OrderEvent decoded = codec.deserialize("OrderShipped", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderShipped.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderDelivered preserva orderId e timestamp")
        void orderDeliveredRoundTrip() {
            OrderDelivered original = orderDelivered();

            OrderEvent decoded = codec.deserialize("OrderDelivered", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderDelivered.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderCancelled preserva reason e details")
        void orderCancelledRoundTrip() {
            OrderCancelled original = orderCancelled();

            OrderEvent decoded = codec.deserialize("OrderCancelled", codec.serialize(original));

            assertThat(decoded).isInstanceOf(OrderCancelled.class).isEqualTo(original);
        }

        @Test
        @DisplayName("OrderCancelled com details nulo continua válido após round-trip")
        void orderCancelledWithNullDetailsRoundTrip() {
            OrderCancelled original = new OrderCancelled(
                    UUID.randomUUID(),
                    OrderId.generate(),
                    OrderCancelled.CancellationReason.FRAUD_DETECTED,
                    null,
                    FIXED_INSTANT,
                    1
            );

            OrderEvent decoded = codec.deserialize("OrderCancelled", codec.serialize(original));

            assertThat(decoded).isEqualTo(original);
            assertThat(((OrderCancelled) decoded).details()).isNull();
        }
    }

    @Nested
    @DisplayName("formato do payload JSON")
    class JsonFormat {

        @Test
        @DisplayName("serializa Instant como string ISO-8601 (não como timestamp numérico)")
        void instantsAreSerializedAsIsoStrings() {
            String json = codec.serialize(orderConfirmed());

            assertThat(json).contains("\"2025-01-15T10:00:00Z\"");
            assertThat(json).doesNotContain("\"occurredAt\":17");
        }

        @Test
        @DisplayName("inclui o schemaVersion no payload para suportar upcasters")
        void payloadIncludesSchemaVersion() {
            String json = codec.serialize(orderConfirmed());

            assertThat(json).contains("\"schemaVersion\":1");
        }

        @Test
        @DisplayName("propriedades desconhecidas no JSON são ignoradas (compatibilidade aditiva)")
        void unknownPropertiesAreTolerated() {
            String original = codec.serialize(orderConfirmed());
            String withExtra = original.substring(0, original.length() - 1)
                    + ",\"futureField\":\"will-be-added-later\"}";

            OrderEvent decoded = codec.deserialize("OrderConfirmed", withExtra);

            assertThat(decoded).isInstanceOf(OrderConfirmed.class);
        }
    }

    @Nested
    @DisplayName("falhas")
    class Failures {

        @Test
        @DisplayName("deserialize com event_type desconhecido lança UnknownEventTypeException")
        void deserializeFailsForUnknownEventType() {
            assertThatThrownBy(() -> codec.deserialize("OrderTeleported", "{}"))
                    .isInstanceOf(UnknownEventTypeException.class)
                    .hasMessageContaining("OrderTeleported");
        }

        @Test
        @DisplayName("deserialize com JSON malformado lança EventSerializationException")
        void deserializeFailsForMalformedJson() {
            assertThatThrownBy(() -> codec.deserialize("OrderConfirmed", "{not-json"))
                    .isInstanceOf(EventSerializationException.class)
                    .hasMessageContaining("OrderConfirmed")
                    .hasCauseInstanceOf(Throwable.class);
        }
    }

    // ---------- fixtures ----------

    private static OrderPlaced orderPlaced() {
        return new OrderPlaced(
                UUID.randomUUID(),
                OrderId.generate(),
                CustomerId.of(UUID.randomUUID()),
                List.of(
                        new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(2), Money.of("10.00", "BRL")),
                        new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(1), Money.of("5.50", "BRL"))
                ),
                Money.of("25.50", "BRL"),
                ADDRESS,
                FIXED_INSTANT,
                1
        );
    }

    private static OrderPaymentConfirmed orderPaymentConfirmed() {
        return new OrderPaymentConfirmed(
                UUID.randomUUID(),
                OrderId.generate(),
                UUID.randomUUID(),
                FIXED_INSTANT,
                1
        );
    }

    private static OrderInventoryReserved orderInventoryReserved() {
        return new OrderInventoryReserved(
                UUID.randomUUID(),
                OrderId.generate(),
                UUID.randomUUID(),
                FIXED_INSTANT,
                1
        );
    }

    private static OrderConfirmed orderConfirmed() {
        return new OrderConfirmed(
                UUID.randomUUID(),
                OrderId.generate(),
                FIXED_INSTANT,
                1
        );
    }

    private static OrderShipped orderShipped() {
        return new OrderShipped(
                UUID.randomUUID(),
                OrderId.generate(),
                TrackingNumber.of("BR123456789"),
                "Correios",
                FIXED_INSTANT,
                1
        );
    }

    private static OrderDelivered orderDelivered() {
        return new OrderDelivered(
                UUID.randomUUID(),
                OrderId.generate(),
                FIXED_INSTANT,
                1
        );
    }

    private static OrderCancelled orderCancelled() {
        return new OrderCancelled(
                UUID.randomUUID(),
                OrderId.generate(),
                OrderCancelled.CancellationReason.CUSTOMER_REQUESTED,
                "Customer changed their mind",
                FIXED_INSTANT,
                1
        );
    }
}
