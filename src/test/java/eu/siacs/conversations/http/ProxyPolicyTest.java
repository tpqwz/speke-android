package eu.siacs.conversations.http;

import static eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT;
import static eu.siacs.conversations.http.HttpConnectionManager.Route.I2P;
import static eu.siacs.conversations.http.HttpConnectionManager.Route.TOR;
import static eu.siacs.conversations.http.HttpConnectionManager.route;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Assert;
import org.junit.Test;

/** The single Tor/I2P routing policy used by HTTP, XMPP and SOCKS5 bytestreams. */
public class ProxyPolicyTest {

    @Test
    public void hiddenServicesAlwaysUseTheirOwnOverlay() {
        for (final boolean tor : new boolean[] {false, true}) {
            for (final boolean i2p : new boolean[] {false, true}) {
                Assert.assertEquals(TOR, route("abcdef.onion", tor, i2p));
                Assert.assertEquals(I2P, route("site.i2p", tor, i2p));
            }
        }
    }

    @Test
    public void clearnetFollowsSettingsWithTorFirst() {
        Assert.assertEquals(DIRECT, route("example.com", false, false));
        Assert.assertEquals(TOR, route("example.com", true, false));
        Assert.assertEquals(I2P, route("example.com", false, true));
        Assert.assertEquals(TOR, route("example.com", true, true));
    }

    @Test
    public void hostSuffixMatchingIsNormalized() {
        Assert.assertEquals(TOR, route("ABCDEF.ONION", false, false));
        Assert.assertEquals(TOR, route("abcdef.onion.", false, true));
        Assert.assertEquals(I2P, route("Site.I2P..", true, false));
        Assert.assertEquals(DIRECT, route("notonion.com", false, false));
        Assert.assertEquals(DIRECT, route("onion", false, false));
        Assert.assertEquals(DIRECT, route(null, false, false));
    }

    @Test
    public void proxyListIsNeverEmptyAndPointsAtTheRightPort() {
        assertPort(HttpConnectionManager.selectProxies("abcdef.onion", false, true), 9050, 8118);
        assertPort(HttpConnectionManager.selectProxies("site.i2p", true, false), 4447, 4444);
        assertPort(HttpConnectionManager.selectProxies("example.com", true, true), 9050, 8118);
        final List<Proxy> direct = HttpConnectionManager.selectProxies("example.com", false, false);
        Assert.assertEquals(List.of(Proxy.NO_PROXY), direct);
    }

    /**
     * OkHttp skips the ProxySelector when java.net.URI cannot parse the host (underscore). The
     * guard Dns must then refuse the local lookup instead of letting the request go out directly.
     */
    @Test
    public void selectorBypassIsFailClosed() throws Exception {
        assertLocalLookupRefused(
                HttpConnectionManager.newBuilder(true, false).build(), "https://a_b.example.com/");
        assertLocalLookupRefused(
                HttpConnectionManager.newBuilder(false, true).build(), "https://a_b.example.com/");
        assertLocalLookupRefused(
                HttpConnectionManager.newBuilder(false, false).build(), "http://a_b.onion/");
        assertLocalLookupRefused(
                HttpConnectionManager.newBuilder(false, false).build(), "http://a_b.i2p/");
    }

