package eu.siacs.conversations.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.crypto.TrustManagers;
import eu.siacs.conversations.crypto.XmppDomainVerifier;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.minidns.MiniDnsException;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsname.DnsName;
import org.minidns.dnsqueryresult.DnsQueryResult;
import org.minidns.dnsqueryresult.StandardDnsQueryResult;
import org.minidns.source.NetworkDataSource;
import org.minidns.util.InetAddressUtil;

/**
 * DNS-over-TLS (RFC 7858) for all MiniDNS lookups of the app, configured with the dns_server_dot
 * setting. The server is authenticated by its certificate (the app's CA store) and hostname, like
 * Android's Private DNS. When it is configured there is no fallback to plain DNS: an unreachable
 * server means no DNS, so blocking port 853 can't downgrade to a resolver that strips DNSSEC.
 */
public final class DnsOverTls {

    public static final String PREFERENCE = "dns_server_dot";

    private static final int PORT = 853;
    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;
    private static final long BOOTSTRAP_TTL_MILLIS = 60 * 60 * 1000;

    private static final Object BOOTSTRAP_LOCK = new Object();
    private static String bootstrapHostname;
    private static List<InetAddress> bootstrapAddresses = ImmutableList.of();
    private static long bootstrapTime;

    private static volatile SSLContext sslContext;

    private DnsOverTls() {}

    /**
     * The DoT server to use, or null if DoT is not used: the app's own setting, otherwise Android's
     * strict Private DNS, unless the app has its own plain DNS server set (see {@link #select}).
     */
    @Nullable
    public static String hostname() {
        final Context context = Conversations.getContext();
        if (context == null) {
            return null;
        }
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final boolean appHasPlainServer =
                InetAddressUtil.isIpV4Address(preferences.getString("dns_server_ipv4", "").trim())
                        || InetAddressUtil.isIpV6Address(
                                preferences.getString("dns_server_ipv6", "").trim());
        return select(
                preferences.getString(PREFERENCE, ""),
                appHasPlainServer,
                appHasPlainServer ? null : androidPrivateDnsHostname(context));
    }

    /**
     * Priority of the DNS settings: the app's DoT server, then the app's own plain DNS servers
     * (which mean no DoT), then Android's strict Private DNS. Pure function for the unit test.
     */
    @Nullable
    static String select(
            @Nullable final String appDot,
            final boolean appHasPlainServer,
            @Nullable final String androidPrivateDns) {
        final String app = normalize(appDot);
        if (app != null) {
            return app;
        }
        if (appHasPlainServer) {
            return null;
        }
        return normalize(androidPrivateDns);
    }

