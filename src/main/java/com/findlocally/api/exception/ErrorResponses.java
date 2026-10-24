package com.findlocally.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ErrorResponses {
    private final ObjectMapper mapper;
    public ErrorResponses(ObjectMapper mapper) { this.mapper = mapper; }
    public ProblemDetail problem(HttpStatusCode status, String detail, String path) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(path));
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }
    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem(status, detail, request.getRequestURI()));
    }
}
