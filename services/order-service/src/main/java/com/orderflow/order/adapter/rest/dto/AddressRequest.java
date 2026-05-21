package com.orderflow.order.adapter.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Endereço de entrega. {@code complement} e {@code neighborhood} são
 * opcionais — o value object {@code Address} só exige os demais campos.
 */
public record AddressRequest(

        @NotBlank
        @Size(max = 255, message = "street cannot exceed 255 characters")
        String street,

        @NotBlank
        @Size(max = 20, message = "number cannot exceed 20 characters")
        String number,

        @Size(max = 100, message = "complement cannot exceed 100 characters")
        String complement,

        @Size(max = 100, message = "neighborhood cannot exceed 100 characters")
        String neighborhood,

        @NotBlank
        @Size(max = 100, message = "city cannot exceed 100 characters")
        String city,

        @NotBlank
        @Size(min = 2, max = 50, message = "state must be between 2 and 50 characters")
        String state,

        @NotBlank
        @Size(max = 20, message = "postalCode cannot exceed 20 characters")
        String postalCode,

        @NotBlank
        @Size(min = 2, max = 2, message = "country must be a valid 2-letter ISO code") // Supondo ISO 3166-1 alpha-2
        String country
) {
}
