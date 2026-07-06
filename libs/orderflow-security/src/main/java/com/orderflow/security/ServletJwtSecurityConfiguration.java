package com.orderflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Plumbing JWT para serviços servlet (Spring MVC + virtual threads). Provê o
 * decoder com validação de issuer, timestamp e audience e o conversor de
 * authorities do Keycloak. O decoder é construído de forma preguiçosa
 * ({@link SupplierJwtDecoder}) para que a descoberta OIDC só ocorra no
 * primeiro token recebido — o serviço sobe sem exigir o Keycloak disponível
 * no startup.
 *
 * Beans marcados {@code @ConditionalOnMissingBean}: um serviço com requisito
 * divergente pode declarar o próprio bean e sobrepor o padrão.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OrderFlowSecurityProperties.class)
class ServletJwtSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            OrderFlowSecurityProperties securityProperties) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder =
                    (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new JwtAudienceValidator(securityProperties.audiences()));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
