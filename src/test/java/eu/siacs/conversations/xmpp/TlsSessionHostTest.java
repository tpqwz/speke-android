package eu.siacs.conversations.xmpp;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.Assert;
import org.junit.Test;

/**
 * Through Tor/I2P the TCP peer of every connection is the local proxy, so the TLS session must be
 * keyed by the real server host - otherwise sessions/tickets of different servers would share one
 * cache entry and could be offered across servers.
 */
public class TlsSessionHostTest {

    @Test
    public void proxiedConnectionsUseTheServerHost() throws Exception {
        try (final ServerSocket proxy = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
                final Socket socket =
                        new Socket(InetAddress.getLoopbackAddress(), proxy.getLocalPort())) {
            Assert.assertEquals(
                    "abcdefghijklmnop.onion",
                    XmppConnection.tlsSessionHost("abcdefghijklmnop.onion", socket));
            Assert.assertEquals(
                    "xmpp.example.org", XmppConnection.tlsSessionHost("xmpp.example.org", socket));
            // direct connection: the peer address, as before
            Assert.assertEquals("127.0.0.1", XmppConnection.tlsSessionHost(null, socket));
            Assert.assertEquals("127.0.0.1", XmppConnection.tlsSessionHost("", socket));
        }
    }
}
