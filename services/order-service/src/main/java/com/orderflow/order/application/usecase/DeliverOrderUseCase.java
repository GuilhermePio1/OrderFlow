package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.DeliverOrderCommand;
import com.orderflow.order.application.support.ConcurrencyConflictRetry;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.Objects;

public final class DeliverOrderUseCase {

    private final OrderRepository repository;

    public DeliverOrderUseCase(OrderRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Mono<Void> execute(DeliverOrderCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.findById(command.orderId())
                .flatMap(this::applyAndSave)
                .retryWhen(ConcurrencyConflictRetry.defaultPolicy());
    }

    private Mono<Void> applyAndSave(Order order) {
        long expectedVersion = order.version();
        order.deliver();
        return repository.save(order, expectedVersion);
    }
}
