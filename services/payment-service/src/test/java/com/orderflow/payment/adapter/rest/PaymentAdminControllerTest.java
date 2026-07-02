package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.application.command.RefundPaymentCommand;
import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.application.usecase.RefundPaymentUseCase;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.RefundExceedsCapturedAmountException;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de fatia web com a cadeia de segurança real importada
 * ({@link SecurityConfiguration}): além do contrato REST, verifica a
 * autorização coarse-grained — token ausente → 401, papel insuficiente →
 * 403, {@code PAYMENT_ADMIN} → autorizado. O decoder JWT é mockado (a
 * validação criptográfica pertence ao Keycloak; aqui interessam as regras
 * de rota e o RBAC).
 */
@WebMvcTest(PaymentAdminController.class)
@Import({SecurityConfiguration.class, PaymentAuditLogger.class})
@DisplayName("PaymentAdminController")
class PaymentAdminControllerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2025-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final GatewayTransactionId GW_TX = GatewayTransactionId.of("ch_test_123");
    private static final AuthorizationCode AUTH_CODE = AuthorizationCode.of("A1B2C3");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.orderflow.payment.domain.repository.PaymentRepository paymentRepository;

    @MockitoBean
    private RefundPaymentUseCase refundPaymentUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor admin() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_PAYMENT_ADMIN"));
    }

    private static RequestPostProcessor customer() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_CUSTOMER"));
    }

    private static Payment capturedPayment() {
        Payment payment = Payment.initiate(
                PaymentId.generate(),
                OrderId.of(UUID.randomUUID()),
                CustomerId.of(UUID.randomUUID()),
                Money.of("149.90", "BRL"),
                PaymentMethod.CREDIT_CARD,
                CLOCK);
        payment.authorize(GW_TX, AUTH_CODE);
        payment.capture();
        payment.pullUncommittedEvents();
        return payment;
    }

    @Test
    @DisplayName("GET /api/payments/{id} sem token responde 401")
    void getPaymentWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/payments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/payments/{id} sem papel PAYMENT_ADMIN responde 403")
    void getPaymentWithoutAdminRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/payments/" + UUID.randomUUID()).with(customer()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/payments/{id} responde 200 com a visão administrativa")
    void getPaymentReturnsAdminView() throws Exception {
        Payment payment = capturedPayment();
        when(paymentRepository.findById(payment.id())).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/api/payments/" + payment.id()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment.id().value().toString()))
                .andExpect(jsonPath("$.orderId").value(payment.orderId().value().toString()))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.method").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.authorizedAmount.amount").value(149.90))
                .andExpect(jsonPath("$.authorizedAmount.currency").value("BRL"))
                .andExpect(jsonPath("$.capturedAmount.amount").value(149.90))
                .andExpect(jsonPath("$.gatewayTransactionId").value("ch_test_123"));
    }

    @Test
    @DisplayName("GET /api/payments/{id} responde 404 quando o pagamento não existe")
    void getPaymentNotFound() throws Exception {
        when(paymentRepository.findById(any(PaymentId.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/payments/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    @Test
    @DisplayName("GET /api/payments/{id} com UUID inválido responde 400")
    void getPaymentRejectsMalformedId() throws Exception {
        mockMvc.perform(get("/api/payments/not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/payments/by-order/{orderId} responde 200")
    void getPaymentByOrderReturnsAdminView() throws Exception {
        Payment payment = capturedPayment();
        when(paymentRepository.findByOrderId(payment.orderId())).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/api/payments/by-order/" + payment.orderId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment.id().value().toString()))
                .andExpect(jsonPath("$.orderId").value(payment.orderId().value().toString()));
    }

    @Test
    @DisplayName("GET /api/payments/by-order/{orderId} responde 404 quando não há pagamento")
    void getPaymentByOrderNotFound() throws Exception {
        when(paymentRepository.findByOrderId(any(OrderId.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/payments/by-order/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/payments/{id}/refund executa o estorno e responde 200")
    void refundExecutesUseCase() throws Exception {
        Payment payment = capturedPayment();
        payment.refund(Money.of("50.00", "BRL"), "Disputa resolvida a favor do cliente");
        payment.pullUncommittedEvents();
        when(refundPaymentUseCase.execute(any(RefundPaymentCommand.class))).thenReturn(payment);

        mockMvc.perform(post("/api/payments/" + payment.id() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("""
                                {
                                  "amount": 50.00,
                                  "currency": "BRL",
                                  "reason": "Disputa resolvida a favor do cliente"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.refundedAmount.amount").value(50.00));

        ArgumentCaptor<RefundPaymentCommand> captor =
                ArgumentCaptor.forClass(RefundPaymentCommand.class);
        verify(refundPaymentUseCase).execute(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(payment.id());
        assertThat(captor.getValue().amount()).isEqualTo(Money.of("50.00", "BRL"));
        assertThat(captor.getValue().reason()).isEqualTo("Disputa resolvida a favor do cliente");
    }

    @Test
    @DisplayName("POST refund sem papel PAYMENT_ADMIN responde 403")
    void refundWithoutAdminRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(customer())
                        .contentType("application/json")
                        .content("{\"amount\": 50.00, \"currency\": \"BRL\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST refund com valor não positivo responde 400 com erros de validação")
    void refundRejectsNonPositiveAmount() throws Exception {
        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"amount\": -1.00, \"currency\": \"BRL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    @DisplayName("POST refund com moeda desconhecida responde 400")
    void refundRejectsUnknownCurrency() throws Exception {
        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"amount\": 50.00, \"currency\": \"XXX-INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST refund mapeia transição inválida como 409")
    void refundMapsInvalidStateTo409() throws Exception {
        when(refundPaymentUseCase.execute(any(RefundPaymentCommand.class)))
                .thenThrow(new InvalidPaymentStateTransitionException(
                        PaymentStatus.AUTHORIZED, "refund"));

        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"amount\": 50.00, \"currency\": \"BRL\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST refund acima do saldo capturado responde 400")
    void refundMapsExceededAmountTo400() throws Exception {
        when(refundPaymentUseCase.execute(any(RefundPaymentCommand.class)))
                .thenThrow(new RefundExceedsCapturedAmountException(
                        Money.of("200.00", "BRL"), Money.of("149.90", "BRL")));

        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"amount\": 200.00, \"currency\": \"BRL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Domain rule violation"));
    }

    @Test
    @DisplayName("POST refund com gateway indisponível responde 502")
    void refundMapsGatewayFailureTo502() throws Exception {
        when(refundPaymentUseCase.execute(any(RefundPaymentCommand.class)))
                .thenThrow(new PaymentGatewayException("gateway indisponível"));

        mockMvc.perform(post("/api/payments/" + UUID.randomUUID() + "/refund")
                        .with(admin())
                        .contentType("application/json")
                        .content("{\"amount\": 50.00, \"currency\": \"BRL\"}"))
                .andExpect(status().isBadGateway());
    }
}