    /**
     * The link-preview fetch must have a total deadline: readTimeout alone lets a server that sends
     * one byte at a time hold the (single) attachment executor indefinitely.
     */
    @Test(timeout = 15_000)
    public void callTimeoutBoundsADrippingServer() throws Exception {
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Thread dripper =
                    new Thread(() -> {
                        try (final Socket socket = server.accept()) {
                            final OutputStream out = socket.getOutputStream();
                            out.write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 100000\r\n\r\n"
                                            .getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            for (int i = 0; i < 100; i++) {
                                out.write('a');
                                out.flush();
                                Thread.sleep(500);
                            }
                        } catch (final IOException | InterruptedException ignored) {
                        }
                    });
            dripper.setDaemon(true);
            dripper.start();
            final OkHttpClient client =
                    HttpConnectionManager.newBuilder(false, false)
                            .readTimeout(5, TimeUnit.SECONDS)
                            .callTimeout(2, TimeUnit.SECONDS)
                            .build();
            final long start = System.nanoTime();
            try (final var response =
                    client.newCall(
                                    new Request.Builder()
                                            .url("http://127.0.0.1:" + server.getLocalPort() + "/")
                                            .build())
                            .execute()) {
                response.body().source().request(90_000);
                Assert.fail("dripping body must hit the call timeout");
            } catch (final IOException expected) {
                // InterruptedIOException("timeout") or a closed stream after cancellation
            }
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Assert.assertTrue("took " + elapsedMs + " ms", elapsedMs < 4_000);
        }
    }

    @Test
    public void linkPreviewUrlRule() {
        Assert.assertTrue(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("https://example.org/a")));
        Assert.assertTrue(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("https://abcdef.onion/a")));
        Assert.assertTrue(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("http://abcdef.onion/a")));
        Assert.assertTrue(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("http://site.i2p/a")));
        Assert.assertFalse(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("http://example.org/a")));
        Assert.assertFalse(
                HttpConnectionManager.linkPreviewUrlAllowed(
                        okhttp3.HttpUrl.get("http://127.0.0.1/a")));
    }

    @Test
    public void localTargets() {
        for (final String local :
                new String[] {
                    "localhost",
                    "LOCALHOST.",
                    "printer.local",
                    "app.localhost",
                    "127.0.0.1",
                    "127.1.2.3",
                    "0.0.0.0",
                    "10.0.0.1",
                    "172.16.5.4",
                    "192.168.1.1",
                    "169.254.169.254",
                    "100.64.1.1",
                    "224.0.0.1",
                    "[::1]",
                    "::1",
                    "[fe80::1]",
                    "[fd00::1]",
                    "[::ffff:192.168.0.1]",
                    "::"
                }) {
            Assert.assertTrue(local, HttpConnectionManager.isLocalTarget(local));
        }
        for (final String remote :
                new String[] {
                    "example.org",
                    "localhost.example.org",
                    "local.example.org",
                    "8.8.8.8",
                    "100.128.0.1",
                    "[2001:db8::1]",
                    "172.32.0.1",
                    "abcdef.onion"
                }) {
            Assert.assertFalse(remote, HttpConnectionManager.isLocalTarget(remote));
        }
    }

    /**
     * OkHttp keeps "127.1" etc. as the host and connects to any [0-9.]+ host through
     * InetAddress.getByName (bypassing our Dns), which resolves it to 127.0.0.1. Such shorthand
     * numeric hosts must count as local.
     */
    @Test
    public void shorthandNumericHostsAreLocal() {
        for (final String host :
                new String[] {"127.1", "127.0.1", "2130706433", "1.2.3", "0", "10.1", "127.1."}) {
            Assert.assertTrue(host, HttpConnectionManager.isLocalTarget(host));
        }
        // OkHttp really keeps the host as written, which is why the check above is needed
        Assert.assertEquals("127.1", okhttp3.HttpUrl.get("https://127.1/x").host());
        Assert.assertFalse(HttpConnectionManager.isLocalTarget("203.0.113.5"));
        Assert.assertFalse(HttpConnectionManager.isLocalTarget("1password.com"));
    }

    @Test
    public void previewHopIntoLocalServicesIsRefusedForRemoteLinks() throws Exception {
        // remote https link redirecting to https://127.0.0.1 - refused before any connection
        final okhttp3.Request request =
                new Request.Builder().url("https://example.invalid/").build();
        final OkHttpClient client =
                previewClient()
                        .newBuilder()
                        .addInterceptor(chain ->
                                new okhttp3.Response.Builder()
                                        .request(chain.request())
                                        .protocol(okhttp3.Protocol.HTTP_1_1)
                                        .code(302)
                                        .message("Found")
                                        .header("Location", "https://127.0.0.1:1/admin")
                                        .body(okhttp3.ResponseBody.create(new byte[0], null))
                                        .build())
                        .build();
        try {
            HttpConnectionManager.executeLinkPreviewRequest(client, request).close();
            Assert.fail("redirect into local services must be refused");
        } catch (final IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("refuses"));
        }
    }

    /** Allowed hops are followed (incl. relative Locations); the final response is returned. */
    @Test(timeout = 15_000)
    public void allowedRedirectHopsAreFollowed() throws Exception {
        try (final ServerSocket target = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                final ServerSocket origin =
                        new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            serve(
                    origin,
                    1,
                    "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"
                            + target.getLocalPort()
                            + "/final\r\n");
            serve(target, 1, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n");
            try (final var response =
                    HttpConnectionManager.executeFollowingRedirects(
                            previewClient(), get(origin, "/start"), url -> true, 5)) {
                Assert.assertEquals(200, response.code());
                Assert.assertEquals("/final", response.request().url().encodedPath());
            }
        }
    }

    /** A hop the rule refuses (e.g. https -> http on the clearnet) is never contacted. */
    @Test(timeout = 15_000)
    public void refusedRedirectHopIsNeverContacted() throws Exception {
        try (final ServerSocket target = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
                final ServerSocket origin =
                        new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(1_000);
            serve(
                    origin,
                    1,
                    "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:"
                            + target.getLocalPort()
                            + "/\r\n");
            final int originPort = origin.getLocalPort();
            try {
                HttpConnectionManager.executeFollowingRedirects(
                                previewClient(),
                                get(origin, "/"),
                                url -> url.port() == originPort,
                                5)
                        .close();
                Assert.fail("the refused hop must not be followed");
            } catch (final IOException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("refuses"));
            }
            try {
                target.accept().close();
                Assert.fail("the refused redirect target must not be contacted");
            } catch (final java.net.SocketTimeoutException expected) {
            }
        }
    }

    @Test(timeout = 15_000)
    public void redirectLoopsAreCut() throws Exception {
        try (final ServerSocket loop = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            serve(loop, 10, "HTTP/1.1 302 Found\r\nLocation: /again\r\n");
            try {
                HttpConnectionManager.executeFollowingRedirects(
                                previewClient(), get(loop, "/"), url -> true, 3)
                        .close();
                Assert.fail("a redirect loop must end");
            } catch (final IOException expected) {
                Assert.assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("too many redirects"));
            }
        }
    }

    private static OkHttpClient previewClient() {
        return HttpConnectionManager.linkPreviewClient(
                HttpConnectionManager.newBuilder(false, false).build(), false);
    }

    private static Request get(final ServerSocket server, final String path) {
        return new Request.Builder()
                .url("http://127.0.0.1:" + server.getLocalPort() + path)
                .build();
    }

    /** Answers {@code connections} connections with {@code head} (plus an empty body). */
    private static void serve(final ServerSocket server, final int connections, final String head) {
        final Thread thread =
                new Thread(() -> {
                    for (int i = 0; i < connections; i++) {
                        try (final Socket socket = server.accept()) {
                            socket.getInputStream().read(new byte[4096]);
                            socket.getOutputStream()
                                    .write(
                                            (head
                                                            + "Content-Length: 0\r\n"
                                                            + "Connection: close\r\n\r\n")
                                                    .getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        } catch (final IOException e) {
                            return;
                        }
                    }
                });
        thread.setDaemon(true);
        thread.start();
    }

    private static void assertLocalLookupRefused(final OkHttpClient client, final String url)
            throws Exception {
        try {
            client.newCall(new Request.Builder().url(url).build()).execute().close();
            Assert.fail("request to " + url + " must not be attempted directly");
        } catch (final UnknownHostException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("refusing local DNS lookup"));
        }
    }

    private static void assertPort(
            final List<Proxy> proxies, final int socksPort, final int httpPort) {
        Assert.assertEquals(1, proxies.size());
        final Proxy proxy = proxies.get(0);
        final int port = ((InetSocketAddress) proxy.address()).getPort();
        Assert.assertEquals(proxy.type() == Proxy.Type.SOCKS ? socksPort : httpPort, port);
    }
}
