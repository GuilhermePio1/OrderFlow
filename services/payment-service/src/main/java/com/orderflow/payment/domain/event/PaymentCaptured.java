package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.GatewayTransactionId;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.time.Instant;
import java.util.UUID;

public record PaymentCaptured(
        UUID eventId,
        PaymentId paymentId,
        OrderId orderId,
        Money amount,
        GatewayTransactionId gatewayTransactionId,
        Instant occurredAt,
        int schemaVersion
) implements PaymentEvent {
}
