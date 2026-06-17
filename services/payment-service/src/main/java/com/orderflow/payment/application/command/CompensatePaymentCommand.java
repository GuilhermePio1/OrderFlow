package com.orderflow.payment.application.command;

import com.orderflow.payment.domain.model.valueobject.OrderId;

import java.util.Objects;

/**
 * Comando de compensação do pagamento de um pedido cancelado. Construído pelo
 * consumer de {@code OrderCancelled}, é o passo do Payment na saga de
 * compensação coreografada ({@code docs/architecture.md}, "Saga de Compensação").
 *
 * <p>O caso de uso decide a reversão adequada conforme o estado do pagamento —
 * cancelar a autorização ainda não capturada ou estornar o que já fora
 * capturado — de modo que o contexto Ordering não precisa conhecer esse detalhe.
 *
 * @param orderId pedido cancelado cujo pagamento deve ser revertido
 * @param reason  motivo propagado do cancelamento, anexado ao evento de reversão
 */
public record CompensatePaymentCommand(OrderId orderId, String reason) {

    public CompensatePaymentCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
