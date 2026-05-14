package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("OrderId — value object")
class OrderIdTest {

    @Test
    @DisplayName("encapsula um UUID")
    void wrapsUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(OrderId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("rejeita UUID nulo")
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> OrderId.of((UUID) null))
                .withMessageContaining("OrderId");
    }

    @Test
    @DisplayName("generate produz IDs distintos")
    void generateProducesDistinctIds() {
        assertThat(OrderId.generate()).isNotEqualTo(OrderId.generate());
    }

    @Test
    @DisplayName("toString delega para o UUID")
    void toStringDelegatesToUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(OrderId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    @DisplayName("igualdade segue o UUID interno")
    void equalityByValue() {
        UUID uuid = UUID.randomUUID();

        assertThat(OrderId.of(uuid)).isEqualTo(OrderId.of(uuid));
        assertThat(OrderId.of(uuid)).hasSameHashCodeAs(OrderId.of(uuid));
    }

    @Test
    @DisplayName("cria a partir de uma String UUID válida")
    void createsFromString() {
        String uuidStr = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(OrderId.of(uuidStr).toString()).isEqualTo(uuidStr);
    }

    @Test
    @DisplayName("rejeita String que não seja um UUID válido")
    void rejectsInvalidStringFormat() {
        assertThatThrownBy(() -> OrderId.of("id-invalido"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
