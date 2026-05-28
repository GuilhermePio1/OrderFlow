package com.orderflow.payment.domain.model.valueobject;

/**
 * Meio de pagamento. Vocabulário interno do contexto Payment.
 * A ACL com gateways externos (Stripe, PagSeguro) traduz entre estes
 * valores e os equivalentes dos provedores.
 */
public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    PIX,
    BOLETO,
    WALLET
}
