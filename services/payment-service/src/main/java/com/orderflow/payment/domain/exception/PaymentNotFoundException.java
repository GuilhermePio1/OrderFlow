package com.orderflow.payment.domain.exception;

import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

public final class PaymentNotFoundException extends DomainException {

    public PaymentNotFoundException(PaymentId paymentId) {
        super("Payment not found: " + paymentId);
    }

    public PaymentNotFoundException(OrderId orderId) {
        super("Payment not found for order: " + orderId);
    }
}
