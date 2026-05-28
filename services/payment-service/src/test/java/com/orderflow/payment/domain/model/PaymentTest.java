package com.orderflow.payment.domain.model;

import com.orderflow.payment.domain.event.*;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.RefundExceedsCapturedAmountException;
import com.orderflow.payment.domain.model.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Payment — agregado transacional")
class PaymentTest {

    private static final Instant FIXED_NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final Money AMOUNT = Money.of("100.00", "BRL");
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");

    private static Payment pending() {
        return Payment.initiate(
                PaymentId.generate(),
                OrderId.of(UUID.randomUUID()),
                CustomerId.of(UUID.randomUUID()),
                AMOUNT,
                PaymentMethod.CREDIT_CARD,
                CLOCK
        );
    }

    private static Payment authorized() {
        Payment p = pending();
        p.authorize(GW_TX, AUTH_CODE);
        p.pullUncommittedEvents();
        return p;
    }

    private static Payment captured() {
        Payment p = authorized();
        p.capture();
        p.pullUncommittedEvents();
        return p;
    }

    @Nested
    @DisplayName("initiate (factory)")
    class Initiate {

        @Test
        @DisplayName("rejeita paymentId nulo")
        void rejectsNullPaymentId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(null, OrderId.of(UUID.randomUUID()),
                            CustomerId.of(UUID.randomUUID()), AMOUNT, PaymentMethod.CREDIT_CARD, CLOCK))
                    .withMessageContaining("paymentId");
        }

        @Test
        @DisplayName("rejeita orderId nulo")
        void rejectsNullOrderId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(PaymentId.generate(), null,
                            CustomerId.of(UUID.randomUUID()), AMOUNT, PaymentMethod.CREDIT_CARD, CLOCK))
                    .withMessageContaining("orderId");
        }

        @Test
        @DisplayName("rejeita customerId nulo")
        void rejectsNullCustomerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(PaymentId.generate(),
                            OrderId.of(UUID.randomUUID()), null, AMOUNT,
                            PaymentMethod.CREDIT_CARD, CLOCK))
                    .withMessageContaining("customerId");
        }

        @Test
        @DisplayName("rejeita amount nulo")
        void rejectsNullAmount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(PaymentId.generate(),
                            OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                            null, PaymentMethod.CREDIT_CARD, CLOCK))
                    .withMessageContaining("amount");
        }

        @Test
        @DisplayName("rejeita method nulo")
        void rejectsNullMethod() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(PaymentId.generate(),
                            OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                            AMOUNT, null, CLOCK))
                    .withMessageContaining("method");
        }

        @Test
        @DisplayName("rejeita clock nulo")
        void rejectsNullClock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.initiate(PaymentId.generate(),
                            OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                            AMOUNT, PaymentMethod.CREDIT_CARD, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("rejeita amount não-positivo")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> Payment.initiate(PaymentId.generate(),
                    OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                    Money.zero(java.util.Currency.getInstance("BRL")),
                    PaymentMethod.CREDIT_CARD, CLOCK))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");

            assertThatThrownBy(() -> Payment.initiate(PaymentId.generate(),
                    OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                    Money.of("-1.00", "BRL"), PaymentMethod.CREDIT_CARD, CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("status inicial é PENDING")
        void initialStatusIsPending() {
            assertThat(pending().status()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("não emite eventos na iniciação")
        void doesNotEmitEvents() {
            assertThat(pending().hasUncommittedEvents()).isFalse();
        }

        @Test
        @DisplayName("registra initiatedAt em Instant.now(clock)")
        void recordsInitiatedAt() {
            assertThat(pending().initiatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("amounts capturado e estornado começam em zero na moeda do amount")
        void capturedAndRefundedStartAtZero() {
            Payment p = pending();
            assertThat(p.capturedAmount().isZero()).isTrue();
            assertThat(p.refundedAmount().isZero()).isTrue();
            assertThat(p.capturedAmount().currency()).isEqualTo(AMOUNT.currency());
            assertThat(p.refundedAmount().currency()).isEqualTo(AMOUNT.currency());
        }

        @Test
        @DisplayName("version inicial é 0 (nenhum evento ainda)")
        void versionStartsAtZero() {
            assertThat(pending().version()).isZero();
        }
    }

    @Nested
    @DisplayName("authorize")
    class Authorize {

        @Test
        @DisplayName("a partir de PENDING transiciona para AUTHORIZED e emite PaymentAuthorized")
        void authorizesFromPending() {
            Payment p = pending();
            p.authorize(GW_TX, AUTH_CODE);

            assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            assertThat(p.gatewayTransactionId()).isEqualTo(GW_TX);
            assertThat(p.authorizationCode()).isEqualTo(AUTH_CODE);

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentAuthorized.class, e -> {
                assertThat(e.paymentId()).isEqualTo(p.id());
                assertThat(e.orderId()).isEqualTo(p.orderId());
                assertThat(e.customerId()).isEqualTo(p.customerId());
                assertThat(e.amount()).isEqualTo(AMOUNT);
                assertThat(e.method()).isEqualTo(PaymentMethod.CREDIT_CARD);
                assertThat(e.gatewayTransactionId()).isEqualTo(GW_TX);
                assertThat(e.authorizationCode()).isEqualTo(AUTH_CODE);
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
                assertThat(e.schemaVersion()).isEqualTo(1);
                assertThat(e.eventId()).isNotNull();
            });
        }

        @Test
        @DisplayName("rejeita gatewayTransactionId nulo")
        void rejectsNullGatewayTransactionId() {
            Payment p = pending();
            assertThatNullPointerException().isThrownBy(() -> p.authorize(null, AUTH_CODE));
        }

        @Test
        @DisplayName("rejeita authorizationCode nulo")
        void rejectsNullAuthorizationCode() {
            Payment p = pending();
            assertThatNullPointerException().isThrownBy(() -> p.authorize(GW_TX, null));
        }

        @Test
        @DisplayName("é idempotente quando já AUTHORIZED")
        void idempotentWhenAlreadyAuthorized() {
            Payment p = pending();
            p.authorize(GW_TX, AUTH_CODE);
            p.pullUncommittedEvents();

            p.authorize(GW_TX, AUTH_CODE);

            assertThat(p.pullUncommittedEvents()).isEmpty();
            assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        }

        @Test
        @DisplayName("é idempotente quando já CAPTURED")
        void idempotentWhenAlreadyCaptured() {
            Payment p = captured();

            p.authorize(GW_TX, AUTH_CODE);

            assertThat(p.pullUncommittedEvents()).isEmpty();
            assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
        }

        @Test
        @DisplayName("rejeita a partir de FAILED")
        void rejectsFromFailed() {
            Payment p = pending();
            p.fail(PaymentFailed.FailureReason.CARD_DECLINED, null);

            assertThatThrownBy(() -> p.authorize(GW_TX, AUTH_CODE))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("authorize")
                    .hasMessageContaining("FAILED");
        }

        @Test
        @DisplayName("rejeita a partir de VOIDED")
        void rejectsFromVoided() {
            Payment p = authorized();
            p.voidAuthorization("test");

            assertThatThrownBy(() -> p.authorize(GW_TX, AUTH_CODE))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }

        @Test
        @DisplayName("rejeita a partir de REFUNDED")
        void rejectsFromRefunded() {
            Payment p = captured();
            p.refund(AMOUNT, "full refund");

            assertThatThrownBy(() -> p.authorize(GW_TX, AUTH_CODE))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("a partir de PENDING transiciona para FAILED e emite PaymentFailed")
        void failsFromPending() {
            Payment p = pending();

            p.fail(PaymentFailed.FailureReason.INSUFFICIENT_FUNDS, "saldo insuficiente");

            assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(p.status().isTerminal()).isTrue();
            assertThat(p.failureReason()).isEqualTo(PaymentFailed.FailureReason.INSUFFICIENT_FUNDS);
            assertThat(p.failureDetails()).isEqualTo("saldo insuficiente");

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentFailed.class, e -> {
                assertThat(e.paymentId()).isEqualTo(p.id());
                assertThat(e.orderId()).isEqualTo(p.orderId());
                assertThat(e.customerId()).isEqualTo(p.customerId());
                assertThat(e.amount()).isEqualTo(AMOUNT);
                assertThat(e.reason()).isEqualTo(PaymentFailed.FailureReason.INSUFFICIENT_FUNDS);
                assertThat(e.details()).isEqualTo("saldo insuficiente");
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
            });
        }

        @Test
        @DisplayName("rejeita reason nula")
        void rejectsNullReason() {
            Payment p = pending();
            assertThatNullPointerException().isThrownBy(() -> p.fail(null, "x"));
        }

        @Test
        @DisplayName("aceita details nulo")
        void acceptsNullDetails() {
            Payment p = pending();
            p.fail(PaymentFailed.FailureReason.UNKNOWN, null);
            assertThat(p.failureDetails()).isNull();
        }

        @Test
        @DisplayName("é idempotente quando já FAILED")
        void idempotent() {
            Payment p = pending();
            p.fail(PaymentFailed.FailureReason.CARD_DECLINED, null);
            p.pullUncommittedEvents();

            p.fail(PaymentFailed.FailureReason.CARD_DECLINED, null);

            assertThat(p.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("rejeita a partir de AUTHORIZED")
        void rejectsFromAuthorized() {
            Payment p = authorized();
            assertThatThrownBy(() -> p.fail(PaymentFailed.FailureReason.UNKNOWN, null))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("fail")
                    .hasMessageContaining("AUTHORIZED");
        }

        @Test
        @DisplayName("rejeita a partir de CAPTURED")
        void rejectsFromCaptured() {
            Payment p = captured();
            assertThatThrownBy(() -> p.fail(PaymentFailed.FailureReason.UNKNOWN, null))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("capture")
    class Capture {

        @Test
        @DisplayName("a partir de AUTHORIZED transiciona para CAPTURED e emite PaymentCaptured")
        void capturesFromAuthorized() {
            Payment p = authorized();

            p.capture();

            assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.capturedAmount()).isEqualTo(AMOUNT);

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentCaptured.class, e -> {
                assertThat(e.paymentId()).isEqualTo(p.id());
                assertThat(e.orderId()).isEqualTo(p.orderId());
                assertThat(e.amount()).isEqualTo(AMOUNT);
                assertThat(e.gatewayTransactionId()).isEqualTo(GW_TX);
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
            });
        }

        @Test
        @DisplayName("é idempotente quando já CAPTURED")
        void idempotent() {
            Payment p = captured();
            p.capture();
            assertThat(p.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("rejeita a partir de PENDING")
        void rejectsFromPending() {
            Payment p = pending();
            assertThatThrownBy(p::capture)
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("capture")
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("rejeita a partir de FAILED")
        void rejectsFromFailed() {
            Payment p = pending();
            p.fail(PaymentFailed.FailureReason.UNKNOWN, null);
            assertThatThrownBy(p::capture)
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }

        @Test
        @DisplayName("rejeita a partir de VOIDED")
        void rejectsFromVoided() {
            Payment p = authorized();
            p.voidAuthorization("test");
            assertThatThrownBy(p::capture)
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("refund")
    class Refund {

        @Test
        @DisplayName("estorno total a partir de CAPTURED transiciona para REFUNDED")
        void fullRefund() {
            Payment p = captured();

            p.refund(AMOUNT, "customer requested");

            assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(p.status().isTerminal()).isTrue();
            assertThat(p.refundedAmount()).isEqualTo(AMOUNT);
            assertThat(p.remainingCapturedAmount().isZero()).isTrue();

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentRefunded.class, e -> {
                assertThat(e.refundedAmount()).isEqualTo(AMOUNT);
                assertThat(e.totalRefundedAmount()).isEqualTo(AMOUNT);
                assertThat(e.fullRefund()).isTrue();
                assertThat(e.reason()).isEqualTo("customer requested");
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
            });
        }

        @Test
        @DisplayName("estorno parcial mantém status CAPTURED e acumula totalRefundedAmount")
        void partialRefundStaysInCaptured() {
            Payment p = captured();

            p.refund(Money.of("30.00", "BRL"), "partial 1");
            p.refund(Money.of("20.00", "BRL"), "partial 2");

            assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(p.refundedAmount().amount()).isEqualByComparingTo("50.00");
            assertThat(p.remainingCapturedAmount().amount()).isEqualByComparingTo("50.00");

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(2);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentRefunded.class, e -> {
                assertThat(e.refundedAmount().amount()).isEqualByComparingTo("30.00");
                assertThat(e.totalRefundedAmount().amount()).isEqualByComparingTo("30.00");
                assertThat(e.fullRefund()).isFalse();
            });
            assertThat(events.get(1)).isInstanceOfSatisfying(PaymentRefunded.class, e -> {
                assertThat(e.refundedAmount().amount()).isEqualByComparingTo("20.00");
                assertThat(e.totalRefundedAmount().amount()).isEqualByComparingTo("50.00");
                assertThat(e.fullRefund()).isFalse();
            });
        }

        @Test
        @DisplayName("estornos parciais sucessivos atingindo o total transicionam para REFUNDED")
        void partialRefundsAddingUpReachRefunded() {
            Payment p = captured();

            p.refund(Money.of("60.00", "BRL"), "first");
            assertThat(p.status()).isEqualTo(PaymentStatus.CAPTURED);

            p.refund(Money.of("40.00", "BRL"), "second");
            assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(2);
            assertThat(((PaymentRefunded) events.get(1)).fullRefund()).isTrue();
        }

        @Test
        @DisplayName("rejeita amount nulo")
        void rejectsNullAmount() {
            Payment p = captured();
            assertThatNullPointerException().isThrownBy(() -> p.refund(null, "x"));
        }

        @Test
        @DisplayName("rejeita amount não-positivo")
        void rejectsNonPositiveAmount() {
            Payment p = captured();
            assertThatThrownBy(() -> p.refund(Money.zero(java.util.Currency.getInstance("BRL")), "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> p.refund(Money.of("-1.00", "BRL"), "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejeita estorno que excede o valor capturado restante")
        void rejectsRefundExceedingCaptured() {
            Payment p = captured();

            assertThatThrownBy(() -> p.refund(Money.of("100.01", "BRL"), "too much"))
                    .isInstanceOf(RefundExceedsCapturedAmountException.class);
        }

        @Test
        @DisplayName("rejeita estorno parcial cuja soma excede o capturado")
        void rejectsCumulativeOverflow() {
            Payment p = captured();
            p.refund(Money.of("60.00", "BRL"), "first");

            assertThatThrownBy(() -> p.refund(Money.of("50.00", "BRL"), "overflow"))
                    .isInstanceOf(RefundExceedsCapturedAmountException.class);
        }

        @Test
        @DisplayName("rejeita estorno fora de CAPTURED (PENDING)")
        void rejectsFromPending() {
            Payment p = pending();
            assertThatThrownBy(() -> p.refund(AMOUNT, "x"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("refund")
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("rejeita estorno fora de CAPTURED (AUTHORIZED)")
        void rejectsFromAuthorized() {
            Payment p = authorized();
            assertThatThrownBy(() -> p.refund(AMOUNT, "x"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("AUTHORIZED");
        }

        @Test
        @DisplayName("rejeita estorno após REFUNDED total")
        void rejectsAfterFullRefund() {
            Payment p = captured();
            p.refund(AMOUNT, "full");

            assertThatThrownBy(() -> p.refund(Money.of("1.00", "BRL"), "again"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("REFUNDED");
        }

        @Test
        @DisplayName("rejeita estorno em moeda diferente")
        void rejectsDifferentCurrency() {
            Payment p = captured();
            assertThatThrownBy(() -> p.refund(Money.of("10.00", "USD"), "x"))
                    .isInstanceOf(com.orderflow.payment.domain.exception.CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("voidAuthorization")
    class Void {

        @Test
        @DisplayName("a partir de AUTHORIZED transiciona para VOIDED e emite PaymentVoided")
        void voidsFromAuthorized() {
            Payment p = authorized();

            p.voidAuthorization("out of stock");

            assertThat(p.status()).isEqualTo(PaymentStatus.VOIDED);
            assertThat(p.status().isTerminal()).isTrue();

            List<PaymentEvent> events = p.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(PaymentVoided.class, e -> {
                assertThat(e.paymentId()).isEqualTo(p.id());
                assertThat(e.orderId()).isEqualTo(p.orderId());
                assertThat(e.reason()).isEqualTo("out of stock");
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
            });
        }

        @Test
        @DisplayName("é idempotente quando já VOIDED")
        void idempotent() {
            Payment p = authorized();
            p.voidAuthorization("x");
            p.pullUncommittedEvents();

            p.voidAuthorization("x");
            assertThat(p.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("rejeita void a partir de PENDING")
        void rejectsFromPending() {
            Payment p = pending();
            assertThatThrownBy(() -> p.voidAuthorization("x"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("voidAuthorization")
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("rejeita void a partir de CAPTURED (usar refund)")
        void rejectsFromCaptured() {
            Payment p = captured();
            assertThatThrownBy(() -> p.voidAuthorization("x"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class)
                    .hasMessageContaining("CAPTURED");
        }

        @Test
        @DisplayName("rejeita void a partir de FAILED")
        void rejectsFromFailed() {
            Payment p = pending();
            p.fail(PaymentFailed.FailureReason.UNKNOWN, null);
            assertThatThrownBy(() -> p.voidAuthorization("x"))
                    .isInstanceOf(InvalidPaymentStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("event plumbing")
    class EventPlumbing {

        @Test
        @DisplayName("pullUncommittedEvents retorna snapshot e limpa o buffer")
        void pullClears() {
            Payment p = pending();
            p.authorize(GW_TX, AUTH_CODE);
            assertThat(p.hasUncommittedEvents()).isTrue();

            List<PaymentEvent> first = p.pullUncommittedEvents();
            assertThat(first).hasSize(1);
            assertThat(p.hasUncommittedEvents()).isFalse();
            assertThat(p.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("pullUncommittedEvents retorna lista imutável")
        void pullReturnsImmutable() {
            Payment p = pending();
            p.authorize(GW_TX, AUTH_CODE);
            List<PaymentEvent> events = p.pullUncommittedEvents();

            assertThatThrownBy(() -> events.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("peekUncommittedEvents não limpa e é não-modificável")
        void peekDoesNotClear() {
            Payment p = pending();
            p.authorize(GW_TX, AUTH_CODE);

            List<PaymentEvent> view = p.peekUncommittedEvents();
            assertThat(view).hasSize(1);
            assertThat(p.hasUncommittedEvents()).isTrue();
            assertThatThrownBy(() -> view.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("version incrementa a cada evento registrado")
        void versionIncrements() {
            Payment p = pending();
            assertThat(p.version()).isZero();

            p.authorize(GW_TX, AUTH_CODE);
            assertThat(p.version()).isEqualTo(1L);

            p.capture();
            assertThat(p.version()).isEqualTo(2L);

            p.refund(Money.of("40.00", "BRL"), "x");
            assertThat(p.version()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("restore (rehidratação)")
    class Restore {

        @Test
        @DisplayName("rejeita snapshot nulo")
        void rejectsNullSnapshot() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.restore(null, CLOCK))
                    .withMessageContaining("snapshot");
        }

        @Test
        @DisplayName("rejeita clock nulo")
        void rejectsNullClock() {
            Payment.Snapshot snap = snapshotOfCapturedPayment();
            assertThatNullPointerException()
                    .isThrownBy(() -> Payment.restore(snap, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("reconstrói estado completo sem emitir eventos")
        void rehydratesWithoutEvents() {
            Payment.Snapshot snap = snapshotOfCapturedPayment();
            Payment p = Payment.restore(snap, CLOCK);

            assertThat(p.id()).isEqualTo(snap.id());
            assertThat(p.orderId()).isEqualTo(snap.orderId());
            assertThat(p.customerId()).isEqualTo(snap.customerId());
            assertThat(p.authorizedAmount()).isEqualTo(snap.authorizedAmount());
            assertThat(p.capturedAmount()).isEqualTo(snap.capturedAmount());
            assertThat(p.refundedAmount()).isEqualTo(snap.refundedAmount());
            assertThat(p.method()).isEqualTo(snap.method());
            assertThat(p.status()).isEqualTo(snap.status());
            assertThat(p.gatewayTransactionId()).isEqualTo(snap.gatewayTransactionId());
            assertThat(p.authorizationCode()).isEqualTo(snap.authorizationCode());
            assertThat(p.version()).isEqualTo(snap.version());
            assertThat(p.hasUncommittedEvents()).isFalse();
        }

        @Test
        @DisplayName("rehidratado permite continuar comandos válidos para o estado")
        void rehydratedSupportsValidCommands() {
            Payment.Snapshot snap = snapshotOfCapturedPayment();
            Payment p = Payment.restore(snap, CLOCK);

            p.refund(snap.authorizedAmount(), "post-restore refund");

            assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(p.pullUncommittedEvents()).hasSize(1);
        }

        @Test
        @DisplayName("Snapshot rejeita campos não-anuláveis nulos")
        void snapshotRejectsRequiredNulls() {
            assertThatNullPointerException().isThrownBy(() -> new Payment.Snapshot(
                    null, OrderId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()),
                    AMOUNT, Money.zero(java.util.Currency.getInstance("BRL")),
                    Money.zero(java.util.Currency.getInstance("BRL")),
                    PaymentMethod.CREDIT_CARD, PaymentStatus.PENDING,
                    null, null, null, null, FIXED_NOW, 0L));
        }

        private static Payment.Snapshot snapshotOfCapturedPayment() {
            return new Payment.Snapshot(
                    PaymentId.generate(),
                    OrderId.of(UUID.randomUUID()),
                    CustomerId.of(UUID.randomUUID()),
                    AMOUNT,
                    AMOUNT, // capturedAmount
                    Money.zero(java.util.Currency.getInstance("BRL")),
                    PaymentMethod.CREDIT_CARD,
                    PaymentStatus.CAPTURED,
                    GW_TX,
                    AUTH_CODE,
                    null,
                    null,
                    FIXED_NOW,
                    2L
            );
        }
    }

    @Nested
    @DisplayName("PaymentStatus")
    class Status {

        @Test
        @DisplayName("estados terminais: FAILED, VOIDED, REFUNDED")
        void terminalStates() {
            assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
            assertThat(PaymentStatus.VOIDED.isTerminal()).isTrue();
            assertThat(PaymentStatus.REFUNDED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("estados não-terminais: PENDING, AUTHORIZED, CAPTURED")
        void nonTerminalStates() {
            assertThat(PaymentStatus.PENDING.isTerminal()).isFalse();
            assertThat(PaymentStatus.AUTHORIZED.isTerminal()).isFalse();
            assertThat(PaymentStatus.CAPTURED.isTerminal()).isFalse();
        }
    }
}
