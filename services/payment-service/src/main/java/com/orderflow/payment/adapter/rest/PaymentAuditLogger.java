package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Emite eventos de auditoria das ações administrativas do contexto Payment,
 * conforme {@code docs/security.md} ("toda ação significativa produz um
 * evento de auditoria... quem, o quê, quando, de onde, para qual recurso,
 * com qual resultado" — e "operações administrativas geram logs
 * detalhados"). Os registros vão para um logger dedicado ({@code AUDIT})
 * que o {@code logback-spring.xml} serializa como JSON estruturado
 * ({@code StructuredArguments}: valores monetários indexados como numéricos)
 * para a stack de observabilidade rotear ao armazenamento append-only,
 * separado dos logs de aplicação.
 *
 * O "quando" é o timestamp do próprio registro de log (UTC por configuração
 * da stack); aqui carregamos quem (actor), o quê (action), de onde (sourceIp),
 * recurso e resultado. Nenhum dado PCI é logado — apenas identificadores e
 * valores monetários ({@code docs/security.md}, "Cards são jamais logados").
 */
@Component
class PaymentAuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    void paymentViewed(String actor, PaymentId paymentId, String sourceIp) {
        audit.info("{} {} {} {} {}",
                kv("action", "VIEW_PAYMENT"),
                kv("actor", actor),
                kv("resource", "payment:" + paymentId),
                kv("sourceIp", sourceIp),
                kv("result", "OK"));
    }

    void paymentViewedByOrder(String actor, OrderId orderId, PaymentId paymentId, String sourceIp) {
        audit.info("{} {} {} {} {} {}",
                kv("action", "VIEW_PAYMENT"),
                kv("actor", actor),
                kv("resource", "payment:" + paymentId),
                kv("order", orderId.toString()),
                kv("sourceIp", sourceIp),
                kv("result", "OK"));
    }

    void refundExecuted(String actor, PaymentId paymentId, Money amount, String sourceIp) {
        audit.info("{} {} {} {} {} {} {}",
                kv("action", "REFUND_PAYMENT"),
                kv("actor", actor),
                kv("resource", "payment:" + paymentId),
                kv("amount", amount.amount()),
                kv("currency", amount.currency().getCurrencyCode()),
                kv("sourceIp", sourceIp),
                kv("result", "OK"));
    }
}
