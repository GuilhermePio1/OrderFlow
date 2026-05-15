package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.PlaceOrderCommand;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

/**
 * Caso de uso de criação de pedido. Constrói o agregado a partir do
 * comando, dispara {@code OrderPlaced} e persiste eventos + outbox numa
 * única transação. Não há retentativa: a criação carrega
 * {@code expectedVersion=0} e qualquer colisão indica reuso indevido
 * de {@link OrderId}, condição que deve emergir como erro.
 */
public final class PlaceOrderUseCase {

    private final OrderRepository repository;
    private final Clock clock;

    public PlaceOrderUseCase(OrderRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Mono<OrderId> execute(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        return Mono.fromCallable(() -> {
                    OrderId orderId = OrderId.generate();
                    return Order.place(
                            orderId,
                            command.customerId(),
                            command.items(),
                            command.shippingAddress(),
                            clock
                    );
                })
                .flatMap(order -> repository.save(order, 0L).thenReturn(order.id()));
    }
}
