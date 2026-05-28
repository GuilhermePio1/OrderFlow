package com.orderflow.payment.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AuthorizationCode — value object")
class AuthorizationCodeTest {

    @Test
    @DisplayName("rejeita valor nulo")
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> AuthorizationCode.of(null))
                .withMessageContaining("AuthorizationCode");
    }

    @Test
    @DisplayName("rejeita valor em branco")
    void rejectsBlank() {
        assertThatThrownBy(() -> AuthorizationCode.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthorizationCode.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString retorna o valor cru")
    void toStringIsRaw() {
        assertThat(AuthorizationCode.of("A12B34").toString()).isEqualTo("A12B34");
    }

    @Test
    @DisplayName("igualdade por valor")
    void equality() {
        assertThat(AuthorizationCode.of("X")).isEqualTo(AuthorizationCode.of("X"));
        assertThat(AuthorizationCode.of("X")).hasSameHashCodeAs(AuthorizationCode.of("X"));
    }
}
