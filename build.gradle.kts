// Plugin versions must be literals; keep springBootVersion in sync with gradle.properties.
plugins {
    id("org.springframework.boot") version "4.0.7" apply false
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1" apply false
}

val springBootVersion: String by project

allprojects {
    group = "com.orderflow"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // ':common' is only a folder grouping the shared library modules
    if (childProjects.isNotEmpty()) return@subprojects

    pluginManager.apply("java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

val services = listOf(
    "api-gateway",
    "order-service",
    "payment-service",
    "inventory-service",
    "shipping-service",
    "notification-service",
)

tasks.register("bootAllRun") {
    group = "application"
    description = "Starts all OrderFlow services with the local profile (relies on org.gradle.parallel=true)."
    dependsOn(services.map { ":$it:bootRun" })
}