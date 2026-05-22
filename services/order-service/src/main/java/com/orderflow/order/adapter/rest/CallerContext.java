package com.orderflow.order.adapter.rest;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resolve a identidade autenticada a partir do contexto reativo de
 * segurança. Concentra a leitura do subject e do papel privilegiado para que
 * os handlers REST apliquem autorização fine-grained sem se acoplar aos
 * detalhes do token.
 */
final class CallerContext {

    private static final String PRIVILEGED_ROLE = "ROLE_ORDER_ADMIN";

    private CallerContext() {
    }

    static Mono<AuthenticatedCaller> currentCaller() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .cast(JwtAuthenticationToken.class)
                .map(CallerContext::toCaller);
    }

    private static AuthenticatedCaller toCaller(JwtAuthenticationToken token) {
        boolean privileged = token.getAuthorities().stream()
                .anyMatch(authority -> PRIVILEGED_ROLE.equals(authority.getAuthority()));
        return new AuthenticatedCaller(subjectOf(token), privileged);
    }

    private static UUID subjectOf(JwtAuthenticationToken token) {
        try {
            return UUID.fromString(token.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccessDeniedException("Token subject is not a valid customer identity");
        }
    }
}
