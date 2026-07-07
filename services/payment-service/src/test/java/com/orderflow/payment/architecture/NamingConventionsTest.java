package com.orderflow.payment.architecture;

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
 *  - comandos da application terminam em Command;
 *  - eventos pertencem ao agregado e implementam {@code PaymentEvent} (nomeados
 *    no passado: PaymentAuthorized, PaymentCaptured, PaymentRefunded, ...);
 *  - exceções de domínio terminam em Exception e estendem DomainException;
 *  - value objects vivem em domain.model.valueobject.
 */
@AnalyzeClasses(
        packages = "com.orderflow.payment",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class NamingConventionsTest {

    @ArchTest
    static final ArchRule repositories_are_named_with_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.domain.repository..")
                    .should().haveSimpleNameEndingWith("Repository")
                    .as("repositórios devem terminar em 'Repository'");

    @ArchTest
    static final ArchRule classes_named_repository_live_in_repository_package =
            classes()
                    .that().haveSimpleNameEndingWith("Repository")
                    .should().resideInAnyPackage(
                            "com.orderflow.payment.domain.repository..",
                            "com.orderflow.payment.adapter..");

    @ArchTest
    static final ArchRule use_cases_end_with_use_case_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.application.usecase..")
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("UseCase")
                    .as("use cases devem terminar em 'UseCase'");

    @ArchTest
    static final ArchRule classes_named_use_case_live_in_application =
            classes()
                    .that().haveSimpleNameEndingWith("UseCase")
                    .should().resideInAPackage("com.orderflow.payment.application..");

    @ArchTest
    static final ArchRule commands_end_with_command_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.application.command..")
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameEndingWith("Command")
                    .as("comandos da application devem terminar em 'Command'");

    @ArchTest
    static final ArchRule domain_event_classes_live_in_event_package =
            classes()
                    .that().implement("com.orderflow.payment.domain.event.PaymentEvent")
                    .and().areNotInterfaces()
                    .should().resideInAPackage("com.orderflow.payment.domain.event..");

    @ArchTest
    static final ArchRule domain_exceptions_end_with_exception_suffix =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.domain.exception..")
                    .should().haveSimpleNameEndingWith("Exception")
                    .as("exceções do domínio devem terminar em 'Exception'");

    @ArchTest
    static final ArchRule domain_exceptions_extend_domain_exception =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.domain.exception..")
                    .and().haveSimpleNameNotEndingWith("DomainException")
                    .should().beAssignableTo("com.orderflow.payment.domain.exception.DomainException")
                    .as("exceções concretas do domínio devem estender DomainException");

    @ArchTest
    static final ArchRule value_objects_live_in_value_object_package =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.domain.model.valueobject..")
                    .should().bePublic()
                    .as("value objects são públicos para serem reutilizados pelos consumidores do domínio");

    @ArchTest
    static final ArchRule no_field_injection_with_autowired =
            fields()
                    .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .as("injeção por campo (@Autowired) é proibida — preferir injeção por construtor");
}
