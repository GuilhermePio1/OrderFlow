package com.orderflow.order.domain.model;

import com.orderflow.order.domain.model.valueobject.Money;
import com.orderflow.order.domain.model.valueobject.ProductId;
import com.orderflow.order.domain.model.valueobject.Quantity;

import java.util.Objects;

/**
 * Linha de pedido. Identificada dentro do agregado Order pelo ProductId.
 * Imutável: alterações produzem uma nova instância.
 */
public record OrderItem(
        ProductId productId,
        Quantity quantity,
        Money unitPrice
) {

    public OrderItem {
        Objects.requireNonNull(productId, "OrderItem productId must not be null");
        Objects.requireNonNull(quantity, "OrderItem quantity must not be null");
        Objects.requireNonNull(unitPrice, "OrderItem unitPrice must not be null");
        if (!unitPrice.isPositive()) {
            throw new IllegalArgumentException(
                    "OrderItem unitPrice must be positive, got: " + unitPrice.amount());
        }
    }

    public Money subtotal() {
        return unitPrice.multiply(quantity.value());
    }

    public OrderItem withQuantity(Quantity newQuantity) {
        return new OrderItem(productId, newQuantity, unitPrice);
    }
}
