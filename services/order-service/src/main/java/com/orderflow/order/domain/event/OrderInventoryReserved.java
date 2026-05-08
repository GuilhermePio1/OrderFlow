package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.UUID;

public record OrderInventoryReserved(
        UUID eventId,
        OrderId orderId,
        UUID reservationId,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {
}
