package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.command.CompensatePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGateway.RefundRequest;
import com.orderflow.payment.application.port.PaymentGateway.VoidRequest;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso de compensação do pagamento. Disparado pelo consumer de
 * {@code OrderCancelled}, executa o passo do Payment na saga de compensação
 * coreografada ({@code docs/architecture.md}, "Saga de Compensação"): "o Payment
 * Service consome a transição e estorna o pagamento".
 *
 * <p>A reversão depende do estado em que o pagamento se encontra — e essa decisão
 * vive aqui, não no contexto Ordering:
 * <ul>
 *   <li>{@code AUTHORIZED} (ainda não capturado) → cancela a autorização junto ao
 *       gateway e emite {@code PaymentVoided}, liberando o limite retido;</li>
 *   <li>{@code CAPTURED} → estorna o valor ainda capturado e emite
 *       {@code PaymentRefunded};</li>
 *   <li>{@code PENDING}, {@code FAILED}, {@code VOIDED}, {@code REFUNDED} → nada a
 *       compensar (nunca houve cobrança) ou já compensado: no-op idempotente.</li>
 * </ul>
 *
 * <p><b>Idempotência</b> (entrega "at least once"): reentregas de
 * {@code OrderCancelled} não revertem duas vezes — estados já terminais são
 * no-op, e uma corrida entre consumers emerge como
 * {@link com.orderflow.payment.domain.exception.ConcurrencyConflictException} na
 * escrita, tratada na borda como reentrega já liquidada.
 *
 * <p><b>Falha técnica.</b> Uma indisponibilidade do gateway propaga como
 * {@link com.orderflow.payment.application.port.PaymentGatewayException}: nada é
 * persistido e o consumer pode retentar a compensação.
 */
public final class CompensatePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompensatePaymentUseCase.class);

    private final PaymentRepository repository;
    private final PaymentGateway gateway;

    public CompensatePaymentUseCase(PaymentRepository repository, PaymentGateway gateway) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public void execute(CompensatePaymentCommand command) {
        Objects.requireNonNull(command, "command");

        Optional<Payment> found = repository.findByOrderId(command.orderId());
        if (found.isEmpty()) {
            // Cancelamento de um pedido cujo pagamento nunca foi iniciado (ex.: o
            // próprio PaymentFailed disparou o cancelamento, mas a leitura ocorre
            // por outro caminho). Nada a reverter.
            log.info("OrderCancelled para o pedido {} sem pagamento correspondente; "
                    + "nada a compensar", command.orderId());
            return;
        }

        Payment payment = found.get();
        long expectedVersion = payment.version();
        switch (payment.status()) {
            case AUTHORIZED -> {
                gateway.voidAuthorization(new VoidRequest(
                        payment.id(), payment.gatewayTransactionId(), command.reason()));
                payment.voidAuthorization(command.reason());
                repository.save(payment, expectedVersion);
            }
            case CAPTURED -> {
                Money toRefund = payment.remainingCapturedAmount();
                gateway.refund(new RefundRequest(
                        payment.id(), payment.gatewayTransactionId(), toRefund, command.reason()));
                payment.refund(toRefund, command.reason());
                repository.save(payment, expectedVersion);
            }
            case PENDING, FAILED, VOIDED, REFUNDED -> {
                // Nada a compensar (sem cobrança efetiva) ou já compensado por uma
                // entrega anterior — no-op idempotente.
                log.debug("Pagamento do pedido {} em estado {} não requer compensação",
                        command.orderId(), payment.status());
            }
        }
    }
}
