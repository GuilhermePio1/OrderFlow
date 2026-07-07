package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.adapter.rest.dto.PaymentResponse;
import com.orderflow.payment.adapter.rest.dto.RefundPaymentRequest;
import com.orderflow.payment.application.command.RefundPaymentCommand;
import com.orderflow.payment.application.usecase.RefundPaymentUseCase;
import com.orderflow.payment.domain.exception.PaymentNotFoundException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.model.valueobject.PaymentId;
import com.orderflow.payment.domain.repository.PaymentRepository;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Adapter de borda (Spring MVC + virtual threads) do contexto Payment.
 * Toda a API é administrativa (back-office), restrita ao papel
 * {@code PAYMENT_ADMIN} pela autorização coarse-grained em
 * {@link SecurityConfiguration}:
 *
 * <ul>
 *   <li>{@code GET /api/payments/{id}} — consulta o pagamento pelo seu
 *       identificador;</li>
 *   <li>{@code GET /api/payments/by-order/{orderId}} — consulta pelo pedido
 *       de origem (o vocabulário com que chegam disputas e chamados);</li>
 *   <li>{@code POST /api/payments/{id}/refund} — estorno administrativo,
 *       parcial ou total, de um pagamento capturado (atendimento ao cliente,
 *       resolução de disputa).</li>
 * </ul>
 *
 * As transições do fluxo de negócio (autorização, captura, compensação da
 * saga) não são expostas como REST: chegam por eventos Kafka na coreografia
 * descrita em {@code docs/architecture.md}. Cada ação administrativa —
 * inclusive leitura de dados de cliente — gera um registro de auditoria
 * ({@code docs/security.md}); a identidade e a origem do chamador chegam
 * resolvidas como {@link AuthenticatedCaller}.
 */
@RestController
@RequestMapping(path = "/api/payments", produces = MediaType.APPLICATION_JSON_VALUE)
class PaymentAdminController {

    private final PaymentRepository paymentRepository;
    private final RefundPaymentUseCase refundPaymentUseCase;
    private final PaymentAuditLogger auditLogger;

    PaymentAdminController(
            PaymentRepository paymentRepository,
            RefundPaymentUseCase refundPaymentUseCase,
            PaymentAuditLogger auditLogger
    ) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
        this.refundPaymentUseCase = Objects.requireNonNull(refundPaymentUseCase, "refundPaymentUseCase");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    @GetMapping("/{paymentId}")
    PaymentResponse getPayment(
            @PathVariable("paymentId") String paymentId,
            AuthenticatedCaller caller
    ) {
        PaymentId id = PaymentId.of(paymentId);
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        auditLogger.paymentViewed(caller.actor(), id, caller.sourceIp());
        return PaymentRestMapper.toResponse(payment);
    }

    @GetMapping("/by-order/{orderId}")
    PaymentResponse getPaymentByOrder(
            @PathVariable("orderId") String orderId,
            AuthenticatedCaller caller
    ) {
        OrderId id = OrderId.of(orderId);
        Payment payment = paymentRepository.findByOrderId(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
        auditLogger.paymentViewedByOrder(caller.actor(), id, payment.id(), caller.sourceIp());
        return PaymentRestMapper.toResponse(payment);
    }

    @PostMapping(path = "/{paymentId}/refund", consumes = MediaType.APPLICATION_JSON_VALUE)
    PaymentResponse refundPayment(
            @PathVariable("paymentId") String paymentId,
            @Valid @RequestBody RefundPaymentRequest body,
            AuthenticatedCaller caller
    ) {
        PaymentId id = PaymentId.of(paymentId);
        Money amount = toMoney(body.amount(), body.currency());
        Payment payment = refundPaymentUseCase.execute(
                new RefundPaymentCommand(id, amount, body.reason()));
        auditLogger.refundExecuted(caller.actor(), id, amount, caller.sourceIp());
        return PaymentRestMapper.toResponse(payment);
    }

    private static Money toMoney(BigDecimal amount, String currencyCode) {
        // Currency.getInstance lança IllegalArgumentException para códigos
        // desconhecidos — traduzida em 400 pelo exception handler.
        return Money.of(amount, Currency.getInstance(currencyCode));
    }
}
