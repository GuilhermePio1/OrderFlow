package com.orderflow.order.adapter.rest;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidade autenticada resolvida do JWT. {@code id} é o subject do token
 * — o identificador do cliente no Keycloak. {@code privileged} indica um
 * chamador com papel administrativo/serviço, autorizado a agir em nome de
 * outros clientes.
 *
 * Sustenta a regra ABAC de {@code docs/security.md} ("um cliente pode ver
 * seus próprios pedidos mas não os de outros"): a autorização depende da
 * relação entre a identidade do chamador e o atributo {@code customerId} do
 * recurso.
 */
record AuthenticatedCaller(UUID id, boolean privileged) {

    AuthenticatedCaller {
        Objects.requireNonNull(id, "id");
    }

    boolean canActFor(UUID customerId) {
        return privileged || id.equals(customerId);
    }
}
