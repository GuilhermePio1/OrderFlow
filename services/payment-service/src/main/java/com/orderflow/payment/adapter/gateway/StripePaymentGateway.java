package com.orderflow.payment.adapter.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentFailed.FailureReason;
import com.orderflow.payment.domain.model.valueobject.AuthorizationCode;
import com.orderflow.payment.domain.model.valueobject.GatewayTransactionId;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.PaymentMethod;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Adapter da Anti-Corruption Layer (ACL) com o gateway Stripe.
 * {@code docs/ddd.md}: o contexto Payment "possui uma ACL ao se comunicar com
 * gateways de pagamento externos (Stripe, PagSeguro), traduzindo modelos
 * externos para o vocabulário interno". Aqui essa tradução é concreta:
 *
 * <ul>
 *   <li>A {@link AuthorizationRequest} interna vira um {@code POST
 *       /v1/payment_intents} (montante em centavos, moeda minúscula,
 *       {@code payment_method_types} mapeados, metadados de rastreabilidade).</li>
 *   <li>A resposta da Stripe é traduzida de volta: {@code succeeded} →
 *       {@link AuthorizationResult.Approved}; um {@code card_error} (HTTP 402) →
 *       {@link AuthorizationResult.Declined} com a {@link FailureReason} de
 *       domínio; qualquer falha técnica (5xx, 429, timeout, corpo malformado) →
 *       {@link PaymentGatewayException}.</li>
 * </ul>
 *
 * <p><b>Circuit breaker.</b> Toda chamada externa é envolvida por um
 * {@link CircuitBreaker} Resilience4j ({@code docs/architecture.md},
 * "Estratégias de Resiliência"). Declínios de negócio retornam normalmente e
 * <em>não</em> contam como falha — não é o cartão recusado que indica
 * indisponibilidade. Apenas falhas técnicas ({@link PaymentGatewayException})
 * são registradas; após o limiar, o circuito abre e as chamadas seguintes falham
 * rápido com {@link CallNotPermittedException}, reembaladas como
 * {@link PaymentGatewayException} para o caller — que então retenta ou encaminha
 * à DLQ em vez de marcar o pagamento como definitivamente falho.
 *
 * <p>O modelo externo (records {@code Stripe*} abaixo) nunca vaza para fora deste
 * adapter: é o ponto exato em que o vocabulário da Stripe é isolado do domínio.
 */
public final class StripePaymentGateway implements PaymentGateway {

