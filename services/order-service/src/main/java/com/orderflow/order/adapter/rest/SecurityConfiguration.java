package com.orderflow.order.adapter.rest;

import com.orderflow.security.EnableOrderFlowSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Cada serviço valida o JWT independentemente do gateway já tê-lo feito
 * ({@code docs/security.md}: "JWTs são verificados independente do gateway").
 * O plumbing de validação — decoder reativo com issuer, expiração e audience
 * contra o Keycloak (JWKS via descoberta OIDC preguiçosa), conversor de realm
 * roles — vem do módulo compartilhado {@code orderflow-security} via
 * {@link EnableOrderFlowSecurity}.
 *
 * Aqui permanece o que é político e por contexto: a autorização
 * coarse-grained — exige token válido com um dos roles necessários para a
 * rota (RBAC). A autorização fine-grained (ABAC: o cliente só acessa os
 * próprios pedidos) é aplicada nos handlers REST, que conhecem o
 * {@code customerId} do recurso.
 *
 * Probes de saúde ficam abertas para o Kubernetes; o restante exige
 * autenticação. API stateless: sem CSRF, sem sessão.
 */
@Configuration
@EnableWebFluxSecurity
@EnableOrderFlowSecurity
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
}
