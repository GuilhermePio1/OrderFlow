package com.orderflow.payment.adapter.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.orderflow.payment.application.port.PaymentGateway.AuthorizationRequest;
import com.orderflow.payment.application.port.PaymentGateway.AuthorizationResult;
import com.orderflow.payment.application.port.PaymentGateway.CaptureRequest;
import com.orderflow.payment.application.port.PaymentGateway.RefundRequest;
import com.orderflow.payment.application.port.PaymentGateway.VoidRequest;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentFailed.FailureReason;
import com.orderflow.payment.domain.model.valueobject.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.*;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de integração do adapter {@link StripePaymentGateway} contra um gateway
 * HTTP real simulado por WireMock ({@code docs/testing.md}: "WireMock cobre" a
 * fronteira com serviços externos). Valida as duas direções da Anti-Corruption
 * Layer — a requisição traduzida para o formato Stripe e a resposta traduzida de
 * volta para o vocabulário interno — além do comportamento de resiliência:
 * falhas técnicas viram {@link PaymentGatewayException} e o circuit breaker abre
 * sob falhas persistentes, passando a falhar rápido sem tocar na rede.
 */
@DisplayName("StripePaymentGateway — integração WireMock")
class StripePaymentGatewayWireMockTest {

    private static final String PATH = "/v1/payment_intents";
    private static final String REFUNDS_PATH = "/v1/refunds";
    private static final String SECRET_KEY = "sk_test_123";
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_3Nabc");