    private static final String PAYMENT_INTENTS_PATH = "/v1/payment_intents";
    private static final String REFUNDS_PATH = "/v1/refunds";
    private static final int HTTP_PAYMENT_REQUIRED = 402;

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    public StripePaymentGateway(RestClient restClient,
                                CircuitBreaker circuitBreaker,
                                ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return circuitBreaker.executeSupplier(() -> callStripe(request));
        } catch (CallNotPermittedException e) {
            // Circuito aberto: falha rápida sem tocar na rede. Transitório do
            // ponto de vista do caller — sinaliza para retentar mais tarde.
            throw new PaymentGatewayException(
                    "Circuit breaker '" + circuitBreaker.getName()
                            + "' aberto — gateway Stripe considerado indisponível", e);
        }
    }

    /**
     * Captura a autorização: {@code POST /v1/payment_intents/{id}/capture}.
     * Diferente da autorização, não há desfecho de "declínio de negócio" — capturar
     * uma transação válida sucede ou esbarra numa condição técnica (mapeada para
     * {@link PaymentGatewayException}).
     */
    @Override
    public void capture(CaptureRequest request) {
        Objects.requireNonNull(request, "request");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount_to_capture", String.valueOf(toMinorUnits(request.amount())));
        String path = PAYMENT_INTENTS_PATH + "/" + request.gatewayTransactionId().value() + "/capture";
        write(path, form, idempotencyKey(request.paymentId().toString(), "capture"), "captura");
    }

    /** Estorna a captura: {@code POST /v1/refunds} referenciando a transação (charge). */
    @Override
    public void refund(RefundRequest request) {
        Objects.requireNonNull(request, "request");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("charge", request.gatewayTransactionId().value());
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        if (request.reason() != null && !request.reason().isBlank()) {
            form.add("metadata[reason]", request.reason());
        }
        write(REFUNDS_PATH, form, idempotencyKey(request.paymentId().toString(), "refund"), "estorno");
    }

    /** Cancela a autorização não capturada: {@code POST /v1/payment_intents/{id}/cancel}. */
    @Override
    public void voidAuthorization(VoidRequest request) {
        Objects.requireNonNull(request, "request");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (request.reason() != null && !request.reason().isBlank()) {
            form.add("metadata[reason]", request.reason());
        }
        String path = PAYMENT_INTENTS_PATH + "/" + request.gatewayTransactionId().value() + "/cancel";
        write(path, form, idempotencyKey(request.paymentId().toString(), "void"), "cancelamento");
    }

    /**
     * Executa um POST de escrita (captura/estorno/cancelamento) envolvido pelo
     * circuit breaker. Qualquer não-2xx ou falha de transporte vira
     * {@link PaymentGatewayException} — não há "declínio" nestas operações sobre
     * uma transação já autorizada.
     */
    private void write(String path, MultiValueMap<String, String> form, String idempotencyKey, String operation) {
        try {
            circuitBreaker.executeRunnable(() -> postExpectingSuccess(path, form, idempotencyKey, operation));
        } catch (CallNotPermittedException e) {
            throw new PaymentGatewayException(
                    "Circuit breaker '" + circuitBreaker.getName()
                            + "' aberto — gateway Stripe considerado indisponível", e);
        }
    }

    private void postExpectingSuccess(String path,
                                      MultiValueMap<String, String> form,
                                      String idempotencyKey,
                                      String operation) {
        try {
            restClient.post()
                    .uri(path)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((httpRequest, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new PaymentGatewayException("Stripe respondeu status " + status.value()
                                    + " para " + operation + ": " + readBody(response));
                        }
                        return null;
                    });
        } catch (PaymentGatewayException e) {
            throw e; // já classificada como falha técnica
        } catch (RestClientException e) {
            throw new PaymentGatewayException(
                    "Falha de comunicação com o gateway Stripe (" + operation + ")", e);
        }
    }

    /**
     * Chave de idempotência distinta por operação: a Stripe devolve a resposta
     * cacheada para uma mesma chave, então autorizar e capturar o mesmo pagamento
     * precisam de chaves diferentes para não colidir.
     */
    private static String idempotencyKey(String paymentId, String operation) {
        return paymentId + ":" + operation;
    }

    private AuthorizationResult callStripe(AuthorizationRequest request) {
        try {
            return restClient.post()
                    .uri(PAYMENT_INTENTS_PATH)
                    .header("Idempotency-Key", request.paymentId().toString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody(request))
                    .exchange((httpRequest, response) -> translate(response.getStatusCode(), readBody(response)));
        } catch (PaymentGatewayException e) {
            throw e; // já classificada como falha técnica
        } catch (RestClientException e) {
            // Timeout de conexão/leitura, DNS, reset — I/O de transporte.
            throw new PaymentGatewayException("Falha de comunicação com o gateway Stripe", e);
        }
    }

    private static String readBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            String body = response.bodyTo(String.class);
            return body == null ? "" : body;
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Não foi possível ler a resposta do gateway Stripe", e);
        }
    }

    /** Traduz a resposta HTTP da Stripe para o vocabulário interno. */
    private AuthorizationResult translate(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return translateSuccess(body);
        }
        if (status.value() == HTTP_PAYMENT_REQUIRED) {
            return translateDecline(body);
        }
        // 5xx, 429 (rate limit), 4xx inesperados: condição técnica/transitória.
        throw new PaymentGatewayException(
                "Stripe respondeu status técnico " + status.value() + ": " + body);
    }

    private AuthorizationResult translateSuccess(String body) {
        StripePaymentIntent intent = parse(body, StripePaymentIntent.class);
        if (!"succeeded".equals(intent.status())) {
            // confirm=true que não terminou em succeeded (ex.: requires_payment_method):
            // o pagamento não passou — declínio, não outage.
            return AuthorizationResult.declined(FailureReason.CARD_DECLINED,
                    "Stripe payment_intent status=" + intent.status());
        }
        String transactionId = intent.transactionId();
        return AuthorizationResult.approved(
                GatewayTransactionId.of(transactionId),
                AuthorizationCode.of(intent.resolveAuthorizationCode(transactionId)));
    }

    private AuthorizationResult translateDecline(String body) {
        StripeErrorEnvelope envelope = parse(body, StripeErrorEnvelope.class);
        StripeError error = envelope.error();
        if (error == null) {
            return AuthorizationResult.declined(FailureReason.CARD_DECLINED, "card_error");
        }
        String code = error.declineCode() != null ? error.declineCode() : error.code();
        return AuthorizationResult.declined(mapDeclineReason(code), error.detail());
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new PaymentGatewayException("Resposta malformada do gateway Stripe: " + body, e);
        }
    }

    /** Mapeia o {@code decline_code}/{@code code} da Stripe para a razão de domínio. */
    private static FailureReason mapDeclineReason(String code) {
        if (code == null) {
            return FailureReason.CARD_DECLINED;
        }
        return switch (code) {
            case "insufficient_funds" -> FailureReason.INSUFFICIENT_FUNDS;
            case "fraudulent", "stolen_card", "lost_card", "pickup_card" -> FailureReason.FRAUD_SUSPECTED;
            case "card_not_supported", "currency_not_supported", "invalid_account" ->
                FailureReason.INVALID_PAYMENT_METHOD;
            case "card_declined", "generic_decline", "do_not_honor", "expired_card",
                 "incorrect_cvc", "incorrect_number" -> FailureReason.CARD_DECLINED;
            default -> FailureReason.UNKNOWN;
        };
    }

    private static MultiValueMap<String, String> formBody(AuthorizationRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        form.add("currency", request.amount().currency().getCurrencyCode().toLowerCase());
        form.add("payment_method_types[]", toStripeMethod(request.method()));
        form.add("confirm", "true");
        form.add("metadata[order_id]", request.orderId().toString());
        form.add("metadata[payment_id]", request.paymentId().toString());
        form.add("metadata[customer_id]", request.customerId().toString());
        return form;
    }

    /** Converte para a menor unidade da moeda (centavos), como a Stripe espera. */
    private static long toMinorUnits(Money money) {
        int fractionDigits = money.currency().getDefaultFractionDigits();
        BigDecimal minor = money.amount().movePointRight(Math.max(fractionDigits, 0));
        return minor.longValueExact();
    }

    private static String toStripeMethod(PaymentMethod method) {
        return switch (method) {
            case CREDIT_CARD, DEBIT_CARD -> "card";
            case PIX -> "pix";
            case BOLETO -> "boleto";
            case WALLET -> "wallet";
        };
    }

    // ----- Modelo externo da Stripe (isolado neste adapter pela ACL) -----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StripePaymentIntent(
            @JsonProperty("id") String id,
            @JsonProperty("status") String status,
            @JsonProperty("latest_charge") String latestCharge,
            @JsonProperty("payment_method_details") StripePaymentMethodDetails paymentMethodDetails
    ) {
        /** Prefere o id da cobrança (charge) para captura/estorno; cai para o id do intent. */
        String transactionId() {
            if (latestCharge != null && !latestCharge.isBlank()) {
                return latestCharge;
            }
            if (id == null || id.isBlank()) {
                throw new PaymentGatewayException("Stripe não retornou identificador de transação");
            }
            return id;
        }

        /**
         * Usa o código de autorização da adquirente quando presente (cartões);
         * para meios sem esse conceito (pix, boleto), deriva um código
         * determinístico de 6 dígitos do id da transação para conciliação.
         */
        String resolveAuthorizationCode(String transactionId) {
            if (paymentMethodDetails != null
                    && paymentMethodDetails.card() != null
                    && paymentMethodDetails.card().authorizationCode() != null
                    && !paymentMethodDetails.card().authorizationCode().isBlank()) {
                return paymentMethodDetails.card().authorizationCode();
            }
            int positiveHash = transactionId.hashCode() & 0x7fffffff;
            return String.format("%06d", positiveHash % 1_000_000);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StripePaymentMethodDetails(@JsonProperty("card") StripeCardDetails card) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StripeCardDetails(@JsonProperty("authorization_code") String authorizationCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StripeErrorEnvelope(@JsonProperty("error") StripeError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StripeError(
            @JsonProperty("type") String type,
            @JsonProperty("code") String code,
            @JsonProperty("decline_code") String declineCode,
            @JsonProperty("message") String message
    ) {
        String detail() {
            if (message != null && !message.isBlank()) {
                return message;
            }
            return declineCode != null ? declineCode : code;
        }
    }
}
