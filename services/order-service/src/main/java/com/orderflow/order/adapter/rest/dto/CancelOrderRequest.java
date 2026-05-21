package com.orderflow.order.adapter.rest.dto;

import jakarta.validation.constraints.Size;

/**
 * Corpo opcional do cancelamento iniciado pelo cliente. A razão é fixada
 * em {@code CUSTOMER_REQUESTED} pelo adapter — as demais razões
 * ({@code PAYMENT_FAILED}, {@code OUT_OF_STOCK}, ...) são produzidas pela
 * saga de compensação, não por um comando REST.
 */
public record CancelOrderRequest(

        @Size(max = 500)
        String details
) {
}
