package com.findlocally.api.service;

import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MailPasswordResetSender implements PasswordResetSender {
    private static final Logger LOG = LoggerFactory.getLogger(MailPasswordResetSender.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String resetUrl;
    private final String host;

    public MailPasswordResetSender(ObjectProvider<JavaMailSender> mail,
            @Value("${app.auth.password-reset.from:}") String from,
            @Value("${app.auth.password-reset.url:}") String resetUrl,
            @Value("${spring.mail.host:}") String host) {
        this.mail = mail;
        this.from = from;
        this.resetUrl = resetUrl;
        this.host = host;
    }

    @Override
    public void requireConfigured() {
        try {
            URI uri = URI.create(resetUrl);
            boolean localhost = Set.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost()));
            if (host.isBlank() || mail.getIfAvailable() == null || from.isBlank() || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && localhost))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Password reset email is not configured");
        }
    }

    @Override
    public boolean send(String email, String token) {
        requireConfigured();
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Reset your Find Locally password");
        // Fragments are read by the frontend and are not sent in HTTP requests or Referer headers.
        message.setText("Open this link to reset your password within 15 minutes:\n\n" + resetUrl + "#token=" + token
                + "\n\nIf you did not request this change, you can ignore this email.");
        try {
            mail.getObject().send(message);
            return true;
        } catch (MailException ex) {
            // Do not expose account existence through status codes, or log recipients, tokens or SMTP credentials.
            LOG.warn("Password reset email delivery failed");
            return false;
        }
    }
}
