package com.orderflow.order.application.usecase;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.command.ConfirmOrderPaymentCommand;
import com.orderflow.order.domain.event.OrderConfirmed;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.event.OrderInventoryReserved;
import com.orderflow.order.domain.event.OrderPaymentConfirmed;
import com.orderflow.order.domain.event.OrderPlaced;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.Address;
import com.orderflow.order.domain.model.valueobject.CustomerId;
import com.orderflow.order.domain.model.valueobject.Money;
import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.model.valueobject.ProductId;
import com.orderflow.order.domain.model.valueobject.Quantity;
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

@DisplayName("ConfirmOrderPaymentUseCase")
class ConfirmOrderPaymentUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", null, "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private InMemoryOrderRepository repository;
    private ConfirmOrderPaymentUseCase useCase;
    private OrderId orderId;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        useCase = new ConfirmOrderPaymentUseCase(repository);
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
    @DisplayName("registra OrderPaymentConfirmed para um pedido PLACED")
    void recordsPaymentConfirmation() {
        UUID paymentId = UUID.randomUUID();

        StepVerifier.create(useCase.execute(new ConfirmOrderPaymentCommand(orderId, paymentId)))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(OrderPlaced.class);
        assertThat(events.get(1)).isInstanceOfSatisfying(OrderPaymentConfirmed.class,
                e -> assertThat(e.paymentId()).isEqualTo(paymentId));
    }

    @Test
    @DisplayName("é idempotente — pagamento já confirmado não emite novos eventos")
    void idempotentOnRepeatedPayment() {
        UUID paymentId = UUID.randomUUID();
        useCase.execute(new ConfirmOrderPaymentCommand(orderId, paymentId)).block();
        int eventsAfterFirstCall = repository.events(orderId).size();

        StepVerifier.create(useCase.execute(new ConfirmOrderPaymentCommand(orderId, paymentId)))
                .verifyComplete();

        assertThat(repository.events(orderId)).hasSize(eventsAfterFirstCall);
    }

    @Test
    @DisplayName("emite OrderConfirmed quando estoque já estava reservado")
    void emitsOrderConfirmedWhenInventoryAlreadyReserved() {
        Order order = Order.loadFromHistory(repository.events(orderId), CLOCK);
        order.reserveInventory(UUID.randomUUID());
        repository.save(order, 1L).block();

        StepVerifier.create(useCase.execute(new ConfirmOrderPaymentCommand(orderId, UUID.randomUUID())))
                .verifyComplete();

        List<OrderEvent> events = repository.events(orderId);
        assertThat(events).hasSize(4);
        assertThat(events.get(1)).isInstanceOf(OrderInventoryReserved.class);
        assertThat(events.get(2)).isInstanceOf(OrderPaymentConfirmed.class);
        assertThat(events.get(3)).isInstanceOf(OrderConfirmed.class);
    }

    @Test
    @DisplayName("retenta automaticamente em ConcurrencyConflictException")
    void retriesOnConcurrencyConflict() {
        repository.injectConcurrencyConflicts(2);

        StepVerifier.create(useCase.execute(new ConfirmOrderPaymentCommand(orderId, UUID.randomUUID())))
                .verifyComplete();

        assertThat(repository.saveCount()).isGreaterThanOrEqualTo(3);
        assertThat(repository.events(orderId)).hasSize(2);
    }

    @Test
    @DisplayName("propaga OrderNotFoundException quando o pedido não existe")
    void propagatesNotFound() {
        StepVerifier.create(useCase.execute(new ConfirmOrderPaymentCommand(OrderId.generate(), UUID.randomUUID())))
                .expectError(OrderNotFoundException.class)
                .verify();
    }
}
