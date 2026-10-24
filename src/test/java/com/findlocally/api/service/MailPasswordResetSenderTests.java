package com.findlocally.api.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailPasswordResetSenderTests {
    private final JavaMailSender mail = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

    private MailPasswordResetSender sender(String url) {
        when(provider.getIfAvailable()).thenReturn(mail);
        when(provider.getObject()).thenReturn(mail);
        return new MailPasswordResetSender(provider, "noreply@example.com", url, "smtp.example.com");
    }

    @Test
    void buildsAResetLinkFromTrustedConfigurationAndKeepsTokenInFragment() {
        String token = "A".repeat(43);
        assertTrue(sender("https://app.example.com/reset-password").send("asha@example.com", token));
        var captured = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(captured.capture());
        assertArrayEquals(new String[] {"asha@example.com"}, captured.getValue().getTo());
        assertEquals("noreply@example.com", captured.getValue().getFrom());
        assertTrue(captured.getValue().getText().contains("https://app.example.com/reset-password#token=" + token));
    }

    @Test
    void rejectsMissingSmtpConfigurationAndUnsafeFrontendUrls() {
        for (String url : new String[] {"", "http://app.example.com/reset", "https://user:pass@example.com/reset", "https://example.com/reset#bad"}) {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                    assertThrows(ResponseStatusException.class, () -> sender(url).requireConfigured()).getStatusCode());
        }
        assertThrows(ResponseStatusException.class,
                () -> new MailPasswordResetSender(provider, "", "https://app.example.com/reset", "smtp.example.com").requireConfigured());
        assertThrows(ResponseStatusException.class,
                () -> new MailPasswordResetSender(provider, "noreply@example.com", "https://app.example.com/reset", "").requireConfigured());
        assertDoesNotThrow(() -> sender("http://localhost:4200/reset-password").requireConfigured());
        verifyNoInteractions(mail);
    }

    @Test
    void deliveryErrorsReturnFalseInsteadOfExposingTheAccountInAnHttpError() {
        doThrow(new MailSendException("sensitive SMTP error")).when(mail).send(any(SimpleMailMessage.class));
        assertFalse(sender("https://app.example.com/reset-password").send("asha@example.com", "A".repeat(43)));
    }
}
