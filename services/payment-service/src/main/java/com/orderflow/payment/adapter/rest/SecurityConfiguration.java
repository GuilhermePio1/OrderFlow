package com.orderflow.payment.adapter.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Cada serviço valida o JWT independentemente do gateway já tê-lo feito
 * ({@code docs/security.md}: "JWTs são verificados independente do gateway").
 * O resource server valida assinatura, expiração, issuer e audience contra o
 * Keycloak (chaves via JWKS, cacheadas pelo decoder).
 *
 * Aqui aplica-se a autorização coarse-grained — o contexto Payment não expõe
 * rotas de cliente final: toda a API REST é administrativa (back-office),
 * restrita ao papel {@code PAYMENT_ADMIN} (RBAC, papéis mapeados das realm
 * roles do Keycloak por {@link KeycloakRealmRoleConverter}). O fluxo de
 * negócio (autorização, captura, compensação) chega por eventos Kafka, não
 * por REST.
 *
 * Probes de saúde ficam abertas para o Kubernetes; o restante exige
 * autenticação. API stateless: sem CSRF, sem sessão.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/payments/**").hasRole("PAYMENT_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Decoder com validação de issuer, timestamp e audience. Construído de
     * forma preguiçosa ({@link SupplierJwtDecoder}) para que a descoberta
     * OIDC só ocorra no primeiro token recebido — o serviço sobe sem exigir
     * o Keycloak disponível no startup.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${orderflow.security.audiences}") List<String> audiences) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder =
                    (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuerUri),
                    new JwtAudienceValidator(audiences));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
