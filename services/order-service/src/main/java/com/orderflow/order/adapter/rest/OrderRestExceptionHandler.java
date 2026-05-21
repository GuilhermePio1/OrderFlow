package com.orderflow.order.adapter.rest;

import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import com.orderflow.order.domain.exception.DomainException;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduz exceções de domínio e de validação em respostas HTTP RFC 7807
 * ({@code application/problem+json}). A política de status segue a
 * semântica do erro, não a exceção crua:
 *
 * <ul>
 *   <li>agregado inexistente → 404;</li>
 *   <li>conflito de concorrência otimista / transição inválida de
 *       estado → 409 (o cliente pode reidratar e reenviar);</li>
 *   <li>violação de invariante de domínio ou entrada malformada →
 *       400.</li>
 * </ul>
 *
 * Mensagens internas não vazam detalhes de implementação; o corpo carrega
 * apenas o necessário para o cliente corrigir a requisição.
 */
@RestControllerAdvice
class OrderRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderRestExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(OrderNotFoundException ex) {
        log.debug("Order not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Order not found", ex.getMessage());
    }

    @ExceptionHandler(ConcurrencyConflictException.class)
    ResponseEntity<ProblemDetail> handleConcurrencyConflict(ConcurrencyConflictException ex) {
        log.warn("Concurrency conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrency conflict",
                "The order was modified concurrently; reload and retry.");
    }

    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidStateTransition(InvalidOrderStateTransitionException ex) {
        log.debug("Invalid state transition: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Invalid order state transition", ex.getMessage());
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

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemDetail> handleValidation(WebExchangeBindException ex) {
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

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ProblemDetail> handleMalformedBody(ServerWebInputException ex) {
        log.debug("Unreadable request: {}", ex.getReason());
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
