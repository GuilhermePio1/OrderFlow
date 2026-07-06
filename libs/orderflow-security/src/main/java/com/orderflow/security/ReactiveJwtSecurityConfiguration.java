package com.orderflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

/**
 * Plumbing JWT para serviços reativos (WebFlux). Espelha
 * {@link ServletJwtSecurityConfiguration} na variante reativa: decoder com
 * validação de issuer, timestamp e audience — construído de forma preguiçosa
 * ({@link SupplierReactiveJwtDecoder}) para que a descoberta OIDC só ocorra
 * no primeiro token recebido — e conversor de authorities do Keycloak
 * adaptado ao contrato reativo.
 *
 * Beans marcados {@code @ConditionalOnMissingBean}: um serviço com requisito
 * divergente pode declarar o próprio bean e sobrepor o padrão.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(OrderFlowSecurityProperties.class)
class ReactiveJwtSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    @Bean
    @ConditionalOnMissingBean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            OrderFlowSecurityProperties securityProperties) {
        return new SupplierReactiveJwtDecoder(() -> {
            NimbusReactiveJwtDecoder decoder =
                    (NimbusReactiveJwtDecoder) ReactiveJwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new JwtAudienceValidator(securityProperties.audiences()));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
