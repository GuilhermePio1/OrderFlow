package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emite eventos de auditoria das ações administrativas do contexto Payment,
 * conforme {@code docs/security.md} ("toda ação significativa produz um
 * evento de auditoria... quem, o quê, quando, de onde, para qual recurso,
 * com qual resultado" — e "operações administrativas geram logs
 * detalhados"). Os registros vão para um logger dedicado ({@code AUDIT})
 * que a stack de observabilidade roteia para o armazenamento append-only,
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
        audit.info("action=VIEW_PAYMENT actor={} resource=payment:{} sourceIp={} result=OK",
                actor, paymentId, sourceIp);
    }

    void paymentViewedByOrder(String actor, OrderId orderId, PaymentId paymentId, String sourceIp) {
        audit.info("action=VIEW_PAYMENT actor={} resource=payment:{} order={} sourceIp={} result=OK",
                actor, paymentId, orderId, sourceIp);
    }

    void refundExecuted(String actor, PaymentId paymentId, Money amount, String sourceIp) {
        audit.info("action=REFUND_PAYMENT actor={} resource=payment:{} amount={} {} sourceIp={} result=OK",
                actor, paymentId, amount.amount(), amount.currency().getCurrencyCode(), sourceIp);
    }
}
