package com.orderflow.payment.application.command;

import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.util.Objects;

/**
 * Comando de estorno administrativo (parcial ou total) de um pagamento já
 * capturado. Construído pelo adapter REST a partir de uma requisição de
 * back-office — atendimento ao cliente, resolução de disputa — ao contrário
 * do estorno da saga de compensação, que chega por {@code OrderCancelled}
 * ({@link CompensatePaymentCommand}).
 *
 * <p>Identifica o pagamento por {@link PaymentId} porque o operador raciocina
 * em termos da transação de pagamento, não do pedido.
 *
 * @param paymentId pagamento capturado a estornar
 * @param amount    valor a estornar (positivo, na moeda do pagamento)
 * @param reason    motivo do estorno, anexado ao evento e repassado ao gateway
 */
public record RefundPaymentCommand(PaymentId paymentId, Money amount, String reason) {

    public RefundPaymentCommand {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Refund amount must be positive, got: " + amount.amount());
        }
    }
}
