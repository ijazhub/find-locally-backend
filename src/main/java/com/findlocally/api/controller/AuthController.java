package com.findlocally.api.controller;

import com.findlocally.api.contract.AuthApi;
import com.findlocally.api.service.AuthService;
import com.findlocally.api.model.AuthResponse;
import com.findlocally.api.model.RegisterRequest;
import com.findlocally.api.model.LoginRequest;
import com.findlocally.api.model.RefreshTokenRequest;
import com.findlocally.api.model.TokenPair;
import com.findlocally.api.model.ForgotPasswordRequest;
import com.findlocally.api.model.ResetPasswordRequest;
import com.findlocally.api.service.PasswordResetService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController implements AuthApi {
    private final AuthService service;
    private final PasswordResetService passwordReset;

    public AuthController(AuthService service, PasswordResetService passwordReset) {
        this.service = service;
        this.passwordReset = passwordReset;
    }

    @Override
    public ResponseEntity<Void> forgotPassword(ForgotPasswordRequest request) {
        passwordReset.request(request);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> resetPassword(ResetPasswordRequest request) {
        passwordReset.reset(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> logout() {
        service.logout();
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<TokenPair> refreshToken(RefreshTokenRequest refreshTokenRequest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refreshToken(refreshTokenRequest));
    }

    @Override
    public ResponseEntity<AuthResponse> register(RegisterRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.register(request));
    }

    @Override
    public ResponseEntity<AuthResponse> login(LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.login(request));
    }
}
