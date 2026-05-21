package com.orderflow.order.adapter.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Linha de pedido no payload de entrada. As invariantes finais (unicidade
 * por produto, total) são do agregado; aqui apenas garantimos sanidade
 * estrutural mínima.
 */
public record OrderItemRequest(

        @NotNull
        UUID productId,

        @Positive
        int quantity,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = false, message = "unitPrice must be positive")
        @Digits(integer = 10, fraction = 2, message = "unitPrice cannot have more than 2 decimal places")
        BigDecimal unitPrice
) {
}
