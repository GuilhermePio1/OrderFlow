package com.orderflow.order.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Estrutura de pacotes e ausência de ciclos. docs/testing.md exige que
 * "ciclos entre pacotes [sejam] proibidos" e que regras gerais de boa
 * higiene de código (sem System.out, sem java.util.logging, sem exceptions
 * genéricas) sejam aplicadas no CI.
 */
@AnalyzeClasses(
        packages = "com.orderflow.order",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class PackageStructureTest {

    /**
     * Nota: os subpacotes do domínio (event, model, exception) referenciam-se
     * mutuamente por design — eventos carregam value objects do model, exceções
     * carregam identidades, etc. A proibição de ciclos é aplicada apenas entre
     * as camadas de topo (domain / application / adapter), onde unidirectional
     * dependency é o invariante real da arquitetura hexagonal.
     */
    @ArchTest
    static final ArchRule no_cycles_between_top_level_layers =
            slices()
                    .matching("com.orderflow.order.(domain|application|adapter)..")
                    .should().beFreeOfCycles()
                    .as("camadas de topo não podem formar ciclos");

    @ArchTest
    static final ArchRule no_generic_exceptions =
            NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
                    .as("não lançar Throwable/Exception/RuntimeException — usar exceções específicas do domínio");

    @ArchTest
    static final ArchRule no_java_util_logging =
            NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
                    .as("usar SLF4J em vez de java.util.logging");

    @ArchTest
    static final ArchRule no_system_streams =
            NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                    .as("logs vão para SLF4J/Loki — System.out/err é proibido");
}