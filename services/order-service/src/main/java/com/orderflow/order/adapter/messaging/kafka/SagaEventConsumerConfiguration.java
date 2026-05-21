package com.orderflow.order.adapter.messaging.kafka;

import com.orderflow.order.application.usecase.CancelOrderUseCase;
import com.orderflow.order.application.usecase.ConfirmOrderPaymentUseCase;
import com.orderflow.order.application.usecase.ReserveOrderInventoryUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Adapter de mensageria (Spring Cloud Stream) do contexto Ordering.
 *
 * O Ordering é upstream: seus próprios eventos são publicados via Outbox/CDC
 * (Debezium lê a tabela {@code outbox}, vide {@code R2dbcOrderRepository} e
 * {@code docs/event-sourcing.md}) — por isso não há produtor aqui. Este
 * adapter é o lado consumidor: assina os tópicos dos contextos a jusante e
 * dispara as transições da saga coreografada ({@code docs/architecture.md}).
 *
 * <p>Cada tópico carrega vários tipos de evento; o roteamento é feito pelo
 * header {@value #EVENT_TYPE_HEADER}, propagado pelo padrão Outbox junto ao
 * {@code event_id} (vide comentário da migração {@code V2__create_outbox_table.sql}).
 *
 * <p>Os consumers são imperativos e bloqueiam no caso de uso reativo: rodam
 * em threads do listener Kafka (não no event loop), de modo que o bloqueio é
 * seguro e ainda provê backpressure natural — o poll só avança quando o
 * processamento conclui. O modelo imperativo também habilita o tratamento de
 * erro nativo do binder (retentativas + DLQ), conforme {@code docs/architecture.md}.
 */
@Configuration
class SagaEventConsumerConfiguration {

    static final String EVENT_TYPE_HEADER = "event_type";

    @Bean
    InboundEventDeserializer inboundEventDeserializer() {
        return InboundEventDeserializer.withDefaultObjectMapper();
    }

    @Bean
    PaymentEventHandler paymentEventHandler(
            ConfirmOrderPaymentUseCase confirmOrderPayment,
            CancelOrderUseCase cancelOrder,
            InboundEventDeserializer deserializer
    ) {
        return new PaymentEventHandler(confirmOrderPayment, cancelOrder, deserializer);
    }

    @Bean
    InventoryEventHandler inventoryEventHandler(
            ReserveOrderInventoryUseCase reserveOrderInventory,
            CancelOrderUseCase cancelOrder,
            InboundEventDeserializer deserializer
    ) {
        return new InventoryEventHandler(reserveOrderInventory, cancelOrder, deserializer);
    }

    @Bean
    Consumer<Message<byte[]>> paymentEvents(PaymentEventHandler handler) {
        return message -> handler.handle(eventType(message), message.getPayload()).block();
    }

    @Bean
    Consumer<Message<byte[]>> inventoryEvents(InventoryEventHandler handler) {
        return message -> handler.handle(eventType(message), message.getPayload()).block();
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
