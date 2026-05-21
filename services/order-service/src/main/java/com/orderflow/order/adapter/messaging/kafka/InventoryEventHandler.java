package com.orderflow.order.adapter.messaging.kafka;

import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.application.command.ReserveOrderInventoryCommand;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ReserveOrderInventoryUseCase;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.model.valueobject.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Traduz os eventos do contexto de Inventory para os comandos de domínio do
 * Ordering, fechando o lado feliz e a compensação da saga coreografada
 * ({@code docs/architecture.md}):
 *
 * <ul>
 *   <li>{@code InventoryReserved} → registra a reserva no agregado;</li>
 *   <li>{@code InventoryOutOfStock} → cancela o pedido (compensação).</li>
 * </ul>
 *
 * Idempotência idêntica à do consumer de pagamentos: o agregado absorve
 * reentregas e transições já liquidadas não são tratadas como erro
 * ({@code docs/event-sourcing.md}).
 */
public final class InventoryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventHandler.class);

    static final String INVENTORY_RESERVED = "InventoryReserved";
    static final String INVENTORY_OUT_OF_STOCK = "InventoryOutOfStock";

    private final ReserveOrderInventoryUseCase reserveOrderInventory;
    private final CancelOrderUseCase cancelOrder;
    private final InboundEventDeserializer deserializer;

    public InventoryEventHandler(
            ReserveOrderInventoryUseCase reserveOrderInventory,
            CancelOrderUseCase cancelOrder,
            InboundEventDeserializer deserializer
    ) {
        this.reserveOrderInventory = Objects.requireNonNull(reserveOrderInventory, "reserveOrderInventory");
        this.cancelOrder = Objects.requireNonNull(cancelOrder, "cancelOrder");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
    }

    public Mono<Void> handle(String eventType, byte[] payload) {
        if (eventType == null) {
            log.warn("Mensagem do tópico de estoque sem header de tipo; ignorando");
            return Mono.empty();
        }
        return switch (eventType) {
            case INVENTORY_RESERVED -> reserve(payload);
            case INVENTORY_OUT_OF_STOCK -> compensate(payload);
            default -> {
                log.debug("Evento de estoque '{}' não é consumido pelo Ordering; ignorando", eventType);
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> reserve(byte[] payload) {
        return Mono.fromCallable(() -> deserializer.deserialize(payload, InventoryReservedEvent.class))
                .flatMap(event -> reserveOrderInventory.execute(
                        new ReserveOrderInventoryCommand(OrderId.of(event.orderId()), event.reservationId()))
                        .onErrorResume(InvalidOrderStateTransitionException.class,
                                e -> alreadySettled(event.orderId(), INVENTORY_RESERVED, e)));
    }

    private Mono<Void> compensate(byte[] payload) {
        return Mono.fromCallable(() -> deserializer.deserialize(payload, InventoryOutOfStockEvent.class))
                .flatMap(event -> cancelOrder.execute(new CancelOrderCommand(
                        OrderId.of(event.orderId()),
                        OrderCancelled.CancellationReason.OUT_OF_STOCK,
                        event.details()))
                        .onErrorResume(InvalidOrderStateTransitionException.class,
                                e -> alreadySettled(event.orderId(), INVENTORY_OUT_OF_STOCK, e)));
    }

    private static Mono<Void> alreadySettled(UUID orderId, String eventType, InvalidOrderStateTransitionException e) {
        log.warn("Evento {} para o pedido {} não é aplicável no estado {}; tratando como reentrega idempotente",
                eventType, orderId, e.currentStatus());
        return Mono.empty();
    }
}
