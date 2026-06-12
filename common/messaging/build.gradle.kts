// Shared messaging infrastructure: transactional outbox (ADR-0003),
// idempotent consumer support (ADR-0004), tracing propagation, retry/DLT handling.
// No business logic lives here (docs/ARCHITECTURE.md §1).
plugins {
    `java-library`
}

val confluentVersion: String by project

dependencies {
    api(project(":common:events"))

    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.confluent:kafka-avro-serializer:$confluentVersion")
}