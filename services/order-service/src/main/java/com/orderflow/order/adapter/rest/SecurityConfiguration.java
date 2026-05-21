package com.orderflow.order.adapter.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Cada serviço valida o JWT independentemente do gateway já tê-lo feito
 * ({@code docs/security.md}: "JWTs são verificados independente do gateway").
 * O resource server valida assinatura, expiração, issuer e audience contra
 * o Keycloak (chaves via JWKS, cacheadas). A autorização aqui é
 * coarse-grained — exige token válido; regras fine-grained (ABAC: cliente
 * só vê seus próprios pedidos) pertencem a uma camada de política posterior.
 *
 * Probes de saúde ficam abertas para o Kubernetes; o restante exige
 * autenticação. API stateless: sem CSRF, sem sessão.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
