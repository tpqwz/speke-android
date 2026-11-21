package eu.siacs.conversations.utils;

import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.http.HttpConnectionManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsqueryresult.DnsQueryResult;
import org.minidns.dnsqueryresult.StandardDnsQueryResult;
import org.minidns.source.NetworkDataSource;
import org.minidns.util.InetAddressUtil;

/**
 * MiniDNS data source that sends every query through Tor or I2P (DNS over TCP inside the SOCKS
 * tunnel). Used for the DANE TLSA lookups of XMPP connections that are themselves routed through
 * Tor/I2P, so neither the server's name nor the fact that it is being contacted leaks to the local
 * network's resolver. DNSSEC is still validated locally by MiniDNS' DnssecClient.
 *
 * <p>The address MiniDNS picks (a system or app-configured resolver, possibly a private LAN address
 * that is unreachable through Tor) is ignored; {@link #select} chooses the resolver.
 */
public class ProxiedDnsDataSource extends NetworkDataSource {

    static final String DEFAULT_DOT_SERVER = "dns.quad9.net";
    private static final int DOT_PORT = 853;
    private static final int DNS_PORT = 53;
    // Tor answers the SOCKS request only after the circuit is built.
    private static final int MIN_TIMEOUT_MS = 30_000;

    /** Where a query goes: {@code tlsHostname} set means DoT to it, otherwise plain TCP. */
    record Target(String host, int port, @Nullable String tlsHostname) {}

    private final HttpConnectionManager.Route route;

    /**
     * Cancels the queries of one lookup (e.g. a DANE check, see {@link #runCancellable}).
     * Interrupting a thread does not unblock a socket read, so {@link #cancel} closes the socket of
     * the query in flight, and no further query of the lookup is started.
     */
    public static final class Cancellation {
        private volatile boolean cancelled = false;
        private volatile Socket socket = null;

        public void cancel() {
            cancelled = true;
            final Socket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (final IOException ignored) {
                    // closing is all we wanted
                }
            }
        }

        private void checkCancelled(final HttpConnectionManager.Route route)
                throws InterruptedIOException {
            if (cancelled) {
                throw new InterruptedIOException("DNS query through " + route + " cancelled");
            }
        }
    }

    private static final ThreadLocal<Cancellation> CURRENT = new ThreadLocal<>();

    /**
     * Runs {@code task} on this thread with its queries cancellable through {@code cancellation}.
     */
    public static <T> T runCancellable(
            final Cancellation cancellation, final java.util.concurrent.Callable<T> task)
            throws Exception {
        CURRENT.set(cancellation);
        try {
            return task.call();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * The server list MiniDNS' DnsClient uses for this data source (patched in {@code
     * org.minidns.DnsClient#getServerAddresses}): one unspecified address that is never contacted,
     * because {@link #query} chooses the resolver itself. A fresh list each time, as DnsClient may
     * reorder it.
     */
    public static java.util.List<InetAddress> placeholderServers() {
        try {
            return new java.util.ArrayList<>(
                    java.util.List.of(InetAddress.getByAddress(new byte[] {0, 0, 0, 0})));
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    public ProxiedDnsDataSource(final HttpConnectionManager.Route route) {
        if (route == HttpConnectionManager.Route.DIRECT) {
            throw new IllegalArgumentException("ProxiedDnsDataSource needs a Tor or I2P route");
        }
        this.route = route;
    }

    /**
     * Resolver choice, in the same priority as the direct path: the app's DoT server (or Android's
     * strict Private DNS), then the app's own plain DNS servers if they are publicly routable (a
     * LAN or loopback resolver can't be reached through Tor/I2P), then a public DoT default. Pure
     * function for the unit test.
     */
    static Target select(
            @Nullable final String dotHostname,
            @Nullable final String plainIpv4,
            @Nullable final String plainIpv6) {
        if (dotHostname != null) {
            return new Target(dotHostname, DOT_PORT, dotHostname);
        }
        for (final String plain : new String[] {plainIpv4, plainIpv6}) {
            if (isPublicIpLiteral(plain)) {
                return new Target(plain.trim(), DNS_PORT, null);
            }
        }
        return new Target(DEFAULT_DOT_SERVER, DOT_PORT, DEFAULT_DOT_SERVER);
    }

    static boolean isPublicIpLiteral(@Nullable final String value) {
        if (value == null) return false;
        final String literal = value.trim();
        if (!InetAddressUtil.isIpAddress(literal)) return false;
        final InetAddress address;
        try {
            address = InetAddress.getByName(literal); // a literal: no lookup
        } catch (final UnknownHostException e) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        // IPv6 unique local addresses (fc00::/7), not covered by isSiteLocalAddress
        return !(address instanceof Inet6Address) || (address.getAddress()[0] & 0xfe) != 0xfc;
    }

    private static Target currentTarget() {
        final var context = Conversations.getContext();
        String ipv4 = null;
        String ipv6 = null;
        if (context != null) {
            final SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            ipv4 = preferences.getString("dns_server_ipv4", "");
            ipv6 = preferences.getString("dns_server_ipv6", "");
        }
        return select(DnsOverTls.hostname(), ipv4, ipv6);
    }

    @Override
    public StandardDnsQueryResult query(
            final DnsMessage message, final InetAddress address, final int port)
            throws IOException {
        final Cancellation cancellation = CURRENT.get();
        if (cancellation != null) cancellation.checkCancelled(route);
        final Target target = currentTarget();
        final int timeoutMs = Math.max(timeout, MIN_TIMEOUT_MS);
        try (final Socket socket =
                SocksSocketFactory.createSocketVia(
                        route, target.host(), target.port(), timeoutMs)) {
            if (cancellation != null) {
                cancellation.socket = socket;
                // cancelled while connecting: cancel() had no socket to close yet
                cancellation.checkCancelled(route);
            }
            final DnsMessage response = DnsOverTls.exchange(socket, target.tlsHostname(), message);
            return new StandardDnsQueryResult(
                    address, port, DnsQueryResult.QueryMethod.tcp, message, response);
        } finally {
            if (cancellation != null) cancellation.socket = null;
        }
    }
}
