package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.InMemoryPaymentRepository;
import com.orderflow.payment.application.ScriptedPaymentGateway;
import com.orderflow.payment.application.command.RefundPaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway.RefundRequest;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.exception.CurrencyMismatchException;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.PaymentNotFoundException;
import com.orderflow.payment.domain.exception.RefundExceedsCapturedAmountException;
import com.orderflow.payment.domain.event.PaymentRefunded;
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

@DisplayName("RefundPaymentUseCase — estorno administrativo via REST")
class RefundPaymentUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);

    private static final OrderId ORDER_ID = OrderId.of(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = CustomerId.of(UUID.randomUUID());
    private static final Money AMOUNT = Money.of("149.90", "BRL");
    private static final PaymentMethod METHOD = PaymentMethod.CREDIT_CARD;
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");
    private static final String REASON = "Disputa resolvida a favor do cliente";

    private InMemoryPaymentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentRepository(CLOCK);
    }

    private static Payment newPayment() {
        return Payment.initiate(PaymentId.generate(), ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD, CLOCK);
    }

    private Payment seedCaptured() {
        Payment payment = newPayment();
        payment.authorize(GW_TX, AUTH_CODE);
        payment.capture();
        repository.seed(payment);
        return payment;
    }

    private RefundPaymentUseCase useCaseWith(ScriptedPaymentGateway gateway) {
        return new RefundPaymentUseCase(repository, gateway);
    }

    private static RefundPaymentCommand command(PaymentId paymentId, String amount) {
        return new RefundPaymentCommand(paymentId, Money.of(amount, "BRL"), REASON);
    }

    @Nested
    @DisplayName("pagamento capturado")
    class Captured {

        @Test
        @DisplayName("estorno parcial mantém CAPTURED e publica PaymentRefunded parcial")
        void partialRefundStaysCaptured() {
            Payment captured = seedCaptured();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            Payment result = useCaseWith(gateway)
                    .execute(command(captured.id(), "50.00"));

            assertThat(result.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(result.refundedAmount()).isEqualTo(Money.of("50.00", "BRL"));

            Payment stored = repository.findById(captured.id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(stored.refundedAmount()).isEqualTo(Money.of("50.00", "BRL"));

            assertThat(gateway.refunds()).hasSize(1);
            RefundRequest request = gateway.lastRefund();
            assertThat(request.paymentId()).isEqualTo(captured.id());
            assertThat(request.gatewayTransactionId()).isEqualTo(GW_TX);
            assertThat(request.amount()).isEqualTo(Money.of("50.00", "BRL"));
            assertThat(request.reason()).isEqualTo(REASON);

            assertThat(repository.events(captured.id()))
                    .last().isInstanceOfSatisfying(PaymentRefunded.class, e -> {
                        assertThat(e.fullRefund()).isFalse();
                        assertThat(e.refundedAmount()).isEqualTo(Money.of("50.00", "BRL"));
                        assertThat(e.reason()).isEqualTo(REASON);
                    });
        }

        @Test
        @DisplayName("estorno do valor total transiciona para REFUNDED")
        void fullRefundTransitionsToRefunded() {
            Payment captured = seedCaptured();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            Payment result = useCaseWith(gateway)
                    .execute(command(captured.id(), "149.90"));

            assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(repository.findById(captured.id()).orElseThrow().status())
                    .isEqualTo(PaymentStatus.REFUNDED);
            assertThat(repository.events(captured.id()))
                    .last().isInstanceOfSatisfying(PaymentRefunded.class,
                            e -> assertThat(e.fullRefund()).isTrue());
        }

        @Test
        @DisplayName("estorno acima do saldo remanescente falha antes de tocar no gateway")
        void refundExceedingRemainingFailsBeforeGateway() {
            Payment captured = seedCaptured();
            captured.refund(Money.of("100.00", "BRL"), "estorno parcial prévio");
            repository.seed(captured);
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            assertThatThrownBy(() -> useCaseWith(gateway).execute(command(captured.id(), "50.00")))
                    .isInstanceOf(RefundExceedsCapturedAmountException.class);

            assertThat(gateway.refunds()).isEmpty();
            assertThat(repository.saveCount()).isZero();
        }

        @Test
        @DisplayName("estorno em moeda diferente da do pagamento falha antes de tocar no gateway")
        void currencyMismatchFailsBeforeGateway() {
            Payment captured = seedCaptured();
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            RefundPaymentCommand usd = new RefundPaymentCommand(
                    captured.id(), Money.of("50.00", "USD"), REASON);

            assertThatThrownBy(() -> useCaseWith(gateway).execute(usd))
                    .isInstanceOf(CurrencyMismatchException.class);

            assertThat(gateway.refunds()).isEmpty();
            assertThat(repository.saveCount()).isZero();
        }
    }

    @Nested
    @DisplayName("estados não estornáveis")
    class NotRefundable {

        @Test
        @DisplayName("pagamento apenas AUTHORIZED falha sem efeito colateral no gateway")
        void authorizedIsRejected() {
            Payment authorized = newPayment();
            authorized.authorize(GW_TX, AUTH_CODE);
            repository.seed(authorized);
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            assertThatThrownBy(() -> useCaseWith(gateway).execute(command(authorized.id(), "50.00")))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);

            assertThat(gateway.refunds()).isEmpty();
            assertThat(repository.saveCount()).isZero();
        }

        @Test
        @DisplayName("pagamento inexistente falha com PaymentNotFoundException")
        void missingPaymentIsRejected() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);

            assertThatThrownBy(() -> useCaseWith(gateway).execute(command(PaymentId.generate(), "50.00")))
                    .isInstanceOf(PaymentNotFoundException.class);

            assertThat(gateway.refunds()).isEmpty();
        }
    }

    @Test
    @DisplayName("falha técnica do gateway propaga e nada é persistido")
    void technicalFailurePropagatesWithoutPersisting() {
        Payment captured = seedCaptured();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.unavailable("gateway indisponível");

        assertThatThrownBy(() -> useCaseWith(gateway).execute(command(captured.id(), "50.00")))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("gateway indisponível");

        assertThat(repository.saveCount()).isZero();
        Payment stored = repository.findById(captured.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(stored.refundedAmount().isZero()).isTrue();
    }

    @Test
    @DisplayName("rejeita comando nulo")
    void rejectsNullCommand() {
        RefundPaymentUseCase useCase = useCaseWith(ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE));

        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("comando rejeita valor não positivo")
    void commandRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new RefundPaymentCommand(
                PaymentId.generate(), Money.of("0.00", "BRL"), REASON))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
