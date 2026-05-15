package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.ReserveOrderInventoryCommand;
import com.orderflow.order.application.support.ConcurrencyConflictRetry;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Caso de uso disparado pelo consumer de {@code InventoryReserved}. Reidrata
 * o agregado, registra a reserva e — quando pagamento e estoque convergem —
 * o próprio agregado emite {@code OrderConfirmed}, fechando o lado feliz
 * da saga descrito em {@code docs/architecture.md}.
 */
public final class ReserveOrderInventoryUseCase {

    private final OrderRepository repository;

    public ReserveOrderInventoryUseCase(OrderRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Mono<Void> execute(ReserveOrderInventoryCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.findById(command.orderId())
                .flatMap(order -> applyAndSave(order, command))
                .retryWhen(ConcurrencyConflictRetry.defaultPolicy());
    }

    private Mono<Void> applyAndSave(Order order, ReserveOrderInventoryCommand command) {
        long expectedVersion = order.version();
        order.reserveInventory(command.reservationId());
        if (!order.hasUncommittedEvents()) {
            return Mono.empty();
        }
        return repository.save(order, expectedVersion);
    }
}
