package com.familyhub.demo.service;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class GoogleCredentialService {
    private final GoogleOAuthTokenRepository tokenRepository;
    private final TokenEncryptionService encryptionService;
    private final GoogleOAuthConfig config;
    private final HttpTransport httpTransport;
    private final JsonFactory jsonFactory;

    @Autowired
    public GoogleCredentialService(GoogleOAuthTokenRepository tokenRepository,
                                   TokenEncryptionService encryptionService,
                                   GoogleOAuthConfig config) throws GeneralSecurityException, IOException {
        this(tokenRepository, encryptionService, config,
                GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance());
    }

    GoogleCredentialService(GoogleOAuthTokenRepository tokenRepository,
                            TokenEncryptionService encryptionService,
                            GoogleOAuthConfig config,
                            HttpTransport httpTransport,
                            JsonFactory jsonFactory) {
        this.tokenRepository = tokenRepository;
        this.encryptionService = encryptionService;
        this.config = config;
        this.httpTransport = httpTransport;
        this.jsonFactory = jsonFactory;
    }

    @Transactional
    @SuppressWarnings("deprecation")
    public Credential getCredential(UUID memberId) {
        GoogleOAuthToken token = tokenRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BadRequestException("Google account not connected for member " + memberId));

        String accessToken = encryptionService.decrypt(token.getAccessToken());
        String refreshToken = encryptionService.decrypt(token.getRefreshToken());

        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(httpTransport)
                .setJsonFactory(jsonFactory)
                .setClientSecrets(config.getClientId(), config.getClientSecret())
                .build();

        credential.setAccessToken(accessToken);
        credential.setRefreshToken(refreshToken);
        credential.setExpirationTimeMilliseconds(token.getTokenExpiry().toEpochMilli());

        if (token.getTokenExpiry().isBefore(Instant.now())) {
            try {
                boolean refreshed = credential.refreshToken();
                if (refreshed) {
                    token.setAccessToken(encryptionService.encrypt(credential.getAccessToken()));
                    token.setTokenExpiry(Instant.ofEpochMilli(credential.getExpirationTimeMilliseconds()));
                    tokenRepository.save(token);
                    log.info("Refreshed access token for member {}", memberId);
                } else {
                    throw new BadRequestException("Google access has expired or been revoked; reconnect the account");
                }
            } catch (IOException e) {
                log.error("Failed to refresh token for member {}: {}", memberId, e.getMessage());
                throw new RuntimeException("Failed to refresh Google access token", e);
            }
        }

        return credential;
    }

    HttpTransport getHttpTransport() {
        return httpTransport;
    }

    JsonFactory getJsonFactory() {
        return jsonFactory;
    }
}
