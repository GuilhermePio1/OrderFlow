package com.orderflow.security;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Ativa o plumbing compartilhado de validação de JWT do OrderFlow
 * ({@code docs/security.md}): decoder validando assinatura (JWKS do
 * Keycloak, descoberta OIDC preguiçosa), expiração, issuer e audience
 * ({@code orderflow.security.audiences}), e conversor das realm roles do
 * Keycloak em authorities {@code ROLE_} para o RBAC.
 *
 * A configuração adequada à stack do serviço é selecionada automaticamente
 * ({@link ServletJwtSecurityConfiguration} para MVC,
 * {@link ReactiveJwtSecurityConfiguration} para WebFlux). A <em>política</em>
 * de autorização — regras de rota e papéis na {@code SecurityFilterChain} —
 * permanece deliberadamente em cada serviço: ela é por contexto e "codificada
 * em código, não em configuração externa" ({@code docs/security.md}).
 *
 * Propriedades exigidas:
 * <ul>
 *   <li>{@code spring.security.oauth2.resourceserver.jwt.issuer-uri}</li>
 *   <li>{@code orderflow.security.audiences}</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({ServletJwtSecurityConfiguration.class, ReactiveJwtSecurityConfiguration.class})
public @interface EnableOrderFlowSecurity {
}
