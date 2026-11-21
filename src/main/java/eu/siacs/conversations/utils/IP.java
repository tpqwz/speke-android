package eu.siacs.conversations.utils;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;

public class IP {

    /**
     * Whether {@code server} is an IPv4 or IPv6 literal (no brackets). Guava instead of regexes, as
     * upstream 4512a4aa3.
     */
    public static boolean matches(final String server) {
        return server != null && InetAddresses.isInetAddress(server);
    }

    public static String wrapIPv6(final String host) {
        if (matches(host)) {
            final InetAddress inetAddress;
            try {
                inetAddress = InetAddresses.forString(host);
            } catch (final IllegalArgumentException e) {
                return host;
            }
            // brackets for IPv6 only; an IPv4 literal stays as it is
            return InetAddresses.toUriString(inetAddress);
        } else {
            return host;
        }
    }

    public static String unwrapIPv6(final String host) {
        if (host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            final String ip = host.substring(1, host.length() - 1);
            if (InetAddresses.isInetAddress(ip)) {
                return ip;
            }
        }
        return host;
    }
}
