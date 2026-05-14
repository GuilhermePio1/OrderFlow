package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TrackingNumber — value object")
class TrackingNumberTest {

    @Test
    @DisplayName("aceita código de rastreio preenchido corretamente")
    void acceptsNonBlankValue() {
        assertThat(TrackingNumber.of("BR123456789XX").value()).isEqualTo("BR123456789XX");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    @DisplayName("rejeita valores em branco/nulos")
    void rejectsBlank(String invalid) {
        assertThatThrownBy(() -> TrackingNumber.of(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tracking number");
    }

    @Test
    @DisplayName("igualdade considera o valor da string")
    void equalityByValue() {
        assertThat(TrackingNumber.of("ABC")).isEqualTo(TrackingNumber.of("ABC"));
        assertThat(TrackingNumber.of("ABC")).hasSameHashCodeAs(TrackingNumber.of("ABC"));
        assertThat(TrackingNumber.of("ABC")).isNotEqualTo(TrackingNumber.of("XYZ"));
    }
}
