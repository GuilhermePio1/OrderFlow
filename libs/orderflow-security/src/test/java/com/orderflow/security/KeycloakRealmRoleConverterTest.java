package com.orderflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KeycloakRealmRoleConverter")
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    @DisplayName("mapeia realm roles para authorities ROLE_ normalizadas")
    void mapsRealmRolesToAuthorities() {
        Jwt jwt = jwtWithRealmRoles(List.of("customer", "payment-admin"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_PAYMENT_ADMIN");
    }

    @Test
    @DisplayName("retorna vazio quando não há claim realm_access")
    void emptyWhenNoRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("retorna vazio quando realm_access não tem roles")
    void emptyWhenNoRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of())
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("ignora elementos na lista de roles que não são strings")
    void ignoresNonStringRoles() {
        // Simula um payload onde o Keycloak (ou um atacante) enviou um número e um booleano na lista
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("payment-admin", 123, true)))
                .build();

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PAYMENT_ADMIN");
    }

    @Test
    @DisplayName("retorna vazio quando claim roles não é uma coleção")
    void emptyWhenRolesIsNotACollection() {
        // Simula a claim "roles" sendo uma String simples ao invés de um Array/List
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", "invalid_type"))
                .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", roles))
                .build();
    }
}
