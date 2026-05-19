package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.application.support.ConcurrencyConflictRetry;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Caso de uso de cancelamento. Pode ser disparado tanto por um comando
 * direto do cliente quanto pelas etapas de compensação da saga
 * ({@code PaymentFailed}, {@code InventoryOutOfStock}). A razão do
 * cancelamento distingue a origem, conforme o vocabulário descrito em
 * {@code docs/event-sourcing.md}.
 */
public final class CancelOrderUseCase {

    private final OrderRepository repository;

    public CancelOrderUseCase(OrderRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Mono<Void> execute(CancelOrderCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.findById(command.orderId())
                .flatMap(order -> applyAndSave(order, command))
                .retryWhen(ConcurrencyConflictRetry.defaultPolicy());
    }

    private Mono<Void> applyAndSave(Order order, CancelOrderCommand command) {
        long expectedVersion = order.version();
        order.cancel(command.reason(), command.details());
        return repository.save(order, expectedVersion);
    }
}
