package com.orderflow.order.domain.event;

import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.Address;
import com.orderflow.order.domain.model.valueobject.CustomerId;
import com.orderflow.order.domain.model.valueobject.Money;
import com.orderflow.order.domain.model.valueobject.OrderId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderPlaced(
        UUID eventId,
        OrderId orderId,
        CustomerId customerId,
        List<OrderItem> items,
        Money totalAmount,
        Address shippingAddress,
        Instant occurredAt,
        int schemaVersion
) implements OrderEvent {

    public OrderPlaced {
        items = List.copyOf(items);
    }
}
