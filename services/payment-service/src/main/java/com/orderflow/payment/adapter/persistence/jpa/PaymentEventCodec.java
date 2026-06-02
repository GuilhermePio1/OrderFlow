package com.orderflow.payment.adapter.persistence.jpa;

import com.orderflow.payment.domain.event.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tradução de {@link PaymentEvent} para o payload JSON gravado em
 * {@code outbox.payload} e, daí, publicado no Kafka via Debezium.
 *
 * É uma Anti-Corruption Layer de saída: os value objects do domínio
 * (PaymentId, OrderId, Money, ...) são achatados para escalares no contrato
 * externo (UUIDs crus, montante + moeda), de modo que os consumidores a
 * jusante (ex.: Order Service) leiam campos planos sem conhecer o modelo
 * interno deste contexto. O {@code event_type} é uma chave estável de
 * registro — não o nome qualificado da classe — para que refactorings de
 * pacote não quebrem o roteamento do Debezium nem os consumidores.
 */
public final class PaymentEventCodec {

    private final ObjectMapper mapper;

    public PaymentEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static PaymentEventCodec withDefaultObjectMapper() {
        return new PaymentEventCodec(defaultObjectMapper());
    }

    static ObjectMapper defaultObjectMapper() {
        // Jackson 3 registra o módulo java.time via ServiceLoader e usa ISO-8601
        // para Instant por padrão — formato esperado pelos consumidores.
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public String eventTypeOf(PaymentEvent event) {
        return switch (event) {
            case PaymentAuthorized ignored -> "PaymentAuthorized";
            case PaymentFailed ignored -> "PaymentFailed";
            case PaymentCaptured ignored -> "PaymentCaptured";
            case PaymentRefunded ignored -> "PaymentRefunded";
            case PaymentVoided ignored -> "PaymentVoided";
        };
    }

    public String serialize(PaymentEvent event) {
        try {
            return mapper.writeValueAsString(toPayload(event));
        } catch (JacksonException e) {
            throw new EventSerializationException(
                    "Failed to serialize PaymentEvent " + event.eventId(), e);
        }
    }

    private Object toPayload(PaymentEvent event) {
        return switch (event) {
            case PaymentAuthorized e -> new AuthorizedPayload(
                    e.eventId(),
                    e.paymentId().value(),
                    e.orderId().value(),
                    e.customerId().value(),
                    e.amount().amount(),
                    e.amount().currency().getCurrencyCode(),
                    e.method().name(),
                    e.gatewayTransactionId().value(),
                    e.authorizationCode().value(),
                    e.occurredAt(),
                    e.schemaVersion());
            case PaymentFailed e -> new FailedPayload(
                    e.eventId(),
                    e.paymentId().value(),
                    e.orderId().value(),
                    e.customerId().value(),
                    e.amount().amount(),
                    e.amount().currency().getCurrencyCode(),
                    e.reason().name(),
                    e.details(),
                    e.occurredAt(),
                    e.schemaVersion());
            case PaymentCaptured e -> new CapturedPayload(
                    e.eventId(),
                    e.paymentId().value(),
                    e.orderId().value(),
                    e.amount().amount(),
                    e.amount().currency().getCurrencyCode(),
                    e.gatewayTransactionId().value(),
                    e.occurredAt(),
                    e.schemaVersion());
            case PaymentRefunded e -> new RefundedPayload(
                    e.eventId(),
                    e.paymentId().value(),
                    e.orderId().value(),
                    e.refundedAmount().amount(),
                    e.totalRefundedAmount().amount(),
                    e.refundedAmount().currency().getCurrencyCode(),
                    e.fullRefund(),
                    e.reason(),
                    e.occurredAt(),
                    e.schemaVersion());
            case PaymentVoided e -> new VoidedPayload(
                    e.eventId(),
                    e.paymentId().value(),
                    e.orderId().value(),
                    e.reason(),
                    e.occurredAt(),
                    e.schemaVersion());
        };
    }

    private record AuthorizedPayload(
            UUID eventId,
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            String method,
            String gatewayTransactionId,
            String authorizationCode,
            Instant occurredAt,
            int schemaVersion
    ) {
    }

    private record FailedPayload(
            UUID eventId,
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String currency,
            String reason,
            String details,
            Instant occurredAt,
            int schemaVersion
    ) {
    }

    private record CapturedPayload(
            UUID eventId,
            UUID paymentId,
            UUID orderId,
            BigDecimal amount,
            String currency,
            String gatewayTransactionId,
            Instant occurredAt,
            int schemaVersion
    ) {
    }

    private record RefundedPayload(
            UUID eventId,
            UUID paymentId,
            UUID orderId,
            BigDecimal refundedAmount,
            BigDecimal totalRefundedAmount,
            String currency,
            boolean fullRefund,
            String reason,
            Instant occurredAt,
            int schemaVersion
    ) {
    }

    private record VoidedPayload(
            UUID eventId,
            UUID paymentId,
            UUID orderId,
            String reason,
            Instant occurredAt,
            int schemaVersion
    ) {
    }
}
