package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.BookingsService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
class BookingsHttpTests {
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @MockitoBean BookingsService bookings;
    @MockitoBean com.findlocally.api.service.NegotiationService negotiation;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bookingEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bookings/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bookings);
    }

    @Test
    void createRequiresIdempotencyHeaderAndValidBody() throws Exception {
        mvc.perform(post("/api/v1/bookings").with(user(UUID.randomUUID().toString()))
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings").with(user(UUID.randomUUID().toString()))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(bookings);
    }

    @Test
    void quotationRoutesRequireAuthenticationAndCompleteVersionedInput() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post("/api/v1/business/bookings/"+id+"/quotations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/"+id+"/quotation-response")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/"+id+"/quotation-response").with(user(UUID.randomUUID().toString()))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(negotiation);
    }

    @Test
    void quotationHttpContractBindsMinorUnitsAndIdempotencyKey() throws Exception {
        UUID id=UUID.randomUUID(),key=UUID.randomUUID();
        when(negotiation.issue(eq(id),eq(key),any())).thenReturn(new Booking().id(id).status(BookingStatus.AWAITING_CUSTOMER_APPROVAL));
        mvc.perform(post("/api/v1/business/bookings/"+id+"/quotations").with(user(UUID.randomUUID().toString()))
                .header("Idempotency-Key",key).contentType("application/json").content("{\"price\":{\"amount\":48000,\"currency\":\"INR\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_CUSTOMER_APPROVAL"));
        verify(negotiation).issue(id,key,new QuotationInput().price(new Money(48000L,"INR")));
    }

    @Test
    void cancellationBodyIsOptional() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookings.cancelBooking(id, null)).thenReturn(new Booking().id(id).status(BookingStatus.CANCELLED));
        mvc.perform(post("/api/v1/bookings/" + id + "/cancel").with(user(UUID.randomUUID().toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(bookings).cancelBooking(id, null);
    }
}
