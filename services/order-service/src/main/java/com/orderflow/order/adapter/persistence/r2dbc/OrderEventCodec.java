package com.orderflow.order.adapter.persistence.r2dbc;

import com.orderflow.order.domain.event.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Tradução entre {@link OrderEvent} e o payload JSON gravado em
 * {@code events.payload}. O {@code event_type} salvo na coluna é a
 * chave do registry — não é o nome qualificado da classe, para que
 * refactorings de pacote não quebrem leituras históricas. Mudanças
 * quebrantes de schema produzem um novo tipo (ex: {@code OrderPlacedV2})
 * registrado aqui sem remover o antigo.
 */
public final class OrderEventCodec {

    private final ObjectMapper mapper;
    private final Map<String, Class<? extends OrderEvent>> registry;
    private final Map<Class<? extends OrderEvent>, String> reverseRegistry;

    public OrderEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.registry = Map.of(
                "OrderPlaced", OrderPlaced.class,
                "OrderPaymentConfirmed", OrderPaymentConfirmed.class,
                "OrderInventoryReserved", OrderInventoryReserved.class,
                "OrderConfirmed", OrderConfirmed.class,
                "OrderShipped", OrderShipped.class,
                "OrderDelivered", OrderDelivered.class,
                "OrderCancelled", OrderCancelled.class
        );
        this.reverseRegistry = Map.of(
                OrderPlaced.class, "OrderPlaced",
                OrderPaymentConfirmed.class, "OrderPaymentConfirmed",
                OrderInventoryReserved.class, "OrderInventoryReserved",
                OrderConfirmed.class, "OrderConfirmed",
                OrderShipped.class, "OrderShipped",
                OrderDelivered.class, "OrderDelivered",
                OrderCancelled.class, "OrderCancelled"
        );
    }

    public static OrderEventCodec withDefaultObjectMapper() {
        return new OrderEventCodec(defaultObjectMapper());
    }

    static ObjectMapper defaultObjectMapper() {
        // Jackson 3 já registra automaticamente o módulo java.time via ServiceLoader
        // e usa ISO-8601 como default para Instant/OffsetDateTime — sem necessidade
        // de configurar WRITE_DATES_AS_TIMESTAMPS explicitamente.
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String eventTypeOf(OrderEvent event) {
        String type = reverseRegistry.get(event.getClass());
        if (type == null) {
            throw new UnknownEventTypeException(event.getClass().getName());
        }
        return type;
    }

    public String serialize(OrderEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JacksonException e) {
            throw new EventSerializationException(
                    "Failed to serialize OrderEvent " + event.eventId(), e);
        }
    }

    public OrderEvent deserialize(String eventType, String json) {
        Class<? extends OrderEvent> klass = registry.get(eventType);
        if (klass == null) {
            throw new UnknownEventTypeException(eventType);
        }
        try {
            return mapper.readValue(json, klass);
        } catch (JacksonException e) {
            throw new EventSerializationException(
                    "Failed to deserialize OrderEvent of type " + eventType, e);
        }
    }
}
