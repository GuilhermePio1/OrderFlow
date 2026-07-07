package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.adapter.rest.dto.PaymentResponse;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.Money;

/**
 * Tradução do agregado {@link Payment} para o DTO de resposta administrativo.
 * Mantém o vocabulário do domínio fora do contrato REST (enums viram strings;
 * value objects viram tipos primitivos), permitindo que os dois evoluam de
 * forma independente.
 */
final class PaymentRestMapper {

    private PaymentRestMapper() {
    }

    static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.id().value(),
                payment.orderId().value(),
                payment.customerId().value(),
                payment.status().name(),
                payment.method().name(),
                toMoney(payment.authorizedAmount()),
                toMoney(payment.capturedAmount()),
                toMoney(payment.refundedAmount()),
                payment.gatewayTransactionId() == null ? null : payment.gatewayTransactionId().value(),
                payment.failureReason() == null ? null : payment.failureReason().name(),
                payment.failureDetails(),
                payment.initiatedAt(),
                payment.version()
        );
    }

    private static PaymentResponse.Money toMoney(Money money) {
        return new PaymentResponse.Money(money.amount(), money.currency().getCurrencyCode());
    }
}
