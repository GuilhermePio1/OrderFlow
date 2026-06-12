// Single source of truth for event/command schemas (docs/EVENT_CATALOG.md).
// Avro .avsc files live in src/main/avro; Java classes are generated at build time.
plugins {
    `java-library`
    id("com.github.davidmc24.gradle.plugin.avro")
}

val avroVersion: String by project

dependencies {
    api("org.apache.avro:avro:$avroVersion")
}