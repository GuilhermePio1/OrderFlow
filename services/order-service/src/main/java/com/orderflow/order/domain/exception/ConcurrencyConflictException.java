package com.orderflow.order.domain.exception;

import com.orderflow.order.domain.model.valueobject.OrderId;

public final class ConcurrencyConflictException extends DomainException {

    private final OrderId orderId;
    private final long expectedVersion;
    private final long actualVersion;

    public ConcurrencyConflictException(OrderId orderId, long expectedVersion, long actualVersion) {
        super("Concurrency conflict for order " + orderId
                + ": expected version " + expectedVersion
                + " but found " + actualVersion);
        this.orderId = orderId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public OrderId orderId() {
        return orderId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
