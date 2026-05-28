package com.orderflow.payment.domain.exception;

import com.orderflow.payment.domain.model.valueobject.PaymentId;

public final class PaymentNotFoundException extends DomainException {

    public PaymentNotFoundException(PaymentId paymentId) {
        super("Payment not found: " + paymentId);
    }
}
