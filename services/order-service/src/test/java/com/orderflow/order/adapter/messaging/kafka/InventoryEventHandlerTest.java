package com.orderflow.order.adapter.messaging.kafka;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ConfirmOrderPaymentUseCase;
import com.orderflow.order.application.usecase.ReserveOrderInventoryUseCase;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.event.OrderConfirmed;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.event.OrderInventoryReserved;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.OrderStatus;
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

@DisplayName("InventoryEventHandler — tradução Inventory → comandos do Ordering")
class InventoryEventHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", null, "Centro",
            "São Paulo", "SP", "01000-000", "BR");

    private InMemoryOrderRepository repository;
    private InventoryEventHandler handler;
    private ConfirmOrderPaymentUseCase confirmPayment;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        confirmPayment = new ConfirmOrderPaymentUseCase(repository);
        handler = new InventoryEventHandler(
                new ReserveOrderInventoryUseCase(repository),
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
    @DisplayName("InventoryReserved registra a reserva no agregado")
    void inventoryReservedRegistersReservation() {
        UUID reservationId = UUID.randomUUID();

        StepVerifier.create(handler.handle(
                        InventoryEventHandler.INVENTORY_RESERVED,
                        inventoryReservedJson(orderId.value(), reservationId)))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(OrderInventoryReserved.class,
                e -> assertThat(e.reservationId()).isEqualTo(reservationId));
    }

    @Test
    @DisplayName("pagamento confirmado + InventoryReserved convergem em OrderConfirmed")
    void paymentThenInventoryConvergeToConfirmed() {
        confirmPayment.execute(new com.orderflow.order.application.command.ConfirmOrderPaymentCommand(
                orderId, UUID.randomUUID())).block();

        StepVerifier.create(handler.handle(
                        InventoryEventHandler.INVENTORY_RESERVED,
                        inventoryReservedJson(orderId.value(), UUID.randomUUID())))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events.getLast()).isInstanceOf(OrderConfirmed.class);
        Order finalState = Order.loadFromHistory(events, CLOCK);
        assertThat(finalState.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("InventoryReserved reentregue é idempotente")
    void inventoryReservedIsIdempotent() {
        byte[] payload = inventoryReservedJson(orderId.value(), UUID.randomUUID());
        handler.handle(InventoryEventHandler.INVENTORY_RESERVED, payload).block();
        int afterFirst = repository.events(orderId).size();

        StepVerifier.create(handler.handle(InventoryEventHandler.INVENTORY_RESERVED, payload))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(afterFirst);
    }

    @Test
    @DisplayName("InventoryOutOfStock cancela o pedido com motivo OUT_OF_STOCK (compensação)")
    void inventoryOutOfStockCancelsOrder() {
        StepVerifier.create(handler.handle(
                        InventoryEventHandler.INVENTORY_OUT_OF_STOCK,
                        inventoryOutOfStockJson(orderId.value())))
                .verifyComplete();

        assertThat(repository.events(orderId).getLast())
                .isInstanceOfSatisfying(OrderCancelled.class,
                        e -> assertThat(e.reason()).isEqualTo(OrderCancelled.CancellationReason.OUT_OF_STOCK));
    }

    @Test
    @DisplayName("tipo de evento desconhecido é ignorado")
    void unknownEventTypeIsIgnored() {
        StepVerifier.create(handler.handle("InventoryReleased",
                        inventoryReservedJson(orderId.value(), UUID.randomUUID())))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("payload malformado falha como InboundEventDeserializationException (poison pill → DLQ)")
    void malformedPayloadFails() {
        StepVerifier.create(handler.handle(
                        InventoryEventHandler.INVENTORY_RESERVED,
                        "{not-json".getBytes(StandardCharsets.UTF_8)))
                .expectError(InboundEventDeserializationException.class)
                .verify();
    }

    private static byte[] inventoryReservedJson(UUID orderId, UUID reservationId) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "reservationId": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z"
                }
                """.formatted(UUID.randomUUID(), orderId, reservationId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] inventoryOutOfStockJson(UUID orderId) {
        return """
                {
                  "eventId": "%s",
                  "orderId": "%s",
                  "occurredAt": "2025-01-15T10:00:00Z"
                }
                """.formatted(UUID.randomUUID(), orderId).getBytes(StandardCharsets.UTF_8);
    }
}
