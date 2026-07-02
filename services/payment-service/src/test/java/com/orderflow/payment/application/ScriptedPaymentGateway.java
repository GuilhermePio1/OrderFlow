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
 *
 * <p>Captura, estorno e cancelamento sucedem por padrão e são registrados; uma
 * instância {@link #unavailable(String)} faz <em>todas</em> as operações
 * lançarem a falha técnica encenada, exercitando os caminhos de retentativa.
 */
public final class ScriptedPaymentGateway implements PaymentGateway {

    private final List<AuthorizationRequest> requests = new ArrayList<>();
    private final List<CaptureRequest> captures = new ArrayList<>();
    private final List<RefundRequest> refunds = new ArrayList<>();
    private final List<VoidRequest> voids = new ArrayList<>();
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

    @Override
    public void capture(CaptureRequest request) {
        captures.add(Objects.requireNonNull(request, "request"));
        if (error != null) {
            throw error;
        }
    }

    @Override
    public void refund(RefundRequest request) {
        refunds.add(Objects.requireNonNull(request, "request"));
        if (error != null) {
            throw error;
        }
    }

    @Override
    public void voidAuthorization(VoidRequest request) {
        voids.add(Objects.requireNonNull(request, "request"));
        if (error != null) {
            throw error;
        }
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

    public List<CaptureRequest> captures() {
        return Collections.unmodifiableList(captures);
    }

    public CaptureRequest lastCapture() {
        return captures.getLast();
    }

    public List<RefundRequest> refunds() {
        return Collections.unmodifiableList(refunds);
    }

    public RefundRequest lastRefund() {
        return refunds.getLast();
    }

    public List<VoidRequest> voids() {
        return Collections.unmodifiableList(voids);
    }

    public VoidRequest lastVoid() {
        return voids.getLast();
    }
}
