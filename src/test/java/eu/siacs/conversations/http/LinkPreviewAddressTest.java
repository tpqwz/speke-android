package eu.siacs.conversations.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Assert;
import org.junit.Test;

/**
 * Link previews check the addresses a name resolves to, not just the name: a public name pointing
 * at (or rebinding to) a LAN/loopback address must not make the preview request a local service.
 */
public class LinkPreviewAddressTest {

    private static OkHttpClient resolvingTo(final String... ips) {
        return new OkHttpClient.Builder()
                .dns(hostname -> {
                    final InetAddress[] addresses = new InetAddress[ips.length];
                    for (int i = 0; i < ips.length; i++) {
                        addresses[i] =
                                InetAddress.getByAddress(
                                        hostname,
                                        com.google.common.net.InetAddresses.forString(ips[i])
                                                .getAddress());
                    }
                    return List.of(addresses);
                })
                .build();
    }

    @Test
    public void localAddresses() {
        for (final String ip :
                new String[] {
                    "127.0.0.1",
                    "10.1.2.3",
                    "172.16.0.1",
                    "192.168.1.1",
                    "169.254.1.1",
                    "100.64.0.1",
                    "0.0.0.0",
                    "224.0.0.1",
                    "::1",
                    "fe80::1",
                    "fd00::1",
                    "::"
                }) {
            Assert.assertTrue(
                    ip,
                    HttpConnectionManager.isLocalAddress(
                            com.google.common.net.InetAddresses.forString(ip)));
        }
        for (final String ip : new String[] {"93.184.216.34", "2606:4700::1111", "100.128.0.1"}) {
            Assert.assertFalse(
                    ip,
                    HttpConnectionManager.isLocalAddress(
                            com.google.common.net.InetAddresses.forString(ip)));
        }
    }

    @Test
    public void nameResolvingToLanAddressIsRefused() throws Exception {
        final OkHttpClient client =
                HttpConnectionManager.linkPreviewClient(resolvingTo("192.168.1.1"), false);
        final Request request =
                new Request.Builder().url("https://preview.example.com/").get().build();
        Assert.assertThrows(
                UnknownHostException.class,
                () -> HttpConnectionManager.executeLinkPreviewRequest(client, request).close());
    }

    @Test
    public void anyLocalAddressAmongSeveralIsRefused() {
        final OkHttpClient guarded =
                HttpConnectionManager.withPublicAddressesOnly(
                        resolvingTo("93.184.216.34", "127.0.0.1"));
        Assert.assertThrows(
                UnknownHostException.class, () -> guarded.dns().lookup("preview.example.com"));
    }

    @Test
    public void publicAddressesPassUnchanged() throws Exception {
        final OkHttpClient guarded =
                HttpConnectionManager.withPublicAddressesOnly(
                        resolvingTo("93.184.216.34", "2606:4700::1111"));
        final List<InetAddress> addresses = guarded.dns().lookup("preview.example.com");
        Assert.assertEquals(2, addresses.size());
        Assert.assertEquals("93.184.216.34", addresses.get(0).getHostAddress());
    }
}
