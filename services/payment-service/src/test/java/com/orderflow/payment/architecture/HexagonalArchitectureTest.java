package com.orderflow.payment.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Garante a estrutura hexagonal descrita em docs/ddd.md e docs/architecture.md:
 * domain no centro, application orquestrando casos de uso, adapter na borda.
 * No contexto Payment as três camadas já existem em código (domain, application
 * e adapter — REST, JPA, Kafka, gateway); a regra impede que a dependência
 * inverta de direção conforme o serviço evolui.
 */
@AnalyzeClasses(
        packages = "com.orderflow.payment",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule layers_respect_hexagonal_dependencies =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .layer("Domain").definedBy("com.orderflow.payment.domain..")
                    .layer("Application").definedBy("com.orderflow.payment.application..")
                    .layer("Adapter").definedBy("com.orderflow.payment.adapter..")

                    .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter")
                    .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter");
}
