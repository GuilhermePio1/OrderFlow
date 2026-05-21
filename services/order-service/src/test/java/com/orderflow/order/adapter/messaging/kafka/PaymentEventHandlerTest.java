package com.orderflow.order.adapter.messaging.kafka;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ConfirmOrderPaymentUseCase;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.event.OrderPaymentConfirmed;
import com.orderflow.order.domain.event.OrderPlaced;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentEventHandler — tradução Payment → comandos do Ordering")
class PaymentEventHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", null, "Centro",
            "São Paulo", "SP", "01000-000", "BR");

    private InMemoryOrderRepository repository;
    private PaymentEventHandler handler;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        handler = new PaymentEventHandler(
                new ConfirmOrderPaymentUseCase(repository),
                new CancelOrderUseCase(repository),
                InboundEventDeserializer.withDefaultObjectMapper());
        orderId = OrderId.generate();
        Order placed = Order.place(
                orderId,
                CustomerId.of(UUID.randomUUID()),
                List.of(new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(1), Money.of("9.90", "BRL"))),
                ADDRESS,
                CLOCK);
        repository.seed(placed);
    }

    @Test
    @DisplayName("PaymentAuthorized confirma o pagamento no agregado")
    void paymentAuthorizedConfirmsPayment() {
        UUID paymentId = UUID.randomUUID();
        byte[] payload = paymentAuthorizedJson(orderId.value(), paymentId);

        StepVerifier.create(handler.handle(PaymentEventHandler.PAYMENT_AUTHORIZED, payload))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst()).isInstanceOf(OrderPlaced.class);
        assertThat(events.get(1)).isInstanceOfSatisfying(OrderPaymentConfirmed.class,
                e -> assertThat(e.paymentId()).isEqualTo(paymentId));
    }

    @Test
    @DisplayName("PaymentAuthorized reentregue é idempotente — não emite novos eventos")
    void paymentAuthorizedIsIdempotent() {
        byte[] payload = paymentAuthorizedJson(orderId.value(), UUID.randomUUID());
        handler.handle(PaymentEventHandler.PAYMENT_AUTHORIZED, payload).block();
        int afterFirst = repository.events(orderId).size();

        StepVerifier.create(handler.handle(PaymentEventHandler.PAYMENT_AUTHORIZED, payload))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(afterFirst);
    }

    @Test
    @DisplayName("PaymentFailed cancela o pedido com motivo PAYMENT_FAILED (compensação)")
    void paymentFailedCancelsOrder() {
        byte[] payload = paymentFailedJson(orderId.value(), "card_declined");

        StepVerifier.create(handler.handle(PaymentEventHandler.PAYMENT_FAILED, payload))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events.getLast()).isInstanceOfSatisfying(OrderCancelled.class, e -> {
            assertThat(e.reason()).isEqualTo(OrderCancelled.CancellationReason.PAYMENT_FAILED);
            assertThat(e.details()).isEqualTo("card_declined");
        });
    }

    @Test
    @DisplayName("PaymentAuthorized num pedido já cancelado é tratado como reentrega (sem erro)")
    void paymentAuthorizedOnCancelledOrderIsNoOp() {
        Order order = Order.loadFromHistory(repository.events(orderId), CLOCK);
        order.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, "test");
        repository.save(order, 1L).block();
        int afterCancel = repository.events(orderId).size();

        StepVerifier.create(handler.handle(
                        PaymentEventHandler.PAYMENT_AUTHORIZED,
                        paymentAuthorizedJson(orderId.value(), UUID.randomUUID())))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(afterCancel);
    }

    @Test
    @DisplayName("tipo de evento desconhecido é ignorado")
    void unknownEventTypeIsIgnored() {
        StepVerifier.create(handler.handle("PaymentRefunded",
                        paymentAuthorizedJson(orderId.value(), UUID.randomUUID())))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("mensagem sem header de tipo é ignorada")
    void missingEventTypeIsIgnored() {
        StepVerifier.create(handler.handle(null, new byte[0]))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("payload malformado falha como InboundEventDeserializationException (poison pill → DLQ)")
    void malformedPayloadFails() {
        StepVerifier.create(handler.handle(
                        PaymentEventHandler.PAYMENT_AUTHORIZED,
                        "{not-json".getBytes(StandardCharsets.UTF_8)))
                .expectError(InboundEventDeserializationException.class)
                .verify();
    }

    private static byte[] paymentAuthorizedJson(UUID orderId, UUID paymentId) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "paymentId": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z"
                }
                """.formatted(UUID.randomUUID(), orderId, paymentId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] paymentFailedJson(UUID orderId, String reason) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "reason": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z"
                }
                """.formatted(UUID.randomUUID(), orderId, reason).getBytes(StandardCharsets.UTF_8);
    }
}
