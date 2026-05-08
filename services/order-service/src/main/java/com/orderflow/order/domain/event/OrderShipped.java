package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.model.valueobject.TrackingNumber;

import java.time.Instant;
import java.util.UUID;

public record OrderShipped(
        UUID eventId,
        OrderId orderId,
        TrackingNumber trackingNumber,
        String carrier,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {
}
