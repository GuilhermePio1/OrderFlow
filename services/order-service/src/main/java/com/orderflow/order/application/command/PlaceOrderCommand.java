package com.orderflow.order.application.command;

import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.Address;
import com.orderflow.order.domain.model.valueobject.CustomerId;

import java.util.List;
import java.util.Objects;

/**
 * Entrada do {@code PlaceOrderUseCase}. Carrega apenas o estritamente
 * necessário para criar o agregado — o adapter de borda é quem traduz
 * o payload externo (HTTP/JSON) neste tipo.
 */
public record PlaceOrderCommand(
        CustomerId customerId,
        List<OrderItem> items,
        Address shippingAddress
) {

    public PlaceOrderCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(shippingAddress, "shippingAddress");
        items = List.copyOf(items);
    }
}
