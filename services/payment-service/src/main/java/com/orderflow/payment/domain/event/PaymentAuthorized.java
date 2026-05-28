package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.*;

import java.time.Instant;
import java.util.UUID;

public record PaymentAuthorized(
        UUID eventId,
        PaymentId paymentId,
        OrderId orderId,
        CustomerId customerId,
        Money amount,
        PaymentMethod method,
        GatewayTransactionId gatewayTransactionId,
        AuthorizationCode authorizationCode,
        Instant occurredAt,
        int schemaVersion
) implements PaymentEvent {
}
