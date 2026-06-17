package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.InMemoryPaymentRepository;
import com.orderflow.payment.application.ScriptedPaymentGateway;
import com.orderflow.payment.application.command.CapturePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway.CaptureRequest;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentCaptured;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CapturePaymentUseCase — captura disparada por OrderConfirmed")
class CapturePaymentUseCaseTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);

    private static final OrderId ORDER_ID = OrderId.of(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = CustomerId.of(UUID.randomUUID());
    private static final Money AMOUNT = Money.of("149.90", "BRL");
    private static final PaymentMethod METHOD = PaymentMethod.CREDIT_CARD;
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");

    private InMemoryPaymentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentRepository(CLOCK);
    }

    private Payment seedAuthorized() {
        Payment payment = Payment.initiate(PaymentId.generate(), ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD, CLOCK);
        payment.authorize(GW_TX, AUTH_CODE);
        repository.seed(payment);
        return payment;
    }

    @Test
    @DisplayName("captura a autorização, transiciona para CAPTURED e publica PaymentCaptured")
    void capturesAuthorizedPayment() {
        Payment authorized = seedAuthorized();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        useCase.execute(new CapturePaymentCommand(ORDER_ID));

        Payment stored = repository.findById(authorized.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(stored.capturedAmount()).isEqualTo(AMOUNT);

        assertThat(repository.events(authorized.id()))
                .last().isInstanceOfSatisfying(PaymentCaptured.class, e -> {
                    assertThat(e.orderId()).isEqualTo(ORDER_ID);
                    assertThat(e.amount()).isEqualTo(AMOUNT);
                    assertThat(e.gatewayTransactionId()).isEqualTo(GW_TX);
                });
    }

    @Test
    @DisplayName("traduz a captura numa CaptureRequest para o gateway")
    void translatesIntoGatewayRequest() {
        Payment authorized = seedAuthorized();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        useCase.execute(new CapturePaymentCommand(ORDER_ID));

        assertThat(gateway.captures()).hasSize(1);
        CaptureRequest request = gateway.lastCapture();
        assertThat(request.paymentId()).isEqualTo(authorized.id());
        assertThat(request.gatewayTransactionId()).isEqualTo(GW_TX);
        assertThat(request.amount()).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("reentrega de OrderConfirmed é idempotente — não recaptura nem toca no gateway")
    void redeliveryIsIdempotent() {
        Payment authorized = seedAuthorized();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        useCase.execute(new CapturePaymentCommand(ORDER_ID));
        int savesAfterFirst = repository.saveCount();

        useCase.execute(new CapturePaymentCommand(ORDER_ID));

        assertThat(gateway.captures()).hasSize(1);
        assertThat(repository.saveCount()).isEqualTo(savesAfterFirst);
        assertThat(repository.findById(authorized.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("pedido sem pagamento correspondente é no-op — não toca no gateway")
    void noPaymentIsNoOp() {
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        useCase.execute(new CapturePaymentCommand(ORDER_ID));

        assertThat(gateway.captures()).isEmpty();
        assertThat(repository.saveCount()).isZero();
    }

    @Test
    @DisplayName("estado inválido para captura falha sem efeito colateral no gateway")
    void invalidStateThrowsWithoutGatewayCall() {
        Payment pending = Payment.initiate(PaymentId.generate(), ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD, CLOCK);
        pending.fail(com.orderflow.payment.domain.event.PaymentFailed.FailureReason.CARD_DECLINED, "recusado");
        repository.seed(pending);
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        assertThatThrownBy(() -> useCase.execute(new CapturePaymentCommand(ORDER_ID)))
                .isInstanceOf(InvalidPaymentStateTransitionException.class);

        assertThat(gateway.captures()).isEmpty();
    }

    @Test
    @DisplayName("falha técnica do gateway propaga e o pagamento permanece AUTHORIZED")
    void technicalFailurePropagatesWithoutPersisting() {
        Payment authorized = seedAuthorized();
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.unavailable("gateway indisponível");
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(repository, gateway);

        assertThatThrownBy(() -> useCase.execute(new CapturePaymentCommand(ORDER_ID)))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessage("gateway indisponível");

        assertThat(repository.saveCount()).isZero();
        assertThat(repository.findById(authorized.id()).orElseThrow().status())
                .isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    @DisplayName("rejeita comando nulo")
    void rejectsNullCommand() {
        CapturePaymentUseCase useCase = new CapturePaymentUseCase(
                repository, ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE));

        assertThatThrownBy(() -> useCase.execute(null)).isInstanceOf(NullPointerException.class);
    }
}
