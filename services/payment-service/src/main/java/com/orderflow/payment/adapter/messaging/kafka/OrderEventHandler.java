package com.orderflow.payment.adapter.messaging.kafka;

import com.orderflow.payment.application.command.AuthorizePaymentCommand;
import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
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
 * Traduz o evento {@code OrderPlaced} do contexto Ordering para o comando de
 * domínio {@link AuthorizePaymentCommand}, disparando a autorização do
 * pagamento — o passo inicial da saga coreografada ({@code docs/architecture.md}).
 *
 * É o lado consumidor da Anti-Corruption Layer ({@code docs/ddd.md}): o modelo
 * externo do Order ({@link OrderPlacedEvent}) é convertido para o vocabulário
 * interno (value objects {@code OrderId}, {@code Money}, ...) antes de tocar a
 * camada de aplicação. O resultado da autorização — {@code PaymentAuthorized}
 * ou {@code PaymentFailed} — é persistido na outbox pelo caso de uso e
 * publicado de volta no Kafka via Debezium.
 *
 * <p><b>Idempotência</b> (entrega "at least once", {@code docs/event-sourcing.md}):
 * reentregas de {@code OrderPlaced} não geram pagamentos duplicados. O caso de
 * uso já deduplicaa por {@code orderId}; uma corrida entre dois consumers para o
 * mesmo pedido emerge como {@link ConcurrencyConflictException} na escrita e é
 * tratada aqui como reentrega já liquidada — não como erro — evitando poison
 * pill na DLQ.
 *
 * <p><b>Falha técnica vs. declínio.</b> Um declínio de negócio leva o pagamento
 * a {@code FAILED} normalmente (e o Order compensa). Já uma falha técnica do
 * gateway propaga como {@code PaymentGatewayException}: o handler a deixa subir
 * para que o binder retente e, esgotadas as tentativas, encaminhe à DLQ.
 *
 * <p>Bloqueante por escolha: roda nas threads do listener Kafka, alinhado ao
 * modelo Spring MVC + virtual threads do contexto Payment ({@code docs/architecture.md}).
 */
public final class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    static final String ORDER_PLACED = "OrderPlaced";

    private final AuthorizePaymentUseCase authorizePayment;
    private final InboundEventDeserializer deserializer;
    private final PaymentMethod defaultMethod;

    /**
     * @param defaultMethod meio de pagamento aplicado por padrão, já que o
     *                      contrato atual de {@code OrderPlaced} não o carrega.
     */
    public OrderEventHandler(
            AuthorizePaymentUseCase authorizePayment,
            InboundEventDeserializer deserializer,
            PaymentMethod defaultMethod
    ) {
        this.authorizePayment = Objects.requireNonNull(authorizePayment, "authorizePayment");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.defaultMethod = Objects.requireNonNull(defaultMethod, "defaultMethod");
    }

    public void handle(String eventType, byte[] payload) {
        if (eventType == null) {
            log.warn("Mensagem do tópico de pedidos sem header de tipo; ignorando");
            return;
        }
        if (!ORDER_PLACED.equals(eventType)) {
            // orders.events carrega todo o ciclo de vida do pedido; o Payment só
            // reage à colocação inicial. Demais eventos não são deste contexto.
            log.debug("Evento de pedido '{}' não é consumido pelo Payment; ignorando", eventType);
            return;
        }

        OrderPlacedEvent event = deserializer.deserialize(payload, OrderPlacedEvent.class);
        try {
            authorizePayment.execute(toCommand(event));
        } catch (ConcurrencyConflictException e) {
            // Outro consumer já criou o pagamento deste pedido (criação concorrente
            // / reentrega). O resultado já foi registrado — nada a fazer.
            log.warn("OrderPlaced para o pedido {} já possui pagamento em andamento; "
                    + "tratando como reentrega idempotente", e.paymentId());
        }
    }

    private AuthorizePaymentCommand toCommand(OrderPlacedEvent event) {
        return new AuthorizePaymentCommand(
                OrderId.of(event.orderId().value()),
                CustomerId.of(event.customerId().value()),
                Money.of(event.totalAmount().amount(), Currency.getInstance(event.totalAmount().currency())),
                defaultMethod
        );
    }
}
