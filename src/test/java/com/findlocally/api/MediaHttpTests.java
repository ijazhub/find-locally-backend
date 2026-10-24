package com.findlocally.api;

import com.findlocally.api.model.UploadPurpose;
import com.findlocally.api.model.UploadResponse;
import com.findlocally.api.service.MediaService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "spring.sql.init.mode=never"})
class MediaHttpTests {
    @Autowired WebApplicationContext context;
    @MockitoBean MediaService media;
    @MockitoBean org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private MockMvc mvc;
    private MockMultipartFile file() { return new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3}); }

    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void multipartUploadsReachTheBackendAndReturnACompletedIdWithoutPresignedUrls() throws Exception {
        UUID id = UUID.randomUUID();
        when(media.createUpload(eq(UploadPurpose.AVATAR), any())).thenReturn(new UploadResponse().uploadId(id)
                .status(UploadResponse.StatusEnum.UPLOADED).contentType("image/jpeg").sizeBytes(3L));
        mvc.perform(multipart("/api/v1/media/uploads").file(file()).param("purpose", "AVATAR").with(jwt()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.uploadId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("UPLOADED")).andExpect(jsonPath("$.sizeBytes").value(3))
                .andExpect(jsonPath("$.uploadUrl").doesNotExist()).andExpect(jsonPath("$.expiresAt").doesNotExist());
        verify(media).createUpload(eq(UploadPurpose.AVATAR), any());
    }

    @Test
    void jwtAndBothMultipartFieldsAreRequiredAndJsonUploadSlotsAreNoLongerAccepted() throws Exception {
        mvc.perform(multipart("/api/v1/media/uploads").file(file()).param("purpose", "AVATAR"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/media/uploads").file(file()).with(jwt())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/media/uploads").param("purpose", "AVATAR").with(jwt())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/media/uploads").file(file()).param("purpose", "INVALID").with(jwt())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/media/uploads").contentType("application/json").content("{}").with(jwt()))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(media);
    }

    @Test
    void oversizedMultipartRequestsReturnPayloadTooLargeAndTheWebsocketEndpointIsGone() throws Exception {
        when(media.createUpload(any(), any())).thenThrow(new MaxUploadSizeExceededException(100L * 1024 * 1024));
        mvc.perform(multipart("/api/v1/media/uploads").file(file()).param("purpose", "AVATAR").with(jwt()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status").value(413));
        mvc.perform(get("/ws")).andExpect(status().isNotFound());
    }
}