    private static final OrderId ORDER_ID = OrderId.of(UUID.randomUUID());
    private static final CustomerId CUSTOMER_ID = CustomerId.of(UUID.randomUUID());
    private static final PaymentId PAYMENT_ID = PaymentId.generate();
    private static final Money AMOUNT = Money.of("149.90", "BRL");

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(PAYMENT_ID, ORDER_ID, CUSTOMER_ID, AMOUNT, PaymentMethod.CREDIT_CARD);
    }

    private StripePaymentGateway gateway(CircuitBreaker circuitBreaker, Duration readTimeout) {
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET_KEY)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(Duration.ofSeconds(1))
                                .withReadTimeout(readTimeout)))
                .build();
        ObjectMapper objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new StripePaymentGateway(restClient, circuitBreaker, objectMapper);
    }

    private StripePaymentGateway gateway() {
        return gateway(CircuitBreaker.ofDefaults("test"), Duration.ofSeconds(5));
    }

    @Nested
    @DisplayName("autorização aprovada")
    class Approved {

        @Test
        @DisplayName("traduz succeeded em Approved com transação e código de autorização")
        void translatesSucceededIntoApproved() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {
                              "id": "pi_3Nabc",
                              "status": "succeeded",
                              "latest_charge": "ch_3Nabc",
                              "payment_method_details": { "card": { "authorization_code": "246810" } }
                            }
                            """)));

            AuthorizationResult result = gateway().authorize(request());

            assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Approved.class, approved -> {
                assertThat(approved.transactionId().value()).isEqualTo("ch_3Nabc");
                assertThat(approved.authorizationCode().value()).isEqualTo("246810");
            });
        }

        @Test
        @DisplayName("traduz o comando interno para o formato esperado pela Stripe")
        void translatesRequestIntoStripeFormat() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"pi_1\",\"status\":\"succeeded\",\"latest_charge\":\"ch_1\"}")));

            gateway().authorize(request());

            verify(postRequestedFor(urlEqualTo(PATH))
                    .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + SECRET_KEY))
                    .withHeader("Idempotency-Key", equalTo(PAYMENT_ID.toString()))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    // BRL tem 2 casas: 149.90 -> 14990 centavos; moeda minúscula; cartão.
                    .withRequestBody(containing("amount=14990"))
                    .withRequestBody(containing("currency=brl"))
                    .withRequestBody(containing("payment_method_types%5B%5D=card"))
                    .withRequestBody(containing("confirm=true"))
                    .withRequestBody(containing("metadata%5Border_id%5D=" + ORDER_ID))
                    .withRequestBody(containing("metadata%5Bpayment_id%5D=" + PAYMENT_ID)));
        }

        @Test
        @DisplayName("deriva um código de autorização quando o meio não tem cartão")
        void derivesAuthorizationCodeWhenAbsent() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"pi_pix\",\"status\":\"succeeded\"}")));

            AuthorizationResult result = gateway().authorize(request());

            assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Approved.class, approved -> {
                // sem latest_charge cai para o id do intent; código de 6 dígitos derivado.
                assertThat(approved.transactionId().value()).isEqualTo("pi_pix");
                assertThat(approved.authorizationCode().value()).hasSize(6).containsOnlyDigits();
            });
        }
    }

    @Nested
    @DisplayName("declínio de negócio")
    class Declined {

        @Test
        @DisplayName("traduz card_error/insufficient_funds em Declined INSUFFICIENT_FUNDS")
        void translatesInsufficientFunds() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(402)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                            {
                              "error": {
                                "type": "card_error",
                                "code": "card_declined",
                                "decline_code": "insufficient_funds",
                                "message": "Your card has insufficient funds."
                              }
                            }
                            """)));

            AuthorizationResult result = gateway().authorize(request());

            assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Declined.class, declined -> {
                assertThat(declined.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
                assertThat(declined.details()).isEqualTo("Your card has insufficient funds.");
            });
        }

        @Test
        @DisplayName("mapeia decline_code de fraude em FRAUD_SUSPECTED")
        void translatesFraud() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(402)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\","
                            + "\"decline_code\":\"fraudulent\",\"message\":\"suspeita de fraude\"}}")));

            AuthorizationResult result = gateway().authorize(request());

            assertThat(result).isInstanceOfSatisfying(AuthorizationResult.Declined.class,
                    declined -> assertThat(declined.reason()).isEqualTo(FailureReason.FRAUD_SUSPECTED));
        }

        @Test
        @DisplayName("um declínio não conta como falha técnica para o circuit breaker")
        void declineDoesNotOpenCircuit() {
            CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("decline");
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(402)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\"}}")));

            StripePaymentGateway gateway = gateway(circuitBreaker, Duration.ofSeconds(5));
            for (int i = 0; i < 10; i++) {
                gateway.authorize(request());
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        }
    }

    @Nested
    @DisplayName("falha técnica")
    class TechnicalFailure {

        @Test
        @DisplayName("um 5xx vira PaymentGatewayException")
        void serverErrorBecomesGatewayException() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

            assertThatThrownBy(() -> gateway().authorize(request()))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("503");
        }

        @Test
        @DisplayName("um corpo malformado vira PaymentGatewayException")
        void malformedBodyBecomesGatewayException() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("isto não é json")));

            assertThatThrownBy(() -> gateway().authorize(request()))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("malformada");
        }

        @Test
        @DisplayName("um read timeout vira PaymentGatewayException")
        void readTimeoutBecomesGatewayException() {
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withFixedDelay(500)
                    .withBody("{\"id\":\"pi_1\",\"status\":\"succeeded\"}")));

            StripePaymentGateway gateway = gateway(CircuitBreaker.ofDefaults("timeout"), Duration.ofMillis(100));

            assertThatThrownBy(() -> gateway.authorize(request()))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("comunicação");
        }
    }

    @Nested
    @DisplayName("circuit breaker")
    class CircuitBreakerBehavior {

        @Test
        @DisplayName("abre após falhas persistentes e passa a falhar rápido sem tocar na rede")
        void opensAfterPersistentFailuresAndShortCircuits() {
            CircuitBreaker circuitBreaker = CircuitBreaker.of("payment-gateway", CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .recordExceptions(PaymentGatewayException.class)
                    .build());
            stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

            StripePaymentGateway gateway = gateway(circuitBreaker, Duration.ofSeconds(5));

            // 4 chamadas falham de verdade (tocam o WireMock) e enchem a janela.
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> gateway.authorize(request()))
                        .isInstanceOf(PaymentGatewayException.class);
            }
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // Com o circuito aberto, a próxima chamada falha rápido — sem HTTP.
            assertThatThrownBy(() -> gateway.authorize(request()))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("Circuit breaker")
                    .hasCauseInstanceOf(CallNotPermittedException.class);

            // O WireMock só viu as 4 primeiras: o short-circuit não tocou a rede.
            verify(exactly(4), postRequestedFor(urlEqualTo(PATH)));
        }
    }

    @Nested
    @DisplayName("captura")
    class Capture {

        private final String capturePath = PATH + "/" + GW_TX.value() + "/capture";

        @Test
        @DisplayName("traduz a captura para POST /capture com amount_to_capture e Idempotency-Key própria")
        void translatesCaptureRequest() {
            stubFor(post(urlEqualTo(capturePath)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"pi_1\",\"status\":\"succeeded\"}")));

            gateway().capture(new CaptureRequest(PAYMENT_ID, GW_TX, AMOUNT));

            verify(postRequestedFor(urlEqualTo(capturePath))
                    .withHeader("Idempotency-Key", equalTo(PAYMENT_ID + ":capture"))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withRequestBody(containing("amount_to_capture=14990")));
        }

        @Test
        @DisplayName("um 5xx vira PaymentGatewayException")
        void serverErrorBecomesGatewayException() {
            stubFor(post(urlEqualTo(capturePath)).willReturn(aResponse().withStatus(503)));

            assertThatThrownBy(() -> gateway().capture(new CaptureRequest(PAYMENT_ID, GW_TX, AMOUNT)))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("captura");
        }
    }

    @Nested
    @DisplayName("estorno")
    class Refund {

        @Test
        @DisplayName("traduz o estorno para POST /v1/refunds referenciando a transação")
        void translatesRefundRequest() {
            stubFor(post(urlEqualTo(REFUNDS_PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"re_1\",\"status\":\"succeeded\"}")));

            gateway().refund(new RefundRequest(PAYMENT_ID, GW_TX, AMOUNT, "OUT_OF_STOCK"));

            verify(postRequestedFor(urlEqualTo(REFUNDS_PATH))
                    .withHeader("Idempotency-Key", equalTo(PAYMENT_ID + ":refund"))
                    .withRequestBody(containing("charge=" + GW_TX.value()))
                    .withRequestBody(containing("amount=14990"))
                    .withRequestBody(containing("metadata%5Breason%5D=OUT_OF_STOCK")));
        }

        @Test
        @DisplayName("um 5xx vira PaymentGatewayException")
        void serverErrorBecomesGatewayException() {
            stubFor(post(urlEqualTo(REFUNDS_PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() ->
                    gateway().refund(new RefundRequest(PAYMENT_ID, GW_TX, AMOUNT, "x")))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("estorno");
        }
    }

    @Nested
    @DisplayName("cancelamento de autorização")
    class VoidAuthorization {

        private final String cancelPath = PATH + "/" + GW_TX.value() + "/cancel";

        @Test
        @DisplayName("traduz o cancelamento para POST /cancel com Idempotency-Key própria")
        void translatesVoidRequest() {
            stubFor(post(urlEqualTo(cancelPath)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"pi_1\",\"status\":\"canceled\"}")));

            gateway().voidAuthorization(new VoidRequest(PAYMENT_ID, GW_TX, "OUT_OF_STOCK"));

            verify(postRequestedFor(urlEqualTo(cancelPath))
                    .withHeader("Idempotency-Key", equalTo(PAYMENT_ID + ":void")));
        }

        @Test
        @DisplayName("um 5xx vira PaymentGatewayException")
        void serverErrorBecomesGatewayException() {
            stubFor(post(urlEqualTo(cancelPath)).willReturn(aResponse().withStatus(502)));

            assertThatThrownBy(() ->
                    gateway().voidAuthorization(new VoidRequest(PAYMENT_ID, GW_TX, "x")))
                    .isInstanceOf(PaymentGatewayException.class)
                    .hasMessageContaining("cancelamento");
        }
    }
}
