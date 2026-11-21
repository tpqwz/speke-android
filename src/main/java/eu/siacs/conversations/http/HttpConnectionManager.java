package eu.siacs.conversations.http;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.core.util.Consumer;
import com.google.common.base.Supplier;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.TrustManagers;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.utils.TLSSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.apache.http.conn.ssl.StrictHostnameVerifier;

public class HttpConnectionManager extends AbstractConnectionManager {

    private final List<HttpDownloadConnection> downloadConnections = new ArrayList<>();
    private final List<HttpUploadConnection> uploadConnections = new ArrayList<>();

    public static final Executor EXECUTOR = Executors.newFixedThreadPool(4);

    private static final OkHttpClient OK_HTTP_CLIENT;

    static {
        OK_HTTP_CLIENT =
                new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            final Request original = chain.request();
                            // a caller may pick its own (see LINK_PREVIEW_USER_AGENT)
                            if (original.header("User-Agent") != null)
                                return chain.proceed(original);
                            final Request modified =
                                    original.newBuilder()
                                            .header("User-Agent", getUserAgent())
                                            .build();
                            return chain.proceed(modified);
                        })
                        .build();
    }

    public static String getUserAgent() {
        return String.format("%s/%s", BuildConfig.APP_NAME, BuildConfig.VERSION_NAME);
    }

    public HttpConnectionManager(XmppConnectionService service) {
        super(service);
    }

    public static Proxy getProxy(boolean isI2P) {
        final InetAddress localhost;
        try {
            localhost = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new Proxy(
                    Proxy.Type.SOCKS, new InetSocketAddress(localhost, isI2P ? 4447 : 9050));
        } else {
            return new Proxy(
                    Proxy.Type.HTTP, new InetSocketAddress(localhost, isI2P ? 4444 : 8118));
        }
    }

    public static boolean isOnionHost(final String host) {
        return normalizeHost(host).endsWith(".onion");
    }

    public static boolean isI2PHost(final String host) {
        return normalizeHost(host).endsWith(".i2p");
    }

    private static String normalizeHost(final String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /**
     * Localhost names and loopback, link-local, private (RFC 1918, ULA, CGNAT), unspecified and
     * multicast address literals. WebView hands shouldInterceptRequest canonical URLs, so numeric
     * forms like {@code 2130706433} arrive as {@code 127.0.0.1}. Names that merely resolve to a
     * local address are not caught here (no DNS lookup): link previews check the resolved addresses
     * as well, see {@link #executeLinkPreviewRequest}.
     */
    public static boolean isLocalTarget(final String rawHost) {
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        if (!com.google.common.net.InetAddresses.isInetAddress(host)) {
            // Shorthand numeric IPv4 forms ("127.1", "127.0.1", "2130706433") are not literals to
            // Guava, but OkHttp connects to any [0-9.]+ host via InetAddress.getByName without
            // asking our Dns, and that resolves them to e.g. 127.0.0.1. There is no canonical
            // address to check, so treat them as local (refused).
            return host.matches("[0-9.]+");
        }
        return isLocalAddress(com.google.common.net.InetAddresses.forString(host));
    }

    /**
     * Loopback, link-local, private (RFC 1918, ULA, CGNAT), unspecified and multicast addresses,
     * including IPv4-mapped IPv6 forms of them.
     */
    public static boolean isLocalAddress(final InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        final byte[] bytes = address.getAddress();
        if (address instanceof java.net.Inet4Address) {
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // 100.64.0.0/10 (CGNAT)
        }
        if (address instanceof java.net.Inet6Address) {
            if ((bytes[0] & 0xfe) == 0xfc) return true; // fc00::/7 (ULA)
            boolean mapped = bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
            for (int i = 0; i < 10 && mapped; i++) mapped = bytes[i] == 0;
            if (mapped) {
                try {
                    return isLocalAddress(
                            InetAddress.getByAddress(
                                    new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (final UnknownHostException e) {
                    return true; // can't happen for 4 bytes; refuse rather than allow
                }
            }
        }
        return false;
    }

    /** Which network a connection to a given host must use. */
    public enum Route {
        TOR,
        I2P,
        DIRECT
    }

    /**
     * The single routing policy for every outgoing connection (HTTP, XMPP, SOCKS5 bytestreams).
     * Hidden-service hosts always go to their own overlay, whatever the settings say: a .onion/.i2p
     * name must never reach the system resolver or a clearnet socket. Everything else goes through
     * Tor when enabled (Tor wins when both are on: it is built for clearnet, I2P outproxies are
     * not), otherwise through the I2P outproxy when I2P is enabled, otherwise direct.
     */
    public static Route route(final String host, final boolean tor, final boolean i2p) {
        if (isOnionHost(host)) return Route.TOR;
        if (isI2PHost(host)) return Route.I2P;
        if (tor) return Route.TOR;
        if (i2p) return Route.I2P;
        return Route.DIRECT;
    }

    /**
     * {@link #route} as an OkHttp proxy list. Never returns an empty list, so OkHttp never falls
     * back to a direct connection when the proxy is unreachable (fail-closed). For SOCKS proxies
     * OkHttp hands the proxy an unresolved address, so DNS happens inside Tor/I2P.
     */
    public static List<Proxy> selectProxies(
            final String host, final boolean tor, final boolean i2p) {
        return switch (route(host, tor, i2p)) {
            case TOR -> Collections.singletonList(getProxy(false));
            case I2P -> Collections.singletonList(getProxy(true));
            case DIRECT -> Collections.singletonList(Proxy.NO_PROXY);
        };
    }

    /**
     * ProxySelector applying {@link #selectProxies}. OkHttp consults it for every connection,
     * including each redirect hop, so a redirect to (or from) a .onion/.i2p host is re-routed
     * correctly. The suppliers are read per request so live preference changes apply.
     *
     * <p>Use {@link #applyProxyPolicy} instead of installing this alone: OkHttp skips the selector
     * for URLs java.net.URI cannot parse a host from.
     */
    public static ProxySelector proxySelector(
            final Supplier<Boolean> tor, final Supplier<Boolean> i2p) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(final URI uri) {
                final String host = uri == null ? null : uri.getHost();
                return selectProxies(
                        host, Boolean.TRUE.equals(tor.get()), Boolean.TRUE.equals(i2p.get()));
            }

            @Override
            public void connectFailed(
                    final URI uri, final SocketAddress sa, final IOException ioe) {}
        };
    }

    /**
     * Installs the routing policy on an OkHttp builder: the host-aware {@link #proxySelector} plus
     * a guard {@link Dns}. OkHttp bypasses the ProxySelector and connects directly when {@code
     * url.toUri().getHost()} is null (e.g. hosts with an underscore, which HttpUrl accepts but
     * java.net.URI does not). Such a direct connection would need a local DNS lookup, so the guard
     * refuses every lookup that the policy would not have made directly. Proxied routes never
     * consult {@code Dns} (SOCKS gets an unresolved address, the HTTP proxy is an IP literal), so
     * normal traffic is unaffected.
     */
    public static OkHttpClient.Builder applyProxyPolicy(
            final OkHttpClient.Builder builder,
            final Supplier<Boolean> tor,
            final Supplier<Boolean> i2p) {
        builder.proxySelector(proxySelector(tor, i2p));
        builder.dns(hostname -> {
            if (route(hostname, Boolean.TRUE.equals(tor.get()), Boolean.TRUE.equals(i2p.get()))
                    != Route.DIRECT) {
                throw new UnknownHostException(
                        "refusing local DNS lookup for " + hostname + " (Tor/I2P routing)");
            }
            return Dns.SYSTEM.lookup(hostname);
        });
        return builder;
    }

    public void createNewDownloadConnection(Message message) {
        this.createNewDownloadConnection(message, false);
    }

    public void createNewDownloadConnection(final Message message, boolean interactive) {
        createNewDownloadConnection(message, interactive, null);
    }

    public void createNewDownloadConnection(
            final Message message, boolean interactive, Consumer<DownloadableFile> cb) {
        synchronized (this.downloadConnections) {
            for (HttpDownloadConnection connection : this.downloadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(
                            Config.LOGTAG,
                            message.getConversation().getAccount().getJid().asBareJid()
                                    + ": download already in progress");
                    return;
                }
            }
            final HttpDownloadConnection connection = new HttpDownloadConnection(message, this, cb);
            connection.init(interactive);
            this.downloadConnections.add(connection);
        }
    }

    public void createNewUploadConnection(final Message message, boolean delay) {
        createNewUploadConnection(message, delay, null);
    }

    public void createNewUploadConnection(final Message message, boolean delay, final Runnable cb) {
        synchronized (this.uploadConnections) {
            for (HttpUploadConnection connection : this.uploadConnections) {
                if (connection.getMessage() == message) {
                    Log.d(
                            Config.LOGTAG,
                            message.getConversation().getAccount().getJid().asBareJid()
                                    + ": upload already in progress");
                    return;
                }
            }
            HttpUploadConnection connection =
                    new HttpUploadConnection(
                            message,
                            Method.determine(message.getConversation().getAccount()),
                            this,
                            cb);
            connection.initForMessage(delay);
            this.uploadConnections.add(connection);
        }
    }

    public void createNewUploadConnection(
            final DownloadableFile file,
            final Account account,
            final boolean delay,
            final UiCallback<String> callback) {
        synchronized (this.uploadConnections) {
            HttpUploadConnection connection =
                    new HttpUploadConnection(
                            account, file, Method.determine(account), this, callback);
            connection.initForFile();
            this.uploadConnections.add(connection);
        }
    }

    void finishConnection(HttpDownloadConnection connection) {
        synchronized (this.downloadConnections) {
            this.downloadConnections.remove(connection);
        }
    }

    void finishUploadConnection(HttpUploadConnection httpUploadConnection) {
        synchronized (this.uploadConnections) {
            this.uploadConnections.remove(httpUploadConnection);
        }
    }

    public OkHttpClient buildHttpClient(
            final HttpUrl url, final Account account, boolean interactive) {
        return buildHttpClient(url, account, 30, interactive);
    }

    public OkHttpClient buildHttpClient(
            final HttpUrl url, final Account account, int readTimeout, boolean interactive) {
        // .onion/.i2p hosts (the slot itself or any redirect target) are routed by the
        // host-aware ProxySelector installed in newBuilder().
        final OkHttpClient.Builder builder =
                newBuilder(
                        mXmppConnectionService.useTorToConnect() || account.isOnion(),
                        mXmppConnectionService.useI2PToConnect() || account.isI2P());
        builder.readTimeout(readTimeout, TimeUnit.SECONDS);
        setupTrustManager(builder, interactive);
        return builder.build();
    }

    private void setupTrustManager(final OkHttpClient.Builder builder, final boolean interactive) {
        final X509TrustManager trustManager;
        if (interactive) {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getInteractive();
        } else {
            trustManager = mXmppConnectionService.getMemorizingTrustManager().getNonInteractive();
        }
        try {
            final SSLSocketFactory sf =
                    new TLSSocketFactory(
                            new X509TrustManager[] {trustManager}, mXmppConnectionService);
            builder.sslSocketFactory(sf, trustManager);
            builder.hostnameVerifier(new StrictHostnameVerifier());
        } catch (final KeyManagementException | NoSuchAlgorithmException ignored) {
        }
    }

    /**
     * The client for link previews, derived from {@link #buildHttpClient}'s. {@code callTimeout}
     * bounds each request sequence (see {@link #executeLinkPreviewRequest}: redirects + body), so a
     * server dripping bytes can't stall the single attachment executor; Tor/I2P circuits get
     * longer. OkHttp's own redirect following is off: redirects are followed by {@link
     * #executeLinkPreviewRequest}, which checks every hop before connecting to it.
     */
    public static OkHttpClient linkPreviewClient(final OkHttpClient base, final boolean proxied) {
        return base.newBuilder()
                .callTimeout(proxied ? 20 : 10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public static final int LINK_PREVIEW_MAX_REDIRECTS = 5;

    /**
     * The User-Agent for fetching the page a link preview is built from. Many sites serve their
     * OpenGraph tags compactly (or at all) only to link-preview crawlers: YouTube puts them at the
     * very top for this agent, but ~800 KB into the page otherwise, far beyond what a preview
     * reads. It also keeps the app's name and version from every linked site.
     */
    public static final String LINK_PREVIEW_USER_AGENT =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

    /**
     * Which URLs a link preview may request, for the link itself and every redirect hop: https, or
     * plain http only to a .onion/.i2p host (whose transport Tor/I2P already encrypts). So an
     * http->https upgrade is followed, an https->http downgrade on the clearnet is not.
     */
    public static boolean linkPreviewUrlAllowed(final HttpUrl url) {
        if (url.isHttps()) return true;
        return "http".equals(url.scheme()) && (isOnionHost(url.host()) || isI2PHost(url.host()));
    }

    /** Executes {@code request} with {@link #linkPreviewClient}, following allowed redirects. */
    public static okhttp3.Response executeLinkPreviewRequest(
            final OkHttpClient client, final Request request) throws IOException {
        // A redirect must not bounce the preview into local services (loopback, LAN) unless the
        // link the user wrote points there itself.
        final boolean linkIsLocal = isLocalTarget(request.url().host());
        return executeFollowingRedirects(
                linkIsLocal ? client : withPublicAddressesOnly(client),
                request,
                url -> linkPreviewUrlAllowed(url) && (linkIsLocal || !isLocalTarget(url.host())),
                LINK_PREVIEW_MAX_REDIRECTS);
    }

    /**
     * {@code client} refusing every host that resolves to a local address ({@link
     * #isLocalAddress}): a public name pointing at a LAN or loopback address (or rebinding to one)
     * must not turn a link preview into a request to a local service. OkHttp connects to exactly
     * the addresses returned here, so a second lookup can't swap them. Only direct connections
     * consult {@code Dns}: through Tor/I2P the proxy resolves, nothing is looked up locally (see
     * {@link #applyProxyPolicy}), and the local network is not reachable that way anyway.
     */
    static OkHttpClient withPublicAddressesOnly(final OkHttpClient client) {
        final Dns dns = client.dns();
        return client.newBuilder()
                .dns(hostname -> {
                    final List<InetAddress> addresses = dns.lookup(hostname);
                    for (final InetAddress address : addresses) {
                        if (isLocalAddress(address)) {
                            throw new UnknownHostException(
                                    "link preview refuses "
                                            + hostname
                                            + ": resolves to a local address");
                        }
                    }
                    return addresses;
                })
                .build();
    }

    /**
     * Executes {@code request}, following up to {@code maxRedirects} redirects, each only after
     * {@code allowed} accepted its URL (a refused hop is never contacted). All hops share the
     * client's call timeout as one overall deadline. The method is kept (HEAD/GET only here).
     */
    static okhttp3.Response executeFollowingRedirects(
            final OkHttpClient client,
            final Request request,
            final java.util.function.Predicate<HttpUrl> allowed,
            final int maxRedirects)
            throws IOException {
        final long budgetNanos = TimeUnit.MILLISECONDS.toNanos(client.callTimeoutMillis());
        final long deadline = System.nanoTime() + budgetNanos;
        Request current = request;
        for (int redirects = 0; ; redirects++) {
            if (!allowed.test(current.url())) {
                throw new IOException(
                        "link preview refuses "
                                + current.url().scheme()
                                + "://"
                                + current.url().host());
            }
            final okhttp3.Call call = client.newCall(current);
            if (budgetNanos > 0) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.io.InterruptedIOException("timeout");
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            }
            final okhttp3.Response response = call.execute();
            if (!response.isRedirect()) return response;
            final String location = response.header("Location");
            response.close();
            if (redirects >= maxRedirects) throw new IOException("too many redirects");
            final HttpUrl next = location == null ? null : current.url().resolve(location);
            if (next == null) throw new IOException("redirect without a usable Location");
            current = current.newBuilder().url(next).build();
        }
    }

    public static OkHttpClient.Builder newBuilder(final boolean tor, final boolean i2p) {
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        builder.writeTimeout(30, TimeUnit.SECONDS);
        builder.readTimeout(30, TimeUnit.SECONDS);
        applyProxyPolicy(builder, () -> tor, () -> i2p);
        return builder;
    }

    public static InputStream open(final String url, final boolean tor, final boolean i2p)
            throws IOException {
        return open(HttpUrl.get(url), tor, i2p);
    }

    public static InputStream open(final HttpUrl httpUrl, final boolean tor, final boolean i2p)
            throws IOException {
        final OkHttpClient client = newBuilder(tor, i2p).build();
        final Request request = new Request.Builder().get().url(httpUrl).build();
        final ResponseBody body = client.newCall(request).execute().body();
        if (body == null) {
            throw new IOException("No response body found");
        }
        return body.byteStream();
    }

    public static String extractFilenameFromResponse(okhttp3.Response response) {
        String filename = null;

        // Try to extract filename from the Content-Disposition header
        String contentDisposition = response.header("Content-Disposition");
        if (contentDisposition != null && contentDisposition.contains("filename=")) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("filename=")) {
                    filename = part.substring("filename=".length()).trim().replace("\"", "");
                    break;
                }
            }
        }

        // If filename is not found in the Content-Disposition header, try to get it from the URL
        if (filename == null || filename.isEmpty()) {
            HttpUrl httpUrl = response.request().url();
            List<String> pathSegments = httpUrl.pathSegments();
            if (!pathSegments.isEmpty()) {
                filename = pathSegments.get(pathSegments.size() - 1);
            }
        }

        return filename;
    }

    public static OkHttpClient okHttpClient(final Context context) {
        final OkHttpClient.Builder builder = HttpConnectionManager.OK_HTTP_CLIENT.newBuilder();
        try {
            final X509TrustManager trustManager;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                trustManager = TrustManagers.defaultWithBundledLetsEncrypt(context);
            } else {
                trustManager = TrustManagers.createDefaultTrustManager();
            }
            final SSLSocketFactory socketFactory =
                    new TLSSocketFactory(new X509TrustManager[] {trustManager}, context);
            builder.sslSocketFactory(socketFactory, trustManager);
        } catch (final IOException
                | KeyManagementException
                | NoSuchAlgorithmException
                | KeyStoreException
                | CertificateException e) {
            Log.d(Config.LOGTAG, "not reconfiguring service to work with bundled LetsEncrypt");
            throw new RuntimeException(e);
        }
        return builder.build();
    }
}
