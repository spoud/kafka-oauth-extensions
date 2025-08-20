package io.confluent.oauth;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.AzureCliCredential;
import com.azure.identity.AzureCliCredentialBuilder;
import io.confluent.oauth.azure.managedidentity.utils.WorkloadIdentityUtils;
import org.apache.kafka.common.security.oauthbearer.internals.secured.UnretryableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpAccessTokenRetrieverTest {

    @Mock
    private com.azure.identity.WorkloadIdentityCredential mockWorkloadIdentityCredential;

    @Mock
    private TokenRequestContext mockTokenRequestContext;

    @Mock
    private HttpURLConnection mockConnection;

    @Test
    void testFormatAuthorizationHeader_validInputs() {
        String header = HttpAccessTokenRetriever.formatAuthorizationHeader("client", "secret");
        assertTrue(header.startsWith("Basic "));
        assertTrue(header.length() > 6);
    }

    @Test
    void testFormatRequestBody_withScope() throws IOException {
        String scope = "https://example.com/.default";
        String body = HttpAccessTokenRetriever.formatRequestBody(scope);
        assertTrue(body.contains("grant_type=client_credentials"));
        assertTrue(body.contains("scope="));
    }

    @Test
    void testFormatRequestBody_withoutScope() throws IOException {
        String body = HttpAccessTokenRetriever.formatRequestBody(null);
        assertEquals("grant_type=client_credentials", body);
    }

    @Test
    void testSanitizeString_validAndInvalidInputs() {
        assertEquals("foo", HttpAccessTokenRetriever.sanitizeString("test", "foo"));
        assertThrows(IllegalArgumentException.class, () -> HttpAccessTokenRetriever.sanitizeString("test", null));
        assertThrows(IllegalArgumentException.class, () -> HttpAccessTokenRetriever.sanitizeString("test", " "));
        assertThrows(IllegalArgumentException.class, () -> HttpAccessTokenRetriever.sanitizeString("test", ""));
    }

    @Test
    void testParseAccessToken_validAndInvalidJson() throws IOException {
        String validJson = "{\"access_token\":\"abc123\"}";
        assertEquals("abc123", HttpAccessTokenRetriever.parseAccessToken(validJson));
        String invalidJson = "{\"not_token\":\"nope\"}";
        assertThrows(IllegalArgumentException.class, () -> HttpAccessTokenRetriever.parseAccessToken(invalidJson));
    }

    @Test
    void testRetrieveToken_withWorkloadIdentity() throws IOException {
        // Given
        String expectedToken = "test-token-123";
        AccessToken mockAccessToken = new AccessToken(expectedToken, OffsetDateTime.now().plusHours(1));

        try (MockedStatic<WorkloadIdentityUtils> mockedStatic = Mockito.mockStatic(WorkloadIdentityUtils.class)) {
            // Setup static mocks
            mockedStatic.when(() -> WorkloadIdentityUtils.createWorkloadIdentityCredentialFromEnvironment())
                    .thenReturn(mockWorkloadIdentityCredential);
            mockedStatic.when(() -> WorkloadIdentityUtils.createTokenRequestContextFromEnvironment(anyString()))
                    .thenReturn(mockTokenRequestContext);

            // Setup token credential mock
            when(mockWorkloadIdentityCredential.getTokenSync(any(TokenRequestContext.class)))
                    .thenReturn(mockAccessToken);

            // Create retriever with workload identity enabled
            HttpAccessTokenRetriever retriever = new HttpAccessTokenRetriever(
                    "clientId", "clientSecret", "scope",
                    null, "https://example.com/token",
                    100, 1000, 1000, 1000, "POST",
                    true, false);

            // When
            String token = retriever.retrieve();

            // Then
            assertEquals(expectedToken, token);
            verify(mockWorkloadIdentityCredential).getTokenSync(any(TokenRequestContext.class));
        }
    }

    @Test
    void testRetrieveToken_withUserIdentity() throws IOException {
        // Given
        String expectedToken = "user-token-456";
        AccessToken mockAccessToken = new AccessToken(expectedToken, OffsetDateTime.now().plusHours(1));
        AzureCliCredential mockCliCredential = mock(AzureCliCredential.class);

        try (MockedStatic<HttpAccessTokenRetriever> mockedStatic = Mockito.mockStatic(HttpAccessTokenRetriever.class)) {
            AzureCliCredentialBuilder mockBuilder = mock(AzureCliCredentialBuilder.class);
            mockedStatic.when(HttpAccessTokenRetriever::createAzureCliCredentialBuilder).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockCliCredential);

            // Setup credential mock (use doAnswer to ensure the method is stubbed as a mock, not a real method)
            doAnswer(invocation -> mockAccessToken)
                .when(mockCliCredential).getTokenSync(any(TokenRequestContext.class));

            // Create retriever with user identity enabled
            HttpAccessTokenRetriever retriever = new HttpAccessTokenRetriever(
                    "clientId", "clientSecret", "scope",
                    null, "https://example.com/token",
                    100, 1000, 1000, 1000, "POST",
                    false, true);

            // When
            String token = retriever.retrieve();

            // Then
            assertEquals(expectedToken, token);
            verify(mockCliCredential).getTokenSync(any(TokenRequestContext.class));
        }
    }

    @Test
    void testRetrieveToken_withClientCredentials() throws IOException {
        // Given
        String expectedToken = "client-token-789";
        String responseBody = "{\"access_token\":\"client-token-789\"}";
        // Mock connection
        when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(mockConnection.getInputStream()).thenReturn(
                new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        when(mockConnection.getURL()).thenReturn(new java.net.URL("https://example.com/token"));

        // Spy on the retriever to override fetch and handleOutput
        HttpAccessTokenRetriever retriever = spy(new HttpAccessTokenRetriever(
                "clientId", "clientSecret", "scope",
                null, "https://example.com/token",
                100, 1000, 1000, 1000, "POST",
                false, false));

        // Mock static fetch to use our mockConnection
        try (MockedStatic<HttpAccessTokenRetriever> staticMock = Mockito.mockStatic(HttpAccessTokenRetriever.class, Mockito.CALLS_REAL_METHODS)) {
            staticMock.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        // Call the real handleOutput with our mockConnection
                        return HttpAccessTokenRetriever.handleOutput(mockConnection);
                    });

            // When
            String token = retriever.retrieve();

            // Then
            assertEquals(expectedToken, token);
        }
    }

    @Test
    void testHandleOutput_successResponse() throws IOException {
        // Given
        String responseBody = "{\"access_token\":\"test-token\"}";

        // Mock connection
        when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(mockConnection.getInputStream()).thenReturn(
                new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        when(mockConnection.getURL()).thenReturn(new java.net.URL("https://example.com"));

        // When
        String result = HttpAccessTokenRetriever.handleOutput(mockConnection);

        // Then
        assertEquals(responseBody, result);
    }

    @Test
    void testHandleOutput_errorResponse() throws IOException {
        // Given
        when(mockConnection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_BAD_REQUEST);
        when(mockConnection.getURL()).thenReturn(new java.net.URL("https://example.com"));

        // Then
        assertThrows(UnretryableException.class, () -> HttpAccessTokenRetriever.handleOutput(mockConnection));
    }
}
