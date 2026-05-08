package com.orderflow.order.domain.exception;

import com.orderflow.order.domain.model.valueobject.OrderId;

public final class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(OrderId orderId) {
        super("Order not found: " + orderId);
    }
}
