package com.orderflow.payment.adapter.rest;

import java.util.Objects;

/**
 * Identidade autenticada e origem da requisição, resolvidas uma única vez por
 * requisição pelo {@link AuthenticatedCallerArgumentResolver} e injetadas nos
 * handlers REST. Carrega o que a auditoria exige ({@code docs/security.md}:
 * "quem" e "de onde") sem acoplar os handlers aos detalhes do token ou do
 * transporte HTTP.
 *
 * @param actor    subject do JWT — identidade no Keycloak (usuário
 *                 administrativo ou service account)
 * @param sourceIp IP de origem, honrando {@code X-Forwarded-For} atrás do
 *                 API Gateway
 */
record AuthenticatedCaller(String actor, String sourceIp) {

    AuthenticatedCaller {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sourceIp, "sourceIp");
    }
}
