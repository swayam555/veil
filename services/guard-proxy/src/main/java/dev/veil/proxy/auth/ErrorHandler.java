package dev.veil.proxy.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ProxyException.class)
    ResponseEntity<Map<String, Object>> proxy(ProxyException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", e.getMessage());
        error.put("type", e.type());
        error.put("code", e.code());
        error.put("param", null);
        error.putAll(e.details());
        var builder = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() > 0) builder.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        return builder.body(Map.of("error", error));
    }

    @ExceptionHandler({ServerWebInputException.class, DecodingException.class})
    ResponseEntity<Map<String, Object>> badInput(Exception e) {
        return proxy(ProxyException.badRequest("Request body is not valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return proxy(new ProxyException(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "internal_error", "Veil hit an internal error"));
    }
}
