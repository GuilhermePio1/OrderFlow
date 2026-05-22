package com.orderflow.order.adapter.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Cada serviço valida o JWT independentemente do gateway já tê-lo feito
 * ({@code docs/security.md}: "JWTs são verificados independente do gateway").
 * O resource server valida assinatura, expiração, issuer e audience contra o
 * Keycloak (chaves via JWKS, cacheadas pelo decoder).
 *
 * Aqui aplica-se a autorização coarse-grained — exige token válido com um
 * dos roles necessários para a rota (RBAC, papéis mapeados das realm roles
 * do Keycloak por {@link KeycloakRealmRoleConverter}). A autorização
 * fine-grained (ABAC: o cliente só acessa os próprios pedidos) é aplicada nos
 * handlers REST, que conhecem o {@code customerId} do recurso.
 *
 * Probes de saúde ficam abertas para o Kubernetes; o restante exige
 * autenticação. API stateless: sem CSRF, sem sessão.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api/orders/**").hasAnyRole("CUSTOMER", "ORDER_ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(delegate);
    }

    /**
     * Decoder com validação de issuer, timestamp e audience. Construído de
     * forma preguiçosa ({@link SupplierReactiveJwtDecoder}) para que a
     * descoberta OIDC só ocorra no primeiro token recebido — o serviço sobe
     * sem exigir o Keycloak disponível no startup.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${orderflow.security.audiences}") List<String> audiences) {
        return new SupplierReactiveJwtDecoder(() -> {
            NimbusReactiveJwtDecoder decoder =
                    (NimbusReactiveJwtDecoder) ReactiveJwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new JwtAudienceValidator(audiences));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
