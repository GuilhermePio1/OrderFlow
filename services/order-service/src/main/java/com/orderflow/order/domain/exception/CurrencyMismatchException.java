package com.orderflow.order.domain.exception;

import java.util.Currency;

public final class CurrencyMismatchException extends DomainException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected " + expected.getCurrencyCode()
                + " but got " + actual.getCurrencyCode());
    }
}
