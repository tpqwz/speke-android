package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class MediaCryptoAreaTest {

    private static File filesDir() throws Exception {
        return Files.createTempDirectory("files").toFile();
    }

    @Test
    public void privateContentFoldersAreEncrypted() throws Exception {
        final File files = filesDir();
        assertTrue(MediaCrypto.isEncryptedArea(files, new File(files, "media/zb2abc.jpg")));
        assertTrue(MediaCrypto.isEncryptedArea(files, new File(files, "avatars/0a1b2c")));
        assertTrue(MediaCrypto.isEncryptedArea(files, new File(files, "backgrounds/bg.jpg")));
        assertTrue(MediaCrypto.isEncryptedArea(files, new File(files, "Stickers/Pack/a.webp")));
        assertTrue(MediaCrypto.isEncryptedArea(files, new File(files, "Stickers/GIFs/b.gif")));
    }

    @Test
    public void otherPlacesAreNot() throws Exception {
        final File files = filesDir();
        assertFalse(MediaCrypto.isEncryptedArea(files, new File(files, "Camera/IMG_1.jpg")));
        assertFalse(MediaCrypto.isEncryptedArea(files, new File(files, "media")));
        assertFalse(MediaCrypto.isEncryptedArea(files, new File(files, "mediafoo/x.jpg")));
        assertFalse(MediaCrypto.isEncryptedArea(files, new File(files, "media/../Camera/x.jpg")));
        assertFalse(MediaCrypto.isEncryptedArea(files, new File("/sdcard/Documents/x.jpg")));
        assertFalse(MediaCrypto.isEncryptedArea(files, null));
    }
}
