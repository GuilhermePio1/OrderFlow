package com.orderflow.payment.domain.exception;

import com.orderflow.payment.domain.model.PaymentStatus;

public final class InvalidPaymentStateTransitionException extends DomainException {

    private final PaymentStatus currentStatus;
    private final String command;

    public InvalidPaymentStateTransitionException(PaymentStatus currentStatus, String command) {
        super("Command '" + command + "' is not accepted in status " + currentStatus);
        this.currentStatus = currentStatus;
        this.command = command;
    }

    public PaymentStatus currentStatus() {
        return currentStatus;
    }

    public String command() {
        return command;
    }
}
