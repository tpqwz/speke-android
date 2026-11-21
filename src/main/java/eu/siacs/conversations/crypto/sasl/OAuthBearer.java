package eu.siacs.conversations.crypto.sasl;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;

/**
 * RFC 7628 OAUTHBEARER. Only ever selected for an account without a password (see {@link
 * SaslMechanism.Factory}), and only on SASL2 where the server hands out a FAST token afterwards:
 * the bearer token is kept in memory and dropped once that token is stored.
 */
public class OAuthBearer extends SaslMechanism {

    public static final String MECHANISM = "OAUTHBEARER";

    private static final String STATUS_INVALID_TOKEN = "invalid_token";
    // the challenge is a small JSON document; anything bigger is not one
    private static final int MAX_CHALLENGE_LENGTH = 8 * 1024;
    private static final Gson GSON = new Gson();

    /** The server's error document (RFC 7628 §3.2.2). */
    public static final class Response {
        public String status;
        public String scope;

        @SerializedName("openid-configuration")
        public String openIdConfiguration;
    }

    protected OAuthBearer(final Account account) {
        super(account);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    @Override
    public String getClientFirstMessage(final SSLSocket sslSocket) {
        Preconditions.checkState(
                this.state == State.INITIAL, "Calling getClientFirstMessage from invalid state");
        this.state = State.AUTH_TEXT_SENT;
        return BaseEncoding.base64()
                .encode(
                        clientFirstMessage(account.getUsername(), account.getOauthAccessToken())
                                .getBytes(StandardCharsets.UTF_8));
    }

    static String clientFirstMessage(final String username, final String accessToken) {
        final var a = CryptoHelper.saslEscape(CryptoHelper.saslPrep(username));
        if (Strings.isNullOrEmpty(accessToken)) {
            // no token yet: this provokes the error document that names the identity provider
            return String.format("n,a=%s,\u0001auth=\u0001\u0001", a);
        }
        return String.format("n,a=%s,\u0001auth=Bearer %s\u0001\u0001", a, accessToken);
    }

    @Override
    public String getResponse(final String challenge, final SSLSocket sslSocket)
            throws AuthenticationException {
        checkState(State.AUTH_TEXT_SENT);
        if (Strings.isNullOrEmpty(challenge)) {
            this.state = State.VALID_SERVER_RESPONSE;
            return null;
        }
        throw failure(challenge);
    }

    /** Turns the server's (base64) error document into the matching exception. */
    static AuthenticationException failure(final String challenge) {
        final Response response;
        try {
            if (challenge.length() > MAX_CHALLENGE_LENGTH) {
                return new AuthenticationException("OAuth challenge too long");
            }
            final var json =
                    new String(BaseEncoding.base64().decode(challenge), StandardCharsets.UTF_8);
            response = GSON.fromJson(json, Response.class);
        } catch (final IllegalArgumentException | JsonParseException e) {
            return new AuthenticationException("Invalid OAuth challenge", e);
        }
        if (response == null || Strings.isNullOrEmpty(response.openIdConfiguration)) {
            return new AuthenticationException(
                    "SASL response did not contain OpenId configuration");
        }
        if (STATUS_INVALID_TOKEN.equals(response.status)) {
            return new InvalidTokenException(response);
        }
        return new AuthenticationException("Unrecognized OAuth status");
    }

    /** The server wants a (new) token and told us where the identity provider is. */
    public static class InvalidTokenException extends AuthenticationException {
        private final Response response;

        public InvalidTokenException(final Response response) {
            super("OAuth authentication failed");
            this.response = response;
        }

        public Response getResponse() {
            return this.response;
        }
    }
}
