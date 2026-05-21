package com.orderflow.order.adapter.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * Payload externo de criação de pedido. Validado estruturalmente (Bean
 * Validation) na fronteira do serviço antes de qualquer processamento,
 * conforme {@code docs/security.md}. A moeda é única para o pedido — o
 * domínio rejeita itens em moedas distintas via {@code Money}.
 */
public record PlaceOrderRequest(

        @NotNull
        UUID customerId,

        @NotEmpty
        @Size(max = 100, message = "an order cannot have more than 100 items")
        List<@Valid OrderItemRequest> items,

        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be an ISO 4217 code")
        String currency,

        @NotNull
        @Valid
        AddressRequest shippingAddress
) {
}
