package com.orderflow.order.application.usecase;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.command.DeliverOrderCommand;
import com.orderflow.order.domain.event.OrderDelivered;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
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

@DisplayName("DeliverOrderUseCase")
class DeliverOrderUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", null, "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private InMemoryOrderRepository repository;
    private DeliverOrderUseCase useCase;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        useCase = new DeliverOrderUseCase(repository);
        orderId = OrderId.generate();
    }

    private Order placedOrder() {
        return Order.place(
                orderId,
                CustomerId.of(UUID.randomUUID()),
                List.of(new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(1), Money.of("9.90", "BRL"))),
                ADDRESS,
                CLOCK
        );
    }

    private void seedShippedOrder() {
        Order order = placedOrder();
        order.confirmPayment(UUID.randomUUID());
        order.reserveInventory(UUID.randomUUID());
        order.ship(TrackingNumber.of("BR123456789"), "Correios");
        repository.seed(order);
    }

    @Test
    @DisplayName("emite OrderDelivered para um pedido SHIPPED")
    void recordsDelivery() {
        seedShippedOrder();

        StepVerifier.create(useCase.execute(new DeliverOrderCommand(orderId)))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events.getLast()).isInstanceOf(OrderDelivered.class);
    }

    @Test
    @DisplayName("rejeita entrega de pedido que ainda não foi enviado")
    void rejectsDeliveryOfNonShippedOrder() {
        repository.seed(placedOrder());

        StepVerifier.create(useCase.execute(new DeliverOrderCommand(orderId)))
                .expectError(InvalidOrderStateTransitionException.class)
                .verify();
        assertThat(repository.events(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("retenta automaticamente em ConcurrencyConflictException")
    void retriesOnConcurrencyConflict() {
        seedShippedOrder();
        int eventsBefore = repository.events(orderId).size();
        repository.injectConcurrencyConflicts(2);

        StepVerifier.create(useCase.execute(new DeliverOrderCommand(orderId)))
                .verifyComplete();

        assertThat(repository.saveCount()).isGreaterThanOrEqualTo(3);
        assertThat(repository.events(orderId)).hasSize(eventsBefore + 1);
    }

    @Test
    @DisplayName("propaga OrderNotFoundException quando o pedido não existe")
    void propagatesNotFound() {
        StepVerifier.create(useCase.execute(new DeliverOrderCommand(OrderId.generate())))
                .expectError(OrderNotFoundException.class)
                .verify();
    }
}
