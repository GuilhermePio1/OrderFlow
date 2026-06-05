package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.repository.PaymentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Beans de suporte ao adapter JPA.
 *
 * O {@link Clock} é exposto aqui porque o repositório é o ponto onde
 * agregados são reidratados a partir do estado persistido — o clock injetado
 * governa o "agora" dos comandos posteriores no agregado reidratado.
 *
 * O Flyway é configurado automaticamente pelo Spring Boot a partir do
 * {@code spring.datasource} (migrações em {@code classpath:db/migration}),
 * executando antes da validação de schema do Hibernate ({@code ddl-auto: validate}).
 */
@Configuration
class PersistenceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PaymentEventCodec paymentEventCodec() {
        return PaymentEventCodec.withDefaultObjectMapper();
    }

    @Bean
    PaymentRepository paymentRepository(
            PaymentJpaSpringRepository payments,
            OutboxJpaSpringRepository outbox,
            PaymentEventCodec codec,
            Clock clock
    ) {
        return new JpaPaymentRepository(payments, outbox, codec, clock);
    }
}
