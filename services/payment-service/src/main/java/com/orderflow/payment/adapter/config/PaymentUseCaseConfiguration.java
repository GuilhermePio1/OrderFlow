package com.orderflow.payment.adapter.config;

import com.orderflow.payment.adapter.gateway.FakePaymentGateway;
import com.orderflow.payment.application.port.PaymentGateway;
import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Expõe os casos de uso e o gateway como beans. Os casos de uso são POJOs puros
 * (a camada application não depende de Spring, por design hexagonal — vide
 * {@code docs/ddd.md} e os testes de arquitetura); a fiação fica na borda.
 *
 * {@code PaymentRepository} é provido pelo adapter JPA e {@code Clock} pela
 * {@code PersistenceConfiguration}. O {@link PaymentGateway} é, por ora, o
 * {@link FakePaymentGateway}; quando o adapter real (Stripe/PagSeguro) existir,
 * basta trocar este bean.
 */
@Configuration
class PaymentUseCaseConfiguration {

    @Bean
    PaymentGateway paymentGateway() {
        return new FakePaymentGateway();
    }

    @Bean
    AuthorizePaymentUseCase authorizePaymentUseCase(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            Clock clock
    ) {
        return new AuthorizePaymentUseCase(paymentRepository, paymentGateway, clock);
    }
}
