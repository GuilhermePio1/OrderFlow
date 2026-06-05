package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.command.AuthorizePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGateway.AuthorizationRequest;
import com.orderflow.payment.application.port.PaymentGateway.AuthorizationResult;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.valueobject.PaymentId;
import com.orderflow.payment.domain.repository.PaymentRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Caso de uso de autorização de pagamento. Disparado pelo consumer de
 * {@code OrderPlaced}: inicia o agregado {@link Payment}, solicita autorização
 * ao gateway externo através da Anti-Corruption Layer ({@link PaymentGateway})
 * e registra o resultado já traduzido para o vocabulário interno —
 * {@code PaymentAuthorized} ou {@code PaymentFailed} — persistindo estado +
 * outbox numa única transação (vide {@code docs/architecture.md}).
 *
 * <p><b>Idempotência.</b> Reentregas de {@code OrderPlaced} não criam
 * pagamentos duplicados: se já existe um {@link Payment} para o pedido, o caso
 * de uso devolve seu identificador sem tocar no gateway (vide
 * {@link PaymentRepository#findByOrderId}).
 *
 * <p><b>Declínio vs. falha técnica.</b> Um declínio de negócio (cartão
 * recusado, saldo insuficiente) é um resultado normal e leva o pagamento a
 * {@code FAILED}, num evento que o contexto Ordering consome para cancelar o
 * pedido. Já uma falha técnica do gateway propaga como
 * {@link com.orderflow.payment.application.port.PaymentGatewayException}: nada é
 * persistido e o consumer pode retentar, evitando marcar como definitivamente
 * falho um pagamento que apenas esbarrou numa indisponibilidade transitória.
 *
 * <p>Não há retentativa por concorrência otimista aqui: a criação carrega
 * {@code expectedVersion = 0}, e a janela entre {@code findByOrderId} e
 * {@code save} é fechada pela unicidade de {@code orderId} na persistência —
 * uma corrida emerge como {@code ConcurrencyConflictException}.
 */
public final class AuthorizePaymentUseCase {

    private final PaymentRepository repository;
    private final PaymentGateway gateway;
    private final Clock clock;

    public AuthorizePaymentUseCase(PaymentRepository repository, PaymentGateway gateway, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PaymentId execute(AuthorizePaymentCommand command) {
        Objects.requireNonNull(command, "command");

        Optional<Payment> existing = repository.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            return existing.get().id(); // idempotente — OrderPlaced reentregue
        }

        PaymentId paymentId = PaymentId.generate();
        Payment payment = Payment.initiate(
                paymentId,
                command.orderId(),
                command.customerId(),
                command.amount(),
                command.method(),
                clock
        );

        AuthorizationResult result = gateway.authorize(new AuthorizationRequest(
                paymentId,
                command.orderId(),
                command.customerId(),
                command.amount(),
                command.method()
        ));

        applyResult(payment, result);

        repository.save(payment, 0L);
        return payment.id();
    }

    private static void applyResult(Payment payment, AuthorizationResult result) {
        switch (result) {
            case AuthorizationResult.Approved approved ->
                payment.authorize(approved.transactionId(), approved.authorizationCode());
            case AuthorizationResult.Declined declined ->
                payment.fail(declined.reason(), declined.details());
        }
    }
}
