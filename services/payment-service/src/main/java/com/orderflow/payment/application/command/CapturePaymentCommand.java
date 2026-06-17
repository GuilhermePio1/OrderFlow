package com.orderflow.payment.application.command;

import com.orderflow.payment.domain.model.valueobject.OrderId;

import java.util.Objects;

/**
 * Comando para capturar o pagamento de um pedido. Construído pelo consumer de
 * {@code OrderConfirmed}: uma vez que o pedido foi confirmado (pagamento
 * autorizado e estoque reservado, vide {@code docs/architecture.md}), a
 * autorização retida é efetivamente capturada.
 *
 * <p>Identifica o pagamento por {@link OrderId} — não por {@code PaymentId} —
 * porque o gatilho é um evento do contexto Ordering, que raciocina em termos de
 * pedido. O caso de uso resolve o agregado {@code Payment} correspondente.
 */
public record CapturePaymentCommand(OrderId orderId) {

    public CapturePaymentCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
