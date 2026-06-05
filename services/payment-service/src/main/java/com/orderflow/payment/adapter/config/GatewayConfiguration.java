package com.orderflow.payment.adapter.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.payment.adapter.gateway.FakePaymentGateway;
import com.orderflow.payment.adapter.gateway.StripePaymentGateway;
import com.orderflow.payment.adapter.gateway.StripeProperties;
import com.orderflow.payment.application.port.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Seleciona e fia o {@link PaymentGateway} concreto. O adapter é escolhido por
 * configuração ({@code orderflow.payment.gateway}): {@code stripe} em ambientes
 * integrados, {@code fake} (padrão) para desenvolvimento local sem rede externa.
 * Trocar o provedor não toca em domínio nem aplicação — é o pagamento da
 * arquitetura hexagonal: a borda decide a implementação.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
class GatewayConfiguration {

    private static final String CIRCUIT_BREAKER_NAME = "payment-gateway";

    @Bean
    @ConditionalOnProperty(name = "orderflow.payment.gateway", havingValue = "stripe")
    PaymentGateway stripePaymentGateway(StripeProperties properties,
                                        CircuitBreakerRegistry circuitBreakerRegistry,
                                        RestClient.Builder restClientBuilder) { // Injetando o builder do Spring

        RestClient restClient = restClientBuilder // Preserva o tracing do Micrometer/Jaeger
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(properties.connectionTimeout())
                                .withReadTimeout(properties.readTimeout())))
                .build();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        return new StripePaymentGateway(restClient, circuitBreaker, stripeObjectMapper());
    }

    @Bean
    @ConditionalOnProperty(name = "orderflow.payment.gateway", havingValue = "fake", matchIfMissing = true)
    PaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    private static ObjectMapper stripeObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
