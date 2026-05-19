package com.orderflow.order.application.support;

import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Política compartilhada de retentativa por concorrência otimista. O event
 * store rejeita gravações cuja {@code expectedVersion} divergiu (vide
 * {@code R2dbcOrderRepository}); cabe ao caso de uso reidratar o agregado
 * e tentar novamente. Backoff curto com jitter evita thundering-herd entre
 * réplicas do serviço concorrendo pelo mesmo agregado.
 */
public final class ConcurrencyConflictRetry {

    private static final long DEFAULT_MAX_ATTEMPTS = 3;
    private static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(20);

    private ConcurrencyConflictRetry() {
    }

    public static Retry defaultPolicy() {
        return Retry.backoff(DEFAULT_MAX_ATTEMPTS, DEFAULT_MIN_BACKOFF)
                .filter(ConcurrencyConflictException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
