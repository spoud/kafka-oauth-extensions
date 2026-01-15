package io.confluent.oauth.azure.managedidentity;

import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.security.auth.SaslExtensions;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.apache.kafka.common.security.oauthbearer.JwtRetriever;
import org.apache.kafka.common.security.oauthbearer.JwtValidator;
import org.apache.kafka.common.security.oauthbearer.JwtValidatorException;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthBearerLoginCallbackHandlerTest {
    private OAuthBearerLoginCallbackHandler handler;
    private JwtRetriever retriever;
    private JwtValidator validator;

    @BeforeEach
    void setUp() {
        handler = new OAuthBearerLoginCallbackHandler();
        retriever = mock(JwtRetriever.class);
        validator = mock(JwtValidator.class);
        handler.init(retriever, validator);
    }

    @Test
    void testInitSetsInitialized() {
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retriever, validator);
        assertNotNull(h.getJwtRetriever());
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
    void testHandleOAuthBearerTokenCallbackValidatorError() throws Exception {
        OAuthBearerTokenCallback cb = mock(OAuthBearerTokenCallback.class);
        when(retriever.retrieve()).thenReturn("token");
        when(validator.validate("token")).thenThrow(new JwtValidatorException("fail", null));
        handler.handle(new Callback[]{cb});
        verify(cb).error(any(), any(), any());
    }

    @Test
    void testHandleSaslExtensionsCallbackValid() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("extension_test", "val");
        SaslExtensionsCallback cb = mock(SaslExtensionsCallback.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retriever, validator);
        var field = OAuthBearerLoginCallbackHandler.class.getDeclaredField("moduleOptions");
        field.setAccessible(true);
        field.set(h, config);
        h.handle(new Callback[]{cb});
        verify(cb).extensions(any(SaslExtensions.class));
    }

    @Test
    void testHandleSaslExtensionsCallbackInvalid() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("extension_test", "");
        SaslExtensionsCallback cb = mock(SaslExtensionsCallback.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retriever, validator);
        var field = OAuthBearerLoginCallbackHandler.class.getDeclaredField("moduleOptions");
        field.setAccessible(true);
        field.set(h, config);
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
        JwtRetriever retrieverMock = mock(JwtRetriever.class);
        OAuthBearerLoginCallbackHandler h = new OAuthBearerLoginCallbackHandler();
        h.init(retrieverMock, validator);
        h.close();
        verify(retrieverMock).close();
    }
}
