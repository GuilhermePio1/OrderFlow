package com.orderflow.order.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Garante a estrutura hexagonal descrita em docs/ddd.md e docs/architecture.md:
 * domain no centro, application orquestrando casos de uso, adapter na borda.
 * As camadas application e adapter ainda podem não existir em código —
 * a regra continua válida (layers vazios são permitidos) e protege a
 * estrutura conforme novas camadas são introduzidas.
 */
@AnalyzeClasses(
        packages = "com.orderflow.order",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule layers_respect_hexagonal_dependencies =
            layeredArchitecture()
                    .consideringAllDependencies()
                    .layer("Domain").definedBy("com.orderflow.order.domain..")
                    .optionalLayer("Application").definedBy("com.orderflow.order.application..")
                    .optionalLayer("Adapter").definedBy("com.orderflow.order.adapter..")

                    .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter")
                    .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter");
}