package com.orderflow.payment.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GatewayTransactionId — value object")
class GatewayTransactionIdTest {

    @Test
    @DisplayName("rejeita valor nulo")
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> GatewayTransactionId.of(null))
                .withMessageContaining("GatewayTransactionId");
    }

    @Test
    @DisplayName("rejeita valor em branco")
    void rejectsBlank() {
        assertThatThrownBy(() -> GatewayTransactionId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayTransactionId.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString retorna o valor cru")
    void toStringIsRawValue() {
        assertThat(GatewayTransactionId.of("ch_3PqXyZ").toString()).isEqualTo("ch_3PqXyZ");
    }

    @Test
    @DisplayName("igualdade por valor")
    void equalityByValue() {
        assertThat(GatewayTransactionId.of("abc")).isEqualTo(GatewayTransactionId.of("abc"));
        assertThat(GatewayTransactionId.of("abc")).isNotEqualTo(GatewayTransactionId.of("abd"));
    }
}
