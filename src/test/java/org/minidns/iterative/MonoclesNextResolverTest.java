package org.minidns.iterative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.minidns.DnsWorld.record;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.minidns.DnsClient;
import org.minidns.cache.LruCache;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.DnsMessage.RESPONSE_CODE;
import org.minidns.dnsqueryresult.DnsQueryResult;
import org.minidns.dnsqueryresult.TestWorldDnsQueryResult;
import org.minidns.dnsserverlookup.AbstractDnsServerLookupMechanism;
import org.minidns.dnsserverlookup.DnsServerLookupMechanism;
import org.minidns.record.A;
import org.minidns.record.Record.TYPE;
import org.minidns.source.AbstractDnsDataSource;

/**
 * monocles: when the first system resolver strips DNSSEC (no DO bit in the answer, as LTE
 * carriers do), the DNSSEC client asks the next one instead of settling for it.
 */
public class MonoclesNextResolverTest {

    private static final String STRIPPING = "192.0.2.1";
    private static final String DNSSEC = "192.0.2.2";
    private static final String UNREACHABLE = "192.0.2.9";
    /** DNSSEC answers this name with SERVFAIL, like a validating resolver for a broken zone. */
    private static final String BROKEN_NAME = "broken.example.org";

    private DnsServerLookupMechanism servers;

    private void useServers(final String... addresses) {
        servers =
                new AbstractDnsServerLookupMechanism("monocles-test-resolvers", Integer.MIN_VALUE) {
                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public List<String> getDnsServerAddresses() {
                        return Arrays.asList(addresses);
                    }
                };
        DnsClient.addDnsServerLookupMechanism(servers);
    }

    @AfterEach
    public void removeServers() {
        DnsClient.removeDNSServerLookupMechanism(servers);
    }

    /**
     * Answers with a server-specific A record; only DNSSEC echoes the DO and CD bits, UNREACHABLE
     * times out. Counts the queries per server.
     */
    private static final class Source extends AbstractDnsDataSource {
        final Map<String, Integer> queries = new HashMap<>();

        int queries(final String server) {
            return queries.getOrDefault(server, 0);
        }

        @Override
        public DnsQueryResult query(final DnsMessage message, final InetAddress address, final int port)
                throws IOException {
            queries.merge(address.getHostAddress(), 1, Integer::sum);
            if (address.getHostAddress().equals(UNREACHABLE)) {
                throw new SocketTimeoutException("test: " + address);
            }
            final boolean dnssec = address.getHostAddress().equals(DNSSEC);
            if (dnssec && message.getQuestion().name.toString().equals(BROKEN_NAME)) {
                return new TestWorldDnsQueryResult(
                        message,
                        message.getResponseBuilder(RESPONSE_CODE.SERVER_FAIL)
                                .setRecursionAvailable(true)
                                .build());
            }
            final DnsMessage.Builder response =
                    message.getResponseBuilder(RESPONSE_CODE.NO_ERROR)
                            .setRecursionAvailable(true)
                            .setCheckingDisabled(dnssec)
                            .addAnswer(
                                    record(
                                            message.getQuestion().name,
                                            60,
                                            new A(dnssec ? "198.51.100.2" : "198.51.100.1")));
            if (dnssec) {
                response.getEdnsBuilder().setDnssecOk().setUdpPayloadSize(1232);
            }
            return new TestWorldDnsQueryResult(message, response.build());
        }
    }

    /** A recursive-only client that, like DnssecClient, needs the DO bit. */
    private static ReliableDnsClient dnssecLikeClient() {
        return dnssecLikeClient(new Source());
    }

    private static ReliableDnsClient dnssecLikeClient(final Source source) {
        final ReliableDnsClient client =
                new ReliableDnsClient(new LruCache(0)) {
                    @Override
                    protected String isResponseAcceptable(final DnsMessage response) {
                        return response.isDnssecOk() ? null : "DO not set";
                    }
                };
        client.setMode(ReliableDnsClient.Mode.recursiveOnly);
        client.setDataSource(source);
        return client;
    }

    private static String answer(final ReliableDnsClient client) throws IOException {
        return answer(client, "xmpp.example.org");
    }

