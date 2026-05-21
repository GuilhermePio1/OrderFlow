package com.orderflow.order.adapter.rest.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Visão do agregado reidratado a partir do stream de eventos. É a leitura
 * do lado de escrita (consistência forte, imediata após o comando) —
 * distinta das projeções denormalizadas servidas pelo Query Service
 * ({@code docs/event-sourcing.md}).
 */
public record OrderResponse(
        UUID orderId,
        UUID customerId,
        String status,
        List<Item> items,
        Money totalAmount,
        Address shippingAddress,
        boolean paymentConfirmed,
        boolean inventoryReserved,
        long version
) {

    public record Item(UUID productId, int quantity, Money unitPrice, Money subtotal){
    }

    public record Money(BigDecimal amount, String currency){
    }

    public record Address(
            String street,
            String number,
            String complement,
            String neighborhood,
            String city,
            String state,
            String postalCode,
            String country
    ) {
    }
}
