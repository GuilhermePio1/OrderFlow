package com.orderflow.payment.application.command;

import com.orderflow.payment.domain.model.valueobject.CustomerId;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentMethod;

import java.util.Objects;

/**
 * Comando para autorizar o pagamento de um pedido. Construído pelo consumer de
 * {@code OrderPlaced} a partir dos dados do pedido (este contexto é
 * Customer/Supplier downstream de Ordering — vide {@code docs/ddd.md}).
 *
 * Não carrega {@code PaymentId}: o caso de uso o gera, garantindo idempotência
 * pela identidade do pedido e não por um id fornecido externamente.
 */
public record AuthorizePaymentCommand(
        OrderId orderId,
        CustomerId customerId,
        Money amount,
        PaymentMethod method
) {

    public AuthorizePaymentCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(method, "method");
    }
}
