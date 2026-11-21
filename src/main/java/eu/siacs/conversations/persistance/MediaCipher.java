package eu.siacs.conversations.persistance;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Encryption at rest of attachments: Tink's streaming AEAD (AES-256-GCM with per-file HKDF
 * subkeys, per-segment authentication and truncation/reordering detection), which can also be
 * read at random offsets, as players, metadata readers and file descriptors need.
 *
 * <p>An encrypted file is {@link #MAGIC} followed by the Tink ciphertext; anything else is a
 * legacy plain file and read as is. That lets old, new and not-yet-migrated files live side by
 * side, and lets every reader handle both.
 */
public final class MediaCipher {

    /** "MCMEDIA" + format version 1. */
    static final byte[] MAGIC = {'M', 'C', 'M', 'E', 'D', 'I', 'A', 1};

    /** 256 KiB: large enough for fast sequential reads, small enough for seeking and memory. */
    static final int SEGMENT_SIZE = 256 * 1024;

    private static final byte[] AAD = "monocles-media-v1".getBytes(StandardCharsets.UTF_8);

    private final StreamingAead streamingAead;

    private MediaCipher(final KeysetHandle keyset) throws GeneralSecurityException {
        this.streamingAead = keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
    }

    private static void register() throws GeneralSecurityException {
        StreamingAeadConfig.register();
    }

    /** A new random keyset, serialized for storage in the encrypted database. */
    public static String newKeyset() throws GeneralSecurityException {
        register();
        final AesGcmHkdfStreamingParameters parameters =
                AesGcmHkdfStreamingParameters.builder()
                        .setKeySizeBytes(32)
                        .setDerivedAesGcmKeySizeBytes(32)
                        .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                        .setCiphertextSegmentSizeBytes(SEGMENT_SIZE)
                        .build();
        return TinkJsonProtoKeysetFormat.serializeKeyset(
                KeysetHandle.generateNew(parameters), InsecureSecretKeyAccess.get());
    }

    public static MediaCipher fromKeyset(final String serializedKeyset)
            throws GeneralSecurityException {
        register();
        return new MediaCipher(
                TinkJsonProtoKeysetFormat.parseKeyset(
                        serializedKeyset, InsecureSecretKeyAccess.get()));
    }

    /** Whether {@code file} is stored encrypted (starts with the magic). */
    public static boolean isEncrypted(final File file) {
        if (file == null || !file.isFile() || file.length() < MAGIC.length) {
            return false;
        }
        final byte[] head = new byte[MAGIC.length];
        try (final InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < head.length) {
                final int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    return false;
                }
                read += n;
            }
        } catch (final IOException e) {
            return false;
        }
        return Arrays.equals(head, MAGIC);
    }

    /** Plaintext of an encrypted file, read sequentially. */
    public InputStream openInput(final File file) throws IOException {
        final InputStream raw = new BufferedInputStream(new FileInputStream(file), SEGMENT_SIZE);
        try {
            skipMagic(raw);
            return streamingAead.newDecryptingStream(raw, AAD);
        } catch (final GeneralSecurityException e) {
            raw.close();
            throw new IOException("could not decrypt " + file, e);
        } catch (final IOException e) {
            raw.close();
            throw e;
        }
    }

    /** Plaintext of an encrypted file with random access; its {@code size()} is the plain size. */
    public SeekableByteChannel openSeekable(final File file) throws IOException {
        final FileInputStream in = new FileInputStream(file);
        final SeekableByteChannel ciphertext = new OffsetChannel(in.getChannel(), MAGIC.length);
        try {
            final SeekableByteChannel plaintext =
                    streamingAead.newSeekableDecryptingChannel(ciphertext, AAD);
            // the keyset-level decrypter picks its key on the first read; only after that
            // size() works, so read once and rewind (this also checks key and header early)
            plaintext.read(ByteBuffer.allocate(1));
            plaintext.position(0);
            return plaintext;
        } catch (final GeneralSecurityException e) {
            in.close();
            throw new IOException("could not decrypt " + file, e);
        } catch (final IOException e) {
            in.close();
            throw e;
        }
    }

    /** The plaintext size of an encrypted file. */
    public long plainLength(final File file) throws IOException {
        try (final SeekableByteChannel channel = openSeekable(file)) {
            return channel.size();
        }
    }

    /**
     * Encrypts into {@code raw}: writes the magic, then the ciphertext. Closing the returned
     * stream writes the last segment (without it, the file reads as truncated) and closes raw.
     */
    public OutputStream encryptTo(final OutputStream raw) throws IOException {
        raw.write(MAGIC);
        try {
            return streamingAead.newEncryptingStream(raw, AAD);
        } catch (final GeneralSecurityException e) {
            throw new IOException("could not encrypt", e);
        }
    }

    /**
     * A unique temporary name next to {@code target} (same folder, so the final rename is
     * atomic). Files are content-addressed, so two writers can store the same target at the same
     * time; a shared name would let them interleave into one corrupt file.
     */
    public static File partFileFor(final File target) {
        return new File(target.getPath() + "." + java.util.UUID.randomUUID() + ".part");
    }

    private static final java.util.regex.Pattern PART_FILE =
            java.util.regex.Pattern.compile(
                    ".*\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part");

    /**
     * Whether {@code name} is an unfinished write of {@link #partFileFor}. Only that exact shape
     * counts: a received attachment that merely ends in ".part" is an ordinary file.
     */
    public static boolean isPartFile(final String name) {
        return name != null && PART_FILE.matcher(name).matches();
    }

    /**
     * A new encrypted file at {@code target}: written to a unique {@code .part} file, synced, and only
     * renamed into place by {@link EncryptedFileOutputStream#commit()} — a crash or error never
     * leaves a half-written file under the real name.
     */
    public EncryptedFileOutputStream create(final File target) throws IOException {
        final File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        final File part = partFileFor(target);
        final FileOutputStream file = new FileOutputStream(part);
        try {
            // Tink closes its sink when it writes the final segment; keep the file open past
            // that so it can still be synced before it is renamed into place
            final OutputStream sink =
                    new FilterOutputStream(file) {
                        @Override
                        public void write(final byte[] b, final int off, final int len)
                                throws IOException {
                            out.write(b, off, len);
                        }

                        @Override
                        public void close() throws IOException {
                            flush();
                        }
                    };
            return new EncryptedFileOutputStream(encryptTo(sink), file, part, target);
        } catch (final IOException e) {
            file.close();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
    }

    /** Encrypts all of {@code plain} into a new file at {@code target} (atomically). */
    public void encryptFile(final InputStream plain, final File target) throws IOException {
        final EncryptedFileOutputStream out = create(target);
        try {
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = plain.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.commit();
        } finally {
            out.abort(); // no-op after commit
        }
    }

    private static void skipMagic(final InputStream in) throws IOException {
        final byte[] head = new byte[MAGIC.length];
        int read = 0;
        while (read < head.length) {
            final int n = in.read(head, read, head.length - read);
            if (n < 0) {
                throw new IOException("encrypted file too short");
            }
            read += n;
        }
        if (!Arrays.equals(head, MAGIC)) {
            throw new IOException("not an encrypted media file");
        }
    }

    /** Output of {@link #create}: {@link #commit()} makes the file visible, otherwise nothing. */
    public static final class EncryptedFileOutputStream extends FilterOutputStream {
        private final FileOutputStream file;
        private final File part;
        private final File target;
        private boolean finished = false;

        private EncryptedFileOutputStream(
                final OutputStream encrypting,
                final FileOutputStream file,
                final File part,
                final File target) {
            super(encrypting);
            this.file = file;
            this.part = part;
            this.target = target;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            out.write(b, off, len);
        }

        /** Finishes the ciphertext, syncs it to disk and renames it to the target. */
        public void commit() throws IOException {
            if (finished) {
                throw new IOException("already finished");
            }
            finished = true;
            try {
                out.close(); // writes the final segment (the file itself stays open)
                file.getFD().sync(); // everything, including the final segment, is on disk
                file.close();
            } catch (final IOException e) {
                closeQuietly();
                deletePart();
                throw e;
            }
            if (!part.renameTo(target) && !(target.delete() && part.renameTo(target))) {
                deletePart();
                throw new IOException("Could not move " + part + " to " + target);
            }
        }

        /** Drops what was written; safe to call more than once and after commit. */
        public void abort() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                out.close();
            } catch (final IOException ignored) {
                // nothing to keep
            }
            closeQuietly();
            deletePart();
        }

        private void closeQuietly() {
            try {
                file.close();
            } catch (final IOException ignored) {
                // nothing to keep
            }
        }

        @Override
        public void close() throws IOException {
            // closing without commit means: not finished, drop it
            abort();
        }

        private void deletePart() {
            if (part.exists()) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
    }

    /** A file channel seen without its first {@code offset} bytes (the magic). */
    private static final class OffsetChannel implements SeekableByteChannel {
        private final FileChannel channel;
        private final long offset;

        private OffsetChannel(final FileChannel channel, final long offset) throws IOException {
            this.channel = channel;
            this.offset = offset;
            channel.position(offset);
        }

        @Override
        public int read(final ByteBuffer dst) throws IOException {
            return channel.read(dst);
        }

        @Override
        public int write(final ByteBuffer src) throws IOException {
            throw new IOException("read only");
        }

        @Override
        public long position() throws IOException {
            return channel.position() - offset;
        }

        @Override
        public SeekableByteChannel position(final long newPosition) throws IOException {
            channel.position(newPosition + offset);
            return this;
        }

        @Override
        public long size() throws IOException {
            return Math.max(0, channel.size() - offset);
        }

        @Override
        public SeekableByteChannel truncate(final long size) throws IOException {
            throw new IOException("read only");
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
