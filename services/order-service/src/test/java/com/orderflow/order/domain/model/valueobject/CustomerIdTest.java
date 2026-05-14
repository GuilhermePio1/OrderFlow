package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CustomerId — value object")
class CustomerIdTest {

    @Test
    @DisplayName("encapsula um UUID válido")
    void wrapsUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(CustomerId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("cria a partir de uma String UUID válida")
    void buildsFromString() {
        UUID uuid = UUID.randomUUID();
        assertThat(CustomerId.of(uuid.toString()).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("rejeita String que não seja um UUID válido")
    void rejectsInvalidString() {
        assertThatThrownBy(() -> CustomerId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejeita UUID nulo")
    void rejectsNullUuid() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CustomerId(null))
                .withMessageContaining("CustomerId");
    }

    @Test
    @DisplayName("toString delega para o UUID interno")
    void toStringDelegatesToUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(CustomerId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    @DisplayName("igualdade considera o UUID interno")
    void equalityByValue() {
        UUID uuid = UUID.randomUUID();
        assertThat(CustomerId.of(uuid)).isEqualTo(CustomerId.of(uuid));
        assertThat(CustomerId.of(uuid)).hasSameHashCodeAs(CustomerId.of(uuid));
    }
}
