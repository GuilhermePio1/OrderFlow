package com.orderflow.order.adapter.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Emite eventos de auditoria das ações significativas do contexto Ordering,
 * conforme {@code docs/security.md} ("toda ação significativa produz um
 * evento de auditoria... quem, o quê, quando, de onde, para qual recurso,
 * com qual resultado"). Os registros vão para um logger dedicado
 * ({@code AUDIT}) que o {@code logback-spring.xml} serializa como JSON
 * estruturado ({@code StructuredArguments}) para a stack de observabilidade
 * rotear ao armazenamento append-only, separado dos logs de aplicação.
 *
 * O "quando" é o timestamp do próprio registro de log (UTC por configuração
 * da stack); aqui carregamos quem (actor), o quê (action), de onde (sourceIp),
 * recurso e resultado.
 */
@Component
class OrderAuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    void orderPlaced(UUID actor, UUID orderId, String sourceIp) {
        audit.info("{} {} {} {} {}",
                kv("action", "PLACE_ORDER"),
                kv("actor", actor),
                kv("resource", "order:" + orderId),
                kv("sourceIp", sourceIp),
                kv("result", "ACCEPTED"));
    }

    void orderCancellationRequested(UUID actor, UUID orderId, String sourceIp) {
        audit.info("{} {} {} {} {}",
                kv("action", "CANCEL_ORDER"),
                kv("actor", actor),
                kv("resource", "order:" + orderId),
                kv("sourceIp", sourceIp),
                kv("result", "ACCEPTED"));
    }

    void accessDenied(UUID actor, String operation, String resource, String sourceIp) {
        audit.warn("{} {} {} {} {}",
                kv("action", operation),
                kv("actor", actor),
                kv("resource", resource),
                kv("sourceIp", sourceIp),
                kv("result", "DENIED"));
    }
}
