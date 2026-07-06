package com.orderflow.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Objects;

/**
 * Exige que a claim {@code aud} do JWT contenha ao menos uma das audiences
 * configuradas ({@code docs/security.md}: tokens são validados verificando
 * "audience"). Sem esta checagem, um token válido emitido pelo mesmo realm
 * para outro serviço seria aceito aqui — violando o princípio de menor
 * privilégio e o isolamento entre serviços.
 */
public final class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
            "invalid_token", "The required audience is missing", null);

    private final List<String> acceptedAudiences;

    public JwtAudienceValidator(List<String> acceptedAudiences) {
        this.acceptedAudiences = List.copyOf(
                Objects.requireNonNull(acceptedAudiences, "acceptedAudiences"));
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audience = token.getAudience();
        if (audience != null && audience.stream().anyMatch(acceptedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}
