package eu.siacs.conversations.utils;

import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@link PartialFiles#move}: a completed partial file reaches its target in one piece. */
public class PartialFilesTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static void write(final File file, final String content) throws IOException {
        Files.asCharSink(file, StandardCharsets.UTF_8).write(content);
    }

    private static String read(final File file) throws IOException {
        return Files.asCharSource(file, StandardCharsets.UTF_8).read();
    }

    @Test
    public void movesContent() throws IOException {
        final File partial = folder.newFile("partial");
        write(partial, "complete");
        final File target = new File(folder.getRoot(), "target");
        PartialFiles.move(partial, target);
        Assert.assertEquals("complete", read(target));
        Assert.assertFalse(partial.exists());
    }

    @Test
    public void replacesExistingTarget() throws IOException {
        final File partial = folder.newFile("partial");
        write(partial, "new");
        final File target = folder.newFile("target");
        write(target, "old");
        PartialFiles.move(partial, target);
        Assert.assertEquals("new", read(target));
        Assert.assertFalse(partial.exists());
    }

    @Test
    public void createsParentDirectories() throws IOException {
        final File partial = folder.newFile("partial");
        write(partial, "complete");
        final File target = new File(folder.getRoot(), "a/b/target");
        PartialFiles.move(partial, target);
        Assert.assertEquals("complete", read(target));
    }

    @Test
    public void missingSourceLeavesTargetAlone() throws IOException {
        final File partial = new File(folder.getRoot(), "missing");
        final File target = folder.newFile("target");
        write(target, "old");
        try {
            PartialFiles.move(partial, target);
            Assert.fail("expected IOException");
        } catch (final IOException expected) {
            // fine
        }
        Assert.assertEquals("old", read(target));
        Assert.assertFalse(new File(folder.getRoot(), ".target.part").exists());
    }
}