    /**
     * The hostname of Android's Private DNS in strict mode ("Private DNS provider hostname") of the
     * active network, or null. MiniDNS sends its own queries, so without this the app would bypass
     * the user's encrypted DNS and ask the network's resolvers in plain text. The opportunistic
     * "Automatic" mode has no hostname to authenticate and stays plain DNS, as in Android.
     */
    @Nullable
    private static String androidPrivateDnsHostname(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        final ConnectivityManager connectivityManager =
                context.getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) {
            return null;
        }
        final Network network = connectivityManager.getActiveNetwork();
        final LinkProperties linkProperties =
                network == null ? null : connectivityManager.getLinkProperties(network);
        if (linkProperties == null || !linkProperties.isPrivateDnsActive()) {
            return null;
        }
        return linkProperties.getPrivateDnsServerName();
    }

    /**
     * Trimmed IP literal or hostname with at least one dot, null for anything else (including
     * empty). Pure function, used for the setting and its input validation.
     */
    @Nullable
    public static String normalize(@Nullable final String input) {
        if (input == null) {
            return null;
        }
        final String value = input.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (InetAddressUtil.isIpAddress(value)) {
            return value;
        }
        if (!value.contains(".") || value.startsWith(".") || value.endsWith(".")) {
            return null;
        }
        for (final char c : value.toCharArray()) {
            final boolean asciiLetterOrDigit =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!(asciiLetterOrDigit || c == '-' || c == '.')) {
                return null;
            }
        }
        try {
            DnsName.from(value);
        } catch (final RuntimeException e) {
            return null;
        }
        return value;
    }

    /**
     * Addresses of the DoT server. The bootstrap lookup uses the system resolver (never MiniDNS, so
     * no recursion) and doesn't need to be trusted: the TLS handshake authenticates the server. An
     * empty list makes MiniDNS fail the query.
     */
    public static List<InetAddress> serverAddresses(final String hostname) {
        // A .onion/.i2p DoT server only works through Tor/I2P (ProxiedDnsDataSource hands the name
        // to the proxy). Never look it up via the system resolver: that would leak it. The direct
        // path then fails closed, as for any unresolvable DoT server.
        if (eu.siacs.conversations.http.HttpConnectionManager.isOnionHost(hostname)
                || eu.siacs.conversations.http.HttpConnectionManager.isI2PHost(hostname)) {
            Log.d(Config.LOGTAG, "not resolving hidden-service DNS-over-TLS server " + hostname);
            return ImmutableList.of();
        }
        synchronized (BOOTSTRAP_LOCK) {
            final long now = System.currentTimeMillis();
            if (hostname.equals(bootstrapHostname)
                    && !bootstrapAddresses.isEmpty()
                    && now - bootstrapTime < BOOTSTRAP_TTL_MILLIS) {
                return bootstrapAddresses;
            }
            try {
                bootstrapAddresses = ImmutableList.copyOf(InetAddress.getAllByName(hostname));
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "unable to resolve DNS-over-TLS server " + hostname, e);
                bootstrapAddresses = ImmutableList.of();
            }
            bootstrapHostname = hostname;
            bootstrapTime = now;
            return bootstrapAddresses;
        }
    }

    public static void clearCache() {
        synchronized (BOOTSTRAP_LOCK) {
            bootstrapHostname = null;
            bootstrapAddresses = ImmutableList.of();
        }
    }

    /**
     * RFC 7858/6125 name check against the leaf certificate's subjectAltNames: dNSName (RFC 6125
     * wildcard rules of {@link XmppDomainVerifier#matchDomain}) for a hostname, iPAddress for an IP
     * literal. Not HttpsURLConnection's default verifier, which rejects everything outside of
     * Android.
     */
    static boolean matchesCertificate(final String hostname, final SSLSession session)
            throws SSLPeerUnverifiedException {
        final Certificate[] chain = session.getPeerCertificates();
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            return false;
        }
        final Collection<List<?>> alternativeNames;
        try {
            alternativeNames = ((X509Certificate) chain[0]).getSubjectAlternativeNames();
        } catch (final CertificateParsingException e) {
            return false;
        }
        if (alternativeNames == null) {
            return false;
        }
        final boolean ipLiteral = InetAddressUtil.isIpAddress(hostname);
        final List<String> dnsNames = new ArrayList<>();
        for (final List<?> entry : alternativeNames) {
            if (entry.size() < 2
                    || !(entry.get(0) instanceof Integer)
                    || !(entry.get(1) instanceof String)) {
                continue;
            }
            final int type = (Integer) entry.get(0);
            final String value = (String) entry.get(1);
            if (ipLiteral && type == SAN_IP_ADDRESS && InetAddressUtil.isIpAddress(value)) {
                // both are literals, so getByName doesn't do a lookup
                try {
                    if (InetAddress.getByName(value).equals(InetAddress.getByName(hostname))) {
                        return true;
                    }
                } catch (final UnknownHostException e) {
                    // not a usable address, ignore
                }
            } else if (!ipLiteral && type == SAN_DNS_NAME) {
                dnsNames.add(value);
            }
        }
        return !ipLiteral && XmppDomainVerifier.matchDomain(hostname, dnsNames);
    }

    private static SSLContext sslContext() throws IOException {
        SSLContext context = sslContext;
        if (context == null) {
            try {
                context = SSLSockets.getSSLContext();
                // The app's CA store (system + user CAs, minus the blocked roots), like XMPP and
                // HTTP, incl. the bundled Let's Encrypt root on Android <= 7. Never
                // MemorizingTrustManager: no memorized certificates, no prompts.
                final Context appContext = Conversations.getContext();
                final X509TrustManager trustManager =
                        Build.VERSION.SDK_INT <= Build.VERSION_CODES.N && appContext != null
                                ? TrustManagers.defaultWithBundledLetsEncrypt(appContext)
                                : TrustManagers.createDefaultTrustManager();
                context.init(null, new TrustManager[] {trustManager}, null);
            } catch (final GeneralSecurityException | IOException e) {
                throw new IOException("unable to create TLS context for DNS-over-TLS", e);
            }
            sslContext = context;
        }
        return context;
    }

    /**
     * Sends {@code message} over an already connected TCP socket and returns the answer: with TLS
     * (RFC 7858, certificate checked against {@code tlsHostname}) when {@code tlsHostname} is set,
     * otherwise plain DNS over TCP (RFC 1035 §4.2.2 length prefix). The caller owns the socket and
     * sets its timeout.
     */
    static DnsMessage exchange(
            final Socket connected, @Nullable final String tlsHostname, final DnsMessage message)
            throws IOException {
        if (tlsHostname == null) {
            return exchangeFramed(connected, message);
        }
        try (final SSLSocket socket =
                (SSLSocket)
                        sslContext()
                                .getSocketFactory()
                                .createSocket(connected, tlsHostname, PORT, true)) {
            SSLSockets.setSecurity(socket, false);
            if (!InetAddressUtil.isIpAddress(tlsHostname)) {
                // RFC 6066: SNI carries hostnames only, never IP literals
                SSLSockets.setHostname(socket, tlsHostname);
            }
            socket.startHandshake();
            if (!matchesCertificate(tlsHostname, socket.getSession())) {
                throw new SSLPeerUnverifiedException(
                        "DNS-over-TLS certificate doesn't match " + tlsHostname);
            }
            return exchangeFramed(socket, message);
        }
    }

    private static DnsMessage exchangeFramed(final Socket socket, final DnsMessage message)
            throws IOException {
        final DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        message.writeTo(dos);
        dos.flush();
        final DataInputStream dis = new DataInputStream(socket.getInputStream());
        final byte[] data = new byte[dis.readUnsignedShort()];
        dis.readFully(data);
        final DnsMessage response = new DnsMessage(data);
        if (response.id != message.id) {
            throw new MiniDnsException.IdMismatch(message, response);
        }
        return response;
    }

    /** Uses DoT when it is configured, plain DNS (MiniDNS' NetworkDataSource) otherwise. */
    public static class DataSource extends NetworkDataSource {

        @Override
        public StandardDnsQueryResult query(
                final DnsMessage message, final InetAddress address, final int port)
                throws IOException {
            final String hostname = hostname();
            if (hostname == null) {
                return super.query(message, address, port);
            }
            // Always port 853 and TLS, also for queries MiniDNS would send elsewhere (iterative
            // fallback): with DoT configured nothing leaves the device as plain DNS.
            return new StandardDnsQueryResult(
                    address,
                    PORT,
                    DnsQueryResult.QueryMethod.tcp,
                    message,
                    queryTls(message, address, hostname));
        }

        private DnsMessage queryTls(
                final DnsMessage message, final InetAddress address, final String hostname)
                throws IOException {
            final Socket plain = new Socket();
            try {
                plain.connect(new InetSocketAddress(address, PORT), timeout);
                plain.setSoTimeout(timeout);
                return exchange(plain, hostname, message);
            } finally {
                plain.close();
            }
        }
    }
}
