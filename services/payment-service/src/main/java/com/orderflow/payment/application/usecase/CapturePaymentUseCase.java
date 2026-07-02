package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.command.CapturePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGateway.CaptureRequest;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso de captura de pagamento. Disparado pelo consumer de
 * {@code OrderConfirmed}: a autorização retida na colocação do pedido é
 * efetivamente capturada quando o pedido é confirmado (pagamento autorizado +
 * estoque reservado, vide {@code docs/architecture.md}).
 *
 * <p>Solicita a captura ao gateway externo através da Anti-Corruption Layer
 * ({@link PaymentGateway}) e, só então, transiciona o agregado para
 * {@code CAPTURED}, persistindo estado + {@code PaymentCaptured} na outbox numa
 * única transação.
 *
 * <p><b>Idempotência</b> (entrega "at least once"): reentregas de
 * {@code OrderConfirmed} não capturam duas vezes. Se o pagamento já está
 * {@code CAPTURED}, o caso de uso retorna sem tocar no gateway. Uma corrida entre
 * consumers emerge como {@link com.orderflow.payment.domain.exception.ConcurrencyConflictException}
 * na escrita — tratada na borda de mensageria como reentrega já liquidada.
 *
 * <p><b>Falha técnica.</b> Uma indisponibilidade do gateway propaga como
 * {@link com.orderflow.payment.application.port.PaymentGatewayException}: nada é
 * persistido e o consumer pode retentar.
 */
public final class CapturePaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CapturePaymentUseCase.class);

    private final PaymentRepository repository;
    private final PaymentGateway gateway;

    public CapturePaymentUseCase(PaymentRepository repository, PaymentGateway gateway) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    public void execute(CapturePaymentCommand command) {
        Objects.requireNonNull(command, "command");

        Optional<Payment> found = repository.findByOrderId(command.orderId());
        if (found.isEmpty()) {
            // OrderConfirmed sem pagamento correspondente não deveria ocorrer dada
            // a ordenação da saga (a confirmação sucede a autorização). Não é uma
            // poison pill: registra e segue, evitando bloquear o consumer.
            log.warn("OrderConfirmed para o pedido {} sem pagamento correspondente; "
                    + "nada a capturar", command.orderId());
            return;
        }

        Payment payment = found.get();
        PaymentStatus status = payment.status();
        if (status == PaymentStatus.CAPTURED) {
            return; // idempotente — OrderConfirmed reentregue
        }
        if (status != PaymentStatus.AUTHORIZED) {
            // Estado inesperado para captura (terminal ou ainda pendente): falha de
            // invariante da saga. Surface-a sem efeito colateral no gateway.
            throw new InvalidPaymentStateTransitionException(status, "capture");
        }

        long expectedVersion = payment.version();
        gateway.capture(new CaptureRequest(
                payment.id(),
                payment.gatewayTransactionId(),
                payment.authorizedAmount()
        ));

        payment.capture();
        repository.save(payment, expectedVersion);
    }
}
