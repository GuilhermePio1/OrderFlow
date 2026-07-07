package com.orderflow.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtAudienceValidator")
class JwtAudienceValidatorTest {

    private final JwtAudienceValidator validator =
            new JwtAudienceValidator(List.of("payment-service"));

    @Test
    @DisplayName("aceita token com a audience exigida")
    void acceptsRequiredAudience() {
        Jwt jwt = jwtWithAudience(List.of("payment-service"));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("rejeita token emitido para outro serviço do mesmo realm")
    void rejectsMissingAudience() {
        Jwt jwt = jwtWithAudience(List.of("order-service"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("rejeita token sem claim de audience")
    void rejectsAbsentAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("aceita token quando contém a audience exigida junto com outras")
    void acceptsWhenRequiredAudienceIsAmongMultiple() {
        // Token emitido para múltiplos microsserviços
        Jwt jwt = jwtWithAudience(List.of("inventory-service", "payment-service", "order-service"));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    private static Jwt jwtWithAudience(List<String> audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .audience(audience)
                .subject("user")
                .build();
    }
}
