package eu.siacs.conversations.utils;

import eu.siacs.conversations.http.HttpConnectionManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.minidns.DnsClient;
import org.minidns.MiniDnsException;
import org.minidns.cache.LruCache;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsqueryresult.DnsQueryResult;
import org.minidns.dnsqueryresult.StandardDnsQueryResult;
import org.minidns.record.Record;

/** Resolver choice and wire format of the DNS sent through Tor/I2P for DANE. */
public class ProxiedDnsDataSourceTest {

    @Test
    public void dotServerWinsAndIsUsedWithTls() {
        final var target = ProxiedDnsDataSource.select("dns.example.org", "9.9.9.9", null);
        Assert.assertEquals("dns.example.org", target.host());
        Assert.assertEquals(853, target.port());
        Assert.assertEquals("dns.example.org", target.tlsHostname());
    }

    @Test
    public void publicPlainServerIsUsedOverTcp() {
        final var v4 = ProxiedDnsDataSource.select(null, " 9.9.9.9 ", null);
        Assert.assertEquals("9.9.9.9", v4.host());
        Assert.assertEquals(53, v4.port());
        Assert.assertNull(v4.tlsHostname());
        Assert.assertEquals(
                "2620:fe::fe", ProxiedDnsDataSource.select(null, "", "2620:fe::fe").host());
    }

    @Test
    public void unreachableThroughTorPlainServersFallBackToTheDefault() {
        for (final String local :
                new String[] {
                    "192.168.1.1",
                    "10.0.0.1",
                    "172.16.0.53",
                    "127.0.0.1",
                    "169.254.1.1",
                    "0.0.0.0",
                    "::1",
                    "fe80::1",
                    "fd00::53",
                    "not-an-ip",
                    ""
                }) {
            final var target = ProxiedDnsDataSource.select(null, local, local);
            Assert.assertEquals(local, ProxiedDnsDataSource.DEFAULT_DOT_SERVER, target.host());
            Assert.assertEquals(853, target.port());
            Assert.assertEquals(ProxiedDnsDataSource.DEFAULT_DOT_SERVER, target.tlsHostname());
        }
        Assert.assertEquals(
                ProxiedDnsDataSource.DEFAULT_DOT_SERVER,
                ProxiedDnsDataSource.select(null, null, null).host());
    }

    @Test(timeout = 10_000)
    public void plainTcpExchangeIsLengthPrefixed() throws Exception {
        final DnsMessage query = query(0x1234);
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Thread responder = respond(server, 0x1234);
            try (final Socket socket =
                    new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(5_000);
                final DnsMessage response = DnsOverTls.exchange(socket, null, query);
                Assert.assertEquals(0x1234, response.id);
                Assert.assertEquals(query.getQuestion(), response.getQuestion());
            }
            responder.join();
        }
    }

    @Test(timeout = 10_000)
    public void responseWithAnotherIdIsRejected() throws Exception {
        try (final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final Thread responder = respond(server, 0x4321);
            try (final Socket socket =
                    new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                socket.setSoTimeout(5_000);
                DnsOverTls.exchange(socket, null, query(0x1234));
                Assert.fail("mismatching id must be rejected");
            } catch (final MiniDnsException.IdMismatch expected) {
            }
            responder.join();
        }
    }

    /**
     * MiniDNS must not build its usual server list (system resolvers, or the DoT server's address
     * bootstrapped through the system resolver) for a proxied data source: it only ever gets the
     * never-contacted placeholder.
     */
    @Test
    public void dnsClientUsesOnlyThePlaceholderForAProxiedDataSource() throws Exception {
        final List<InetAddress> seen = new ArrayList<>();
        final DnsClient client = new DnsClient(new LruCache());
        client.setDataSource(
                new ProxiedDnsDataSource(HttpConnectionManager.Route.TOR) {
                    @Override
                    public StandardDnsQueryResult query(
                            final DnsMessage message, final InetAddress address, final int port) {
                        seen.add(address);
                        final DnsMessage response =
                                message.asBuilder()
                                        .setQrFlag(true)
                                        .setRecursionAvailable(true)
                                        .build();
                        return new StandardDnsQueryResult(
                                address, port, DnsQueryResult.QueryMethod.tcp, message, response);
                    }
                });
        client.query("example.org", Record.TYPE.A);
        Assert.assertFalse(seen.isEmpty());
        for (final InetAddress address : seen) {
            Assert.assertTrue(address.toString(), address.isAnyLocalAddress());
        }
    }

    private static DnsMessage query(final int id) {
        return DnsMessage.builder()
                .setId(id)
                .setRecursionDesired(true)
                .setQuestion(new Question("_5222._tcp.example.org", Record.TYPE.TLSA))
                .build();
    }

    /** Reads one length-prefixed query and answers it as a response with {@code responseId}. */
    private static Thread respond(final ServerSocket server, final int responseId) {
        final Thread thread =
                new Thread(() -> {
                    try (final Socket client = server.accept()) {
                        final DataInputStream in = new DataInputStream(client.getInputStream());
                        final byte[] data = new byte[in.readUnsignedShort()];
                        in.readFully(data);
                        final DnsMessage request = new DnsMessage(data);
                        final DnsMessage response =
                                request.asBuilder()
                                        .setId(responseId)
                                        .setQrFlag(true)
                                        .setRecursionAvailable(true)
                                        .build();
                        final DataOutputStream out = new DataOutputStream(client.getOutputStream());
                        response.writeTo(out);
                        out.flush();
                    } catch (final IOException ignored) {
                    }
                });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    public void cancelledLookupStartsNoQuery() throws Exception {
        // A DANE check that ran out of time (MemorizingTrustManager) must not open further
        // connections through the proxy: cancelled before the query, nothing is contacted.
        final ProxiedDnsDataSource source =
                new ProxiedDnsDataSource(HttpConnectionManager.Route.TOR);
        final ProxiedDnsDataSource.Cancellation cancellation =
                new ProxiedDnsDataSource.Cancellation();
        cancellation.cancel();
        final DnsMessage query =
                DnsMessage.builder()
                        .setQuestion(new Question("_5222._tcp.example.com", Record.TYPE.TLSA))
                        .build();
        Assert.assertThrows(
                java.io.InterruptedIOException.class,
                () ->
                        ProxiedDnsDataSource.runCancellable(
                                cancellation,
                                () ->
                                        source.query(
                                                query,
                                                InetAddress.getByAddress(new byte[] {0, 0, 0, 0}),
                                                53)));
    }
}
