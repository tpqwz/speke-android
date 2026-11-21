package eu.siacs.conversations.utils;

import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

public class BackupFileHeaderTest {

    private static byte[] write(final BackupFileHeader header) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        header.write(new DataOutputStream(out));
        return out.toByteArray();
    }

    private static BackupFileHeader read(final byte[] bytes) throws IOException {
        return BackupFileHeader.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static BackupFileHeader header(final int version) {
        return new BackupFileHeader(
                version, "monocles chat", Jid.of("kim@example.org"), 1L, new byte[12], new byte[16]);
    }

    @Test
    public void passwordHeaderIsUnchanged() throws IOException {
        final byte[] bytes = write(header(BackupFileHeader.VERSION));
        final BackupFileHeader read = read(bytes);
        Assert.assertEquals(4, read.getVersion());
        Assert.assertFalse(read.usesRecoveryKey());
        // v4 layout: no key-type byte
        Assert.assertEquals(4 + 2 + 13 + 2 + 15 + 8 + 12 + 16, bytes.length);
    }

    @Test
    public void recoveryKeyHeaderRoundTrip() throws IOException {
        final byte[] bytes = write(header(BackupFileHeader.RECOVERY_KEY_VERSION));
        final BackupFileHeader read = read(bytes);
        Assert.assertEquals(5, read.getVersion());
        Assert.assertTrue(read.usesRecoveryKey());
        Assert.assertEquals("kim@example.org", read.getJid().toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void newerVersionsAreRejected() throws IOException {
        read(write(header(6)));
    }
}
