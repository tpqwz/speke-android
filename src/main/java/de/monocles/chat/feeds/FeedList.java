package de.monocles.chat.feeds;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The saved feed addresses, one per line. A feed the user typed is untrusted: only https, or http
 * on an .onion or .i2p host, and never a local or private address. The fetch still refuses a
 * public name that resolves to one.
 */
public final class FeedList {

    public static final int MAX_FEEDS = 100;
    public static final int MAX_URL_LENGTH = 2048;

    private FeedList() {}

    /** The address to store and to request, or null when it must not be used. */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.length() > MAX_URL_LENGTH) {
            return null;
        }
        if (text.indexOf(' ') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0) {
            return null;
        }
        final URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getHost() == null) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || localHost(host)) {
            return null;
        }
        boolean hidden = host.endsWith(".onion") || host.endsWith(".i2p");
        if (!"https".equals(scheme) && !("http".equals(scheme) && hidden)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://").append(host);
        if (uri.getPort() != -1) {
            out.append(':').append(uri.getPort());
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            out.append('/');
        } else {
            out.append(path);
        }
        if (uri.getRawQuery() != null) {
            out.append('?').append(uri.getRawQuery());
        }
        String canonical = out.toString();
        return canonical.length() > MAX_URL_LENGTH ? null : canonical;
    }

    public static List<String> read(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return urls;
        }
        for (String line : text.split("\n", -1)) {
            String url = canonical(line);
            if (url == null || urls.contains(url)) {
                continue;
            }
            urls.add(url);
            if (urls.size() == MAX_FEEDS) {
                break;
            }
        }
        return urls;
    }

    public static String write(List<String> urls) {
        StringBuilder out = new StringBuilder();
        List<String> clean = read(urls == null ? "" : String.join("\n", urls));
        for (String url : clean) {
            out.append(url).append('\n');
        }
        return out.toString();
    }

    static boolean localHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        if (host.matches("[0-9.]+")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) {
                return true;
            }
            int[] octet = new int[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return true;
                }
                try {
                    octet[i] = Integer.parseInt(parts[i]);
                } catch (NumberFormatException e) {
                    return true;
                }
                if (octet[i] > 255) {
                    return true;
                }
            }
            int a = octet[0];
            int b = octet[1];
            if (a == 0 || a == 10 || a == 127) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 100 && (b & 0xc0) == 64) {
                return true;
            }
            return a >= 224;
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
                if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
                    return true;
                }
                return false;
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }
}
