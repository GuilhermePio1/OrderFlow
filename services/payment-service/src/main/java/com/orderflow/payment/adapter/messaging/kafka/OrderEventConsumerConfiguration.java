package com.orderflow.payment.adapter.messaging.kafka;

import com.orderflow.payment.application.usecase.AuthorizePaymentUseCase;
import com.orderflow.payment.application.usecase.CapturePaymentUseCase;
import com.orderflow.payment.application.usecase.CompensatePaymentUseCase;
import com.orderflow.payment.domain.model.valueobject.PaymentMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Adapter de mensageria (Spring Cloud Stream) do contexto Payment — lado
 * consumidor. Assina o tópico {@code orders.events} do contexto Ordering e
 * dispara a autorização do pagamento ao receber {@code OrderPlaced}, o gatilho
 * da saga coreografada ({@code docs/architecture.md}).
 *
 * <p>Não há produtor aqui: os eventos próprios do Payment ({@code PaymentAuthorized},
 * {@code PaymentFailed}, ...) são publicados pelo padrão Outbox/CDC — Debezium
 * lê a tabela {@code outbox} gravada na mesma transação que altera o pagamento
 * (vide {@code JpaPaymentRepository} e {@code docs/event-sourcing.md}).
 *
 * <p>O tópico carrega vários tipos de evento; o roteamento é feito pelo header
 * {@value #EVENT_TYPE_HEADER}, propagado pelo {@code EventRouter} do Debezium
 * junto ao {@code event_id} (vide o conector em
 * {@code infrastructure/docker/kafka-connect/order-outbox-connector.json}).
 *
 * <p>O consumer é imperativo e bloqueante: roda nas threads do listener Kafka,
 * alinhado ao modelo Spring MVC + virtual threads deste contexto. O modelo
 * imperativo também habilita o tratamento de erro nativo do binder
 * (retentativas + DLQ), conforme {@code docs/architecture.md}.
 */
@Configuration
class OrderEventConsumerConfiguration {

    static final String EVENT_TYPE_HEADER = "event_type";

    @Bean
    InboundEventDeserializer inboundEventDeserializer() {
        return InboundEventDeserializer.withDefaultObjectMapper();
    }

    @Bean
    OrderEventHandler orderEventHandler(
            AuthorizePaymentUseCase authorizePayment,
            CapturePaymentUseCase capturePayment,
            CompensatePaymentUseCase compensatePayment,
            InboundEventDeserializer deserializer,
            @Value("${orderflow.payment.default-method:CREDIT_CARD}") PaymentMethod defaultMethod
    ) {
        return new OrderEventHandler(
                authorizePayment, capturePayment, compensatePayment, deserializer, defaultMethod);
    }

    @Bean
    Consumer<Message<byte[]>> orderEvents(OrderEventHandler handler) {
        return message -> handler.handle(eventType(message), message.getPayload());
    }

    private static String eventType(Message<byte[]> message) {
        Object header = message.getHeaders().get(EVENT_TYPE_HEADER);
        if (header == null) {
            return null;
        }
        // Headers nativos do Kafka chegam como byte[]; o mapper do binder pode
        // já tê-los decodificado para String dependendo da configuração.
        if (header instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return header.toString();
    }
}
