package com.orderflow.order.adapter.rest;

import com.orderflow.order.adapter.rest.dto.CancelOrderRequest;
import com.orderflow.order.adapter.rest.dto.OrderResponse;
import com.orderflow.order.adapter.rest.dto.PlaceOrderRequest;
import com.orderflow.order.adapter.rest.dto.PlaceOrderResponse;
import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.PlaceOrderUseCase;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.repository.OrderRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Objects;

/**
 * Adapter de borda (WebFlux) do contexto Ordering. Escopo conforme
 * {@code docs/roadmap.md} (Fase 1 — "criação, consulta e cancelamento"):
 *
 * <ul>
 *   <li>{@code POST /api/orders} — cria o pedido; responde
 *       {@code 202 Accepted} pois o restante da saga (pagamento, estoque)
 *       é assíncrono via eventos ({@code docs/architecture.md}).</li>
 *   <li>{@code GET /api/orders/{id}} — reidrata o agregado a partir do
 *       event store (leitura do lado de escrita; o tráfego de leitura
 *       denormalizado é servido pelo Query Service via CQRS).</li>
 *   <li>{@code POST /api/orders/{id}/cancellation} — cancelamento
 *       solicitado pelo cliente.</li>
 * </ul>
 *
 * As demais transições (pagamento confirmado, estoque reservado, envio,
 * entrega) não são expostas como REST: chegam por eventos Kafka na
 * coreografia descrita em {@code docs/architecture.md}.
 */
@RestController
@RequestMapping(path = "/api/orders", produces = MediaType.APPLICATION_JSON_VALUE)
class OrderController {

    private final PlaceOrderUseCase placeOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final OrderRepository orderRepository;

    OrderController(
            PlaceOrderUseCase placeOrderUseCase,
            CancelOrderUseCase cancelOrderUseCase,
            OrderRepository orderRepository
    ) {
        this.placeOrderUseCase = Objects.requireNonNull(placeOrderUseCase, "placeOrderUseCase");
        this.cancelOrderUseCase = Objects.requireNonNull(cancelOrderUseCase, "cancelOrderUseCase");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<PlaceOrderResponse>> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return placeOrderUseCase.execute(OrderRestMapper.toCommand(request))
                .map(orderId -> ResponseEntity
                        .accepted()
                        .location(URI.create("/api/orders/" + orderId.value()))
                        .body(new PlaceOrderResponse(orderId.value())));
    }

    @GetMapping("/{orderId}")
    Mono<OrderResponse> getOrder(@PathVariable("orderId") String orderId) {
        return orderRepository.findById(OrderId.of(orderId))
                .map(OrderRestMapper::toResponse);
    }

    @PostMapping("/{orderId}/cancellation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> cancelOrder(
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request
    ) {
        String details = request == null ? null : request.details();
        return cancelOrderUseCase.execute(new CancelOrderCommand(
                OrderId.of(orderId),
                OrderCancelled.CancellationReason.CUSTOMER_REQUESTED,
                details));
    }
}
