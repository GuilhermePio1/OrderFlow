package com.orderflow.order.adapter.rest;

import com.orderflow.order.adapter.rest.dto.OrderResponse;
import com.orderflow.order.adapter.rest.dto.PlaceOrderRequest;
import com.orderflow.order.application.command.PlaceOrderCommand;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.OrderItem;
import com.orderflow.order.domain.model.valueobject.*;

import java.util.Currency;
import java.util.List;

/**
 * Tradução entre o vocabulário externo (HTTP/JSON) e o domínio. Vive na
 * borda por design: o domínio não conhece DTOs, e os DTOs não vazam para
 * dentro do agregado. Construir os value objects aqui faz a validação de
 * regra de domínio (quantidade positiva, moeda, endereço) emergir como
 * exceção tratável pelo handler de borda.
 */
final class OrderRestMapper {

    private OrderRestMapper() {
    }

    static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        Currency currency = Currency.getInstance(request.currency());
        List<OrderItem> items = request.items().stream()
                .map(item -> new OrderItem(
                        ProductId.of(item.productId()),
                        Quantity.of(item.quantity()),
                        Money.of(item.unitPrice(), currency)))
                .toList();
        Address shippingAddress = new Address(
                request.shippingAddress().street(),
                request.shippingAddress().number(),
                request.shippingAddress().complement(),
                request.shippingAddress().neighborhood(),
                request.shippingAddress().city(),
                request.shippingAddress().state(),
                request.shippingAddress().postalCode(),
                request.shippingAddress().country());
        return new PlaceOrderCommand(
                CustomerId.of(request.customerId()),
                items,
                shippingAddress);
    }

    static OrderResponse toResponse(Order order) {
        List<OrderResponse.Item> items = order.items().stream()
                .map(item -> new OrderResponse.Item(
                        item.productId().value(),
                        item.quantity().value(),
                        money(item.unitPrice()),
                        money(item.subtotal())))
                .toList();
        Address address = order.shippingAddress();
        return new OrderResponse(
                order.id().value(),
                order.customerId().value(),
                order.status().name(),
                items,
                money(order.totalAmount()),
                new OrderResponse.Address(
                        address.street(),
                        address.number(),
                        address.complement(),
                        address.neighborhood(),
                        address.city(),
                        address.state(),
                        address.postalCode(),
                        address.country()),
                order.isPaymentConfirmed(),
                order.isInventoryReserved(),
                order.version());
    }

    private static OrderResponse.Money money(Money money) {
        return new OrderResponse.Money(money.amount(), money.currency().getCurrencyCode());
    }
}
