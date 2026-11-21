package eu.siacs.conversations.xmpp;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.crypto.TrustManagers;
import eu.siacs.conversations.crypto.sasl.OAuthBearer;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.utils.Random;
import eu.siacs.conversations.utils.TLSSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * The OpenID Connect side of an OAUTHBEARER login: reads the identity provider's configuration,
 * registers this client (RFC 7591), builds the authorization URL (authorization code flow with
 * PKCE, RFC 7636) and exchanges the code from the redirect for an access token.
 *
 * <p>Everything here is driven by URLs the XMPP server and the identity provider hand us, so all of
 * them must be https and must not point at this device or the local network, redirects are not
 * followed, responses are size limited, and requests follow the account's Tor/I2P routing.
 */
public class OAuthAuthorization {

    public static final String REDIRECT_HOST = "oauth-redirect";
    public static final String REDIRECT_URI =
            String.format("%s://%s", BuildConfig.APPLICATION_ID, REDIRECT_HOST);

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_PENDING = 4;
    private static final Executor EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Gson GSON = new Gson();

    /** Every state value handed to a browser and not yet redeemed, across all accounts. */
    private static final Set<String> ISSUED_STATES = ConcurrentHashMap.newKeySet();

    static final class OpenIdConfiguration {
        @SerializedName("authorization_endpoint")
        String authorizationEndpoint;

        @SerializedName("token_endpoint")
        String tokenEndpoint;

        @SerializedName("registration_endpoint")
        String registrationEndpoint;
    }

    static final class ClientRegistration {
        @SerializedName("client_name")
        final String clientName = BuildConfig.APP_NAME;

        @SerializedName("client_uri")
        final String clientUri = "https://monocles.eu";

        @SerializedName("application_type")
        final String applicationType = "native";

        @SerializedName("redirect_uris")
        final List<String> redirectUris = ImmutableList.of(REDIRECT_URI);
    }

    static final class ClientRegistrationResponse {
        @SerializedName("client_id")
        String clientId;

        @SerializedName("client_secret")
        String clientSecret;

        @SerializedName("token_endpoint_auth_method")
        String tokenEndpointAuthMethod;

        @SerializedName("redirect_uris")
        List<String> redirectUris;
    }

    public static final class TokenResponse {
        @SerializedName("access_token")
        public String accessToken;

        @SerializedName("token_type")
        public String tokenType;
    }

    /** One authorization request that is waiting for the user in the browser. */
    public static final class PendingAuthorization {
        public final HttpUrl authorizationUrl;
        final String state;
        final String codeVerifier;
        final HttpUrl tokenEndpoint;
        final String clientId;
        final String clientSecret;
        final String authMethod;

        PendingAuthorization(
                final HttpUrl authorizationUrl,
                final String state,
                final String codeVerifier,
                final HttpUrl tokenEndpoint,
                final String clientId,
                final String clientSecret,
                final String authMethod) {
            this.authorizationUrl = authorizationUrl;
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.tokenEndpoint = tokenEndpoint;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.authMethod = authMethod;
        }
    }

    public static final class OAuthFailedException extends Exception {
        public OAuthFailedException(final String message) {
            super(message);
        }
    }

    private final Context context;
    private final Jid account;
    // Read per request: the XmppConnection (and with it this object) outlives a Tor/I2P toggle.
    private final BooleanSupplier tor;
    private final BooleanSupplier i2p;
    // Peer address of the current XMPP connection, only for direct (unproxied) TCP connections.
    private final Supplier<InetAddress> serverAddress;
    // Insertion ordered. A reconnect while the browser is open prepares another authorization;
    // the one the user is completing has to stay redeemable, so several can be pending.
    private final Map<String, PendingAuthorization> pendingAuthorizations = new LinkedHashMap<>();

    public OAuthAuthorization(
            final Context context,
            final Jid account,
            final BooleanSupplier tor,
            final BooleanSupplier i2p,
            final Supplier<InetAddress> serverAddress) {
        this.context = context.getApplicationContext();
        this.account = account.asBareJid();
        this.tor = tor;
        this.i2p = i2p;
        this.serverAddress = serverAddress;
    }

