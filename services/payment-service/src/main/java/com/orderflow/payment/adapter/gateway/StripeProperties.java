package com.orderflow.payment.adapter.gateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuração do adapter Stripe. Os valores sensíveis (a chave secreta) chegam
 * por variável de ambiente / Vault em produção — jamais commitados — conforme
 * {@code docs/security.md}. Os timeouts são explícitos por princípio: nunca
 * confiar no timeout padrão do cliente HTTP, frequentemente longo demais
 * ({@code docs/architecture.md}, "Estratégias de Resiliência").
 */
@Validated
@ConfigurationProperties(prefix = "orderflow.payment.stripe")
public record StripeProperties(

        @NotBlank
        @DefaultValue("https://api.stripe.com")
        String baseUrl,

        @NotBlank(message = "A secretKey do Stripe deve ser fornecida via ambiente/Vault")
        String secretKey,

        @NotNull
        @DefaultValue("2s")
        Duration connectionTimeout,

        @NotNull
        @DefaultValue("5s")
        Duration readTimeout
) {
}
