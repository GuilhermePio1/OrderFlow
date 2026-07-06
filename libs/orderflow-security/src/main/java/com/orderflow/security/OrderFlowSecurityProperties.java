package com.orderflow.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * Propriedades de segurança comuns aos serviços OrderFlow.
 *
 * @param audiences audiences aceitas no claim {@code aud} do JWT (lista
 *                  separada por vírgula na chave
 *                  {@code orderflow.security.audiences}) — tipicamente o
 *                  clientId do próprio serviço no Keycloak
 */
@ConfigurationProperties("orderflow.security")
public record OrderFlowSecurityProperties(List<String> audiences) {

    public OrderFlowSecurityProperties {
        Objects.requireNonNull(audiences, "orderflow.security.audiences is required");
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException("orderflow.security.audiences must not be empty");
        }
        audiences = List.copyOf(audiences);
    }
}
