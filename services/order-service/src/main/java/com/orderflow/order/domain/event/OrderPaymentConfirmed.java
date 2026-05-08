package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.UUID;

public record OrderPaymentConfirmed(
        UUID eventId,
        OrderId orderId,
        UUID paymentId,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {
}
