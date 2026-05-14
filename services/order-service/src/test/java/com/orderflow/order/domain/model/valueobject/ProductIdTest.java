package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ProductId — value object")
class ProductIdTest {

    @Test
    @DisplayName("encapsula um UUID válido")
    void wrapsUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(ProductId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("cria a partir de uma String UUID válida")
    void buildsFromString() {
        UUID uuid = UUID.randomUUID();
        assertThat(ProductId.of(uuid.toString()).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("rejeita String que não seja um UUID válido")
    void rejectsInvalidString() {
        assertThatThrownBy(() -> ProductId.of("zzz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejeita UUID nulo")
    void rejectsNullUuid() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ProductId(null))
                .withMessageContaining("ProductId");
    }

    @Test
    @DisplayName("toString delega para o UUID interno")
    void toStringDelegatesToUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(ProductId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    @DisplayName("igualdade considera o UUID interno")
    void equalityByValue() {
        UUID uuid = UUID.randomUUID();
        assertThat(ProductId.of(uuid)).isEqualTo(ProductId.of(uuid));
        assertThat(ProductId.of(uuid)).hasSameHashCodeAs(ProductId.of(uuid));
    }
}
