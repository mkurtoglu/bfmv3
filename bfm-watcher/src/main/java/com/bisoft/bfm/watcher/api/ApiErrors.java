package com.bisoft.bfm.watcher.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Requests the API cannot read answer 400 with a JSON reason. Left to Spring, they would be forwarded to /error,
 * which belongs to the console's security chain and answers a script with an empty 403.
 */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
public class ApiErrors {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        String detail = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        int cut = detail.indexOf('\n');
        detail = cut < 0 ? detail : detail.substring(0, cut);
        return bad("the request body is not what this call takes" + (detail.isBlank() ? "" : ": " + detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException e) {
        return bad("the parameter " + e.getParameterName() + " is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
        return bad("the parameter " + e.getName() + " has a value of the wrong kind");
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
