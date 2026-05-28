package com.orderflow.payment.domain.model.valueobject;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Código de autorização retornado pela adquirente (ex.: 6 dígitos
 * para cartão). Útil para conciliação e disputa, não para o fluxo
 * interno de domínio.
 */
public record AuthorizationCode(String value) {

    public AuthorizationCode {
        Objects.requireNonNull(value, "AuthorizationCode value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AuthorizationCode value must not be blank");
        }
    }

    public static AuthorizationCode of(String value) {
        return new AuthorizationCode(value);
    }

    @Override
    public @NonNull String toString() {
        return value;
    }
}
