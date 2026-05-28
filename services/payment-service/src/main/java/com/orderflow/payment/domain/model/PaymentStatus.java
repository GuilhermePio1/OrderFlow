package com.orderflow.payment.domain.model;

import java.util.Set;

/**
 * Máquina de estados explícita do agregado Payment.
 *
 * <pre>
 *                       +--&gt; AUTHORIZED ---&gt; CAPTURED ---&gt; REFUNDED
 *                       |        |                |
 * PENDING ----+---------+        +----&gt; VOIDED    +----&gt; (estornos parciais
 *             |                                          permanecem em CAPTURED)
 *             +--&gt; FAILED
 * </pre>
 *
 * Estados terminais: FAILED, VOIDED, REFUNDED.
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    VOIDED;

    private static final Set<PaymentStatus> TERMINAL = Set.of(FAILED, VOIDED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
