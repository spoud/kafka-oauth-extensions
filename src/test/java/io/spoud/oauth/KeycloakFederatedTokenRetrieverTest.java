package io.spoud.oauth;

import org.apache.kafka.common.security.oauthbearer.JwtRetrieverException;
import org.apache.kafka.common.security.oauthbearer.internals.secured.UnretryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeycloakFederatedTokenRetrieverTest {

    private static final String TOKEN_ENDPOINT = "https://keycloak.example.com/realms/test/protocol/openid-connect/token";
    private static final String K8S_TOKEN_VALUE = "eyJhbGciOiJSUzI1NiJ9.k8s-payload.k8s-sig";

    @TempDir
    Path tempDir;

    private Path k8sTokenFile;

    @BeforeEach
    void setUp() throws IOException {
        k8sTokenFile = tempDir.resolve("k8s-token");
        Files.writeString(k8sTokenFile, K8S_TOKEN_VALUE);
    }

    private KeycloakFederatedTokenRetriever retriever(String clientId) {
        return new KeycloakFederatedTokenRetriever(
                TOKEN_ENDPOINT, k8sTokenFile.toString(), clientId, null, 1, 1, null, null);
    }

    /** Creates a minimal base64url-encoded JWT with only an exp claim. */
    static String jwtWithExp(long expEpochSeconds) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + expEpochSeconds + ",\"sub\":\"test\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fake-signature";
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_success() throws Exception {
        String accessToken = jwtWithExp(System.currentTimeMillis() / 1000 + 3600);

        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenReturn("{\"access_token\":\"" + accessToken + "\"}");

            assertEquals(accessToken, retriever(null).retrieve());
        }
    }

    // -------------------------------------------------------------------------
    // File is re-read on every retrieve() call (K8s token rotation)
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_readsFileOnEachCall() throws Exception {
        String token1 = jwtWithExp(System.currentTimeMillis() / 1000 + 3600);
        String token2 = jwtWithExp(System.currentTimeMillis() / 1000 + 7200);
        String newK8sToken = "eyJhbGciOiJSUzI1NiJ9.rotated-payload.rotated-sig";

        KeycloakFederatedTokenRetriever r = retriever(null);

        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenReturn("{\"access_token\":\"" + token1 + "\"}")
                    .thenReturn("{\"access_token\":\"" + token2 + "\"}");

            r.retrieve();
            Files.writeString(k8sTokenFile, newK8sToken); // simulate K8s token rotation
            r.retrieve();

            List<String> bodies = bodyCaptor.getAllValues();
            assertEquals(2, bodies.size());
            assertTrue(bodies.get(0).contains(URLEncoder.encode(K8S_TOKEN_VALUE, "UTF-8")),
                    "First call should use original K8s token");
            assertTrue(bodies.get(1).contains(URLEncoder.encode(newK8sToken, "UTF-8")),
                    "Second call should use rotated K8s token");
        }
    }

    // -------------------------------------------------------------------------
    // SA token file errors
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_tokenFileNotFound() {
        KeycloakFederatedTokenRetriever r = new KeycloakFederatedTokenRetriever(
                TOKEN_ENDPOINT, "/nonexistent/path/k8s-token", null, null, 1, 1, null, null);

        JwtRetrieverException ex = assertThrows(JwtRetrieverException.class, r::retrieve);
        String message = causeMessage(ex);
        assertTrue(message.contains("/nonexistent/path/k8s-token"),
                "Error should name the missing file, got: " + message);
    }

    @Test
    void testRetrieve_tokenFileIsEmpty() throws IOException {
        Files.writeString(k8sTokenFile, "   ");

        JwtRetrieverException ex = assertThrows(JwtRetrieverException.class, retriever(null)::retrieve);
        String message = causeMessage(ex);
        assertTrue(message.contains(k8sTokenFile.toString()),
                "Error should name the empty file, got: " + message);
    }

    // -------------------------------------------------------------------------
    // Keycloak HTTP errors
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_keycloakReturns400() {
        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenThrow(new UnretryableException(new IOException("HTTP 400 Bad Request")));

            assertThrows(JwtRetrieverException.class, retriever(null)::retrieve);
        }
    }

    @Test
    void testRetrieve_keycloakReturns500() {
        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenThrow(new IOException("HTTP 500 Internal Server Error"));

            assertThrows(JwtRetrieverException.class, retriever(null)::retrieve);
        }
    }

    // -------------------------------------------------------------------------
    // Response does not contain access_token
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_missingAccessTokenInResponse() {
        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenReturn("{\"token_type\":\"Bearer\"}");

            // parseAccessToken (real) throws IllegalArgumentException wrapped in JwtRetrieverException
            assertThrows(JwtRetrieverException.class, retriever(null)::retrieve);
        }
    }

    // -------------------------------------------------------------------------
    // Near-expiry warning: token is still returned, no exception
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_nearExpiryTokenIsReturnedWithWarning() throws Exception {
        String nearExpiryToken = jwtWithExp(System.currentTimeMillis() / 1000 + 5); // 5s < 10s threshold

        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), any(), any(), any()))
                    .thenReturn("{\"access_token\":\"" + nearExpiryToken + "\"}");

            String result = retriever(null).retrieve();
            assertEquals(nearExpiryToken, result, "Near-expiry token should still be returned");
        }
    }

    // -------------------------------------------------------------------------
    // Request body shape
    // -------------------------------------------------------------------------

    @Test
    void testRetrieve_requestBodyContainsClientAssertion() throws Exception {
        String accessToken = jwtWithExp(System.currentTimeMillis() / 1000 + 3600);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenReturn("{\"access_token\":\"" + accessToken + "\"}");

            retriever(null).retrieve();

            String body = bodyCaptor.getValue();
            assertTrue(body.contains("grant_type=client_credentials"),
                    "Body must contain grant_type=client_credentials");
            assertTrue(body.contains("client_assertion_type="),
                    "Body must contain client_assertion_type");
            assertTrue(body.contains("jwt-bearer"),
                    "client_assertion_type must reference jwt-bearer");
            assertTrue(body.contains("client_assertion="),
                    "Body must contain client_assertion");
            assertTrue(body.contains(URLEncoder.encode(K8S_TOKEN_VALUE, "UTF-8")),
                    "client_assertion value must be the URL-encoded K8s token");
            assertFalse(body.contains("client_id="),
                    "Body must not contain client_id when not configured");
        }
    }

    @Test
    void testRetrieve_withOptionalClientId() throws Exception {
        String accessToken = jwtWithExp(System.currentTimeMillis() / 1000 + 3600);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        try (MockedStatic<HttpAccessTokenRetriever> mocked =
                     mockStatic(HttpAccessTokenRetriever.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> HttpAccessTokenRetriever.fetch(any(), any(), bodyCaptor.capture(), any(), any()))
                    .thenReturn("{\"access_token\":\"" + accessToken + "\"}");

            retriever("kafka-demo-app").retrieve();

            String body = bodyCaptor.getValue();
            assertTrue(body.contains("client_id=kafka-demo-app"),
                    "Body must contain client_id when configured");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String causeMessage(JwtRetrieverException ex) {
        Throwable cause = ex.getCause();
        return cause != null ? cause.getMessage() : ex.getMessage();
    }
}
