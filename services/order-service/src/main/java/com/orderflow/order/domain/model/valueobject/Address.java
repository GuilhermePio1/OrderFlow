package com.orderflow.order.domain.model.valueobject;

public record Address(
        String street,
        String number,
        String complement,
        String neighborhood,
        String city,
        String state,
        String postalCode,
        String country
) {

    public Address {
        requireNonBlank(street, "street");
        requireNonBlank(number, "number");
        requireNonBlank(city, "city");
        requireNonBlank(state, "state");
        requireNonBlank(postalCode, "postalCode");
        requireNonBlank(country, "country");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Address." + field + " must not be blank");
        }
    }
}
