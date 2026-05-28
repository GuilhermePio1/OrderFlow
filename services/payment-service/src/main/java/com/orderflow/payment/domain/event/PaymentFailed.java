package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.CustomerId;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailed(
        UUID eventId,
        PaymentId paymentId,
        OrderId orderId,
        CustomerId customerId,
        Money amount,
        FailureReason reason,
        String details,
        Instant occurredAt,
        int schemaVersion
) implements PaymentEvent {

    public enum FailureReason {
        INSUFFICIENT_FUNDS,
        CARD_DECLINED,
        FRAUD_SUSPECTED,
        GATEWAY_UNAVAILABLE,
        INVALID_PAYMENT_METHOD,
        UNKNOWN
    }
}
