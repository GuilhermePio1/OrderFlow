package com.orderflow.order.adapter.persistence.r2dbc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Clock;

/**
 * Beans de suporte ao adapter R2DBC.
 *
 * O {@link Clock} é exposto aqui porque o repositório é o ponto onde
 * agregados são instanciados a partir do histórico de eventos — o
 * clock injetado governa o "agora" dos comandos posteriores no
 * agregado reidratado.
 */
@Configuration
class PersistenceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionalOperator transactionalOperator(ReactiveTransactionManager txManager) {
        return TransactionalOperator.create(txManager);
    }

    @Bean
    OrderEventCodec orderEventCodec() {
        return new OrderEventCodec(OrderEventCodec.defaultObjectMapper());
    }
}
