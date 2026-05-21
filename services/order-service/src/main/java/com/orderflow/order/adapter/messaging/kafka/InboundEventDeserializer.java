package com.orderflow.order.adapter.messaging.kafka;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Desserializa o payload JSON dos eventos de entrada (Payment, Inventory)
 * para os DTOs de borda. Tolera propriedades desconhecidas para honrar a
 * compatibilidade aditiva de schema descrita em {@code docs/event-sourcing.md}:
 * campos novos adicionados pelos contextos upstream não quebram este consumer.
 */
public final class InboundEventDeserializer {

    private final ObjectMapper mapper;

    public InboundEventDeserializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static InboundEventDeserializer withDefaultObjectMapper() {
        return new InboundEventDeserializer(defaultObjectMapper());
    }

    static ObjectMapper defaultObjectMapper() {
        // Jackson 3 registra o módulo java.time via ServiceLoader e usa ISO-8601
        // para Instant por padrão — alinhado ao formato emitido pelos produtores.
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public <T> T deserialize(byte[] payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        } catch (JacksonException e) {
            throw new InboundEventDeserializationException(
                    "Failed to deserialize inbound event of type " + type.getName(), e);
        }
    }
}
