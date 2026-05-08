package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelled(
        UUID eventId,
        OrderId orderId,
        CancellationReason reason,
        String details,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {

    public enum CancellationReason {
        CUSTOMER_REQUESTED,
        PAYMENT_FAILED,
        OUT_OF_STOCK,
        FRAUD_DETECTED,
        SYSTEM
    }
}
