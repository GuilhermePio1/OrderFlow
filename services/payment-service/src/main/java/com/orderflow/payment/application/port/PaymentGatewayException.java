package com.orderflow.payment.application.port;

/**
 * Falha técnica ao comunicar com o gateway externo: timeout, indisponibilidade,
 * resposta malformada, 5xx. Distinta de um declínio de negócio
 * ({@link PaymentGateway.AuthorizationResult.Declined}).
 *
 * Um declínio é uma resposta legítima do gateway — o pagamento falha de forma
 * definitiva. Uma {@code PaymentGatewayException} sinaliza condição
 * potencialmente transitória: o caso de uso a propaga sem persistir nada, para
 * que o consumer de mensagens possa retentar (ou encaminhar à DLQ) em vez de
 * marcar o pagamento como falho por engano.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
