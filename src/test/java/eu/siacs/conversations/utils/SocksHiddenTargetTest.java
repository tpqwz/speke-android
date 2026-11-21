package eu.siacs.conversations.utils;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

/**
 * An SRV/CNAME/see-other-host target on .onion/.i2p is dialed through the SOCKS proxy: the name
 * must reach the proxy as a domain name (ATYP 3), never as a locally resolved address.
 */
public class SocksHiddenTargetTest {

    private record Request(int addressType, String host, int port) {}

    @Test(timeout = 10_000)
    public void hiddenHostIsSentToTheProxyUnresolved() throws Exception {
        for (final String host : new String[] {"abcdefghijklmnop.onion", "chat.example.i2p"}) {
            try (final ServerSocket proxy =
                    new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                final AtomicReference<Request> seen = new AtomicReference<>();
                final Thread stub =
                        new Thread(() -> {
                            try (final Socket client = proxy.accept()) {
                                final DataInputStream in =
                                        new DataInputStream(client.getInputStream());
                                final OutputStream out = client.getOutputStream();
                                in.readFully(new byte[3]); // VER, NMETHODS, METHOD
                                out.write(new byte[] {0x05, 0x00});
                                final byte[] head = new byte[4]; // VER, CMD, RSV, ATYP
                                in.readFully(head);
                                final byte[] name = new byte[in.readUnsignedByte()];
                                in.readFully(name);
                                final int port = in.readUnsignedShort();
                                seen.set(
                                        new Request(
                                                head[3],
                                                new String(name, StandardCharsets.US_ASCII),
                                                port));
                                out.write(new byte[] {0x05, 0x00, 0x00, 0x03, (byte) name.length});
                                out.write(name);
                                out.write(new byte[] {0x00, 0x00});
                                out.flush();
                            } catch (final Exception ignored) {
                            }
                        });
                stub.setDaemon(true);
                stub.start();
                try (final Socket socket =
                        new Socket(InetAddress.getLoopbackAddress(), proxy.getLocalPort())) {
                    socket.setSoTimeout(5_000);
                    SocksSocketFactory.createSocksConnection(socket, host, 5222);
                }
                stub.join(5_000);
                Assert.assertNotNull(seen.get());
                Assert.assertEquals(host, 0x03, seen.get().addressType());
                Assert.assertEquals(host, seen.get().host());
                Assert.assertEquals(5222, seen.get().port());
            }
        }
    }
}
