package com.orderflow.payment.application.port;

import com.orderflow.payment.domain.event.PaymentFailed;
import com.orderflow.payment.domain.model.valueobject.*;

import java.util.Objects;

/**
 * Port da Anti-Corruption Layer com gateways de pagamento externos (Stripe,
 * PagSeguro). {@code docs/ddd.md}: o contexto Payment "possui uma
 * Anti-Corruption Layer (ACL) ao se comunicar com gateways de pagamento
 * externos, traduzindo modelos externos para o vocabulário interno".
 *
 * A interface vive na camada de aplicação — não no domínio: comunicar com o
 * gateway é orquestração de caso de uso, e o agregado {@code Payment} conhece
 * apenas o resultado já traduzido para seu vocabulário ({@link AuthorizationResult}),
 * nunca o modelo do provedor externo.
 *
 * Implementações concretas vivem na camada de adapter (Stripe, PagSeguro ou um
 * {@code FakePaymentGateway} para desenvolvimento local).
 */
public interface PaymentGateway {

    /**
     * Solicita autorização ao gateway externo.
     *
     * <p>Declínios de negócio (cartão recusado, saldo insuficiente, suspeita de
     * fraude) são um <em>resultado normal</em> e retornam
     * {@link AuthorizationResult.Declined} — não uma exceção.
     *
     * <p>Falhas técnicas (timeout, 5xx, indisponibilidade) lançam
     * {@link PaymentGatewayException}, sinalizando ao caller uma condição
     * potencialmente transitória que pode ser retentada.
     */
    AuthorizationResult authorize(AuthorizationRequest request);

    /**
     * Captura uma autorização previamente concedida, movendo os fundos para
     * liquidação. Disparada quando o pedido é confirmado (consumo de
     * {@code OrderConfirmed}).
     *
     * <p>Diferente da autorização, não há "declínio de negócio" aqui: capturar
     * uma autorização válida ou sucede ou esbarra numa condição técnica. Por isso
     * falhas (timeout, 5xx, autorização expirada no provedor) propagam como
     * {@link PaymentGatewayException} — sinalizando uma condição potencialmente
     * transitória que o caller pode retentar.
     */
    void capture(CaptureRequest request);

    /**
     * Estorna (total ou parcialmente) uma captura. Usado pela saga de
     * compensação quando o pagamento já fora capturado e o pedido é cancelado.
     * Falhas técnicas propagam como {@link PaymentGatewayException}.
     */
    void refund(RefundRequest request);

    /**
     * Reverte uma autorização ainda <em>não</em> capturada, liberando o limite
     * retido no cartão. Usado pela saga de compensação quando o pagamento estava
     * apenas autorizado. Falhas técnicas propagam como
     * {@link PaymentGatewayException}.
     */
    void voidAuthorization(VoidRequest request);

    /**
     * Requisição de autorização no vocabulário interno do contexto Payment. O
     * adapter traduz estes campos para o formato esperado pelo provedor externo.
     */
    record AuthorizationRequest(
            PaymentId paymentId,
            OrderId orderId,
            CustomerId customerId,
            Money amount,
            PaymentMethod method
    ) {
        public AuthorizationRequest {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * Requisição de captura. Carrega o {@link GatewayTransactionId} devolvido na
     * autorização — a referência pela qual o provedor externo localiza a
     * transação a capturar.
     */
    record CaptureRequest(
            PaymentId paymentId,
            GatewayTransactionId gatewayTransactionId,
            Money amount
    ) {
        public CaptureRequest {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(gatewayTransactionId, "gatewayTransactionId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Requisição de estorno. {@code reason} é opcional (anotação para o provedor). */
    record RefundRequest(
            PaymentId paymentId,
            GatewayTransactionId gatewayTransactionId,
            Money amount,
            String reason
    ) {
        public RefundRequest {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(gatewayTransactionId, "gatewayTransactionId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Requisição de cancelamento de autorização. {@code reason} é opcional. */
    record VoidRequest(
            PaymentId paymentId,
            GatewayTransactionId gatewayTransactionId,
            String reason
    ) {
        public VoidRequest {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(gatewayTransactionId, "gatewayTransactionId");
        }
    }

    /**
     * Resultado da autorização já traduzido para o vocabulário interno. Aprovado
     * carrega os identificadores de rastreabilidade do provedor; recusado carrega
     * a razão de domínio que o agregado entende.
     */
    sealed interface AuthorizationResult
            permits AuthorizationResult.Approved, AuthorizationResult.Declined {

        record Approved(GatewayTransactionId transactionId, AuthorizationCode authorizationCode)
                implements AuthorizationResult {
            public Approved {
                Objects.requireNonNull(transactionId, "transactionId");
                Objects.requireNonNull(authorizationCode, "authorizationCode");
            }
        }

        record Declined(PaymentFailed.FailureReason reason, String details)
                implements AuthorizationResult {
            public Declined {
                Objects.requireNonNull(reason, "reason");
            }
        }

        static AuthorizationResult approved(GatewayTransactionId transactionId,
                                            AuthorizationCode authorizationCode) {
            return new Approved(transactionId, authorizationCode);
        }

        static AuthorizationResult declined(PaymentFailed.FailureReason reason, String details) {
            return new Declined(reason, details);
        }
    }
}
