package eu.siacs.conversations.persistance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AttachmentStorageTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void restoredFileAppearsOnlyOnCommit() throws IOException {
        final File target = new File(folder.getRoot(), "media/a.jpg");
        final var writer = AttachmentStorage.PLAIN.beginRestore(target);
        writer.write(new byte[] {1, 2});
        writer.write(new byte[] {3});
        Assert.assertFalse(target.exists());
        writer.commit();
        Assert.assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target.toPath()));
        Assert.assertEquals(0, partFiles(target).length);
        try (InputStream in = AttachmentStorage.PLAIN.openForBackup(target)) {
            Assert.assertEquals(1, in.read());
        }
    }

    @Test
    public void abortLeavesNothing() throws IOException {
        final File target = new File(folder.getRoot(), "b.jpg");
        final var writer = AttachmentStorage.PLAIN.beginRestore(target);
        writer.write(new byte[] {1});
        writer.abort();
        writer.abort();
        Assert.assertFalse(target.exists());
        Assert.assertEquals(0, partFiles(target).length);
    }

    @Test
    public void commitReplacesAnExistingFile() throws IOException {
        final File target = folder.newFile("c.jpg");
        Files.write(target.toPath(), new byte[] {9, 9, 9, 9});
        final var writer = AttachmentStorage.PLAIN.beginRestore(target);
        writer.write(new byte[] {7});
        writer.commit();
        Assert.assertArrayEquals(new byte[] {7}, Files.readAllBytes(target.toPath()));
    }

    @Test(expected = IOException.class)
    public void writeAfterCommitFails() throws IOException {
        final var writer = AttachmentStorage.PLAIN.beginRestore(new File(folder.getRoot(), "d"));
        writer.commit();
        writer.write(new byte[] {1});
    }

    /** Unfinished writes left next to {@code target} (unique {@code .part} names). */
    private static File[] partFiles(final File target) {
        final File[] parts =
                target.getParentFile().listFiles((dir, name) -> name.endsWith(".part"));
        return parts == null ? new File[0] : parts;
    }
}
