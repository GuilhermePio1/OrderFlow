package com.orderflow.payment.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CustomerId — value object")
class CustomerIdTest {

    @Test
    @DisplayName("rejeita UUID nulo")
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> CustomerId.of((UUID) null))
                .withMessageContaining("CustomerId");
    }

    @Test
    @DisplayName("igualdade por valor")
    void equality() {
        UUID uuid = UUID.randomUUID();

        assertThat(CustomerId.of(uuid)).isEqualTo(CustomerId.of(uuid));
        assertThat(CustomerId.of(uuid)).hasSameHashCodeAs(CustomerId.of(uuid));
    }

    @Test
    @DisplayName("cria a partir de String UUID")
    void fromString() {
        String s = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(CustomerId.of(s).toString()).isEqualTo(s);
    }

    @Test
    @DisplayName("encapsula um UUID")
    void wrapsUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(CustomerId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("rejeita String inválida")
    void rejectsInvalidString() {
        assertThatThrownBy(() -> CustomerId.of("formato-invalido"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
