package de.monocles.chat;

import java.io.File;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

/** A Signal pack's author picks its title: it must never pick another pack's folder. */
public class StickerDirTest {

    private static final String ID = "9acc9e8aba563d26a4994e69263e3b25";
    private static final String OTHER_ID = "00000000000000000000000000000001";

    @Test
    public void freshTitleIsUsed() throws Exception {
        final File dir = Files.createTempDirectory("stickers").toFile();
        Assert.assertEquals("Cats", DownloadDefaultStickers.chooseSignalPackDir(dir, "Cats", ID));
    }

    @Test
    public void samePackKeepsItsFolder() throws Exception {
        final File dir = Files.createTempDirectory("stickers").toFile();
        Assert.assertTrue(new File(dir, "Cats/.signal-" + ID).mkdirs());
        Assert.assertEquals("Cats", DownloadDefaultStickers.chooseSignalPackDir(dir, "Cats", ID));
    }

    @Test
    public void foreignFolderIsNotReused() throws Exception {
        final File dir = Files.createTempDirectory("stickers").toFile();
        Assert.assertTrue(new File(dir, "Cats").mkdirs()); // e.g. a default pack
        Assert.assertTrue(new File(dir, "Cats 2/.signal-" + OTHER_ID).mkdirs());
        Assert.assertEquals("Cats 3", DownloadDefaultStickers.chooseSignalPackDir(dir, "Cats", ID));
    }

    @Test
    public void unusableTitleFallsBackToPackId() throws Exception {
        final File dir = Files.createTempDirectory("stickers").toFile();
        Assert.assertEquals(ID, DownloadDefaultStickers.chooseSignalPackDir(dir, "..", ID));
        Assert.assertEquals(ID, DownloadDefaultStickers.chooseSignalPackDir(dir, null, ID));
        Assert.assertEquals("_.._x", DownloadDefaultStickers.chooseSignalPackDir(dir, "/../x", ID));
    }
}
