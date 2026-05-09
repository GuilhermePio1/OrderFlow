package com.orderflow.order.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * docs/ddd.md (Hexagonal Architecture): "no centro vive a lógica de domínio
 * pura, sem dependências de framework". docs/testing.md (Testes de Arquitetura):
 * "a camada de domínio não pode importar de Spring nem de bibliotecas de
 * infraestrutura".
 *
 * Reactor é tolerado pois é a abstração de fluxo usada pelo port
 * {@code OrderRepository} (assinaturas que retornam {@code Mono}); ele
 * não é "Spring" nem "infraestrutura" no sentido de I/O concreto.
 */
@AnalyzeClasses(
        packages = "com.orderflow.order",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class DomainPurityTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring =
            noClasses()
                    .that().resideInAPackage("com.orderflow.order.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "org.springframework.boot.."
                    )
                    .as("a camada de domínio não pode depender de Spring");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_persistence_frameworks =
            noClasses()
                    .that().resideInAPackage("com.orderflow.order.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "io.r2dbc..",
                            "org.flywaydb..",
                            "org.hibernate.."
                    )
                    .as("a camada de domínio não pode depender de bibliotecas de persistência");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_messaging_or_web =
            noClasses()
                    .that().resideInAPackage("com.orderflow.order.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.apache.kafka..",
                            "jakarta.servlet..",
                            "jakarta.ws..",
                            "io.netty.."
                    )
                    .as("a camada de domínio não pode depender de Kafka, servlet, JAX-RS ou Netty");

    @ArchTest
    static final ArchRule domain_classes_are_not_annotated_with_spring_stereotypes =
            noClasses()
                    .that().resideInAPackage("com.orderflow.order.domain..")
                    .should().beAnnotatedWith("org.springframework.stereotype.Component")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Controller")
                    .orShould().beAnnotatedWith("org.springframework.context.annotation.Configuration")
                    .as("classes de domínio não podem ser anotadas com stereotypes do Spring");

    @ArchTest
    static final ArchRule application_layer_only_depends_on_domain_or_application =
            classes()
                    .that().resideInAPackage("com.orderflow.order.application..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "com.orderflow.order.application..",
                            "com.orderflow.order.domain..",
                            "java..",
                            "javax..",
                            "jakarta.validation..",
                            "reactor..",
                            "org.slf4j.."
                    )
                    .as("application só depende de domain (e utilitários neutros), não de adapters específicos")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rest_controllers_only_in_adapter_layer =
            classes()
                    .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .or().areAnnotatedWith("org.springframework.stereotype.Controller")
                    .should().resideInAPackage("com.orderflow.order.adapter..")
                    .as("@RestController/@Controller só podem residir na camada de adapter")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_event_implementations_live_in_domain_event_package =
            classes()
                    .that().implement("com.orderflow.order.domain.event.OrderEvent")
                    .should().resideInAPackage("com.orderflow.order.domain.event..")
                    .as("implementações de OrderEvent vivem em domain.event");

    @ArchTest
    static final ArchRule domain_repositories_are_interfaces =
            classes()
                    .that().resideInAPackage("com.orderflow.order.domain.repository..")
                    .should(beInterfaces())
                    .as("ports de repositório no domínio devem ser interfaces");

    private static ArchCondition<JavaClass> beInterfaces() {
        return new ArchCondition<>("be interfaces") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isInterface()) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            item.getName() + " is not an interface"
                    ));
                }
            }
        };
    }
}