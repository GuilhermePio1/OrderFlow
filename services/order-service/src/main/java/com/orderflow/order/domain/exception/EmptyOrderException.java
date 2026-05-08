package com.orderflow.order.domain.exception;

public final class EmptyOrderException extends DomainException {

    public EmptyOrderException() {
        super("An order must contain at least one item");
    }
}
