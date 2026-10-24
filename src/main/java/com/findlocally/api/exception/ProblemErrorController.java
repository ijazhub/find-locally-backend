package com.findlocally.api.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Covers servlet/filter failures that occur outside controller advice. */
@RestController
public class ProblemErrorController implements ErrorController {
    private final ErrorResponses errors;
    public ProblemErrorController(ErrorResponses errors) { this.errors = errors; }
    @RequestMapping("/error")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = value instanceof Integer number && number >= 400 && number <= 599 ? number : 500;
        HttpStatus status = HttpStatus.resolve(code);
        String detail = code >= 500 ? "The request could not be completed. Please try again."
                : status == null ? "The request could not be completed." : status.getReasonPhrase();
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return ResponseEntity.status(code).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(errors.problem(HttpStatusCode.valueOf(code), detail, uri instanceof String path ? path : request.getRequestURI()));
    }
}
