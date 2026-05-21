package com.orderflow.order.adapter.config;

import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ConfirmOrderPaymentUseCase;
import com.orderflow.order.application.usecase.PlaceOrderUseCase;
import com.orderflow.order.application.usecase.ReserveOrderInventoryUseCase;
import com.orderflow.order.domain.repository.OrderRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Expõe os casos de uso como beans. Eles são POJOs puros (a camada
 * application não depende de Spring, por design hexagonal — vide
 * {@code docs/ddd.md} e os testes de arquitetura); a fiação fica na
 * borda. {@code OrderRepository} é provido pelo adapter R2DBC e
 * {@code Clock} pela configuração de persistência.
 */
@Configuration
class OrderUseCaseConfiguration {

    @Bean
    PlaceOrderUseCase placeOrderUseCase(OrderRepository orderRepository, Clock clock) {
        return new PlaceOrderUseCase(orderRepository, clock);
    }

    @Bean
    CancelOrderUseCase cancelOrderUseCase(OrderRepository orderRepository) {
        return new CancelOrderUseCase(orderRepository);
    }

    @Bean
    ConfirmOrderPaymentUseCase confirmOrderPaymentUseCase(OrderRepository orderRepository) {
        return new ConfirmOrderPaymentUseCase(orderRepository);
    }

    @Bean
    ReserveOrderInventoryUseCase reserveOrderInventoryUseCase(OrderRepository orderRepository) {
        return new ReserveOrderInventoryUseCase(orderRepository);
    }
}
