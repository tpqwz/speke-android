package eu.siacs.conversations.persistance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MediaCipherTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static MediaCipher cipher;
    private static byte[] plain;

    @BeforeClass
    public static void setUp() throws Exception {
        cipher = MediaCipher.fromKeyset(MediaCipher.newKeyset());
        // several segments plus a partial one
        plain = new byte[3 * MediaCipher.SEGMENT_SIZE + 12345];
        new Random(42).nextBytes(plain);
    }

    private File encrypted(final byte[] content) throws IOException {
        final File file = new File(folder.getRoot(), "f" + System.nanoTime() + ".jpg");
        cipher.encryptFile(new ByteArrayInputStream(content), file);
        return file;
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void roundTrip() throws IOException {
        final File file = encrypted(plain);
        Assert.assertTrue(MediaCipher.isEncrypted(file));
        Assert.assertEquals(0, partFiles(file).length);
        try (InputStream in = cipher.openInput(file)) {
            Assert.assertArrayEquals(plain, readAll(in));
        }
        Assert.assertEquals(plain.length, cipher.plainLength(file));
    }

    @Test
    public void emptyFileRoundTrip() throws IOException {
        final File file = encrypted(new byte[0]);
        try (InputStream in = cipher.openInput(file)) {
            Assert.assertEquals(0, readAll(in).length);
        }
        Assert.assertEquals(0, cipher.plainLength(file));
    }

    @Test
    public void plainFilesAreRecognized() throws IOException {
        final File file = folder.newFile("plain.jpg");
        Files.write(file.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3, 4, 5, 6, 7});
        Assert.assertFalse(MediaCipher.isEncrypted(file));
        Assert.assertFalse(MediaCipher.isEncrypted(folder.newFile("empty")));
        Assert.assertFalse(MediaCipher.isEncrypted(new File(folder.getRoot(), "missing")));
    }

    @Test
    public void ciphertextHidesTheContent() throws IOException {
        final byte[] jpeg = new byte[100_000];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        final File file = encrypted(jpeg);
        final byte[] raw = Files.readAllBytes(file.toPath());
        Assert.assertNotEquals((byte) 0xff, raw[MediaCipher.MAGIC.length]);
        // long runs of zeros in the plaintext don't show up in the ciphertext
        int zeros = 0;
        for (int i = MediaCipher.MAGIC.length; i < raw.length; i++) {
            zeros = raw[i] == 0 ? zeros + 1 : 0;
            Assert.assertTrue(zeros < 32);
        }
    }

    @Test
    public void seekableReadsAtRandomOffsets() throws IOException {
        final File file = encrypted(plain);
        try (SeekableByteChannel channel = cipher.openSeekable(file)) {
            Assert.assertEquals(plain.length, channel.size());
            final Random random = new Random(7);
            for (int i = 0; i < 50; i++) {
                final int position = random.nextInt(plain.length);
                final int length = Math.min(1 + random.nextInt(70_000), plain.length - position);
                final ByteBuffer buffer = ByteBuffer.allocate(length);
                channel.position(position);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // keep reading
                }
                Assert.assertArrayEquals(
                        Arrays.copyOfRange(plain, position, position + length), buffer.array());
            }
        }
    }

    @Test(expected = IOException.class)
    public void tamperingIsDetected() throws IOException {
        final File file = encrypted(plain);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(MediaCipher.SEGMENT_SIZE + 100);
            final int b = raf.read();
            raf.seek(MediaCipher.SEGMENT_SIZE + 100);
            raf.write(b ^ 1);
        }
        try (InputStream in = cipher.openInput(file)) {
            readAll(in);
        }
    }

    @Test(expected = IOException.class)
    public void truncationIsDetected() throws IOException {
        final File file = encrypted(plain);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(MediaCipher.MAGIC.length + 2L * MediaCipher.SEGMENT_SIZE);
        }
        try (InputStream in = cipher.openInput(file)) {
            readAll(in);
        }
    }

    @Test(expected = IOException.class)
    public void anotherKeyCannotDecrypt() throws Exception {
        final File file = encrypted(plain);
        final MediaCipher other = MediaCipher.fromKeyset(MediaCipher.newKeyset());
        try (InputStream in = other.openInput(file)) {
            readAll(in);
        }
    }

    @Test
    public void abortLeavesNoFile() throws IOException {
        final File target = new File(folder.getRoot(), "aborted.mp4");
        final MediaCipher.EncryptedFileOutputStream out = cipher.create(target);
        out.write(new byte[] {1, 2, 3});
        out.abort();
        Assert.assertFalse(target.exists());
        Assert.assertEquals(0, partFiles(target).length);
    }

    @Test
    public void closeWithoutCommitLeavesNoFile() throws IOException {
        final File target = new File(folder.getRoot(), "closed.mp4");
        try (MediaCipher.EncryptedFileOutputStream out = cipher.create(target)) {
            out.write(new byte[] {1});
        }
        Assert.assertFalse(target.exists());
    }

    /** Content-addressed files: two writers storing the same target must not corrupt it. */
    @Test
    public void concurrentWritersOfOneTarget() throws Exception {
        final File target = new File(folder.getRoot(), "same.jpg");
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        final java.util.List<java.util.concurrent.Future<?>> writes = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            writes.add(
                    pool.submit(
                            () -> {
                                cipher.encryptFile(new ByteArrayInputStream(plain), target);
                                return null;
                            }));
        }
        for (final java.util.concurrent.Future<?> write : writes) {
            write.get();
        }
        pool.shutdown();
        try (InputStream in = cipher.openInput(target)) {
            Assert.assertArrayEquals(plain, readAll(in));
        }
        Assert.assertEquals(0, partFiles(target).length);
    }

    /** Unfinished writes left next to {@code target} (unique {@code .part} names). */
    private static File[] partFiles(final File target) {
        final File[] parts =
                target.getParentFile().listFiles((dir, name) -> name.endsWith(".part"));
        return parts == null ? new File[0] : parts;
    }
}