    /**
     * The XMPP server's address if (and only if) we are connected to it directly and it is in
     * the local network. Read per request, so a reconnect over another network decides anew.
     */
    @Nullable
    private InetAddress localServer() {
        if (tor.getAsBoolean() || i2p.getAsBoolean() || serverAddress == null) {
            return null;
        }
        final InetAddress address = serverAddress.get();
        return address != null && HttpConnectionManager.isLocalAddress(address) ? address : null;
    }

    // ---- pure helpers (unit tested) ----

    /**
     * Whether OAuth may talk to {@code target}. Public addresses always; local ones (loopback,
     * private, link-local, ULA, CGNAT) only while we are directly connected to an XMPP server
     * that is itself in the local network (upstream b1c81fdc0c, self-hosted IdP use case): such a
     * server can reach that network anyway, so sending our requests there gives it nothing new.
     * The device's own loopback is only for a server on loopback, since a LAN server could not
     * reach it; multicast and the unspecified address never.
     *
     * @param server the XMPP server's address, null when connected over Tor/I2P/a proxy
     */
    static boolean isAllowedTarget(final InetAddress target, @Nullable final InetAddress server) {
        if (!HttpConnectionManager.isLocalAddress(target)) {
            return true;
        }
        if (server == null || !HttpConnectionManager.isLocalAddress(server)) {
            return false;
        }
        if (target.isAnyLocalAddress() || target.isMulticastAddress()) {
            return false;
        }
        if (target.isLoopbackAddress()) {
            return server.isLoopbackAddress();
        }
        return true;
    }

