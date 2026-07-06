package com.orderflow.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mapeia as realm roles do Keycloak (claim {@code realm_access.roles}) para
 * authorities com prefixo {@code ROLE_}, habilitando o RBAC descrito em
 * {@code docs/security.md}. O conversor padrão do Spring lê apenas
 * {@code scope}/{@code scp}; o Keycloak entrega os papéis em outra claim, logo
 * sem este conversor o serviço não enxergaria nenhum role e toda autorização
 * baseada em papel falharia.
 */
public final class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?,?> claims)) {
            return List.of();
        }
        if (!(claims.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(KeycloakRealmRoleConverter::toAuthority)
                .toList();
    }

    private static GrantedAuthority toAuthority(String role) {
        String normalized = role.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        return new SimpleGrantedAuthority("ROLE_" + normalized);
    }
}
