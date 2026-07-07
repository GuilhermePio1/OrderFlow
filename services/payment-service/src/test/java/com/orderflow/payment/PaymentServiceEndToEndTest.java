package com.orderflow.payment;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.orderflow.payment.adapter.rest.dto.PaymentResponse;
import com.orderflow.payment.domain.model.Payment;
import com.orderflow.payment.domain.model.PaymentStatus;
import com.orderflow.payment.domain.model.valueobject.Money;
import com.orderflow.payment.domain.model.valueobject.OrderId;
import com.orderflow.payment.domain.repository.PaymentRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integração end-to-end do payment-service com infraestrutura real
 * (docs/testing.md, "Testes de Integração": "não há mock de banco, mock de
 * Kafka" — Testcontainers sobe PostgreSQL e Kafka reais). O contexto Spring
 * completo é iniciado — binder Kafka, Flyway, JPA, cadeia de segurança,
 * casos de uso — e o serviço é exercitado pelas suas duas portas de entrada:
 *
 * <ul>
 *   <li><b>Mensageria</b>: eventos {@code OrderPlaced}/{@code OrderConfirmed}/
 *       {@code OrderCancelled} publicados no tópico {@code orders.events} com o
 *       header {@code event_type}, exatamente como o Debezium os entrega
 *       (a participação do Payment na saga coreografada de
 *       {@code docs/architecture.md});</li>
 *   <li><b>HTTP</b>: a API administrativa, atravessando a cadeia de segurança
 *       real (JWT assinado + RBAC via realm roles) — apenas a chave de
 *       assinatura é local ao teste, no lugar do Keycloak.</li>
 * </ul>
 *
 * As asserções observam os efeitos persistidos: o estado do agregado no
 * PostgreSQL e as linhas da tabela {@code outbox} que o Debezium publicaria
 * (padrão Outbox, {@code docs/event-sourcing.md}). O gateway de pagamento é o
 * {@code FakePaymentGateway} (a fiação padrão para ambientes sem rede externa);
 * os desfechos de declínio/indisponibilidade do provedor são cobertos por
 * testes de camadas mais baixas (use cases e WireMock), seguindo a pirâmide.
 *
 * <p>Cada teste usa um pedido próprio (chave da partição), o que isola os
 * cenários entre si e garante ordem de processamento por pedido — a mesma
 * garantia de produção ({@code docs/architecture.md}, "Tópicos Kafka são
 * particionados pela orderId").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Payment Service — integração end-to-end (Testcontainers)")
class PaymentServiceEndToEndTest {

    private static final String ORDERS_TOPIC = "orders.events";
    private static final String EVENT_TYPE_HEADER = "event_type";
    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(90);

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("orderflow_payments")
            .withUsername("orderflow")
            .withPassword("orderflow");

    // Mesma imagem do docker-compose de infraestrutura, para que o teste
    // valide contra o broker que roda nos demais ambientes.
    @SuppressWarnings("resource")
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    }

    /**
     * Substitui apenas a verificação criptográfica do Keycloak por uma chave
     * simétrica local: o restante da cadeia — decodificação, expiração,
     * conversão de realm roles ({@code KeycloakRealmRoleConverter}) e as regras
     * de autorização de {@code SecurityConfiguration} — permanece o de produção.
     */
    @TestConfiguration
    static class EndToEndJwtConfiguration {

        static final SecretKey JWT_KEY = new SecretKeySpec(
                "orderflow-e2e-jwt-signing-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");

        @Bean
        @Primary
        JwtDecoder endToEndJwtDecoder() {
            return NimbusJwtDecoder.withSecretKey(JWT_KEY).build();
        }
    }

    private static KafkaProducer<String, byte[]> producer;

    @BeforeAll
    static void startProducer() {
        producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()),
                new StringSerializer(),
                new ByteArraySerializer());
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int serverPort;

    // ---------- mensageria: a saga coreografada ----------

    @Test
    @DisplayName("OrderPlaced autoriza o pagamento e grava PaymentAuthorized na outbox")
    void orderPlacedAuthorizesPaymentAndStagesEventInOutbox() {
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(orderId, UUID.randomUUID(), "149.90", "BRL");

        Payment payment = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.AUTHORIZED);

        assertThat(payment.authorizedAmount().isEqualValue(Money.of("149.90", "BRL"))).isTrue();
        assertThat(payment.gatewayTransactionId()).isNotNull();
        assertThat(payment.authorizationCode()).isNotNull();
        assertThat(payment.version()).isEqualTo(1L);
        assertThat(outboxEventTypes(payment)).containsExactly("PaymentAuthorized");
        assertThat(outboxAggregateTypes(payment)).containsOnly("Payment");
    }

    @Test
    @DisplayName("OrderConfirmed captura o pagamento autorizado (lado feliz da saga)")
    void orderConfirmedCapturesTheAuthorizedPayment() {
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(orderId, UUID.randomUUID(), "320.00", "BRL");
        awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.AUTHORIZED);

        publishOrderConfirmed(orderId);

        Payment payment = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.CAPTURED);
        assertThat(payment.capturedAmount().isEqualValue(payment.authorizedAmount())).isTrue();
        assertThat(payment.version()).isEqualTo(2L);
        assertThat(outboxEventTypes(payment))
                .containsExactly("PaymentAuthorized", "PaymentCaptured");
    }

    @Test
    @DisplayName("OrderCancelled antes da captura cancela a autorização (PaymentVoided)")
    void orderCancelledBeforeCaptureVoidsTheAuthorization() {
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(orderId, UUID.randomUUID(), "88.00", "BRL");
        awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.AUTHORIZED);

        publishOrderCancelled(orderId, "INVENTORY_OUT_OF_STOCK", "SKU-42 esgotado");

        Payment payment = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.VOIDED);
        assertThat(payment.capturedAmount().isEqualValue(Money.of("0.00", "BRL"))).isTrue();
        assertThat(outboxEventTypes(payment))
                .containsExactly("PaymentAuthorized", "PaymentVoided");
    }

    @Test
    @DisplayName("OrderCancelled após a captura estorna o valor integral (PaymentRefunded)")
    void orderCancelledAfterCaptureRefundsThePayment() {
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(orderId, UUID.randomUUID(), "250.00", "BRL");
        awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.AUTHORIZED);
        publishOrderConfirmed(orderId);
        awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.CAPTURED);

        publishOrderCancelled(orderId, "CUSTOMER_REQUEST", "Devolução dentro do prazo");

        Payment payment = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.REFUNDED);
        assertThat(payment.refundedAmount().isEqualValue(payment.capturedAmount())).isTrue();
        assertThat(outboxEventTypes(payment))
                .containsExactly("PaymentAuthorized", "PaymentCaptured", "PaymentRefunded");
    }

    @Test
    @DisplayName("reentrega de OrderPlaced não duplica pagamento nem eventos (at least once)")
    void redeliveredOrderPlacedDoesNotDuplicatePaymentOrEvents() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        publishOrderPlaced(orderId, customerId, "75.50", "BRL");
        publishOrderPlaced(orderId, customerId, "75.50", "BRL");
        // Barreira: mensagens do mesmo pedido caem na mesma partição e são
        // processadas em ordem — quando a captura conclui, as duas entregas
        // de OrderPlaced já foram consumidas.
        publishOrderConfirmed(orderId);

        Payment payment = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.CAPTURED);

        Integer paymentRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE order_id = ?", Integer.class, orderId);
        assertThat(paymentRows).isEqualTo(1);
        assertThat(outboxEventTypes(payment))
                .containsExactly("PaymentAuthorized", "PaymentCaptured");
    }

    // ---------- HTTP: API administrativa com a cadeia de segurança real ----------

    @Test
    @DisplayName("back-office consulta e estorna parcialmente via HTTP com JWT de PAYMENT_ADMIN")
    void adminCanInspectAndRefundOverHttpWithRealSecurityChain() throws JOSEException {
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(orderId, UUID.randomUUID(), "200.00", "BRL");
        awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.AUTHORIZED);
        publishOrderConfirmed(orderId);
        Payment captured = awaitPaymentInStatus(OrderId.of(orderId), PaymentStatus.CAPTURED);

        String adminToken = signedToken("back-office-admin", "payment-admin");

        PaymentResponse viewed = restClient()
                .get()
                .uri("/api/payments/by-order/{orderId}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(PaymentResponse.class);
        assertThat(viewed.paymentId()).isEqualTo(captured.id().value());
        assertThat(viewed.status()).isEqualTo("CAPTURED");
        assertThat(viewed.capturedAmount().amount()).isEqualByComparingTo("200.00");

        PaymentResponse refunded = restClient()
                .post()
                .uri("/api/payments/{paymentId}/refund", captured.id().value())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "amount": 50.00,
                          "currency": "BRL",
                          "reason": "Disputa resolvida a favor do cliente"
                        }
                        """)
                .retrieve()
                .body(PaymentResponse.class);
        assertThat(refunded.status()).isEqualTo("CAPTURED"); // estorno parcial não é terminal
        assertThat(refunded.refundedAmount().amount()).isEqualByComparingTo("50.00");

        Payment reloaded = paymentRepository.findByOrderId(OrderId.of(orderId)).orElseThrow();
        assertThat(reloaded.refundedAmount().isEqualValue(Money.of("50.00", "BRL"))).isTrue();
        assertThat(outboxEventTypes(reloaded))
                .containsExactly("PaymentAuthorized", "PaymentCaptured", "PaymentRefunded");
    }

    @Test
    @DisplayName("API administrativa recusa chamadas anônimas (401) e sem papel PAYMENT_ADMIN (403)")
    void httpApiRejectsAnonymousAndNonAdminCallers() throws JOSEException {
        String uri = "/api/payments/by-order/" + UUID.randomUUID();

        HttpStatusCode anonymous = restClient()
                .get().uri(uri)
                .exchange((request, response) -> response.getStatusCode());
        assertThat(anonymous).isEqualTo(HttpStatus.UNAUTHORIZED);

        String customerToken = signedToken("customer-1", "customer");
        HttpStatusCode wrongRole = restClient()
                .get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                .exchange((request, response) -> response.getStatusCode());
        assertThat(wrongRole).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------- helpers: publicação de eventos do Ordering ----------

    /**
     * Payload no contrato do Ordering (event-sourced, value objects aninhados)
     * e header {@code event_type} — o formato que o EventRouter do Debezium
     * propaga a partir da outbox do order-service.
     */
    private void publishOrderPlaced(UUID orderId, UUID customerId, String amount, String currency) {
        publish("OrderPlaced", orderId, """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "customerId": {"value": "%s"},
                  "totalAmount": {"amount": %s, "currency": "%s"},
                  "occurredAt": "%s",
                  "schemaVersion": 1
                }
                """.formatted(UUID.randomUUID(), orderId, customerId, amount, currency, Instant.now()));
    }

    private void publishOrderConfirmed(UUID orderId) {
        publish("OrderConfirmed", orderId, """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "occurredAt": "%s",
                  "schemaVersion": 1
                }
                """.formatted(UUID.randomUUID(), orderId, Instant.now()));
    }

    private void publishOrderCancelled(UUID orderId, String reason, String details) {
        publish("OrderCancelled", orderId, """
                {
                  "eventId": "%s",
                  "orderId": {"value": "%s"},
                  "reason": "%s",
                  "details": "%s",
                  "occurredAt": "%s",
                  "schemaVersion": 1
                }
                """.formatted(UUID.randomUUID(), orderId, reason, details, Instant.now()));
    }

    private void publish(String eventType, UUID orderId, String json) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                ORDERS_TOPIC, orderId.toString(), json.getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido ao publicar " + eventType, e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Falha ao publicar " + eventType, e);
        }
    }

    // ---------- helpers: observação dos efeitos persistidos ----------

    private Payment awaitPaymentInStatus(OrderId orderId, PaymentStatus expected) {
        return await()
                .atMost(SAGA_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(
                        () -> paymentRepository.findByOrderId(orderId)
                                .filter(payment -> payment.status() == expected),
                        Optional::isPresent)
                .orElseThrow();
    }

    private List<String> outboxEventTypes(Payment payment) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM outbox WHERE aggregate_id = ? ORDER BY created_at",
                String.class, payment.id().value());
    }

    private List<String> outboxAggregateTypes(Payment payment) {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_type FROM outbox WHERE aggregate_id = ?",
                String.class, payment.id().value());
    }

    // ---------- helpers: HTTP e tokens ----------

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + serverPort)
                .build();
    }

    /**
     * Assina um JWT com a chave do teste, no formato emitido pelo Keycloak:
     * realm roles em {@code realm_access.roles} (ex.: {@code payment-admin},
     * que o {@code KeycloakRealmRoleConverter} normaliza para
     * {@code ROLE_PAYMENT_ADMIN}).
     */
    private static String signedToken(String subject, String... realmRoles) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://localhost:8080/realms/orderflow")
                .audience("payment-service")
                .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(EndToEndJwtConfiguration.JWT_KEY));
        return jwt.serialize();
    }
}
