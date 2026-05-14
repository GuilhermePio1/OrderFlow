package com.orderflow.order.domain.model.valueobject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Address — value object")
class AddressTest {

    private static Address valid() {
        return new Address(
                "Rua das Flores",
                "100",
                "Apto 12",
                "Centro",
                "São Paulo",
                "SP",
                "01000-000",
                "BR"
        );
    }

    @Test
    @DisplayName("aceita endereço completo válido")
    void acceptsValidAddress() {
        Address address = valid();

        assertThat(address.street()).isEqualTo("Rua das Flores");
        assertThat(address.city()).isEqualTo("São Paulo");
        assertThat(address.country()).isEqualTo("BR");
    }

    @Test
    @DisplayName("complement e neighborhood são opcionais (podem ser nulos/vazios)")
    void allowsOptionalComplementAndNeighborhood() {
        Address address = new Address(
                "Rua das Flores",
                "100",
                null,
                null,
                "São Paulo",
                "SP",
                "01000-000",
                "BR"
        );

        assertThat(address.complement()).isNull();
        assertThat(address.neighborhood()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("rejeita street em branco")
    void rejectsBlankStreet(String invalid) {
        assertThatThrownBy(() -> new Address(invalid, "100", null, null, "City", "ST", "00000", "BR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("street");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    @DisplayName("rejeita number em branco")
    void rejectsBlankNumber(String invalid) {
        assertThatThrownBy(() -> new Address("Rua", invalid, null, null, "City", "ST", "00000", "BR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    @DisplayName("rejeita city em branco")
    void rejectsBlankCity(String invalid) {
        assertThatThrownBy(() -> new Address("Rua", "100", null, null, invalid, "ST", "00000", "BR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("city");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    @DisplayName("rejeita state em branco")
    void rejectsBlankState(String invalid) {
        assertThatThrownBy(() -> new Address("Rua", "100", null, null, "City", invalid, "00000", "BR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    @DisplayName("rejeita postalCode em branco")
    void rejectsBlankPostalCode(String invalid) {
        assertThatThrownBy(() -> new Address("Rua", "100", null, null, "City", "ST", invalid, "BR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postalCode");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t", "\n"})
    @DisplayName("rejeita country em branco")
    void rejectsBlankCountry(String invalid) {
        assertThatThrownBy(() -> new Address("Rua", "100", null, null, "City", "ST", "00000", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");
    }

    @Test
    @DisplayName("igualdade considera todos os atributos")
    void equalityByValue() {
        assertThat(valid()).isEqualTo(valid());
        assertThat(valid()).hasSameHashCodeAs(valid());
    }
}
