package com.orderflow.payment.architecture;

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
 * O contexto Payment é imperativo por decisão registrada (ADR-006, Spring MVC +
 * virtual threads — docs/architecture.md), então nem Reactor é tolerado no
 * domínio: as assinaturas dos ports são bloqueantes. Serialização também fica
 * fora — o codec Jackson dos eventos vive no adapter de persistência
 * ({@code PaymentEventCodec}), não no domínio.
 */
@AnalyzeClasses(
        packages = "com.orderflow.payment",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
public class DomainPurityTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring =
            noClasses()
                    .that().resideInAPackage("com.orderflow.payment.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "org.springframework.boot.."
                    )
                    .as("a camada de domínio não pode depender de Spring");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_persistence_frameworks =
            noClasses()
                    .that().resideInAPackage("com.orderflow.payment.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "jakarta.persistence..",
                            "javax.persistence..",
                            "java.sql..",
                            "javax.sql..",
                            "org.flywaydb..",
                            "org.hibernate.."
                    )
                    .as("a camada de domínio não pode depender de bibliotecas de persistência");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_messaging_web_or_reactive =
            noClasses()
                    .that().resideInAPackage("com.orderflow.payment.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.apache.kafka..",
                            "jakarta.servlet..",
                            "jakarta.ws..",
                            "io.netty..",
                            "reactor.."
                    )
                    .as("a camada de domínio não pode depender de Kafka, servlet, JAX-RS, Netty "
                            + "nem Reactor (o contexto Payment é imperativo — ADR-006)");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_serialization_libraries =
            noClasses()
                    .that().resideInAPackage("com.orderflow.payment.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.fasterxml.jackson..",
                            "tools.jackson.."
                    )
                    .as("serialização de eventos é preocupação do adapter (PaymentEventCodec), "
                            + "não do domínio");

    @ArchTest
    static final ArchRule domain_classes_are_not_annotated_with_spring_stereotypes =
            noClasses()
                    .that().resideInAPackage("com.orderflow.payment.domain..")
                    .should().beAnnotatedWith("org.springframework.stereotype.Component")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Repository")
                    .orShould().beAnnotatedWith("org.springframework.stereotype.Controller")
                    .orShould().beAnnotatedWith("org.springframework.context.annotation.Configuration")
                    .as("classes de domínio não podem ser anotadas com stereotypes do Spring");

    /**
     * Os casos de uso são POJOs fiados na borda por {@code PaymentUseCaseConfiguration}
     * (vide docs/ddd.md): a camada application enxerga apenas domain, seus próprios
     * ports/commands e utilitários neutros (JDK, SLF4J).
     */
    @ArchTest
    static final ArchRule application_layer_only_depends_on_domain_or_application =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.application..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage(
                            "com.orderflow.payment.application..",
                            "com.orderflow.payment.domain..",
                            "java..",
                            "org.slf4j.."
                    )
                    .as("application só depende de domain (e utilitários neutros), não de adapters específicos");

    @ArchTest
    static final ArchRule rest_controllers_only_in_adapter_layer =
            classes()
                    .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .or().areAnnotatedWith("org.springframework.stereotype.Controller")
                    .should().resideInAPackage("com.orderflow.payment.adapter..")
                    .as("@RestController/@Controller só podem residir na camada de adapter")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_event_implementations_live_in_domain_event_package =
            classes()
                    .that().implement("com.orderflow.payment.domain.event.PaymentEvent")
                    .should().resideInAPackage("com.orderflow.payment.domain.event..")
                    .as("implementações de PaymentEvent vivem em domain.event");

    @ArchTest
    static final ArchRule domain_repositories_are_interfaces =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.domain.repository..")
                    .should(beInterfaces())
                    .as("ports de repositório no domínio devem ser interfaces");

    /**
     * Os ports de saída da application ({@code PaymentGateway}) são o contrato da
     * Anti-Corruption Layer com o provedor externo: interfaces implementadas pelos
     * adapters (Stripe, fake), nunca classes concretas. As exceções do port
     * ({@code PaymentGatewayException}) são a exceção literal à regra.
     */
    @ArchTest
    static final ArchRule application_ports_are_interfaces =
            classes()
                    .that().resideInAPackage("com.orderflow.payment.application.port..")
                    .and().areTopLevelClasses()
                    .and().haveSimpleNameNotEndingWith("Exception")
                    .should(beInterfaces())
                    .as("ports da application devem ser interfaces");

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
