package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.time.Instant;
import java.util.UUID;

public record PaymentRefunded(
        UUID eventId,
        PaymentId paymentId,
        OrderId orderId,
        Money refundedAmount,
        Money totalRefundedAmount,
        boolean fullRefund,
        String reason,
        Instant occurredAt,
        int schemaVersion
) implements PaymentEvent {
}
