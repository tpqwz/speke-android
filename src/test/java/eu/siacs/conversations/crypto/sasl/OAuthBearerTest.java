package eu.siacs.conversations.crypto.sasl;

import com.google.common.io.BaseEncoding;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

public class OAuthBearerTest {

    private static String b64(final String s) {
        return BaseEncoding.base64().encode(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void firstMessageWithoutTokenAsksForTheProvider() {
        Assert.assertEquals(
                "n,a=alice,\u0001auth=\u0001\u0001", OAuthBearer.clientFirstMessage("alice", null));
        Assert.assertEquals(
                "n,a=alice,\u0001auth=\u0001\u0001", OAuthBearer.clientFirstMessage("alice", ""));
    }

    @Test
    public void firstMessageCarriesTheBearer() {
        Assert.assertEquals(
                "n,a=alice,\u0001auth=Bearer abc.def\u0001\u0001",
                OAuthBearer.clientFirstMessage("alice", "abc.def"));
    }

    /** A username must not be able to add gs2 header fields. */
    @Test
    public void usernameIsEscaped() {
        Assert.assertEquals(
                "n,a=a=2Cb=3Dc,\u0001auth=\u0001\u0001",
                OAuthBearer.clientFirstMessage("a,b=c", null));
    }

    @Test
    public void invalidTokenNamesTheProvider() {
        final var e =
                OAuthBearer.failure(
                        b64(
                                "{\"status\":\"invalid_token\",\"scope\":\"openid\","
                                    + "\"openid-configuration\":\"https://idp.example.com/.well-known/openid-configuration\"}"));
        Assert.assertTrue(e instanceof OAuthBearer.InvalidTokenException);
        Assert.assertEquals(
                "https://idp.example.com/.well-known/openid-configuration",
                ((OAuthBearer.InvalidTokenException) e).getResponse().openIdConfiguration);
    }

    @Test
    public void anythingElseIsAPlainFailure() {
        final String[] challenges = {
            "not base64 !!",
            b64("not json"),
            b64("[]"),
            b64("null"),
            b64("{}"),
            b64("{\"status\":\"invalid_token\"}"),
            b64(
                    "{\"status\":\"insufficient_scope\",\"openid-configuration\":\"https://idp.example.com/c\"}"),
            b64("{\"status\":\"invalid_token\",\"openid-configuration\":{\"a\":1}}"),
        };
        for (final String challenge : challenges) {
            final var e = OAuthBearer.failure(challenge);
            Assert.assertFalse(challenge, e instanceof OAuthBearer.InvalidTokenException);
        }
    }
}
