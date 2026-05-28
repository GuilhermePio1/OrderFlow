package com.orderflow.payment.domain.exception;

import com.orderflow.payment.domain.model.valueobject.Money;

public final class RefundExceedsCapturedAmountException extends DomainException {

    public RefundExceedsCapturedAmountException(Money requested, Money remaining) {
        super("Refund of " + requested.amount() + " " + requested.currency().getCurrencyCode()
                + " exceeds remaining captured amount of " + remaining.amount()
                + " " + remaining.currency().getCurrencyCode());
    }
}
