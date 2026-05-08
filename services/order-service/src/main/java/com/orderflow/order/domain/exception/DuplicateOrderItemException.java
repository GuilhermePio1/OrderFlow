package com.orderflow.order.domain.exception;

import com.orderflow.order.domain.model.valueobject.ProductId;

public final class DuplicateOrderItemException extends DomainException {

    public DuplicateOrderItemException(ProductId productId) {
        super("Order already contains an item for product " + productId);
    }
}
