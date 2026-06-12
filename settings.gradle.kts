pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Confluent artifacts (kafka-avro-serializer, schema registry client)
        maven("https://packages.confluent.io/maven/")
    }
}

rootProject.name = "orderflow"

include(
    "api-gateway",
    "order-service",
    "payment-service",
    "inventory-service",
    "shipping-service",
    "notification-service",
    "common:events",
    "common:messaging",
)