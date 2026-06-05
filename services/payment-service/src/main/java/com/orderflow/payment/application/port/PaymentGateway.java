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
