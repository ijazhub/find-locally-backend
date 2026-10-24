package com.findlocally.api.service;

public interface PasswordResetSender {
    void requireConfigured();
    boolean send(String email, String token);
}
