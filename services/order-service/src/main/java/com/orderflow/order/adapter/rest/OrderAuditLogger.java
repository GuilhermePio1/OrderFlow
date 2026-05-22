package com.orderflow.order.adapter.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Emite eventos de auditoria das ações significativas do contexto Ordering,
 * conforme {@code docs/security.md} ("toda ação significativa produz um
 * evento de auditoria... quem, o quê, quando, de onde, para qual recurso,
 * com qual resultado"). Os registros vão para um logger dedicado
 * ({@code AUDIT}) que a stack de observabilidade roteia para o armazenamento
 * append-only, separado dos logs de aplicação.
 *
 * O "quando" é o timestamp do próprio registro de log (UTC por configuração
 * da stack); aqui carregamos quem (actor), o quê (action), de onde (sourceIp),
 * recurso e resultado.
 */
@Component
class OrderAuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    void orderPlaced(UUID actor, UUID orderId, String sourceIp) {
        audit.info("action=PLACE_ORDER actor={} resource=order:{} sourceIp={} result=ACCEPTED",
                actor, orderId, sourceIp);
    }

    void orderCancellationRequested(UUID actor, UUID orderId, String sourceIp) {
        audit.info("action=CANCEL_ORDER actor={} resource=order:{} sourceIp={} result=ACCEPTED",
                actor, orderId, sourceIp);
    }

    void accessDenied(UUID actor, String operation, String resource, String sourceIp) {
        audit.warn("action={} actor={} resource={} sourceIp={} result=DENIED",
                operation, actor, resource, sourceIp);
    }
}
