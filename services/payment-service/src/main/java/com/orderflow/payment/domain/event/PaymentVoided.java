package com.orderflow.payment.domain.event;

import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;

import java.time.Instant;
import java.util.UUID;

public record PaymentVoided(
        UUID eventId,
        PaymentId paymentId,
        OrderId orderId,
        String reason,
        Instant occurredAt,
        int schemaVersion
) implements PaymentEvent {
}
