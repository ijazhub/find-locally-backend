package com.findlocally.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
class ApiApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    // This context mocks JDBC, so service transaction boundaries must not open a real connection.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.transaction.PlatformTransactionManager transactions;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.web.context.WebApplicationContext context;

    @Test
    void everySpecOperationHasAControllerMapping() throws Exception {
        String spec = new org.springframework.core.io.ClassPathResource("openapi/findlocally-openapi.yaml")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> expected = new java.util.HashSet<>();
        var matcher = java.util.regex.Pattern.compile("operationId: (\\w+)").matcher(spec);
        while (matcher.find()) {
            expected.add(matcher.group(1));
        }
        var actual = mappings.getHandlerMethods().values().stream()
                .filter(handler -> handler.getBeanType().getPackageName().equals("com.findlocally.api.controller"))
                .map(handler -> handler.getMethod().getName())
                .collect(java.util.stream.Collectors.toSet());
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().equals("com.findlocally.api.controller")) {
                mapping.getPatternValues().forEach(path ->
                        org.junit.jupiter.api.Assertions.assertTrue(path.startsWith("/api/v1/"), path));
            }
        });
    }

    @Test
    void currentUserRequiresAuthentication() throws Exception {
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void categoriesReturnOk() throws Exception {
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/categories")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

	@Test
	void contextLoads() {
	}

}
