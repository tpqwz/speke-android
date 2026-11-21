package eu.siacs.conversations.xmpp.jingle.transports;

import android.util.Log;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Closeables;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.stanzas.IbbTransportInfo;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class InbandBytestreamsTransport implements Transport {

    // Largest base64 payload we put into (or accept in) one <data/>; ~30 KiB keeps the stanza
    // well below common server stanza size limits (cheogram 0362f772b1).
    private static final int MAX_ENCODED_BLOCK_SIZE = 30 * 1024 - 4;
    // Also the largest block size we ever announce or accept from a peer.
    private static final int DEFAULT_BLOCK_SIZE = (MAX_ENCODED_BLOCK_SIZE / 4) * 3;
    // Number of data IQs that may be in flight at the same time.
    private static final int MAX_IN_FLIGHT = 3;
    // A peer asking for tiny blocks would turn a transfer into one IQ per few bytes.
    public static final int MINIMUM_BLOCK_SIZE = 512;

    private final PipedInputStream pipedInputStream = new PipedInputStream(DEFAULT_BLOCK_SIZE);
    private final PipedOutputStream pipedOutputStream = new PipedOutputStream();
    private final CountDownLatch terminationLatch = new CountDownLatch(1);

    private final XmppConnection xmppConnection;

    private final Jid with;

    private final boolean initiator;

    private final String streamId;

    private int blockSize;
    private Callback transportCallback;
    private final BlockSender blockSender;

    private final Thread blockSenderThread;

    private final AtomicBoolean isReceiving = new AtomicBoolean(false);

    private volatile OutputStream sendingOutputStream;

    public InbandBytestreamsTransport(
            final XmppConnection xmppConnection, final Jid with, final boolean initiator) {
        this(xmppConnection, with, initiator, UUID.randomUUID().toString(), DEFAULT_BLOCK_SIZE);
    }

    public InbandBytestreamsTransport(
            final XmppConnection xmppConnection,
            final Jid with,
            final boolean initiator,
            final String streamId,
            final int blockSize) {
        Preconditions.checkArgument(blockSize >= MINIMUM_BLOCK_SIZE, "Block size too small");
        this.xmppConnection = xmppConnection;
        this.with = with;
        this.initiator = initiator;
        this.streamId = streamId;
        this.blockSize = Math.min(DEFAULT_BLOCK_SIZE, blockSize);
        this.blockSender =
                new BlockSender(xmppConnection, with, streamId, this.blockSize, pipedInputStream);
        this.blockSenderThread = new Thread(blockSender);
    }

    public void setTransportCallback(final Callback callback) {
        this.transportCallback = callback;
    }

    public String getStreamId() {
        return this.streamId;
    }

    public void connect() {
        if (initiator) {
            openInBandTransport();
        }
    }

    @Override
    public CountDownLatch getTerminationLatch() {
        return this.terminationLatch;
    }

    private void openInBandTransport() {
        final var iqPacket = new Iq(Iq.Type.SET);
        iqPacket.setTo(with);
        final var open = iqPacket.addChild("open", Namespace.IBB);
        open.setAttribute("block-size", this.blockSize);
        open.setAttribute("sid", this.streamId);
        Log.d(Config.LOGTAG, "sending ibb open");
        Log.d(Config.LOGTAG, iqPacket.toString());
        xmppConnection.sendIqPacket(iqPacket, this::receiveResponseToOpen);
    }

    private void receiveResponseToOpen(final Iq response) {
        if (response.getType() == Iq.Type.RESULT) {
            Log.d(Config.LOGTAG, "ibb open was accepted");
            this.transportCallback.onTransportEstablished();
            this.blockSenderThread.start();
        } else {
            this.transportCallback.onTransportSetupFailed();
        }
    }

    public boolean deliverPacket(
            final PacketType packetType, final Jid from, final Element payload) {
        if (from == null || !from.equals(with)) {
            Log.d(
                    Config.LOGTAG,
                    "ibb packet received from wrong address. was " + from + " expected " + with);
            return false;
        }
        return switch (packetType) {
            case OPEN -> receiveOpen();
            case DATA -> receiveData(payload.getContent());
            case CLOSE -> receiveClose();
            default -> throw new IllegalArgumentException("Invalid packet type");
        };
    }

    private boolean receiveData(final String encoded) {
        final byte[] buffer;
        if (Strings.isNullOrEmpty(encoded)) {
            buffer = new byte[0];
        } else {
            if (encoded.length() > MAX_ENCODED_BLOCK_SIZE + 64) {
                // cheap pre-check (whitespace slack) before decoding
                Log.d(Config.LOGTAG, "ibb data block exceeds maximum block size");
                return false;
            }
            try {
                buffer = BaseEncoding.base64().decode(encoded);
            } catch (final IllegalArgumentException e) {
                // malformed base64 from the peer: answer with an error instead of no reply
                Log.d(Config.LOGTAG, "ibb data block is not valid base64");
                return false;
            }
        }
        if (buffer.length > DEFAULT_BLOCK_SIZE) {
            // no block size above DEFAULT_BLOCK_SIZE is ever negotiated
            Log.d(Config.LOGTAG, "ibb data block of " + buffer.length + " bytes is too large");
            return false;
        }
        Log.d(Config.LOGTAG, "ibb received " + buffer.length + " bytes");
        try {
            pipedOutputStream.write(buffer);
            pipedOutputStream.flush();
            return true;
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "unable to receive ibb data", e);
            return false;
        }
    }

    private boolean receiveClose() {
        if (this.isReceiving.compareAndSet(true, false)) {
            try {
                this.pipedOutputStream.close();
                return true;
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "could not close pipedOutStream");
                return false;
            }
        } else {
            Log.d(Config.LOGTAG, "received ibb close but was not receiving");
            return false;
        }
    }

    private boolean receiveOpen() {
        Log.d(Config.LOGTAG, "receiveOpen()");
        if (this.isReceiving.get()) {
            Log.d(Config.LOGTAG, "ibb received open even though we were already open");
            return false;
        }
        this.isReceiving.set(true);
        transportCallback.onTransportEstablished();
        return true;
    }

    public void terminate() {
        // TODO send close
        Log.d(Config.LOGTAG, "IbbTransport.terminate()");
        this.terminationLatch.countDown();
        this.blockSender.close();
        this.blockSenderThread.interrupt();
        closeQuietly(this.pipedOutputStream);
    }

    private static void closeQuietly(final OutputStream outputStream) {
        try {
            outputStream.close();
        } catch (final IOException ignored) {

        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        final var outputStream = new PipedOutputStream();
        this.pipedInputStream.connect(outputStream);
        this.sendingOutputStream = outputStream;
        return outputStream;
    }

    @Override
    public void finishSending() {
        // The file transmitter keeps its end of the pipe open until the transport terminates;
        // closing it here lets the block sender see EOF and close the IBB stream (cheogram
        // e372bc61bc).
        final var outputStream = this.sendingOutputStream;
        if (outputStream != null) {
            this.sendingOutputStream = null;
            try {
                outputStream.close();
            } catch (final IOException ignored) {
                // nothing to do
            }
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        final var inputStream = new PipedInputStream();
        this.pipedOutputStream.connect(inputStream);
        return inputStream;
    }

    @Override
    public ListenableFuture<TransportInfo> asTransportInfo() {
        return Futures.immediateFuture(
                new TransportInfo(new IbbTransportInfo(streamId, blockSize), null));
    }

    @Override
    public ListenableFuture<InitialTransportInfo> asInitialTransportInfo() {
        return Futures.immediateFuture(
                new InitialTransportInfo(
                        UUID.randomUUID().toString(),
                        new IbbTransportInfo(streamId, blockSize),
                        null));
    }

    public void setPeerBlockSize(long peerBlockSize) {
        Preconditions.checkArgument(peerBlockSize >= MINIMUM_BLOCK_SIZE, "Block size too small");
        this.blockSize = Math.min(Ints.saturatedCast(peerBlockSize), DEFAULT_BLOCK_SIZE);
        if (this.blockSize < DEFAULT_BLOCK_SIZE) {
            Log.d(Config.LOGTAG, "peer reconfigured IBB block size to " + this.blockSize);
        }
        this.blockSender.setBlockSize(this.blockSize);
    }

    private static class BlockSender implements Runnable, Closeable {

        private final XmppConnection xmppConnection;

        private final Jid with;
        private final String streamId;

        // can be lowered by the peer while we are sending
        private volatile int blockSize;
        private final PipedInputStream inputStream;
        private final Semaphore semaphore = new Semaphore(MAX_IN_FLIGHT);
        private final AtomicInteger sequencer = new AtomicInteger();
        private final AtomicBoolean isSending = new AtomicBoolean(true);

        private BlockSender(
                XmppConnection xmppConnection,
                final Jid with,
                String streamId,
                int blockSize,
                PipedInputStream inputStream) {
            this.xmppConnection = xmppConnection;
            this.with = with;
            this.streamId = streamId;
            this.blockSize = blockSize;
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            final var buffer = new byte[blockSize];
            try {
                while (isSending.get()) {
                    // never more than the currently negotiated block size, even if the peer
                    // lowered it after we started
                    final int count =
                            this.inputStream.read(buffer, 0, Math.min(buffer.length, blockSize));
                    if (count < 0) {
                        Log.d(Config.LOGTAG, "block sender reached EOF");
                        // Close only after every data block was acknowledged; an error response
                        // (or termination) clears isSending and then no close is sent.
                        this.semaphore.acquire(MAX_IN_FLIGHT);
                        if (isSending.get()) {
                            sendClose();
                        }
                        return;
                    }
                    this.semaphore.acquire();
                    final var block = new byte[count];
                    System.arraycopy(buffer, 0, block, 0, block.length);
                    sendIbbBlock(sequencer.getAndIncrement(), block);
                }
            } catch (final InterruptedException | InterruptedIOException e) {
                if (isSending.get()) {
                    Log.w(Config.LOGTAG, "IbbBlockSender got interrupted while sending", e);
                }
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "block sender terminated", e);
            } finally {
                Closeables.closeQuietly(inputStream);
            }
        }

        private void sendIbbBlock(final int sequence, final byte[] block) {
            Log.d(Config.LOGTAG, "sending ibb block #" + sequence + " " + block.length + " bytes");
            final var iqPacket = new Iq(Iq.Type.SET);
            iqPacket.setTo(with);
            final var data = iqPacket.addChild("data", Namespace.IBB);
            data.setAttribute("sid", this.streamId);
            data.setAttribute("seq", sequence);
            data.setContent(BaseEncoding.base64().encode(block));
            this.xmppConnection.sendIqPacket(
                    iqPacket,
                    response -> {
                        if (response.getType() != Iq.Type.RESULT) {
                            Log.d(
                                    Config.LOGTAG,
                                    "received iq error in response to data block #" + sequence);
                            isSending.set(false);
                        }
                        semaphore.release();
                    });
        }

        private void sendClose() {
            Log.d(Config.LOGTAG, "sending ibb close");
            final var iqPacket = new Iq(Iq.Type.SET);
            iqPacket.setTo(with);
            final var close = iqPacket.addChild("close", Namespace.IBB);
            close.setAttribute("sid", this.streamId);
            this.xmppConnection.sendIqPacket(iqPacket, null);
        }

        @Override
        public void close() {
            this.isSending.set(false);
        }

        public void setBlockSize(final int blockSize) {
            this.blockSize = blockSize;
        }
    }

    public enum PacketType {
        OPEN,
        DATA,
        CLOSE
    }
}
