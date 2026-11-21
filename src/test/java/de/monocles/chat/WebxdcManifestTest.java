package de.monocles.chat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A WebXDC app is an archive someone sent us. Its manifest is read to label the message and the
 * thread - while drawing a list, before the user opened anything - so whatever is in there must
 * come back as a name or as nothing, never as an exception or an unbounded read.
 */
public class WebxdcManifestTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private File app(final String manifest) throws IOException {
        return appWith(
                "manifest.toml",
                manifest == null ? null : manifest.getBytes(StandardCharsets.UTF_8));
    }

    private File appWith(final String entryName, final byte[] content) throws IOException {
        final File file = folder.newFile();
        try (final ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write("<html></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (content != null) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    public void nameIsRead() throws Exception {
        Assert.assertEquals("Chess Board", WebxdcPage.readName(app("name = \"Chess Board\"\n")));
        Assert.assertEquals(
                "Poll",
                WebxdcPage.readName(
                        app("source_code_url = \"https://example.org\"\nname = '  Poll  '\n")));
    }

    @Test
    public void noManifestOrNoNameIsNothing() throws Exception {
        Assert.assertNull(WebxdcPage.readName(app(null)));
        Assert.assertNull(WebxdcPage.readName(app("")));
        Assert.assertNull(WebxdcPage.readName(app("source_code_url = \"https://example.org\"\n")));
        Assert.assertNull(WebxdcPage.readName(app("name = \"   \"\n")));
    }

    /** TomlTable.getString throws on these; drawing the message used to crash with it. */
    @Test
    public void nameOfTheWrongTypeIsNothing() throws Exception {
        for (final String manifest :
                new String[] {
                    "name = 1\n",
                    "name = true\n",
                    "name = [\"a\", \"b\"]\n",
                    "name = { first = \"a\" }\n",
                    "[name]\nfirst = \"a\"\n",
                    "name = 1979-05-27\n",
                }) {
            Assert.assertNull(manifest, WebxdcPage.readName(app(manifest)));
        }
    }

    @Test
    public void brokenTomlIsNothing() throws Exception {
        // The parser is forgiving and may still recover a value from a damaged line (as it
        // always did for the message bubble); what matters is that it comes back at all, and as
        // a plain label.
        final String recovered = WebxdcPage.readName(app("name = \"unterminated\n"));
        Assert.assertTrue(recovered == null || recovered.equals("unterminated"));
        Assert.assertNull(WebxdcPage.readName(app("\u0000\u0001\u0002 not toml at all = = =")));
        Assert.assertNull(
                WebxdcPage.readName(
                        appWith("manifest.toml", new byte[] {(byte) 0xff, (byte) 0xfe, 0x00})));
    }

    /** A few hundred bytes of archive that unpack to megabytes of manifest. */
    @Test
    public void oversizedManifestIsNotRead() throws Exception {
        final StringBuilder manifest = new StringBuilder("name = \"Bomb\"\n");
        final String filler = "# " + "a".repeat(1022) + "\n";
        while (manifest.length() <= 8 * 1024 * 1024) {
            manifest.append(filler);
        }
        final File file = app(manifest.toString());
        Assert.assertTrue("the archive itself is small", file.length() < 64 * 1024);
        Assert.assertNull(WebxdcPage.readName(file));
    }

    @Test
    public void manifestJustUnderTheLimitIsRead() throws Exception {
        final StringBuilder manifest = new StringBuilder("name = \"Big\"\n");
        while (manifest.length() < WebxdcPage.MAX_MANIFEST_BYTES - 200) {
            manifest.append("# padding padding padding\n");
        }
        Assert.assertEquals("Big", WebxdcPage.readName(app(manifest.toString())));
    }

    /** Must come back - as a name or nothing - rather than overflow the stack. */
    @Test
    public void deeplyNestedValueDoesNotCrash() throws Exception {
        final int depth = 20_000;
        final String manifest =
                "x = " + "[".repeat(depth) + "]".repeat(depth) + "\nname = \"Deep\"\n";
        final String name = WebxdcPage.readName(app(manifest));
        Assert.assertTrue(name == null || name.equals("Deep"));
    }

    @Test
    public void nameIsCutToLabelLength() throws Exception {
        final String name = WebxdcPage.readName(app("name = \"" + "x".repeat(5000) + "\"\n"));
        Assert.assertNotNull(name);
        Assert.assertEquals(WebxdcPage.MAX_NAME_LENGTH, name.length());
    }

    /** No line breaks, no bidi overrides that would let the label lie about what it says. */
    @Test
    public void controlAndDirectionCharactersAreRemoved() throws Exception {
        Assert.assertEquals(
                "Pollgpj.exe", WebxdcPage.readName(app("name = \"Poll\\u202Egpj.exe\\u202C\"\n")));
        Assert.assertEquals("Twolines", WebxdcPage.readName(app("name = \"Two\\nlines\"\n")));
        Assert.assertEquals("Tab", WebxdcPage.readName(app("name = \"T\\tab\\u0007\"\n")));
        Assert.assertNull(WebxdcPage.readName(app("name = \"\\u200B\\u202E\\n\"\n")));
        // ordinary non-ASCII text is untouched
        Assert.assertEquals("Schach ♟ 象棋", WebxdcPage.readName(app("name = \"Schach ♟ 象棋\"\n")));
    }

    @Test
    public void notAnArchiveIsNothing() throws Exception {
        final File notZip = folder.newFile();
        try (final FileOutputStream out = new FileOutputStream(notZip)) {
            out.write("name = \"Plain\"\n".getBytes(StandardCharsets.UTF_8));
        }
        Assert.assertNull(WebxdcPage.readName(notZip));
        Assert.assertNull(WebxdcPage.readName(new File(folder.getRoot(), "missing.xdc")));
        Assert.assertNull(WebxdcPage.readName(null));
    }

    /** Only the entry named manifest.toml counts. */
    @Test
    public void otherEntriesAreNotTheManifest() throws Exception {
        Assert.assertNull(
                WebxdcPage.readName(
                        appWith(
                                "sub/manifest.toml",
                                "name = \"Nested\"\n".getBytes(StandardCharsets.UTF_8))));
    }
}
