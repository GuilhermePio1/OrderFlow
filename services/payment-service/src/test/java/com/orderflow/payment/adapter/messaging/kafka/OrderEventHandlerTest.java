package com.orderflow.payment.adapter.messaging.kafka;

import com.orderflow.payment.application.InMemoryPaymentRepository;
import com.orderflow.payment.application.ScriptedPaymentGateway;
import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrderEventHandler — tradução OrderPlaced → autorização de pagamento")
class OrderEventHandlerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");

    private InMemoryPaymentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPaymentRepository(CLOCK);
    }

    private OrderEventHandler handlerWith(ScriptedPaymentGateway gateway) {
        return new OrderEventHandler(
                new AuthorizePaymentUseCase(repository, gateway, CLOCK),
                InboundEventDeserializer.withDefaultObjectMapper(),
                PaymentMethod.CREDIT_CARD);
    }

    @Nested
    @DisplayName("OrderPlaced")
    class OnOrderPlaced {

        @Test
        @DisplayName("autoriza o pagamento e publica PaymentAuthorized na outbox")
        void authorizesPayment() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);

            handler.handle(OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL"));

            Payment stored = repository.findByOrderId(OrderId.of(ORDER_ID)).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(stored.customerId()).isEqualTo(CustomerId.of(CUSTOMER_ID));
            assertThat(stored.authorizedAmount()).isEqualTo(Money.of("149.90", "BRL"));

            List<PaymentEvent> events = repository.events(stored.id());
            assertThat(events).singleElement().isInstanceOfSatisfying(PaymentAuthorized.class, e -> {
                assertThat(e.orderId().value()).isEqualTo(ORDER_ID);
                assertThat(e.method()).isEqualTo(PaymentMethod.CREDIT_CARD);
            });
        }

        @Test
        @DisplayName("traduz total e moeda do evento para o comando de domínio")
        void translatesAmountAndCurrency() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);

            handler.handle(OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "10.00", "USD"));

            assertThat(gateway.lastRequest().amount()).isEqualTo(Money.of("10.00", "USD"));
            assertThat(gateway.lastRequest().method()).isEqualTo(PaymentMethod.CREDIT_CARD);
        }

        @Test
        @DisplayName("um declínio de negócio leva o pagamento a FAILED e publica PaymentFailed")
        void recordsFailureOnDecline() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.declining(
                    PaymentFailed.FailureReason.CARD_DECLINED, "cartão recusado");
            OrderEventHandler handler = handlerWith(gateway);

            handler.handle(OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL"));

            Payment stored = repository.findByOrderId(OrderId.of(ORDER_ID)).orElseThrow();
            assertThat(stored.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(repository.events(stored.id()))
                    .singleElement().isInstanceOf(PaymentFailed.class);
        }

        @Test
        @DisplayName("reentrega de OrderPlaced é idempotente — não toca no gateway nem duplica pagamento")
        void redeliveryIsIdempotent() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);
            byte[] payload = orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL");

            handler.handle(OrderEventHandler.ORDER_PLACED, payload);
            int savesAfterFirst = repository.saveCount();

            handler.handle(OrderEventHandler.ORDER_PLACED, payload);

            assertThat(gateway.callCount()).isEqualTo(1);
            assertThat(repository.saveCount()).isEqualTo(savesAfterFirst);
        }

        @Test
        @DisplayName("ConcurrencyConflictException (criação concorrente) é tratada como reentrega")
        void concurrencyConflictIsSwallowed() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);
            repository.injectConcurrencyConflicts(1);

            // Não propaga: o handler trata o conflito como pagamento já criado por
            // outro consumer, evitando poison pill na DLQ.
            handler.handle(OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL"));

            assertThat(gateway.callCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("roteamento e robustez")
    class Routing {

        @Test
        @DisplayName("evento de pedido que não é OrderPlaced é ignorado")
        void ignoresOtherOrderEvents() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);

            handler.handle("OrderShipped", orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL"));

            assertThat(gateway.callCount()).isZero();
            assertThat(repository.findByOrderId(OrderId.of(ORDER_ID))).isEmpty();
        }

        @Test
        @DisplayName("mensagem sem header de tipo é ignorada")
        void ignoresMissingEventType() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);

            handler.handle(null, new byte[0]);

            assertThat(gateway.callCount()).isZero();
        }

        @Test
        @DisplayName("payload malformado falha como InboundEventDeserializationException (poison pill → DLQ)")
        void malformedPayloadFails() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
            OrderEventHandler handler = handlerWith(gateway);

            assertThatThrownBy(() -> handler.handle(
                    OrderEventHandler.ORDER_PLACED, "{not-json".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(InboundEventDeserializationException.class);
        }

        @Test
        @DisplayName("falha técnica do gateway propaga sem persistir (binder retenta / DLQ)")
        void technicalFailurePropagates() {
            ScriptedPaymentGateway gateway = ScriptedPaymentGateway.unavailable("gateway indisponível");
            OrderEventHandler handler = handlerWith(gateway);

            assertThatThrownBy(() -> handler.handle(
                    OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL")))
                    .isInstanceOf(com.orderflow.payment.application.port.PaymentGatewayException.class);

            assertThat(repository.findByOrderId(OrderId.of(ORDER_ID))).isEmpty();
        }
    }

    @Test
    @DisplayName("o meio de pagamento padrão configurado é aplicado")
    void appliesConfiguredDefaultMethod() {
        ScriptedPaymentGateway gateway = ScriptedPaymentGateway.approving(GW_TX, AUTH_CODE);
        OrderEventHandler handler = new OrderEventHandler(
                new AuthorizePaymentUseCase(repository, gateway, CLOCK),
                InboundEventDeserializer.withDefaultObjectMapper(),
                PaymentMethod.PIX);

        handler.handle(OrderEventHandler.ORDER_PLACED, orderPlacedJson(ORDER_ID, CUSTOMER_ID, "149.90", "BRL"));

        Optional<Payment> stored = repository.findByOrderId(OrderId.of(ORDER_ID));
        assertThat(stored).get().extracting(Payment::method).isEqualTo(PaymentMethod.PIX);
        assertThat(gateway.lastRequest().method()).isEqualTo(PaymentMethod.PIX);
    }

    private static byte[] orderPlacedJson(UUID orderId, UUID customerId, String amount, String currency) {
        return """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "customerId": {"value": "%s"},
                  "totalAmount": {"amount": %s, "currency": "%s"},
                  "occurredAt": "2025-01-15T10:00:00Z",
                  "schemaVersion": 1
                }
                """.formatted(UUID.randomUUID(), orderId, customerId, amount, currency)
                .getBytes(StandardCharsets.UTF_8);
    }
}
