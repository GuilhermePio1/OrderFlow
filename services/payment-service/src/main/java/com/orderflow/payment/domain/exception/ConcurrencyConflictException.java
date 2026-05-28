package com.orderflow.payment.domain.exception;

import com.orderflow.payment.domain.model.valueobject.PaymentId;

public final class ConcurrencyConflictException extends DomainException {

    private final PaymentId paymentId;
    private final long expectedVersion;
    private final long actualVersion;

    public ConcurrencyConflictException(PaymentId paymentId, long expectedVersion, long actualVersion) {
        super("Concurrency conflict for payment " + paymentId
                + ": expected version " + expectedVersion
                + " but found " + actualVersion);
        this.paymentId = paymentId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
