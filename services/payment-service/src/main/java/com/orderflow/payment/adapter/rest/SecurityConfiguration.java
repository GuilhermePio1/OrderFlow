package com.orderflow.payment.adapter.rest;

import com.orderflow.security.EnableOrderFlowSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cada serviço valida o JWT independentemente do gateway já tê-lo feito
 * ({@code docs/security.md}: "JWTs são verificados independente do gateway").
 * O plumbing de validação — decoder com issuer, expiração e audience contra o
 * Keycloak, conversor de realm roles — vem do módulo compartilhado
 * {@code orderflow-security} via {@link EnableOrderFlowSecurity}.
 *
 * Aqui permanece o que é político e por contexto: a autorização
 * coarse-grained. O contexto Payment não expõe rotas de cliente final: toda a
 * API REST é administrativa (back-office), restrita ao papel
 * {@code PAYMENT_ADMIN}. O fluxo de negócio (autorização, captura,
 * compensação) chega por eventos Kafka, não por REST.
 *
 * Probes de saúde ficam abertas para o Kubernetes; o restante exige
 * autenticação. API stateless: sem CSRF, sem sessão.
 */
@Configuration
@EnableWebSecurity
@EnableOrderFlowSecurity
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
}
