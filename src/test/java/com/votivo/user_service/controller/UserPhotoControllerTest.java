package com.votivo.user_service.controller;

import com.votivo.user_service.controller.advice.ApiExceptionHandler;
import com.votivo.user_service.image.ImageData;
import com.votivo.user_service.image.ImageFormat;
import com.votivo.user_service.image.validation.exception.*;
import com.votivo.user_service.service.PhotoService;
import com.votivo.user_service.service.exception.PhotoNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserPhotoControllerTest {
    private static final String URL = "/api/v1/users/me/photo";
    private final UUID owner = UUID.randomUUID();
    private final PhotoService service = mock(PhotoService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new UserPhotoController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadsReadsAndDeletesUsingJwtOwner() throws Exception {
        byte[] bytes = {1, 2, 3};
        var file = new MockMultipartFile("file", "photo.png", "image/png", bytes);
        mvc.perform(multipart(HttpMethod.PUT, URL).file(file).param("id", UUID.randomUUID().toString()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).replaceOwn(owner, file);
        when(service.getOwn(owner)).thenReturn(new ImageData(bytes, ImageFormat.PNG));
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(delete(URL)).andExpect(status().isNoContent());
        verify(service).deleteOwn(owner);
    }

    @Test
    void rejectsMissingFileAndWrongRequestContentType() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, URL)).andExpect(status().isBadRequest());
        mvc.perform(put(URL).contentType("image/png").content(new byte[]{1})).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void translatesPhotoErrorsAndContainerUploadLimitToProblemDetails() throws Exception {
        var file = new MockMultipartFile("file", new byte[]{1});
        RuntimeException[] errors = {new InvalidImageException(), new ImageTooLargeException(20, 10),
                new UnsupportedImageFormatException("gif"), new ImageDimensionsExceededException(2, 2, 1, 1),
                new MaxUploadSizeExceededException(10)};
        int[] statuses = {400, 413, 415, 400, 413};
        for (int index = 0; index < errors.length; index++) {
            doThrow(errors[index]).when(service).replaceOwn(eq(owner), any());
            mvc.perform(multipart(HttpMethod.PUT, URL).file(file)).andExpect(status().is(statuses[index]))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(statuses[index]));
        }
        when(service.getOwn(owner)).thenThrow(new PhotoNotFoundException());
        mvc.perform(get(URL)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User photo was not found."));
    }
}
