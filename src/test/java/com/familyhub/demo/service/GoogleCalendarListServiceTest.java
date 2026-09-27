package com.familyhub.demo.service;

import com.familyhub.demo.dto.GoogleCalendarInfo;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarListServiceTest {

    @Mock
    private GoogleCredentialService credentialService;

    @Test
    void listCalendars_mapsResponseCorrectly() {
        UUID memberId = UUID.randomUUID();

        String jsonResponse = """
                {
                  "kind": "calendar#calendarList",
                  "items": [
                    { "id": "primary", "summary": "Joe's Calendar", "primary": true },
                    { "id": "work@group.calendar.google.com", "summary": "Work", "primary": false },
                    { "id": "family@group.calendar.google.com", "summary": "Family" }
                  ]
                }
                """;

        HttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() {
                        MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                        response.setContentType("application/json");
                        response.setContent(jsonResponse);
                        return response;
                    }
                };
            }
        };

        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .build();
        credential.setAccessToken("fake-token");

        when(credentialService.getCredential(memberId)).thenReturn(credential);
        when(credentialService.getHttpTransport()).thenReturn(transport);
        when(credentialService.getJsonFactory()).thenReturn(jsonFactory);

        GoogleCalendarListService service = new GoogleCalendarListService(credentialService);
        List<GoogleCalendarInfo> calendars = service.listCalendars(memberId);

        assertThat(calendars).hasSize(3);

        GoogleCalendarInfo primary = calendars.stream()
                .filter(c -> c.id().equals("primary"))
                .findFirst().orElseThrow();
        assertThat(primary.name()).isEqualTo("Joe's Calendar");
        assertThat(primary.primary()).isTrue();

        GoogleCalendarInfo work = calendars.stream()
                .filter(c -> c.id().equals("work@group.calendar.google.com"))
                .findFirst().orElseThrow();
        assertThat(work.name()).isEqualTo("Work");
        assertThat(work.primary()).isFalse();

        GoogleCalendarInfo family = calendars.stream()
                .filter(c -> c.id().equals("family@group.calendar.google.com"))
                .findFirst().orElseThrow();
        assertThat(family.name()).isEqualTo("Family");
        assertThat(family.primary()).isFalse();
    }

    @Test
    void listCalendars_emptyResponse_returnsEmptyList() {
        UUID memberId = UUID.randomUUID();

        String jsonResponse = """
                {
                  "kind": "calendar#calendarList"
                }
                """;

        HttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() {
                        MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                        response.setContentType("application/json");
                        response.setContent(jsonResponse);
                        return response;
                    }
                };
            }
        };

        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .build();
        credential.setAccessToken("fake-token");

        when(credentialService.getCredential(memberId)).thenReturn(credential);
        when(credentialService.getHttpTransport()).thenReturn(transport);
        when(credentialService.getJsonFactory()).thenReturn(jsonFactory);

        GoogleCalendarListService service = new GoogleCalendarListService(credentialService);
        List<GoogleCalendarInfo> calendars = service.listCalendars(memberId);

        assertThat(calendars).isEmpty();
    }

    @Test
    void listCalendars_fetchesAllPagesIncludingHidden() {
        UUID memberId = UUID.randomUUID();
        List<String> urls = new ArrayList<>();
        HttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                urls.add(url);
                String body = urls.size() == 1
                        ? "{\"items\":[{\"id\":\"primary\"}],\"nextPageToken\":\"next\"}"
                        : "{\"items\":[{\"id\":\"hidden\",\"hidden\":true}]}";
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() {
                        return new MockLowLevelHttpResponse().setContentType("application/json").setContent(body);
                    }
                };
            }
        };
        stubCredentials(memberId, transport);

        var result = new GoogleCalendarListService(credentialService).listCalendars(memberId);

        assertThat(result).extracting(GoogleCalendarInfo::id).containsExactly("primary", "hidden");
        assertThat(urls).hasSize(2).allSatisfy(url -> assertThat(url).contains("showHidden=true"));
        assertThat(urls.get(1)).contains("pageToken=next");
    }

    @Test
    void listCalendars_secondPageFailureDoesNotReturnPartialDiscovery() {
        UUID memberId = UUID.randomUUID();
        HttpTransport transport = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() {
                        if (url.contains("pageToken=next")) {
                            return new MockLowLevelHttpResponse().setStatusCode(503).setReasonPhrase("Unavailable")
                                    .setContentType("application/json").setContent("{}");
                        }
                        return new MockLowLevelHttpResponse().setContentType("application/json")
                                .setContent("{\"items\":[{\"id\":\"primary\"}],\"nextPageToken\":\"next\"}");
                    }
                };
            }
        };
        stubCredentials(memberId, transport);

        assertThatThrownBy(() -> new GoogleCalendarListService(credentialService).listCalendars(memberId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to fetch");
    }

    private void stubCredentials(UUID memberId, HttpTransport transport) {
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport).setJsonFactory(jsonFactory).build();
        credential.setAccessToken("fake-token");
        when(credentialService.getCredential(memberId)).thenReturn(credential);
        when(credentialService.getHttpTransport()).thenReturn(transport);
        when(credentialService.getJsonFactory()).thenReturn(jsonFactory);
    }
}
