package com.payments.orchestrator.api.error;

import com.payments.orchestrator.psp.PspUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders errors as RFC 7807 problem details. The base class already covers validation failures,
 * malformed JSON and {@code ResponseStatusException} (e.g. the 400/409/422 idempotency errors).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PspUnavailableException.class)
    ProblemDetail handlePspUnavailable(PspUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The payment provider is currently unavailable. Retry with the same Idempotency-Key.");
        problem.setTitle("Payment provider unavailable");
        return problem;
    }
}
