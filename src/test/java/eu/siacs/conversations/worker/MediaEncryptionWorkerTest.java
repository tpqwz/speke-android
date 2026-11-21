package eu.siacs.conversations.worker;

import eu.siacs.conversations.persistance.MediaCipher;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A crash between writing X.<uuid>.mcenc and renaming it over X never loses or duplicates X. */
public class MediaEncryptionWorkerTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String UUID = "3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b";

    @Test
    public void leftoverIsDroppedWhenTheOriginalStillExists() throws IOException {
        final File original = folder.newFile("a.jpg");
        Files.write(original.toPath(), new byte[] {1, 2, 3});
        final File leftover = folder.newFile("a.jpg." + UUID + ".mcenc");
        MediaEncryptionWorker.recoverInterrupted(folder.getRoot());
        Assert.assertTrue(original.exists());
        Assert.assertFalse(leftover.exists());
    }

    @Test
    public void leftoverReplacesAMissingOriginal() throws Exception {
        final MediaCipher cipher = MediaCipher.fromKeyset(MediaCipher.newKeyset());
        final File leftover = new File(folder.getRoot(), "b.jpg." + UUID + ".mcenc");
        cipher.encryptFile(new ByteArrayInputStream(new byte[] {4, 5}), leftover);
        MediaEncryptionWorker.recoverInterrupted(folder.getRoot());
        final File original = new File(folder.getRoot(), "b.jpg");
        Assert.assertTrue(original.exists());
        Assert.assertTrue(MediaCipher.isEncrypted(original));
        Assert.assertFalse(leftover.exists());
    }

    /** Received attachments named like work files are ordinary attachments. */
    @Test
    public void receivedEncAndPartFilesAreOrdinaryAttachments() throws IOException {
        final File received = folder.newFile("photo.enc");
        Files.write(received.toPath(), new byte[] {7});
        MediaEncryptionWorker.recoverInterrupted(folder.getRoot());
        Assert.assertTrue(received.exists());
        Assert.assertFalse(MediaEncryptionWorker.isWorkFile("photo.enc"));
        Assert.assertFalse(MediaEncryptionWorker.isWorkFile("x.part"));
        Assert.assertFalse(MediaEncryptionWorker.isWorkFile("x.jpg.part"));
        Assert.assertFalse(MediaEncryptionWorker.isWorkFile("x.mcenc"));
    }

    @Test
    public void ownWorkFilesAreRecognised() {
        Assert.assertTrue(MediaEncryptionWorker.isWorkFile("a.jpg." + UUID + ".mcenc"));
        Assert.assertTrue(MediaEncryptionWorker.isWorkFile("a.jpg." + UUID + ".part"));
        Assert.assertTrue(
                MediaEncryptionWorker.isWorkFile(
                        MediaCipher.partFileFor(new File("/x/a.jpg")).getName()));
        Assert.assertEquals("a.jpg", MediaEncryptionWorker.originalOfWorkerCopy("a.jpg." + UUID + ".mcenc"));
    }
}
