package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "familyhub.media-dir=/tmp/familyhub-appearance-test-media")
class AppearanceIntegrationTest {
    @Autowired MockMvc mvc;

    private String token() throws Exception {
        String username = "a" + Long.toString(System.nanoTime(), 36);
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username":"%s","password":"password123","familyName":"Test",
                         "members":[{"name":"Someone","color":"teal"}]}
                        """.formatted(username)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private String request(String accent, String mode, String gradient, int strength) {
        return """
                {"accent":"%s","backgroundMode":"%s","gradient":"%s","backgroundStrength":%d}
                """.formatted(accent, mode, gradient, strength);
    }

    private MockMultipartFile image() throws Exception {
        BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("file", "home.png", "image/png", bytes.toByteArray());
    }

    @Test void defaultsUpdatesValidationAndAuthentication() throws Exception {
        String token = token();
        mvc.perform(get("/api/family/appearance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/family/appearance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accent").value("PURPLE"))
                .andExpect(jsonPath("$.data.backgroundMode").value("DEFAULT"));
        mvc.perform(put("/api/family/appearance").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request("TEAL", "GRADIENT", "LAGOON", 70)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accent").value("TEAL"))
                .andExpect(jsonPath("$.data.gradient").value("LAGOON"));
        mvc.perform(put("/api/family/appearance").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request("INVALID", "DEFAULT", "SUNRISE", 50)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/family/appearance").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request("TEAL", "DEFAULT", "SUNRISE", 101)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/family/appearance").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request("TEAL", "PHOTO", "SUNRISE", 55)))
                .andExpect(status().isBadRequest());
    }

    @Test void uploadedPhotoIsPrivateReplaceableAndRemovable() throws Exception {
        String first = token();
        String second = token();
        mvc.perform(multipart("/api/family/appearance/photo").file(image())
                .header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.photoKey").isNotEmpty());
        mvc.perform(get("/api/family/appearance/photo").header("Authorization", "Bearer " + second))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/family/appearance/photo").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(put("/api/family/appearance").header("Authorization", "Bearer " + first)
                .contentType(MediaType.APPLICATION_JSON).content(request("BLUE", "PHOTO", "SUNRISE", 50)))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/family/appearance/photo").file(image())
                .header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mvc.perform(delete("/api/family/appearance/photo").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.backgroundMode").value("DEFAULT"))
                .andExpect(jsonPath("$.data.photoKey").isEmpty());
        mvc.perform(get("/api/family/appearance/photo").header("Authorization", "Bearer " + first))
                .andExpect(status().isNotFound());
    }

    @Test void malformedAndOversizedUploadsAreRejected() throws Exception {
        String token = token();
        mvc.perform(multipart("/api/family/appearance/photo")
                .file(new MockMultipartFile("file", "bad.png", "image/png", "not an image".getBytes()))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/family/appearance/photo")
                .file(new MockMultipartFile("file", "huge.png", "image/png", new byte[12 * 1024 * 1024 + 1]))
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
