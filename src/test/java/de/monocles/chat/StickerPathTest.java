package de.monocles.chat;

import java.io.File;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

/** Sticker names from a pack's JSON are only used as plain file names. */
public class StickerPathTest {

    @Test
    public void traversalIsNeutralised() {
        Assert.assertEquals(
                "_shared_prefs", DownloadDefaultStickers.safePathComponent("../shared_prefs"));
        Assert.assertEquals(
                "_.._databases", DownloadDefaultStickers.safePathComponent("../../databases"));
        Assert.assertNull(DownloadDefaultStickers.safePathComponent(".."));
        Assert.assertNull(DownloadDefaultStickers.safePathComponent("..."));
        Assert.assertNull(DownloadDefaultStickers.safePathComponent(""));
        Assert.assertNull(DownloadDefaultStickers.safePathComponent(null));
        Assert.assertEquals("a_b", DownloadDefaultStickers.safePathComponent("a\\b"));
        Assert.assertEquals("hidden", DownloadDefaultStickers.safePathComponent(".hidden"));
        Assert.assertEquals("Cats 🐱", DownloadDefaultStickers.safePathComponent("Cats 🐱"));
        for (final String raw :
                new String[] {"../../x", "a/../../b", "..\\..", "/etc/passwd", "x\u0000y"}) {
            final String safe = DownloadDefaultStickers.safePathComponent(raw);
            if (safe != null) {
                Assert.assertFalse(
                        raw, safe.contains("/") || safe.contains("\\") || safe.startsWith("."));
            }
        }
    }

    @Test
    public void onlyImagesAreAccepted() {
        Assert.assertEquals("webp", DownloadDefaultStickers.stickerExtension("image/webp"));
        Assert.assertEquals(
                "png", DownloadDefaultStickers.stickerExtension("image/png; charset=binary"));
        Assert.assertNull(DownloadDefaultStickers.stickerExtension("text/xml"));
        Assert.assertNull(DownloadDefaultStickers.stickerExtension("application/octet-stream"));
        Assert.assertNull(DownloadDefaultStickers.stickerExtension(null));
    }

    @Test
    public void containmentCheck() throws Exception {
        final File dir = Files.createTempDirectory("stickers").toFile();
        Assert.assertTrue(DownloadDefaultStickers.isInside(dir, new File(dir, "pack/a.png")));
        Assert.assertFalse(
                DownloadDefaultStickers.isInside(dir, new File(dir, "../shared_prefs/x.xml")));
        Assert.assertFalse(
                DownloadDefaultStickers.isInside(
                        dir, new File(dir.getParentFile(), dir.getName() + "x/a")));
    }
}
