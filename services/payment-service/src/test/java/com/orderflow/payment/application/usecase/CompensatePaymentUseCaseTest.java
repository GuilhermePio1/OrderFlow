package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.InMemoryPaymentRepository;
import com.orderflow.payment.application.ScriptedPaymentGateway;
import com.orderflow.payment.application.command.CompensatePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway.RefundRequest;
import com.orderflow.payment.application.port.PaymentGateway.VoidRequest;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentRefunded;
import com.orderflow.payment.domain.event.PaymentVoided;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompensatePaymentUseCase — compensação disparada por OrderCancelled")
class CompensatePaymentUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);

    private static final OrderId ORDER_ID = OrderId.of(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = CustomerId.of(UUID.randomUUID());
    private static final Money AMOUNT = Money.of("149.90", "BRL");
    private static final PaymentMethod METHOD = PaymentMethod.CREDIT_CARD;
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");
    private static final String REASON = "Order cancelled: OUT_OF_STOCK";

    private InMemoryPaymentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentRepository(CLOCK);
    }

    private static Payment newPayment() {
        return Payment.initiate(PaymentId.generate(), ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD, CLOCK);
    }

    private Payment seedAuthorized() {
        Payment payment = newPayment();
        payment.authorize(GW_TX, AUTH_CODE);
        repository.seed(payment);
        return payment;
    }

    private Payment seedCaptured() {
        Payment payment = newPayment();
        payment.authorize(GW_TX, AUTH_CODE);
        payment.capture();
        repository.seed(payment);
        return payment;
    }

    private CompensatePaymentUseCase useCaseWith(ScriptedPaymentGateway gateway) {
        return new CompensatePaymentUseCase(repository, gateway);
    }

    private static CompensatePaymentCommand command() {
        return new CompensatePaymentCommand(ORDER_ID, REASON);
    }

    @Nested
    @DisplayName("pagamento apenas autorizado")
    class AuthorizedNotCaptured {

        @Test
        @DisplayName("cancela a autorização, transiciona para VOIDED e publica PaymentVoided")
        void voidsAuthorization() {
            Payment authorized = seedAuthorized();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            useCaseWith(gateway).execute(command());

            Payment stored = repository.findById(authorized.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.VOIDED);

            assertThat(gateway.voids()).hasSize(1);
            VoidRequest request = gateway.lastVoid();
            assertThat(request.paymentId()).isEqualTo(authorized.id());
            assertThat(request.gatewayTransactionId()).isEqualTo(GW_TX);

            assertThat(repository.events(authorized.id()))
                    .last().isInstanceOfSatisfying(PaymentVoided.class,
                            e -> assertThat(e.reason()).isEqualTo(REASON));
            assertThat(gateway.refunds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("pagamento já capturado")
    class AlreadyCaptured {

        @Test
        @DisplayName("estorna o valor capturado, transiciona para REFUNDED e publica PaymentRefunded")
        void refundsCapturedAmount() {
            Payment captured = seedCaptured();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            useCaseWith(gateway).execute(command());

            Payment stored = repository.findById(captured.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(stored.refundedAmount()).isEqualTo(AMOUNT);

            assertThat(gateway.refunds()).hasSize(1);
            RefundRequest request = gateway.lastRefund();
            assertThat(request.gatewayTransactionId()).isEqualTo(GW_TX);
            assertThat(request.amount()).isEqualTo(AMOUNT);

            assertThat(repository.events(captured.id()))
                    .last().isInstanceOfSatisfying(PaymentRefunded.class, e -> {
                        assertThat(e.fullRefund()).isTrue();
                        assertThat(e.totalRefundedAmount()).isEqualTo(AMOUNT);
                        assertThat(e.reason()).isEqualTo(REASON);
                    });
            assertThat(gateway.voids()).isEmpty();
        }

        @Test
        @DisplayName("estorna apenas o saldo remanescente quando já houve estorno parcial")
        void refundsRemainingAfterPartialRefund() {
            Payment captured = newPayment();
            captured.authorize(GW_TX, AUTH_CODE);
            captured.capture();
            captured.refund(Money.of("50.00", "BRL"), "estorno parcial prévio");
            repository.seed(captured);
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            useCaseWith(gateway).execute(command());

            assertThat(gateway.lastRefund().amount()).isEqualTo(Money.of("99.90", "BRL"));
            Payment stored = repository.findById(captured.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(stored.refundedAmount()).isEqualTo(AMOUNT);
        }
    }

    @Nested
    @DisplayName("nada a compensar / idempotência")
    class NothingToCompensate {

        @Test
        @DisplayName("pagamento FAILED é no-op — não toca no gateway")
        void failedIsNoOp() {
            Payment failed = newPayment();
            failed.fail(com.orderflow.payment.domain.event.PaymentFailed.FailureReason.CARD_DECLINED, "recusado");
            repository.seed(failed);
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            useCaseWith(gateway).execute(command());

            assertThat(gateway.voids()).isEmpty();
            assertThat(gateway.refunds()).isEmpty();
            assertThat(repository.saveCount()).isZero();
        }

        @Test
        @DisplayName("reentrega de OrderCancelled sobre pagamento já estornado é no-op")
        void redeliveryOnRefundedIsNoOp() {
            Payment captured = seedCaptured();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            CompensatePaymentUseCase useCase = useCaseWith(gateway);

            useCase.execute(command());
            int savesAfterFirst = repository.saveCount();
            int refundsAfterFirst = gateway.refunds().size();

            useCase.execute(command());

            assertThat(repository.saveCount()).isEqualTo(savesAfterFirst);
            assertThat(gateway.refunds()).hasSize(refundsAfterFirst);
            assertThat(repository.findById(captured.id()).orElseThrow().status())
                    .isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("pedido sem pagamento correspondente é no-op")
        void noPaymentIsNoOp() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            useCaseWith(gateway).execute(command());

            assertThat(gateway.voids()).isEmpty();
            assertThat(gateway.refunds()).isEmpty();
            assertThat(repository.saveCount()).isZero();
        }
    }

    @Test
    @DisplayName("falha técnica do gateway propaga e o pagamento permanece AUTHORIZED")
    void technicalFailurePropagatesWithoutPersisting() {
        Payment authorized = seedAuthorized();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.unavailable("gateway indisponível");

        assertThatThrownBy(() -> useCaseWith(gateway).execute(command()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("gateway indisponível");

        assertThat(repository.saveCount()).isZero();
        assertThat(repository.findById(authorized.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("rejeita comando nulo")
    void rejectsNullCommand() {
        CompensatePaymentUseCase useCase = useCaseWith(ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE));

        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(NullPointerException.class);
    }
}
