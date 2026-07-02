package com.orderflow.payment.adapter.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Visão administrativa do agregado Payment. Expõe apenas o necessário para
 * o back-office ({@code docs/security.md}: "jamais expor o que não precisa
 * ser exposto"): identificadores, estado da máquina de transições, valores e
 * a referência opaca da transação no gateway ({@code gatewayTransactionId})
 * para rastreabilidade junto ao provedor. Nenhum dado PCI — o serviço
 * armazena apenas tokens retornados pelo gateway.
 */
public record PaymentResponse(
        UUID paymentId,
        UUID orderId,
        UUID customerId,
        String status,
        String method,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedAmount,
        String gatewayTransactionId,
        String failureReason,
        String failureDetails,
        Instant initiatedAt,
        long version
) {

    public record Money(BigDecimal amount, String currency) {
    }
}
