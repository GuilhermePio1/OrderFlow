package com.orderflow.payment.adapter.rest;

import com.orderflow.payment.application.port.PaymentGatewayException;
import com.orderflow.payment.domain.exception.ConcurrencyConflictException;
import com.orderflow.payment.domain.exception.DomainException;
import com.orderflow.payment.domain.exception.InvalidPaymentStateTransitionException;
import com.orderflow.payment.domain.exception.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduz exceções de domínio e de validação em respostas HTTP RFC 7807
 * ({@code application/problem+json}). A política de status segue a
 * semântica do erro, não a exceção crua:
 *
 * <ul>
 *   <li>agregado inexistente → 404;</li>
 *   <li>acesso negado → 403;</li>
 *   <li>conflito de concorrência otimista / transição inválida de
 *       estado → 409 (o operador pode reidratar e reenviar);</li>
 *   <li>violação de invariante de domínio ou entrada malformada → 400;</li>
 *   <li>falha técnica do gateway externo → 502 (condição transitória;
 *       nada foi persistido e a operação pode ser retentada).</li>
 * </ul>
 *
 * Mensagens internas não vazam detalhes de implementação; o corpo carrega
 * apenas o necessário para o cliente corrigir a requisição.
 */
@RestControllerAdvice
class PaymentRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRestExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException ex) {
        log.debug("Payment not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Payment not found", ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ResponseEntity<ProblemDetail> handleConcurrencyConflict(ConcurrencyConflictException ex) {
        log.warn("Concurrency conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrency conflict",
                "The payment was modified concurrently; reload and retry.");
    }

    @ExceptionHandler(InvalidPaymentStateTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidStateTransition(InvalidPaymentStateTransitionException ex) {
        log.debug("Invalid state transition: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Invalid payment state transition", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Access denied",
                "You are not allowed to access this resource.");
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ResponseEntity<ProblemDetail> handleGatewayFailure(PaymentGatewayException ex) {
        log.warn("Payment gateway failure: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Payment gateway unavailable",
                "The payment gateway is temporarily unavailable; retry later.");
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        log.debug("Domain rule violation: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Domain rule violation", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Malformed input: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(),
                        error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()));
        log.debug("Request validation failed: {}", fieldErrors);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        body.setTitle("Validation error");
        body.setProperty("errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformedBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body could not be read.");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        return ResponseEntity.status(status).body(body);
    }
}
