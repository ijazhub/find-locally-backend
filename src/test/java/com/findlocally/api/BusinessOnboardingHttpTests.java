package com.findlocally.api;

import com.findlocally.api.model.*;
import com.findlocally.api.service.BusinessOnboardingService;
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
class BusinessOnboardingHttpTests {
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @MockitoBean BusinessOnboardingService onboarding;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(onboarding.saveOnboardingStep(anyString(), any())).thenReturn(new OnboardingState());
    }

    @Test
    void selectsEveryConcreteRequestTypeFromTheStepPath() throws Exception {
        String[][] requests = {
            {"ABOUT", "{\"ownerName\":\"Asha\",\"businessName\":\"Studio\",\"category\":\"makeup\"}"},
            {"LOCATION", "{\"city\":\"Pune\",\"neighbourhood\":\"Koregaon Park\",\"workMode\":\"STUDIO\"}"},
            {"SERVICES", "{\"services\":[{\"name\":\"Makeup\",\"durationMinutes\":60,\"price\":{\"amount\":50000,\"currency\":\"INR\"}}]}"},
            {"AVAILABILITY", "{\"byAppointmentOnly\":true}"},
            {"WORK", "{}"},
            {"VERIFICATION", "{\"idType\":\"PAN\",\"idDocumentUploadId\":\"11111111-1111-1111-1111-111111111111\"}"}
        };
        for (String[] request : requests) {
            mvc.perform(put("/api/v1/business/onboarding/steps/" + request[0])
                    .with(user("11111111-1111-1111-1111-111111111111"))
                    .contentType("application/json").content(request[1])).andExpect(status().isOk());
        }
        verify(onboarding).saveOnboardingStep(eq("ABOUT"), isA(OnboardingAbout.class));
        verify(onboarding).saveOnboardingStep(eq("LOCATION"), isA(OnboardingLocation.class));
        verify(onboarding).saveOnboardingStep(eq("SERVICES"), isA(OnboardingServices.class));
        verify(onboarding).saveOnboardingStep(eq("AVAILABILITY"), isA(AvailabilityInput.class));
        verify(onboarding).saveOnboardingStep(eq("WORK"), isA(OnboardingWork.class));
        verify(onboarding).saveOnboardingStep(eq("VERIFICATION"), isA(OnboardingVerification.class));
    }

    @Test
    void invalidBodiesAndStepsReturnBadRequest() throws Exception {
        mvc.perform(put("/api/v1/business/onboarding/steps/ABOUT").with(user("11111111-1111-1111-1111-111111111111")).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/business/onboarding/steps/UNKNOWN").with(user("11111111-1111-1111-1111-111111111111")).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        verify(onboarding, never()).saveOnboardingStep(anyString(), any());
    }

    @Test
    void unauthenticatedServiceManagementReturnsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/business/services")).andExpect(status().isUnauthorized());
    }
}
