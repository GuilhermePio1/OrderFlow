package com.orderflow.order.adapter.rest.dto;

import java.util.UUID;

/**
 * Resposta de criação. O processamento a jusante (pagamento, estoque) é
 * assíncrono via saga coreografada; por isso o endpoint responde
 * {@code 202 Accepted} carregando apenas o identificador gerado.
 */
public record PlaceOrderResponse(UUID orderId) {
}
