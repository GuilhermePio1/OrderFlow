package com.orderflow.order.application.usecase;

import com.orderflow.order.application.InMemoryOrderRepository;
import com.orderflow.order.application.command.PlaceOrderCommand;
import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.event.OrderPlaced;
import com.orderflow.order.domain.exception.EmptyOrderException;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.Address;
import com.orderflow.order.domain.model.valueobject.CustomerId;
import com.orderflow.order.domain.model.valueobject.Money;
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

@DisplayName("PlaceOrderUseCase")
class PlaceOrderUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", "Apto 12", "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private InMemoryOrderRepository repository;
    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRepository(CLOCK);
        useCase = new PlaceOrderUseCase(repository, CLOCK);
    }

    @Test
    @DisplayName("persiste OrderPlaced e devolve o orderId gerado")
    void persistsOrderPlacedAndReturnsId() {
        PlaceOrderCommand command = new PlaceOrderCommand(
                CustomerId.of(UUID.randomUUID()),
                List.of(new OrderItem(ProductId.of(UUID.randomUUID()), Quantity.of(2), Money.of("10.00", "BRL"))),
                ADDRESS
        );

        var verifier = StepVerifier.create(useCase.execute(command));

        verifier.assertNext(orderId -> {
            List<OrderEvent> events = repository.events(orderId);
            assertThat(events).singleElement().isInstanceOf(OrderPlaced.class);
            OrderPlaced placed = (OrderPlaced) events.getFirst();
            assertThat(placed.orderId()).isEqualTo(orderId);
            assertThat(placed.customerId()).isEqualTo(command.customerId());
            assertThat(placed.items()).isEqualTo(command.items());
            assertThat(placed.totalAmount()).isEqualTo(Money.of("20.00", "BRL"));
        }).verifyComplete();
    }

    @Test
    @DisplayName("propaga EmptyOrderException quando o comando não tem itens")
    void rejectsEmptyItems() {
        PlaceOrderCommand command = new PlaceOrderCommand(
                CustomerId.of(UUID.randomUUID()),
                List.of(),
                ADDRESS
        );

        StepVerifier.create(useCase.execute(command))
                .expectError(EmptyOrderException.class)
                .verify();
        assertThat(repository.saveCount()).isZero();
    }
}
