package eu.siacs.conversations.services;

import com.google.common.io.ByteStreams;
import eu.siacs.conversations.entities.DownloadableFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import org.junit.Assert;
import org.junit.Test;

/** Closing the decrypting stream is where an altered file is noticed. */
public class FileDecryptionStreamTest {

    private static byte[] encrypt(final byte[] key, final byte[] iv, final byte[] plain)
            throws IOException {
        final DownloadableFile keyHolder = new DownloadableFile("unused");
        keyHolder.setKey(key);
        keyHolder.setIv(iv);
        try (InputStream in =
                AbstractConnectionManager.upgrade(keyHolder, new ByteArrayInputStream(plain))) {
            return ByteStreams.toByteArray(in);
        }
    }

    private static void decrypt(
            final byte[] key, final byte[] iv, final byte[] cipher, final File out)
            throws IOException {
        final DownloadableFile target = new DownloadableFile(out.getAbsolutePath());
        target.setKey(key);
        target.setIv(iv);
        try (OutputStream os = AbstractConnectionManager.createOutputStream(target, false, true)) {
            Assert.assertNotNull(os);
            os.write(cipher);
        }
    }

    @Test
    public void unchangedFileDecrypts() throws IOException {
        final SecureRandom random = new SecureRandom();
        final byte[] key = new byte[32];
        final byte[] iv = new byte[12];
        random.nextBytes(key);
        random.nextBytes(iv);
        final byte[] plain = "a picture, more or less".getBytes();
        final File out = File.createTempFile("decrypt", ".bin");
        try {
            decrypt(key, iv, encrypt(key, iv, plain), out);
            Assert.assertArrayEquals(plain, Files.readAllBytes(out.toPath()));
        } finally {
            out.delete();
        }
    }

    @Test
    public void alteredFileFailsOnClose() throws IOException {
        final SecureRandom random = new SecureRandom();
        final byte[] key = new byte[32];
        final byte[] iv = new byte[12];
        random.nextBytes(key);
        random.nextBytes(iv);
        final byte[] cipher = encrypt(key, iv, "a picture, more or less".getBytes());
        cipher[0] ^= 0x01;
        final File out = File.createTempFile("decrypt", ".bin");
        try {
            decrypt(key, iv, cipher, out);
            Assert.fail("altered file was accepted");
        } catch (final IOException expected) {
            // expected
        } finally {
            out.delete();
        }
    }
}
