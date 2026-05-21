package com.orderflow.order.adapter.messaging.kafka;

import com.orderflow.order.application.command.CancelOrderCommand;
import com.orderflow.order.application.command.ConfirmOrderPaymentCommand;
import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ConfirmOrderPaymentUseCase;
import com.orderflow.order.domain.event.OrderCancelled;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.model.valueobject.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Traduz os eventos do contexto de Payment para os comandos de domínio do
 * Ordering, fechando o lado feliz e a compensação da saga coreografada
 * ({@code docs/architecture.md}):
 *
 * <ul>
 *   <li>{@code PaymentAuthorized} → confirma o pagamento no agregado;</li>
 *   <li>{@code PaymentFailed} → cancela o pedido (compensação).</li>
 * </ul>
 *
 * Idempotência (entrega "at least once", {@code docs/event-sourcing.md}): a
 * confirmação é naturalmente idempotente no agregado; reentregas não geram
 * eventos novos. Uma transição que o agregado rejeita por já estar num
 * estado posterior ({@link InvalidOrderStateTransitionException}) é tratada
 * como reentrega já liquidada — não como erro — evitando poison pill na DLQ.
 */
public final class PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);

    static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    static final String PAYMENT_FAILED = "PaymentFailed";

    private final ConfirmOrderPaymentUseCase confirmOrderPayment;
    private final CancelOrderUseCase cancelOrder;
    private final InboundEventDeserializer deserializer;

    public PaymentEventHandler(
            ConfirmOrderPaymentUseCase confirmOrderPayment,
            CancelOrderUseCase cancelOrder,
            InboundEventDeserializer deserializer
    ) {
        this.confirmOrderPayment = Objects.requireNonNull(confirmOrderPayment, "confirmOrderPayment");
        this.cancelOrder = Objects.requireNonNull(cancelOrder, "cancelOrder");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
    }

    public Mono<Void> handle(String eventType, byte[] payload) {
        if (eventType == null) {
            log.warn("Mensagem do tópico de pagamentos sem header de tipo; ignorando");
            return Mono.empty();
        }
        return switch (eventType) {
            case PAYMENT_AUTHORIZED -> confirmPayment(payload);
            case PAYMENT_FAILED -> compensate(payload);
            default -> {
                log.debug("Evento de pagamento '{}' não é consumido pelo Ordering; ignorando", eventType);
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> confirmPayment(byte[] payload) {
        return Mono.fromCallable(() -> deserializer.deserialize(payload, PaymentAuthorizedEvent.class))
                .flatMap(event -> confirmOrderPayment.execute(
                        new ConfirmOrderPaymentCommand(OrderId.of(event.orderId()), event.paymentId()))
                        .onErrorResume(InvalidOrderStateTransitionException.class,
                                e -> alreadySettled(event.orderId(), PAYMENT_FAILED, e)));
    }

    private Mono<Void> compensate(byte[] payload) {
        return Mono.fromCallable(() -> deserializer.deserialize(payload, PaymentFailedEvent.class))
                .flatMap(event -> cancelOrder.execute(new CancelOrderCommand(
                        OrderId.of(event.orderId()),
                        OrderCancelled.CancellationReason.PAYMENT_FAILED,
                        event.reason()))
                        .onErrorResume(InvalidOrderStateTransitionException.class,
                                e -> alreadySettled(event.orderId(), PAYMENT_FAILED, e)));
    }

    private static Mono<Void> alreadySettled(UUID orderId, String eventType, InvalidOrderStateTransitionException e) {
        log.warn("Evento {} para o pedido {} não é aplicável no estado {}; tratando como reentrega idempotente",
                eventType, orderId, e.currentStatus());
        return Mono.empty();
    }
}
