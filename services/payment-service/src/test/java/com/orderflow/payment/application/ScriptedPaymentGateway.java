package com.orderflow.payment.application;

import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.event.PaymentFailed;
import com.orderflow.payment.domain.model.valueobject.AuthorizationCode;
import com.orderflow.payment.domain.model.valueobject.GatewayTransactionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Dublê programável do {@link PaymentGateway} para testes de caso de uso.
 * Cada instância encena um único desfecho — aprovação, declínio ou falha
 * técnica — e registra as requisições recebidas, permitindo verificar tanto o
 * resultado quanto a tradução feita pela ACL ({@link AuthorizationRequest}).
 */
public final class ScriptedPaymentGateway implements PaymentGateway {

    private final List<AuthorizationRequest> requests = new ArrayList<>();
    private final AuthorizationResult result;
    private final RuntimeException error;

    private ScriptedPaymentGateway(AuthorizationResult result, RuntimeException error) {
        this.result = result;
        this.error = error;
    }

    public static ScriptedPaymentGateway approving(GatewayTransactionId transactionId,
                                                   AuthorizationCode authorizationCode) {
        return new ScriptedPaymentGateway(
                AuthorizationResult.approved(transactionId, authorizationCode), null);
    }

    public static ScriptedPaymentGateway declining(PaymentFailed.FailureReason reason, String details) {
        return new ScriptedPaymentGateway(AuthorizationResult.declined(reason, details), null);
    }

    public static ScriptedPaymentGateway unavailable(String message) {
        return new ScriptedPaymentGateway(null, new PaymentGatewayException(message));
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        requests.add(Objects.requireNonNull(request, "request"));
        if (error != null) {
            throw error;
        }
        return result;
    }

    public List<AuthorizationRequest> requests() {
        return Collections.unmodifiableList(requests);
    }

    public int callCount() {
        return requests.size();
    }

    public AuthorizationRequest lastRequest() {
        return requests.getLast();
    }
}
