package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@ConfigurationProperties(prefix = "google.oauth")
@Configuration
@Data
public class GoogleOAuthConfig {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String frontendRedirectUrl;

    public boolean isConfigured() {
        // TokenEncryptionService validates the encryption key at application startup.
        return hasText(clientId) && hasText(clientSecret)
                && validHttpUrl(redirectUri) && validHttpUrl(frontendRedirectUrl);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean validHttpUrl(String value) {
        if (!hasText(value)) return false;
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
