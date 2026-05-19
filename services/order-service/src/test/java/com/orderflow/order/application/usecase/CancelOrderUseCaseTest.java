package com.orderflow.order.application.usecase;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CancelOrderUseCase")
class CancelOrderUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", null, "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private InMemoryOrderRepository repository;
    private CancelOrderUseCase useCase;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        useCase = new CancelOrderUseCase(repository);
        orderId = OrderId.generate();
        Order placed = Order.place(
                orderId,
                CustomerId.of(UUID.randomUUID()),
                List.of(new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(1), Money.of("9.90", "BRL"))),
                ADDRESS,
                CLOCK
        );
        repository.seed(placed);
    }

    @Test
    @DisplayName("emite OrderCancelled preservando razão e detalhes")
    void recordsCancellation() {
        CancelOrderCommand command = new CancelOrderCommand(
                orderId, OrderCancelled.CancellationReason.PAYMENT_FAILED, "card declined");

        StepVerifier.create(useCase.execute(command)).verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events).hasSize(2);
        assertThat(events.get(1)).isInstanceOfSatisfying(OrderCancelled.class, cancelled -> {
            assertThat(cancelled.reason()).isEqualTo(OrderCancelled.CancellationReason.PAYMENT_FAILED);
            assertThat(cancelled.details()).isEqualTo("card declined");
        });
    }

    @Test
    @DisplayName("rejeita cancelamento de pedido em estado terminal")
    void rejectsCancellationOfTerminalOrder() {
        useCase.execute(new CancelOrderCommand(
                orderId, OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, null)).block();

        StepVerifier.create(useCase.execute(new CancelOrderCommand(
                orderId, OrderCancelled.CancellationReason.SYSTEM, null)))
                .expectError(InvalidOrderStateTransitionException.class)
                .verify();
    }
}
