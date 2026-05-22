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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
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
 * Autorização fine-grained (ABAC, {@code docs/security.md}): a identidade do
 * cliente vem do JWT, não do payload — um cliente não pode criar, ver ou
 * cancelar pedidos de outro. Chamadores com papel privilegiado
 * ({@code ROLE_ORDER_ADMIN}) podem agir em nome de terceiros. Cada ação
 * significativa gera um registro de auditoria.
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
    private final OrderAuditLogger auditLogger;

    OrderController(
            PlaceOrderUseCase placeOrderUseCase,
            CancelOrderUseCase cancelOrderUseCase,
            OrderRepository orderRepository,
            OrderAuditLogger auditLogger
    ) {
        this.placeOrderUseCase = Objects.requireNonNull(placeOrderUseCase, "placeOrderUseCase");
        this.cancelOrderUseCase = Objects.requireNonNull(cancelOrderUseCase, "cancelOrderUseCase");
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    Mono<ResponseEntity<PlaceOrderResponse>> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            ServerWebExchange exchange
    ) {
        String sourceIp = clientIp(exchange);
        return CallerContext.currentCaller().flatMap(caller -> {
            if (!caller.canActFor(request.customerId())) {
                auditLogger.accessDenied(caller.id(), "PLACE_ORDER",
                        "customer:" + request.customerId(), sourceIp);
                return Mono.error(new AccessDeniedException(
                        "Cannot place an order on behalf of another customer"));
            }
            return placeOrderUseCase.execute(OrderRestMapper.toCommand(request))
                    .doOnNext(orderId -> auditLogger.orderPlaced(caller.id(), orderId.value(), sourceIp))
                    .map(orderId -> ResponseEntity
                            .accepted()
                            .location(URI.create("/api/orders/" + orderId.value()))
                            .body(new PlaceOrderResponse(orderId.value())));
        });
    }

    @GetMapping("/{orderId}")
    Mono<OrderResponse> getOrder(
            @PathVariable("orderId") String orderId,
            ServerWebExchange exchange
    ) {
        OrderId id = OrderId.of(orderId);
        String sourceIp = clientIp(exchange);
        return CallerContext.currentCaller().flatMap(caller ->
                orderRepository.findById(id).flatMap(order -> {
                    if (!caller.canActFor(order.customerId().value())) {
                        auditLogger.accessDenied(caller.id(), "VIEW_ORDER",
                                "order:" + order.id().value(), sourceIp);
                        return Mono.error(new AccessDeniedException(
                                "Order belongs to another customer"));
                    }
                    return Mono.just(OrderRestMapper.toResponse(order));
                }));
    }

    @PostMapping("/{orderId}/cancellation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Mono<Void> cancelOrder(
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
            ServerWebExchange exchange
    ) {
        OrderId id = OrderId.of(orderId);
        String details = request == null ? null : request.details();
        String sourceIp = clientIp(exchange);
        return CallerContext.currentCaller().flatMap(caller ->
                orderRepository.findById(id).flatMap(order -> {
                    if (!caller.canActFor(order.customerId().value())) {
                        auditLogger.accessDenied(caller.id(), "CANCEL_ORDER",
                                "order:" + order.id().value(), sourceIp);
                        return Mono.error(new AccessDeniedException(
                                "Order belongs to another customer"));
                    }
                    return cancelOrderUseCase.execute(new CancelOrderCommand(
                            id,
                            OrderCancelled.CancellationReason.CUSTOMER_REQUESTED,
                            details))
                            .doOnSuccess(ignored -> auditLogger.orderCancellationRequested(
                                    caller.id(), order.id().value(), sourceIp));
                }));
    }

    private static String clientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
