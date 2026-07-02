package com.orderflow.payment.adapter.gateway;

import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.domain.model.valueobject.AuthorizationCode;
import com.orderflow.payment.domain.model.valueobject.GatewayTransactionId;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Adapter de gateway para desenvolvimento e ambientes locais. Substitui o
 * provedor real (Stripe, PagSeguro) enquanto a integração concreta não existe,
 * permitindo exercitar o fluxo de autorização ponta-a-ponta sem rede externa.
 *
 * Aprova toda requisição válida, gerando identificadores de rastreabilidade
 * (transação + código de autorização) no vocabulário interno — o papel da ACL.
 * Declínios e falhas técnicas são deterministicamente provocados nos testes do
 * caso de uso por dublês programáveis; aqui o comportamento é o caminho feliz,
 * honesto para um fake.
 *
 * A geração de ids é injetável (construtor de pacote) para permitir asserts
 * determinísticos nos testes.
 */
public final class FakePaymentGateway implements PaymentGateway {

    private final Supplier<String> transactionIdFactory;

    public FakePaymentGateway() {
        this(() -> "fake_" + UUID.randomUUID());
    }

    FakePaymentGateway(Supplier<String> transactionIdFactory) {
        this.transactionIdFactory = Objects.requireNonNull(transactionIdFactory, "transactionIdFactory");
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        String transactionId = transactionIdFactory.get();
        return AuthorizationResult.approved(
                GatewayTransactionId.of(transactionId),
                AuthorizationCode.of(authorizationCodeFor(transactionId))
        );
    }

    /**
     * Captura, estorno e cancelamento são bem-sucedidos sem rede no fake — o
     * caminho feliz, honesto para desenvolvimento local. Os desfechos técnicos
     * (indisponibilidade) são exercitados nos testes por dublês programáveis.
     */
    @Override
    public void capture(CaptureRequest request) {
        Objects.requireNonNull(request, "request");
    }

    @Override
    public void refund(RefundRequest request) {
        Objects.requireNonNull(request, "request");
    }

    @Override
    public void voidAuthorization(VoidRequest request) {
        Objects.requireNonNull(request, "request");
    }

    /**
     * Deriva um código de autorização de 6 dígitos do id da transação, imitando
     * o formato de uma adquirente real. Determinístico em relação ao id, o que
     * mantém os testes previsíveis.
     */
    private static String authorizationCodeFor(String transactionId) {
        int positiveHash = transactionId.hashCode() & 0x7fffffff;
        return String.format("%06d", positiveHash % 1_000_000);
    }
}
