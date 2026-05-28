package com.orderflow.payment.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PaymentId — value object")
class PaymentIdTest {

    @Test
    @DisplayName("encapsula um UUID")
    void wrapsUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(PaymentId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    @DisplayName("rejeita UUID nulo")
    void rejectsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> PaymentId.of((UUID) null))
                .withMessageContaining("PaymentId");
    }

    @Test
    @DisplayName("generate produz IDs distintos")
    void generateProducesDistinctIds() {
        assertThat(PaymentId.generate()).isNotEqualTo(PaymentId.generate());
    }

    @Test
    @DisplayName("toString delega para o UUID")
    void toStringDelegatesToUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(PaymentId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    @DisplayName("igualdade segue o UUID interno")
    void equalityByValue() {
        UUID uuid = UUID.randomUUID();

        assertThat(PaymentId.of(uuid)).isEqualTo(PaymentId.of(uuid));
        assertThat(PaymentId.of(uuid)).hasSameHashCodeAs(PaymentId.of(uuid));
    }

    @Test
    @DisplayName("cria a partir de uma String UUID válida")
    void createsFromString() {
        String uuidStr = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(PaymentId.of(uuidStr).toString()).isEqualTo(uuidStr);
    }

    @Test
    @DisplayName("rejeita String que não seja um UUID válido")
    void rejectsInvalidStringFormat() {
        assertThatThrownBy(() -> PaymentId.of("id-invalido"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
