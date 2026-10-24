package com.findlocally.api.utility;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Inputs {
    private final Validator validator;
    public Inputs(Validator validator) { this.validator = validator; }
    public <T> T valid(T input) {
        if (input == null || !validator.validate(input).isEmpty()) throw bad("Invalid request details");
        return input;
    }
    public String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw bad("Text is required and must be at most " + max + " characters");
        return value;
    }
    public static ResponseStatusException bad(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail); }
    public static ResponseStatusException missing(String resource) { return new ResponseStatusException(HttpStatus.NOT_FOUND, resource + " not found"); }
    public static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }
}
