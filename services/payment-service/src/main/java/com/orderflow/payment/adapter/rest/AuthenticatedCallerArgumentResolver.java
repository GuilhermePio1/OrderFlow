package com.orderflow.payment.adapter.rest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolve um {@link AuthenticatedCaller} para os handlers REST, concentrando
 * a leitura da identidade autenticada ({@code SecurityContextHolder}) e do IP
 * de origem ({@code X-Forwarded-For} com fallback para o endereço remoto) num
 * único ponto — os handlers declaram o parâmetro e não repetem a extração.
 *
 * Registrado em {@link WebMvcConfiguration}.
 */
final class AuthenticatedCallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedCaller.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthenticatedCaller resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            // A cadeia de segurança já exige autenticação nas rotas da API;
            // chegar aqui sem identidade indica configuração inconsistente.
            throw new AccessDeniedException("No authenticated caller in security context");
        }
        return new AuthenticatedCaller(authentication.getName(), clientIp(webRequest));
    }

    private static String clientIp(NativeWebRequest webRequest) {
        String forwarded = webRequest.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String remote = request == null ? null : request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
