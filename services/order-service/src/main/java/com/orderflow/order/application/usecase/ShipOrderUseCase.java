package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.ShipOrderCommand;
import com.orderflow.order.application.support.ConcurrencyConflictRetry;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.Objects;

public final class ShipOrderUseCase {

    private final OrderRepository repository;

    public ShipOrderUseCase(OrderRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> execute(ShipOrderCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.findById(command.orderId())
                .flatMap(order -> applyAndSave(order, command))
                .retryWhen(ConcurrencyConflictRetry.defaultPolicy());
    }

    private Mono<Void> applyAndSave(Order order, ShipOrderCommand command) {
        long expectedVersion = order.version();
        order.ship(command.trackingNumber(), command.carrier());
        return repository.save(order, expectedVersion);
    }
}