    /** Same policy as {@link #isAllowedTarget} for a URL host (name or literal address). */
    static boolean isAllowedHost(final String rawHost, @Nullable final InetAddress server) {
        if (!HttpConnectionManager.isLocalTarget(rawHost)) {
            return true;
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (com.google.common.net.InetAddresses.isInetAddress(host)) {
            return isAllowedTarget(com.google.common.net.InetAddresses.forString(host), server);
        }
        if (host.matches("[0-9.]+")) {
            // shorthand numeric IPv4 ("127.1"): no canonical address to check, never allowed
            return false;
        }
        if (server == null || !HttpConnectionManager.isLocalAddress(server)) {
            return false;
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return server.isLoopbackAddress();
        }
        // .local (mDNS); what it resolves to is checked again by the DNS guard
        return true;
    }

    /** An endpoint we are willing to talk to or send the user to: https and not local. */
    static HttpUrl requireEndpoint(final String url, final String what) {
        return requireEndpoint(url, what, null);
    }

    /**
     * An endpoint we are willing to talk to or send the user to: https, and not local unless
     * {@link #isAllowedHost} allows it for this XMPP server.
     */
    static HttpUrl requireEndpoint(
            final String url, final String what, @Nullable final InetAddress server) {
        final HttpUrl parsed = Strings.isNullOrEmpty(url) ? null : HttpUrl.parse(url);
        if (parsed == null) {
            throw new IllegalStateException("Missing or invalid " + what);
        }
        if (!parsed.isHttps()) {
            throw new IllegalStateException(what + " is not https");
        }
        if (!isAllowedHost(parsed.host(), server)) {
            throw new IllegalStateException(what + " points to a local address");
        }
        if (!parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new IllegalStateException(what + " carries credentials");
        }
        return parsed;
    }

    /** {@code base64url(bare jid || 0x00 || random)}: lets the redirect find its account. */
    static String buildState(final Jid account, final byte[] random) {
        return BaseEncoding.base64Url()
                .omitPadding()
                .encode(
                        Bytes.concat(
                                account.asBareJid().toString().getBytes(StandardCharsets.UTF_8),
                                new byte[] {0x00},
                                random));
    }

    /** The account a state value was issued for, or null if it is not one of ours. */
    public static Jid accountFromState(final String state) {
        if (Strings.isNullOrEmpty(state) || state.length() > 4096) {
            return null;
        }
        final byte[] bytes;
        try {
            bytes = BaseEncoding.base64Url().omitPadding().decode(state);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final int nullIndex = Bytes.indexOf(bytes, (byte) 0x00);
        if (nullIndex <= 0) {
            return null;
        }
        try {
            return Jid.of(new String(bytes, 0, nullIndex, StandardCharsets.UTF_8)).asBareJid();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** RFC 7636 S256 code challenge. */
    static String codeChallenge(final String codeVerifier) {
        return BaseEncoding.base64Url()
                .omitPadding()
                .encode(
                        Hashing.sha256()
                                .hashString(codeVerifier, StandardCharsets.US_ASCII)
                                .asBytes());
    }

    /** Whether {@code uri} is our redirect and its state is one we handed to a browser. */
    public static boolean isExpectedRedirect(final Uri uri) {
        if (!isRedirect(uri)) {
            return false;
        }
        final String state = uri.getQueryParameter("state");
        return state != null && ISSUED_STATES.contains(state);
    }

    public static boolean isRedirect(final Uri uri) {
        return uri != null
                && uri.isHierarchical()
                && BuildConfig.APPLICATION_ID.equals(uri.getScheme())
                && REDIRECT_HOST.equals(uri.getAuthority());
    }

    static PendingAuthorization buildPendingAuthorization(
            final Jid account,
            final OpenIdConfiguration configuration,
            final ClientRegistrationResponse registration,
            final byte[] stateBytes,
            final byte[] verifierBytes) {
        return buildPendingAuthorization(
                account, configuration, registration, stateBytes, verifierBytes, null);
    }

    static PendingAuthorization buildPendingAuthorization(
            final Jid account,
            final OpenIdConfiguration configuration,
            final ClientRegistrationResponse registration,
            final byte[] stateBytes,
            final byte[] verifierBytes,
            @Nullable final InetAddress server) {
        if (registration == null || Strings.isNullOrEmpty(registration.clientId)) {
            throw new IllegalStateException("Invalid client id in registration");
        }
        if (registration.redirectUris != null
                && !registration.redirectUris.isEmpty()
                && !registration.redirectUris.contains(REDIRECT_URI)) {
            // never let the provider swap in a redirect target of its choosing
            throw new IllegalStateException("Registration changed the redirect URI");
        }
        final HttpUrl authorizationEndpoint =
                requireEndpoint(
                        configuration.authorizationEndpoint, "authorization endpoint", server);
        final HttpUrl tokenEndpoint =
                requireEndpoint(configuration.tokenEndpoint, "token endpoint", server);
        final String state = buildState(account, stateBytes);
        final String codeVerifier = BaseEncoding.base64Url().omitPadding().encode(verifierBytes);
        final HttpUrl authorizationUrl =
                authorizationEndpoint
                        .newBuilder()
                        .addQueryParameter("response_type", "code")
                        .addQueryParameter("client_id", registration.clientId)
                        .addQueryParameter("redirect_uri", REDIRECT_URI)
                        .addQueryParameter("scope", "openid")
                        .addQueryParameter("state", state)
                        .addQueryParameter("code_challenge", codeChallenge(codeVerifier))
                        .addQueryParameter("code_challenge_method", "S256")
                        .addQueryParameter("prompt", "login")
                        .addQueryParameter("login_hint", account.getLocal())
                        .build();
        return new PendingAuthorization(
                authorizationUrl,
                state,
                codeVerifier,
                tokenEndpoint,
                registration.clientId,
                registration.clientSecret,
                registration.tokenEndpointAuthMethod);
    }

    // ---- flow ----

    public PendingAuthorization getPendingAuthorization() {
        synchronized (this.pendingAuthorizations) {
            PendingAuthorization latest = null;
            for (final PendingAuthorization pending : this.pendingAuthorizations.values()) {
                latest = pending;
            }
            return latest;
        }
    }

    public ListenableFuture<PendingAuthorization> preparePendingAuthorization(
            final OAuthBearer.Response response) {
        final HttpUrl configurationUrl;
        try {
            configurationUrl =
                    requireEndpoint(
                            response.openIdConfiguration, "OpenID configuration", localServer());
        } catch (final IllegalStateException e) {
            return Futures.immediateFailedFuture(e);
        }
        final var configurationFuture =
                fetchJson(
                        new Request.Builder().url(configurationUrl).build(),
                        OpenIdConfiguration.class);
        return Futures.transformAsync(
                configurationFuture,
                configuration -> {
                    Objects.requireNonNull(configuration);
                    // validated before anything is sent to them
                    final InetAddress server = localServer();
                    requireEndpoint(
                            configuration.authorizationEndpoint, "authorization endpoint", server);
                    requireEndpoint(configuration.tokenEndpoint, "token endpoint", server);
                    final HttpUrl registrationEndpoint =
                            requireEndpoint(
                                    configuration.registrationEndpoint,
                                    "registration endpoint",
                                    server);
                    final var request =
                            new Request.Builder()
                                    .url(registrationEndpoint)
                                    .post(
                                            RequestBody.create(
                                                    GSON.toJson(new ClientRegistration()),
                                                    MediaType.get("application/json")))
                                    .build();
                    return Futures.transform(
                            fetchJson(request, ClientRegistrationResponse.class),
                            registration -> {
                                final byte[] stateBytes = new byte[32];
                                Random.SECURE_RANDOM.nextBytes(stateBytes);
                                final byte[] verifierBytes = new byte[32];
                                Random.SECURE_RANDOM.nextBytes(verifierBytes);
                                final var pending =
                                        buildPendingAuthorization(
                                                account,
                                                configuration,
                                                registration,
                                                stateBytes,
                                                verifierBytes,
                                                server);
                                synchronized (this.pendingAuthorizations) {
                                    while (this.pendingAuthorizations.size() >= MAX_PENDING) {
                                        final String eldest =
                                                this.pendingAuthorizations
                                                        .keySet()
                                                        .iterator()
                                                        .next();
                                        this.pendingAuthorizations.remove(eldest);
                                        ISSUED_STATES.remove(eldest);
                                    }
                                    this.pendingAuthorizations.put(pending.state, pending);
                                    ISSUED_STATES.add(pending.state);
                                }
                                return pending;
                            },
                            EXECUTOR);
                },
                EXECUTOR);
    }

    /** Redeems the redirect the browser sent us. Each pending authorization works once. */
    public ListenableFuture<TokenResponse> processRedirect(final Uri uri) {
        if (!isRedirect(uri)) {
            return Futures.immediateFailedFuture(new IllegalStateException("not a redirect"));
        }
        final String state = uri.getQueryParameter("state");
        final Jid stateAccount = accountFromState(state);
        if (stateAccount == null || !this.account.equals(stateAccount)) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("state missing or not for this account"));
        }
        final PendingAuthorization pending;
        synchronized (this.pendingAuthorizations) {
            // a state is redeemable once, whatever the outcome
            pending = this.pendingAuthorizations.remove(state);
            ISSUED_STATES.remove(state);
        }
        if (pending == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("no pending authorization found"));
        }
        final String code = uri.getQueryParameter("code");
        if (Strings.isNullOrEmpty(code)) {
            final String error = Strings.nullToEmpty(uri.getQueryParameter("error"));
            return Futures.immediateFailedFuture(
                    new OAuthFailedException(
                            "OAuth failed" + (error.isEmpty() ? "" : " with " + sanitize(error))));
        }
        final var future = exchangeAuthorizationCode(pending, code);
        // one way or the other this login attempt is over; drop what else was prepared for it
        future.addListener(this::clear, MoreExecutors.directExecutor());
        return future;
    }

    public void clear() {
        synchronized (this.pendingAuthorizations) {
            clearPendingLocked();
        }
    }

    private void clearPendingLocked() {
        ISSUED_STATES.removeAll(this.pendingAuthorizations.keySet());
        this.pendingAuthorizations.clear();
    }

    private static String sanitize(final String value) {
        final String printable = value.replaceAll("[^A-Za-z0-9_.-]", "?");
        return printable.length() > 64 ? printable.substring(0, 64) : printable;
    }

    private ListenableFuture<TokenResponse> exchangeAuthorizationCode(
            final PendingAuthorization pending, final String code) {
        final var form =
                new FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI)
                        .add("code_verifier", pending.codeVerifier);
        final var request =
                new Request.Builder()
                        .url(pending.tokenEndpoint)
                        .header("Accept", "application/json");
        final String secret = Strings.nullToEmpty(pending.clientSecret);
        if ("client_secret_basic".equals(pending.authMethod)) {
            request.header("Authorization", Credentials.basic(pending.clientId, secret));
        } else {
            form.add("client_id", pending.clientId);
            if ("client_secret_post".equals(pending.authMethod) && !secret.isEmpty()) {
                form.add("client_secret", secret);
            }
        }
        return Futures.transform(
                fetchJson(request.post(form.build()).build(), TokenResponse.class),
                token -> {
                    if (token == null || Strings.isNullOrEmpty(token.accessToken)) {
                        throw new IllegalStateException("Token response without access token");
                    }
                    if (!Strings.isNullOrEmpty(token.tokenType)
                            && !"bearer".equalsIgnoreCase(token.tokenType)) {
                        throw new IllegalStateException("Token is not a bearer token");
                    }
                    // the token goes into a SASL message framed by 0x01 and commas
                    if (!token.accessToken.matches("[A-Za-z0-9\\-._~+/]+=*")) {
                        throw new IllegalStateException("Token is not a valid bearer token");
                    }
                    return token;
                },
                MoreExecutors.directExecutor());
    }

