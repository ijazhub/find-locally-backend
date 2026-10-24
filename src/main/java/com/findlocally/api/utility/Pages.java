package com.findlocally.api.utility;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record Pages(int page, int size) {
    public static Pages of(Integer page, Integer size) {
        int p = page == null ? 0 : page, s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a non-negative page and size between 1 and 50");
        return new Pages(p, s);
    }
    public long offset() { return (long) page * size; }
    public int totalPages(long count) { return Math.toIntExact((count + size - 1) / size); }
}
