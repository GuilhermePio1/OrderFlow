package com.orderflow.payment.adapter.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Requisição de estorno administrativo (parcial ou total). A moeda é
 * explícita — não inferida do pagamento — para que um estorno na moeda errada
 * falhe como violação de invariante ({@code CurrencyMismatchException}) ao
 * invés de estornar silenciosamente um valor não intencionado. Constraints
 * declarativos na fronteira ({@code docs/security.md}: "toda entrada externa
 * é validada na fronteira do serviço"); a regra de negócio (valor dentro do
 * saldo capturado remanescente) é aplicada no domínio.
 */
public record RefundPaymentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 3) String currency,
        @Size(max = 500) String reason
) {
}
