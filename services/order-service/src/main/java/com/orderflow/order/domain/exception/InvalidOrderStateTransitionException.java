package com.orderflow.order.domain.exception;

import com.orderflow.order.domain.model.OrderStatus;

public final class InvalidOrderStateTransitionException extends DomainException {

    private final OrderStatus currentStatus;
    private final String command;

    public InvalidOrderStateTransitionException(OrderStatus currentStatus, String command) {
        super("Command '" + command + "' is not accepted in status " + currentStatus);
        this.currentStatus = currentStatus;
        this.command = command;
    }

    public OrderStatus currentStatus() {
        return currentStatus;
    }

    public String command() {
        return command;
    }
}