    private OkHttpClient client() throws Exception {
        final boolean tor = this.tor.getAsBoolean();
        final boolean i2p = this.i2p.getAsBoolean();
        final OkHttpClient.Builder builder = HttpConnectionManager.newBuilder(tor, i2p);
        final X509TrustManager trustManager = TrustManagers.createDefaultTrustManager();
        builder.sslSocketFactory(
                new TLSSocketFactory(new X509TrustManager[] {trustManager}, context), trustManager);
        // a redirect could lead to http or into the local network
        builder.followRedirects(false).followSslRedirects(false);
        builder.connectTimeout(tor || i2p ? 10 : 4, TimeUnit.SECONDS);
        builder.callTimeout(tor || i2p ? 20 : 8, TimeUnit.SECONDS);
        builder.dns(hostname -> {
            // same guard as HttpConnectionManager.applyProxyPolicy: never resolve
            // locally what has to go through Tor/I2P
            if (HttpConnectionManager.route(hostname, tor, i2p)
                    != HttpConnectionManager.Route.DIRECT) {
                throw new UnknownHostException("refusing local DNS lookup for " + hostname);
            }
            final List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            final InetAddress server = localServer();
            for (final InetAddress address : addresses) {
                if (!isAllowedTarget(address, server)) {
                    throw new UnknownHostException(
                            "Not connecting to local address for " + hostname);
                }
            }
            return addresses;
        });
        return builder.build();
    }

    private <T> ListenableFuture<T> fetchJson(final Request request, final Class<T> type) {
        final OkHttpClient client;
        try {
            client = client();
        } catch (final Exception e) {
            return Futures.immediateFailedFuture(e);
        }
        final SettableFuture<T> future = SettableFuture.create();
        client.newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                future.setException(e);
                            }

                            @Override
                            public void onResponse(
                                    @NonNull Call call, @NonNull okhttp3.Response response) {
                                try (response) {
                                    if (!response.isSuccessful()) {
                                        throw new IOException(
                                                String.format(
                                                        Locale.ROOT,
                                                        "Unexpected code %d",
                                                        response.code()));
                                    }
                                    final var source = response.body().source();
                                    if (source.request(MAX_RESPONSE_BYTES + 1L)) {
                                        throw new IOException("Response too large");
                                    }
                                    final T parsed =
                                            GSON.fromJson(source.getBuffer().readUtf8(), type);
                                    if (parsed == null) {
                                        throw new IOException("Empty response");
                                    }
                                    future.set(parsed);
                                } catch (final IOException | JsonParseException e) {
                                    future.setException(e);
                                } catch (final RuntimeException e) {
                                    future.setException(e);
                                }
                            }
                        });
        return future;
    }
}
