package com.orderflow.order.application.usecase;

import com.orderflow.order.application.command.ConfirmOrderPaymentCommand;
import com.orderflow.order.application.support.ConcurrencyConflictRetry;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Caso de uso disparado pelo consumer de {@code PaymentAuthorized}. Reidrata
 * o agregado, executa o comando de domínio e persiste os eventos resultantes
 * (potencialmente {@code OrderPaymentConfirmed} + {@code OrderConfirmed} se
 * o estoque já estava reservado). O agregado é idempotente: reentregas do
 * mesmo evento de pagamento não geram efeitos duplicados.
 */
public final class ConfirmOrderPaymentUseCase {

    private final OrderRepository repository;

    public ConfirmOrderPaymentUseCase(OrderRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Mono<Void> execute(ConfirmOrderPaymentCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.findById(command.orderId())
                .flatMap(order -> applyAndSave(order, command))
                .retryWhen(ConcurrencyConflictRetry.defaultPolicy());
    }

    private Mono<Void> applyAndSave(Order order, ConfirmOrderPaymentCommand command) {
        long expectedVersion = order.version();
        order.confirmPayment(command.paymentId());
        if (!order.hasUncommittedEvents()) {
            return Mono.empty();
        }
        return repository.save(order, expectedVersion);
    }
}
