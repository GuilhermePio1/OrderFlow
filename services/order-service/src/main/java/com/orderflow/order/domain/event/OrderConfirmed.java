package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.UUID;

public record OrderConfirmed(
        UUID eventId,
        OrderId orderId,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {
}
