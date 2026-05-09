package com.orderflow.order.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/**
 * Convenções de nomenclatura derivadas de docs/ddd.md e docs/testing.md:
 *  - "UseCases terminam em UseCase";
 *  - "repositórios em Repository";
 *  - eventos pertencem ao agregado e implementam {@code OrderEvent} (nomeados
 *    no passado: OrderPlaced, OrderPaymentConfirmed, ...);
 *  - exceções de domínio terminam em Exception e estendem DomainException;
 *  - value objects vivem em domain.model.valueobject.
 */
@AnalyzeClasses(
        packages = "com.orderflow.order",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class NamingConventionsTest {

    @ArchTest
    static final ArchRule repositories_are_named_with_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.order.domain.repository..")
                    .should().haveSimpleNameEndingWith("Repository")
                    .as("repositórios devem terminar em 'Repository'");

    @ArchTest
    static final ArchRule classes_named_repository_live_in_repository_package =
            classes()
                    .that().haveSimpleNameEndingWith("Repository")
                    .should().resideInAnyPackage(
                            "com.orderflow.order.domain.repository..",
                            "com.orderflow.order.adapter..");

    @ArchTest
    static final ArchRule use_cases_end_with_use_case_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.order.application.usecase..")
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("UseCase")
                    .as("use cases devem terminar em 'UseCase'")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule classes_named_use_case_live_in_application =
            classes()
                    .that().haveSimpleNameEndingWith("UseCase")
                    .should().resideInAPackage("com.orderflow.order.application..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_event_classes_live_in_event_package =
            classes()
                    .that().implement("com.orderflow.order.domain.event.OrderEvent")
                    .and().areNotInterfaces()
                    .should().resideInAPackage("com.orderflow.order.domain.event..");

    @ArchTest
    static final ArchRule domain_exceptions_end_with_exception_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.order.domain.exception..")
                    .should().haveSimpleNameEndingWith("Exception")
                    .as("exceções do domínio devem terminar em 'Exception'");

    @ArchTest
    static final ArchRule domain_exceptions_extend_domain_exception =
            classes()
                    .that().resideInAPackage("com.orderflow.order.domain.exception..")
                    .and().haveSimpleNameNotEndingWith("DomainException")
                    .should().beAssignableTo("com.orderflow.order.domain.exception.DomainException")
                    .as("exceções concretas do domínio devem estender DomainException");

    @ArchTest
    static final ArchRule value_objects_live_in_value_object_package =
            classes()
                    .that().resideInAPackage("com.orderflow.order.domain.model.valueobject..")
                    .should().bePublic()
                    .as("value objects são públicos para serem reutilizados pelos consumidores do domínio");

    @ArchTest
    static final ArchRule no_field_injection_with_autowired =
            fields()
                    .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .as("injeção por campo (@Autowired) é proibida — preferir injeção por construtor");
}