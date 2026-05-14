package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Quantity — value object")
class QuantityTest {

    @Test
    @DisplayName("aceita valor positivo")
    void acceptsPositiveValue() {
        assertThat(Quantity.of(1).value()).isEqualTo(1);
        assertThat(new Quantity(7).value()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    @DisplayName("rejeita zero e valores negativos")
    void rejectsNonPositiveValues(int invalid) {
        assertThatThrownBy(() -> Quantity.of(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be positive")
                .hasMessageContaining(String.valueOf(invalid));
    }

    @Test
    @DisplayName("plus soma quantidades")
    void plusAddsValues() {
        assertThat(Quantity.of(2).plus(Quantity.of(3)).value()).isEqualTo(5);
    }

    @Test
    @DisplayName("igualdade segue o valor")
    void equalityByValue() {
        assertThat(Quantity.of(4)).isEqualTo(new Quantity(4));
        assertThat(Quantity.of(4)).hasSameHashCodeAs(Quantity.of(4));
        assertThat(Quantity.of(4)).isNotEqualTo(Quantity.of(5));
    }

    @Test
    @DisplayName("plus rejeita soma que causa integer overflow")
    void plusRejectsIntegerOverflow() {
        Quantity max = Quantity.of(Integer.MAX_VALUE);
        Quantity oneMore = Quantity.of(1);

        assertThatThrownBy(() -> max.plus(oneMore))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be positive");
    }
}
