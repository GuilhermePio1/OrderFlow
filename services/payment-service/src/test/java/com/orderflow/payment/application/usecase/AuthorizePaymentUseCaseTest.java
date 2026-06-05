package com.orderflow.payment.application.usecase;

import com.orderflow.payment.application.InMemoryPaymentRepository;
import com.orderflow.payment.application.ScriptedPaymentGateway;
import com.orderflow.payment.application.command.AuthorizePaymentCommand;
import com.orderflow.payment.application.port.PaymentGateway.AuthorizationRequest;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentAuthorized;
import com.orderflow.payment.domain.event.PaymentEvent;
import com.orderflow.payment.domain.event.PaymentFailed;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AuthorizePaymentUseCase")
class AuthorizePaymentUseCaseTest {

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

    private static AuthorizePaymentCommand command() {
        return new AuthorizePaymentCommand(ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD);
    }

    @Nested
    @DisplayName("autorização aprovada")
    class Approved {

        @Test
        @DisplayName("inicia o pagamento, autoriza e publica PaymentAuthorized")
        void authorizesAndPublishesEvent() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(repository, gateway, CLOCK);

            PaymentId paymentId = useCase.execute(command());

            Payment stored = repository.findById(paymentId).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(stored.orderId()).isEqualTo(ORDER_ID);
            assertThat(stored.authorizedAmount()).isEqualTo(AMOUNT);
            assertThat(stored.gatewayTransactionId()).isEqualTo(GW_TX);
            assertThat(stored.authorizationCode()).isEqualTo(AUTH_CODE);

            List<PaymentEvent> events = repository.events(paymentId);
            assertThat(events).singleElement().isInstanceOfSatisfying(PaymentAuthorized.class, e -> {
                assertThat(e.paymentId()).isEqualTo(paymentId);
                assertThat(e.orderId()).isEqualTo(ORDER_ID);
                assertThat(e.amount()).isEqualTo(AMOUNT);
                assertThat(e.gatewayTransactionId()).isEqualTo(GW_TX);
                assertThat(e.authorizationCode()).isEqualTo(AUTH_CODE);
            });
        }

        @Test
        @DisplayName("traduz o comando numa AuthorizationRequest para o gateway")
        void translatesCommandIntoGatewayRequest() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(repository, gateway, CLOCK);

            PaymentId paymentId = useCase.execute(command());

            assertThat(gateway.callCount()).isEqualTo(1);
            AuthorizationRequest request = gateway.lastRequest();
            assertThat(request.paymentId()).isEqualTo(paymentId);
            assertThat(request.orderId()).isEqualTo(ORDER_ID);
            assertThat(request.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(request.amount()).isEqualTo(AMOUNT);
            assertThat(request.method()).isEqualTo(METHOD);
        }
    }

    @Nested
    @DisplayName("autorização recusada")
    class Declined {

        @Test
        @DisplayName("um declínio de negócio leva o pagamento a FAILED e publica PaymentFailed")
        void recordsFailureOnDecline() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.declining(
                    PaymentFailed.FailureReason.INSUFFICIENT_FUNDS, "saldo insuficiente");
            AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(repository, gateway, CLOCK);

            PaymentId paymentId = useCase.execute(command());

            Payment stored = repository.findById(paymentId).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(stored.failureReason()).isEqualTo(PaymentFailed.FailureReason.INSUFFICIENT_FUNDS);
            assertThat(stored.gatewayTransactionId()).isNull();

            List<PaymentEvent> events = repository.events(paymentId);
            assertThat(events).singleElement().isInstanceOfSatisfying(PaymentFailed.class, e -> {
                assertThat(e.reason()).isEqualTo(PaymentFailed.FailureReason.INSUFFICIENT_FUNDS);
                assertThat(e.details()).isEqualTo("saldo insuficiente");
                assertThat(e.amount()).isEqualTo(AMOUNT);
            });
        }
    }

    @Nested
    @DisplayName("idempotência")
    class Idempotency {

        @Test
        @DisplayName("um pagamento já existente para o pedido é devolvido sem tocar no gateway")
        void returnsExistingWithoutCallingGateway() {
            Payment existing = Payment.initiate(
                    PaymentId.generate(), ORDER_ID, CUSTOMER_ID, AMOUNT, METHOD, CLOCK);
            existing.authorize(GW_TX, AUTH_CODE);
            repository.seed(existing);
            int savesAfterSeed = repository.saveCount();

            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(
                    GatewayTransactionId.of("ch_other"), AuthorizationCode.of("ZZZ999"));
            AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(repository, gateway, CLOCK);

            PaymentId result = useCase.execute(command());

            assertThat(result).isEqualTo(existing.id());
            assertThat(gateway.callCount()).isZero();
            assertThat(repository.saveCount()).isEqualTo(savesAfterSeed);
            // o pagamento existente permanece intocado
            assertThat(repository.findById(existing.id()).orElseThrow().gatewayTransactionId())
                    .isEqualTo(GW_TX);
        }
    }

    @Nested
    @DisplayName("falha técnica do gateway")
    class TechnicalFailure {

        @Test
        @DisplayName("propaga PaymentGatewayException sem persistir o pagamento")
        void propagatesAndPersistsNothing() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.unavailable("gateway indisponível");
            AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(repository, gateway, CLOCK);

            assertThatThrownBy(() -> useCase.execute(command()))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessage("gateway indisponível");

            assertThat(repository.saveCount()).isZero();
            assertThat(repository.findByOrderId(ORDER_ID)).isEmpty();
        }
    }

    @Test
    @DisplayName("rejeita comando nulo")
    void rejectsNullCommand() {
        AuthorizePaymentUseCase useCase = new AuthorizePaymentUseCase(
                repository, ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE), CLOCK);

        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
