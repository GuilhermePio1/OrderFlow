package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.command.RefundPaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGateway.RefundRequest;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.PaymentNotFoundException;
import com.orderflow.payment.domain.exception.RefundExceedsCapturedAmountException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.repository.PaymentRepository;

import java.util.Objects;

/**
 * Caso de uso de estorno administrativo (parcial ou total). Disparado pelo
 * adapter REST em operações de back-office ({@code docs/ddd.md}: "pagamentos
 * têm autorização, captura, estorno parcial"), ao contrário do estorno da
 * saga de compensação, que chega por {@code OrderCancelled}
 * ({@link CompensatePaymentUseCase}).
 *
 * <p>Diferente das bordas de mensageria — onde um pagamento ausente é
 * registrado e ignorado para não travar o consumer — aqui a requisição vem de
 * um operador e um alvo inexistente é um erro:
 * {@link PaymentNotFoundException} (traduzida em 404 na borda REST).
 *
 * <p><b>Ordem gateway → agregado.</b> As invariantes de domínio (estado
 * {@code CAPTURED}, valor dentro do saldo capturado remanescente, mesma moeda)
 * são pré-validadas <em>antes</em> de tocar no gateway: um estorno recusável
 * jamais chega ao provedor externo, evitando dinheiro devolvido no gateway sem
 * registro local. Só então o gateway é acionado e o agregado transiciona,
 * persistindo estado + {@code PaymentRefunded} na outbox numa única transação.
 *
 * <p><b>Falha técnica.</b> Uma indisponibilidade do gateway propaga como
 * {@link com.orderflow.payment.application.port.PaymentGatewayException}: nada
 * é persistido e o operador pode retentar. Uma corrida com a saga de
 * compensação emerge como
 * {@link com.orderflow.payment.domain.exception.ConcurrencyConflictException}
 * na escrita — o operador recarrega o pagamento e decide se o estorno ainda se
 * aplica.
 */
public final class RefundPaymentUseCase {

    private final PaymentRepository repository;
    private final PaymentGateway gateway;

    public RefundPaymentUseCase(PaymentRepository repository, PaymentGateway gateway) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * Executa o estorno e devolve o agregado no estado resultante, para a
     * borda REST responder com a visão atualizada.
     */
    public Payment execute(RefundPaymentCommand command) {
        Objects.requireNonNull(command, "command");

        Payment payment = repository.findById(command.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(command.paymentId()));

        if (payment.status() != PaymentStatus.CAPTURED) {
            throw new InvalidPaymentStateTransitionException(payment.status(), "refund");
        }
        Money remaining = payment.remainingCapturedAmount();
        if (command.amount().isGreaterThan(remaining)) {
            throw new RefundExceedsCapturedAmountException(command.amount(), remaining);
        }

        long expectedVersion = payment.version();
        gateway.refund(new RefundRequest(
                payment.id(),
                payment.gatewayTransactionId(),
                command.amount(),
                command.reason()
        ));

        payment.refund(command.amount(), command.reason());
        repository.save(payment, expectedVersion);
        return payment;
    }
}
