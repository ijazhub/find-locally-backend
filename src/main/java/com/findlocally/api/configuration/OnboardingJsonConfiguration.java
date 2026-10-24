package com.findlocally.api.configuration;

import com.findlocally.api.model.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;
import java.util.Map;

/** The path parameter selects the concrete type for the generated oneOf interface. */
@Configuration
public class OnboardingJsonConfiguration {
    @Bean
    SimpleModule onboardingRequestModule() {
        var module = new SimpleModule("onboarding-step-requests");
        module.addDeserializer(SaveOnboardingStepRequest.class, new ValueDeserializer<>() {
            @Override
            public SaveOnboardingStepRequest deserialize(JsonParser parser, DeserializationContext context) {
                var attributes = RequestContextHolder.getRequestAttributes();
                if (!(attributes instanceof ServletRequestAttributes servlet)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing onboarding step");
                }
                Object variables = servlet.getRequest().getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                Object step = variables instanceof Map<?, ?> paths ? paths.get("step") : null;
                Class<? extends SaveOnboardingStepRequest> type = switch (String.valueOf(step)) {
                    case "ABOUT" -> OnboardingAbout.class;
                    case "LOCATION" -> OnboardingLocation.class;
                    case "SERVICES" -> OnboardingServices.class;
                    case "AVAILABILITY" -> AvailabilityInput.class;
                    case "WORK" -> OnboardingWork.class;
                    case "VERIFICATION" -> OnboardingVerification.class;
                    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown onboarding step");
                };
                return context.readValue(parser, type);
            }
        });
        return module;
    }
}
