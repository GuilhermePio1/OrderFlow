package com.orderflow.order.adapter.rest;

import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.application.command.PlaceOrderCommand;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.PlaceOrderUseCase;
import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.OrderStatus;
import com.orderflow.order.domain.model.valueobject.*;
import com.orderflow.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OrderController")
class OrderControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", "Apto 12", "Centro",
            "São Paulo", "SP", "01000-000", "BR");

    private PlaceOrderUseCase placeOrderUseCase;
    private CancelOrderUseCase cancelOrderUseCase;
    private OrderRepository orderRepository;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        placeOrderUseCase = mock(PlaceOrderUseCase.class);
        cancelOrderUseCase = mock(CancelOrderUseCase.class);
        orderRepository = mock(OrderRepository.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        client = WebTestClient
                .bindToController(new OrderController(
                        placeOrderUseCase, cancelOrderUseCase, orderRepository))
                .controllerAdvice(new OrderRestExceptionHandler())
                .validator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /api/orders responde 202 com Location e orderId")
    void placeOrderReturnsAccepted() {
        OrderId orderId = OrderId.generate();
        when(placeOrderUseCase.execute(any(PlaceOrderCommand.class)))
                .thenReturn(Mono.just(orderId));

        client.post().uri("/api/orders")
                .header("Content-Type", "application/json")
                .bodyValue(validPlaceBody())
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/orders/" + orderId.value())
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(orderId.value().toString());
    }

    @Test
    @DisplayName("POST /api/orders sem itens responde 400 com erros de validação")
    void placeOrderRejectsEmptyItems() {
        String body = """
                {
                  "customerId": "%s",
                  "items": [],
                  "currency": "BRL",
                  "shippingAddress": %s
                }
                """.formatted(UUID.randomUUID(), addressJson());

        client.post().uri("/api/orders")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Validation error")
                .jsonPath("$.errors.items").exists();
    }

    @Test
    @DisplayName("POST /api/orders propaga conflito de concorrência como 409")
    void placeOrderMapsConcurrencyConflict() {
        when(placeOrderUseCase.execute(any(PlaceOrderCommand.class)))
                .thenReturn(Mono.error(new ConcurrencyConflictException(
                        OrderId.generate(), 0L, 1L)));

        client.post().uri("/api/orders")
                .header("Content-Type", "application/json")
                .bodyValue(validPlaceBody())
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("GET /api/orders/{id} responde 200 com a visão do agregado")
    void getOrderReturnsView() {
        Order order = Order.place(
                OrderId.generate(),
                CustomerId.of(UUID.randomUUID()),
                List.of(new OrderItem(
                        ProductId.of(UUID.randomUUID()),
                        Quantity.of(2),
                        Money.of("10.00", "BRL"))),
                ADDRESS,
                CLOCK);
        when(orderRepository.findById(any(OrderId.class))).thenReturn(Mono.just(order));

        client.get().uri("/api/orders/" + order.id().value())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(order.id().value().toString())
                .jsonPath("$.status").isEqualTo("PLACED")
                .jsonPath("$.totalAmount.amount").isEqualTo(20.00)
                .jsonPath("$.totalAmount.currency").isEqualTo("BRL")
                .jsonPath("$.items.length()").isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/orders/{id} responde 404 quando o pedido não existe")
    void getOrderNotFound() {
        OrderId orderId = OrderId.generate();
        when(orderRepository.findById(any(OrderId.class)))
                .thenReturn(Mono.error(new OrderNotFoundException(orderId)));

        client.get().uri("/api/orders/" + orderId.value())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /api/orders/{id} com UUID inválido responde 400")
    void getOrderRejectsMalformedId() {
        client.get().uri("/api/orders/not-a-uuid")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("POST /api/orders/{id}/cancellation responde 202")
    void cancelOrderReturnsAccepted() {
        when(cancelOrderUseCase.execute(any(CancelOrderCommand.class)))
                .thenReturn(Mono.empty());

        client.post().uri("/api/orders/" + UUID.randomUUID() + "/cancellation")
                .header("Content-Type", "application/json")
                .bodyValue("{\"details\":\"changed my mind\"}")
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    @DisplayName("POST cancellation mapeia transição inválida como 409")
    void cancelOrderMapsInvalidState() {
        when(cancelOrderUseCase.execute(any(CancelOrderCommand.class)))
                .thenReturn(Mono.error(new InvalidOrderStateTransitionException(
                        OrderStatus.DELIVERED, "cancel")));

        client.post().uri("/api/orders/" + UUID.randomUUID() + "/cancellation")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    private static String validPlaceBody() {
        return """
                {
                  "customerId": "%s",
                  "items": [
                    { "productId": "%s", "quantity": 2, "unitPrice": 10.00 }
                  ],
                  "currency": "BRL",
                  "shippingAddress": %s
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), addressJson());
    }

    private static String addressJson() {
        return """
                {
                  "street": "Rua das Flores",
                  "number": "100",
                  "complement": "Apto 12",
                  "neighborhood": "Centro",
                  "city": "São Paulo",
                  "state": "SP",
                  "postalCode": "01000-000",
                  "country": "BR"
                }
                """;
    }
}
