package io.confluent.oauth.azure.managedidentity;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.auth.SaslExtensions;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.apache.kafka.common.security.oauthbearer.internals.OAuthBearerClientInitialResponse;
import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenRetriever;
import org.apache.kafka.common.security.oauthbearer.internals.secured.AccessTokenValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthBearerLoginCallbackHandlerTest {
    private OAuthBearerLoginCallbackHandler handler;
    private AccessTokenRetriever retriever;
    private AccessTokenValidator validator;

    @BeforeEach
    void setUp() {
        handler = new OAuthBearerLoginCallbackHandler();
        retriever = mock(AccessTokenRetriever.class);
        validator = mock(AccessTokenValidator.class);
        handler.init(retriever, validator);
    }

    @Test
    void testInitSetsInitializedAndCallsRetrieverInit() throws IOException {
        AccessTokenRetriever retrieverMock = mock(AccessTokenRetriever.class);
        AccessTokenValidator validatorMock = mock(AccessTokenValidator.class);
        doNothing().when(retrieverMock).init();
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retrieverMock, validatorMock);
        verify(retrieverMock).init();
    }

    @Test
    void testInitThrowsOnRetrieverInitError() throws IOException {
        AccessTokenRetriever retrieverMock = mock(AccessTokenRetriever.class);
        AccessTokenValidator validatorMock = mock(AccessTokenValidator.class);
        doThrow(new IOException("fail")).when(retrieverMock).init();
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        assertThrows(KafkaException.class, () -> h.init(retrieverMock, validatorMock));
    }

    @Test
    void testHandleOAuthBearerTokenCallback() throws Exception {
        OAuthBearerTokenCallback cb = mock(OAuthBearerTokenCallback.class);
        OAuthBearerToken token = mock(OAuthBearerToken.class);
        when(retriever.retrieve()).thenReturn("token");
        when(validator.validate("token")).thenReturn(token);
        handler.handle(new Callback[]{cb});
        verify(cb).token(token);
    }

    @Test
    void testHandleSaslExtensionsCallbackValid() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("extension_test", "val");
        SaslExtensionsCallback cb = mock(SaslExtensionsCallback.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retriever, validator);
        // Use reflection to set moduleOptions
        var field = OAuthBearerLoginCallbackHandler.class.getDeclaredField("moduleOptions");
        field.setAccessible(true);
        field.set(h, config);
        h.handle(new Callback[]{cb});
        verify(cb).extensions(any(SaslExtensions.class));
    }

    @Test
    void testHandleSaslExtensionsCallbackInvalid() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("extension_test", ""); // invalid value
        SaslExtensionsCallback cb = mock(SaslExtensionsCallback.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retriever, validator);
        var field = OAuthBearerLoginCallbackHandler.class.getDeclaredField("moduleOptions");
        field.setAccessible(true);
        field.set(h, config);
        // Should throw ConfigException due to invalid extension
        assertThrows(ConfigException.class, () -> h.handle(new Callback[]{cb}));
    }

    @Test
    void testHandleUnsupportedCallback() {
        Callback cb = mock(Callback.class);
        assertThrows(UnsupportedCallbackException.class, () -> handler.handle(new Callback[]{cb}));
    }

    @Test
    void testHandleThrowsIfNotInitialized() {
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        Callback cb = mock(Callback.class);
        assertThrows(IllegalStateException.class, () -> h.handle(new Callback[]{cb}));
    }

    @Test
    void testCloseCallsRetrieverClose() throws Exception {
        AccessTokenRetriever retrieverMock = mock(AccessTokenRetriever.class);
        AccessTokenValidator validatorMock = mock(AccessTokenValidator.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retrieverMock, validatorMock);
        h.close();
        verify(retrieverMock).close();
    }
}

