package com.orderflow.payment.adapter.config;

import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
import com.orderflow.payment.application.usecase.CapturePaymentUseCase;
import com.orderflow.payment.application.usecase.CompensatePaymentUseCase;
import com.orderflow.payment.application.usecase.RefundPaymentUseCase;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Expõe os casos de uso como beans. Os casos de uso são POJOs puros (a camada
 * application não depende de Spring, por design hexagonal — vide
 * {@code docs/ddd.md} e os testes de arquitetura); a fiação fica na borda.
 *
 * {@code PaymentRepository} é provido pelo adapter JPA, {@code Clock} pela
 * {@code PersistenceConfiguration} e o {@link PaymentGateway} pela
 * {@code GatewayConfiguration} (Stripe ou fake, conforme configuração).
 */
@Configuration
class PaymentUseCaseConfiguration {

    @Bean
    AuthorizePaymentUseCase authorizePaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            Clock clock
    ) {
        return new AuthorizePaymentUseCase(paymentRepository, paymentGateway, clock);
    }

    @Bean
    CapturePaymentUseCase capturePaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway
    ) {
        return new CapturePaymentUseCase(paymentRepository, paymentGateway);
    }

    @Bean
    CompensatePaymentUseCase compensatePaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway
    ) {
        return new CompensatePaymentUseCase(paymentRepository, paymentGateway);
    }

    @Bean
    RefundPaymentUseCase refundPaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway
    ) {
        return new RefundPaymentUseCase(paymentRepository, paymentGateway);
    }
}