    private static String answer(final ReliableDnsClient client, final String name) throws IOException {
        final DnsQueryResult result = client.query(name, TYPE.A);
        return ((A) result.response.answerSection.get(0).payloadData).toString();
    }

    @Test
    public void skipsResolverWithoutDnssec() throws IOException {
        useServers(STRIPPING, DNSSEC);
        assertEquals("198.51.100.2", answer(dnssecLikeClient()));
    }

    @Test
    public void fallsBackToFirstAnswerIfNoResolverDoesDnssec() throws IOException {
        useServers(STRIPPING, "192.0.2.3");
        assertEquals("198.51.100.1", answer(dnssecLikeClient()));
    }

    @Test
    public void plainClientKeepsFirstAnswer() throws IOException {
        useServers(STRIPPING, DNSSEC);
        final ReliableDnsClient client = new ReliableDnsClient(new LruCache(0));
        client.setMode(ReliableDnsClient.Mode.recursiveOnly);
        client.setDataSource(new Source());
        assertEquals("198.51.100.1", answer(client));
    }

    @Test
    public void goodResolverIsAskedFirstAfterwards() throws IOException {
        useServers(STRIPPING, DNSSEC);
        final Source source = new Source();
        final ReliableDnsClient client = dnssecLikeClient(source);
        assertEquals("198.51.100.2", answer(client));
        assertEquals("198.51.100.2", answer(client));
        assertEquals(1, source.queries(STRIPPING));
        assertEquals(2, source.queries(DNSSEC));
    }

    @Test
    public void unreachableResolverDoesNotCostATimeoutOnEveryQuery() throws IOException {
        useServers(STRIPPING, UNREACHABLE);
        final Source source = new Source();
        final ReliableDnsClient client = dnssecLikeClient(source);
        assertEquals("198.51.100.1", answer(client));
        assertEquals("198.51.100.1", answer(client));
        assertEquals("198.51.100.1", answer(client));
        assertEquals(3, source.queries(STRIPPING));
        assertEquals(1, source.queries(UNREACHABLE));
    }

    @Test
    public void plainClientAsksOnlyTheFirstServer() throws IOException {
        useServers(STRIPPING, UNREACHABLE);
        final Source source = new Source();
        final ReliableDnsClient client = new ReliableDnsClient(new LruCache(0));
        client.setMode(ReliableDnsClient.Mode.recursiveOnly);
        client.setDataSource(source);
        answer(client);
        answer(client);
        assertEquals(2, source.queries(STRIPPING));
        assertEquals(0, source.queries(UNREACHABLE));
    }

    @Test
    public void unreachableFirstResolverIsAskedLastAfterwards() throws IOException {
        // e.g. a DoT server's IPv6 address without a working IPv6 route
        useServers(UNREACHABLE, DNSSEC);
        final Source source = new Source();
        final ReliableDnsClient client = dnssecLikeClient(source);
        assertEquals("198.51.100.2", answer(client));
        assertEquals("198.51.100.2", answer(client));
        assertEquals("198.51.100.2", answer(client));
        assertEquals(1, source.queries(UNREACHABLE));
        assertEquals(3, source.queries(DNSSEC));
    }

    @Test
    public void plainClientAlsoAsksAnUnreachableFirstResolverLast() throws IOException {
        useServers(UNREACHABLE, STRIPPING);
        final Source source = new Source();
        final ReliableDnsClient client = new ReliableDnsClient(new LruCache(0));
        client.setMode(ReliableDnsClient.Mode.recursiveOnly);
        client.setDataSource(source);
        assertEquals("198.51.100.1", answer(client));
        assertEquals("198.51.100.1", answer(client));
        assertEquals(1, source.queries(UNREACHABLE));
        assertEquals(2, source.queries(STRIPPING));
    }

    @Test
    public void servfailForOneNameDoesNotHideTheGoodResolver() throws IOException {
        useServers(STRIPPING, DNSSEC);
        final Source source = new Source();
        final ReliableDnsClient client = dnssecLikeClient(source);
        assertEquals("198.51.100.2", answer(client));
        // SERVFAIL from DNSSEC, the unacceptable answer from STRIPPING is all there is
        assertEquals("198.51.100.1", answer(client, BROKEN_NAME));
        // DNSSEC must still be asked first for other names
        assertEquals("198.51.100.2", answer(client));
    }
}
