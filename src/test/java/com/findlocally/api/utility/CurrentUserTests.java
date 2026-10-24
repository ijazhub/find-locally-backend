package com.findlocally.api.utility;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CurrentUserTests {
    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void rejectsMissingAnonymousAndInvalidIdentities() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, currentUser::id).getStatusCode());
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(ResponseStatusException.class, currentUser::id);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("not-a-uuid", null, List.of()));
        assertThrows(ResponseStatusException.class, currentUser::id);
    }

    @Test
    void resolvesAuthenticatedUserUuid() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        assertEquals(id, currentUser.id());
    }
}
