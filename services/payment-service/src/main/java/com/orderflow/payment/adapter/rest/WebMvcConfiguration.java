package com.orderflow.payment.adapter.rest;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registra o {@link AuthenticatedCallerArgumentResolver}, permitindo que os
 * handlers REST recebam {@link AuthenticatedCaller} por injeção de parâmetro.
 */
@Configuration
class WebMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedCallerArgumentResolver());
    }
}
