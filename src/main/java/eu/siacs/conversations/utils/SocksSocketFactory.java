package eu.siacs.conversations.utils;

import com.google.common.io.ByteStreams;
import eu.siacs.conversations.Config;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class SocksSocketFactory {

    private static final byte[] LOCALHOST = new byte[] {127, 0, 0, 1};

    public static void createSocksConnection(
            final Socket socket, final String destination, final int port) throws IOException {
        // TODO use different Socks Addr Type if destination is IP or IPv6
        final InputStream proxyIs = socket.getInputStream();
        final OutputStream proxyOs = socket.getOutputStream();
        proxyOs.write(new byte[] {0x05, 0x01, 0x00});
        proxyOs.flush();
        final byte[] handshake = new byte[2];
        ByteStreams.readFully(proxyIs, handshake);
        if (handshake[0] != 0x05 || handshake[1] != 0x00) {
            throw new SocksConnectionException("Socks 5 handshake failed");
        }
        final byte[] dest = destination.getBytes();
        final ByteBuffer request = ByteBuffer.allocate(7 + dest.length);
        request.put(new byte[] {0x05, 0x01, 0x00, 0x03});
        request.put((byte) dest.length);
        request.put(dest);
        request.putShort((short) port);
        proxyOs.write(request.array());
        proxyOs.flush();
        final byte[] response = new byte[4];
        ByteStreams.readFully(proxyIs, response);
        final byte ver = response[0];
        if (ver != 0x05) {
            throw new IOException(String.format("Unknown Socks version %02X ", ver));
        }
        final byte status = response[1];
        final byte bndAddrType = response[3];
        final byte[] bndDestination = readDestination(bndAddrType, proxyIs);
        final byte[] bndPort = new byte[2];
        if (bndAddrType == 0x03) {
            final String receivedDestination = new String(bndDestination);
            if (!receivedDestination.equalsIgnoreCase(destination)) {
                throw new IOException(
                        String.format(
                                "Destination mismatch. Received %s Expected %s",
                                receivedDestination, destination));
            }
        }
        ByteStreams.readFully(proxyIs, bndPort);
        if (status != 0x00) {
            if (status == 0x04) {
                throw new HostNotFoundException("Host unreachable");
            }
            if (status == 0x05) {
                throw new HostNotFoundException("Connection refused");
            }
            throw new IOException(String.format("Unknown status code %02X ", status));
        }
    }

    static byte[] readDestination(final byte type, final InputStream inputStream)
            throws IOException {
        final byte[] bndDestination;
        if (type == 0x01) {
            bndDestination = new byte[4];
        } else if (type == 0x03) {
            final int length = inputStream.read();
            if (length < 1) {
                throw new IOException("Invalid destination length");
            }
            bndDestination = new byte[length];
        } else if (type == 0x04) {
            bndDestination = new byte[16];
        } else {
            throw new IOException(String.format("Unknown Socks address type %02X ", type));
        }
        ByteStreams.readFully(inputStream, bndDestination);
        return bndDestination;
    }

    public static boolean contains(byte needle, byte[] haystack) {
        for (byte hay : haystack) {
            if (hay == needle) {
                return true;
            }
        }
        return false;
    }

    private static Socket createSocket(InetSocketAddress address, String destination, int port)
            throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, Config.CONNECT_TIMEOUT * 1000);
        } catch (IOException e) {
            throw new SocksProxyNotFoundException();
        }
        createSocksConnection(socket, destination, port);
        return socket;
    }

    public static Socket createSocketOverTor(String destination, int port) throws IOException {
        return createSocket(
                new InetSocketAddress(InetAddress.getByAddress(LOCALHOST), 9050),
                destination,
                port);
    }

    public static Socket createSocketOverI2P(String destination, int port) throws IOException {
        return createSocket(
                new InetSocketAddress(InetAddress.getByAddress(LOCALHOST), 4447),
                destination,
                port);
    }

    /**
     * Connects to {@code destination:port} through Tor or I2P with {@code timeoutMs} applied to the
     * proxy connect, the SOCKS handshake (Tor answers only once the circuit is built, otherwise
     * this could block for minutes) and later reads. The hostname goes to the proxy unresolved.
     */
    public static Socket createSocketVia(
            final eu.siacs.conversations.http.HttpConnectionManager.Route route,
            final String destination,
            final int port,
            final int timeoutMs)
            throws IOException {
        if (route == eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT) {
            throw new IllegalArgumentException("createSocketVia needs a Tor or I2P route");
        }
        final int proxyPort =
                route == eu.siacs.conversations.http.HttpConnectionManager.Route.I2P ? 4447 : 9050;
        final Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(InetAddress.getByAddress(LOCALHOST), proxyPort),
                    timeoutMs);
        } catch (final IOException e) {
            socket.close();
            throw new SocksProxyNotFoundException();
        }
        try {
            socket.setSoTimeout(timeoutMs);
            createSocksConnection(socket, destination, port);
            return socket;
        } catch (final IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Connects to {@code destination:port} following {@link
     * eu.siacs.conversations.http.HttpConnectionManager#route}: through Tor or I2P as the policy
     * dictates (so a .onion/.i2p name is never resolved locally), or directly.
     */
    public static Socket createSocket(
            final String destination,
            final int port,
            final boolean tor,
            final boolean i2p,
            final int timeout)
            throws IOException {
        return switch (eu.siacs.conversations.http.HttpConnectionManager.route(
                destination, tor, i2p)) {
            case TOR -> createSocketOverTor(destination, port);
            case I2P -> createSocketOverI2P(destination, port);
            case DIRECT -> {
                final Socket socket = new Socket();
                socket.connect(new InetSocketAddress(destination, port), timeout);
                yield socket;
            }
        };
    }

    private static class SocksConnectionException extends IOException {
        SocksConnectionException(String message) {
            super(message);
        }
    }

    public static class SocksProxyNotFoundException extends IOException {}

    public static class HostNotFoundException extends SocksConnectionException {
        HostNotFoundException(String message) {
            super(message);
        }
    }
}
