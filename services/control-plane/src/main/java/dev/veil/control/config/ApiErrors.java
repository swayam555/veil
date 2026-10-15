package dev.veil.control.config;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail conflict(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Already exists");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).findFirst().orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
    }
}
