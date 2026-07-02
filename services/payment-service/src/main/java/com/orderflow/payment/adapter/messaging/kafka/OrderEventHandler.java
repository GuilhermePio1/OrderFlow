package com.orderflow.payment.adapter.messaging.kafka;

import com.orderflow.payment.application.command.AuthorizePaymentCommand;
import com.orderflow.payment.application.command.CapturePaymentCommand;
import com.orderflow.payment.application.command.CompensatePaymentCommand;
import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
import com.orderflow.payment.application.usecase.CapturePaymentUseCase;
import com.orderflow.payment.application.usecase.CompensatePaymentUseCase;
import com.orderflow.payment.domain.exception.ConcurrencyConflictException;
import com.orderflow.payment.domain.model.valueobject.CustomerId;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Currency;
import java.util.Objects;

/**
 * Traduz os eventos do contexto Ordering para os comandos de domínio do Payment,
 * conduzindo a participação deste contexto na saga coreografada
 * ({@code docs/architecture.md}). Roteia, pelo header de tipo, os três eventos do
 * ciclo de vida do pedido que o Payment escuta:
 *
 * <ul>
 *   <li>{@code OrderPlaced} → autoriza o pagamento (início da saga);</li>
 *   <li>{@code OrderConfirmed} → captura a autorização (lado feliz);</li>
 *   <li>{@code OrderCancelled} → compensa o pagamento — cancela a autorização
 *       ou estorna a captura (saga de compensação).</li>
 * </ul>
 *
 * É o lado consumidor da Anti-Corruption Layer ({@code docs/ddd.md}): os modelos
 * externos do Order ({@link OrderPlacedEvent}, {@link OrderConfirmedEvent},
 * {@link OrderCancelledEvent}) são convertidos para o vocabulário interno (value
 * objects {@code OrderId}, {@code Money}, ...) antes de tocar a camada de
 * aplicação. Os eventos próprios do Payment ({@code PaymentAuthorized},
 * {@code PaymentCaptured}, {@code PaymentRefunded}, ...) são persistidos na
 * outbox pelos casos de uso e publicados de volta no Kafka via Debezium.
 *
 * <p><b>Idempotência</b> (entrega "at least once", {@code docs/event-sourcing.md}):
 * reentregas não geram efeitos duplicados — cada caso de uso deduplica pelo
 * estado do agregado. Uma corrida entre consumers para o mesmo pedido emerge como
 * {@link ConcurrencyConflictException} na escrita e é tratada aqui como reentrega
 * já liquidada — não como erro — evitando poison pill na DLQ.
 *
 * <p><b>Falha técnica vs. declínio.</b> Um declínio de negócio leva o pagamento a
 * {@code FAILED} normalmente (e o Order compensa). Já uma falha técnica do
 * gateway propaga como {@code PaymentGatewayException}: o handler a deixa subir
 * para que o binder retente e, esgotadas as tentativas, encaminhe à DLQ.
 *
 * <p>Bloqueante por escolha: roda nas threads do listener Kafka, alinhado ao
 * modelo Spring MVC + virtual threads do contexto Payment ({@code docs/architecture.md}).
 */
public final class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    static final String ORDER_PLACED = "OrderPlaced";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_CANCELLED = "OrderCancelled";

    private final AuthorizePaymentUseCase authorizePayment;
    private final CapturePaymentUseCase capturePayment;
    private final CompensatePaymentUseCase compensatePayment;
    private final InboundEventDeserializer deserializer;
    private final PaymentMethod defaultMethod;

    /**
     * @param defaultMethod meio de pagamento aplicado por padrão, já que o
     *                      contrato atual de {@code OrderPlaced} não o carrega.
     */
    public OrderEventHandler(
            AuthorizePaymentUseCase authorizePayment,
            CapturePaymentUseCase capturePayment,
            CompensatePaymentUseCase compensatePayment,
            InboundEventDeserializer deserializer,
            PaymentMethod defaultMethod
    ) {
        this.authorizePayment = Objects.requireNonNull(authorizePayment, "authorizePayment");
        this.capturePayment = Objects.requireNonNull(capturePayment, "capturePayment");
        this.compensatePayment = Objects.requireNonNull(compensatePayment, "compensatePayment");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.defaultMethod = Objects.requireNonNull(defaultMethod, "defaultMethod");
    }

    public void handle(String eventType, byte[] payload) {
        if (eventType == null) {
            log.warn("Mensagem do tópico de pedidos sem header de tipo; ignorando");
            return;
        }
        switch (eventType) {
            case ORDER_PLACED -> onOrderPlaced(payload);
            case ORDER_CONFIRMED -> onOrderConfirmed(payload);
            case ORDER_CANCELLED -> onOrderCancelled(payload);
            default ->
                // orders.events carrega todo o ciclo de vida do pedido; o Payment
                // só reage à colocação, confirmação e cancelamento. Os demais
                // (OrderShipped, OrderDelivered, ...) não são deste contexto.
                log.debug("Evento de pedido '{}' não é consumido pelo Payment; ignorando", eventType);
        }
    }

    private void onOrderPlaced(byte[] payload) {
        OrderPlacedEvent event = deserializer.deserialize(payload, OrderPlacedEvent.class);
        try {
            authorizePayment.execute(toAuthorizeCommand(event));
        } catch (ConcurrencyConflictException e) {
            // Outro consumer já criou o pagamento deste pedido (criação concorrente
            // / reentrega). O resultado já foi registrado — nada a fazer.
            log.warn("OrderPlaced para o pedido {} já possui pagamento em andamento; "
                    + "tratando como reentrega idempotente", e.paymentId());
        }
    }

    private void onOrderConfirmed(byte[] payload) {
        OrderConfirmedEvent event = deserializer.deserialize(payload, OrderConfirmedEvent.class);
        try {
            capturePayment.execute(new CapturePaymentCommand(OrderId.of(event.orderId().value())));
        } catch (ConcurrencyConflictException e) {
            // Captura concorrente / reentrega de OrderConfirmed já liquidada.
            log.warn("OrderConfirmed para o pagamento {} já capturado concorrentemente; "
                    + "tratando como reentrega idempotente", e.paymentId());
        }
    }

    private void onOrderCancelled(byte[] payload) {
        OrderCancelledEvent event = deserializer.deserialize(payload, OrderCancelledEvent.class);
        try {
            compensatePayment.execute(new CompensatePaymentCommand(
                    OrderId.of(event.orderId().value()), compensationReason(event)));
        } catch (ConcurrencyConflictException e) {
            // Compensação concorrente / reentrega de OrderCancelled já liquidada.
            log.warn("OrderCancelled para o pagamento {} já compensado concorrentemente; "
                    + "tratando como reentrega idempotente", e.paymentId());
        }
    }

    private AuthorizePaymentCommand toAuthorizeCommand(OrderPlacedEvent event) {
        return new AuthorizePaymentCommand(
                OrderId.of(event.orderId().value()),
                CustomerId.of(event.customerId().value()),
                Money.of(event.totalAmount().amount(), Currency.getInstance(event.totalAmount().currency())),
                defaultMethod
        );
    }

    private static String compensationReason(OrderCancelledEvent event) {
        if (event.reason() == null) {
            return "Order cancelled";
        }
        return event.details() == null || event.details().isBlank()
                ? "Order cancelled: " + event.reason()
                : "Order cancelled: " + event.reason() + " — " + event.details();
    }
}
