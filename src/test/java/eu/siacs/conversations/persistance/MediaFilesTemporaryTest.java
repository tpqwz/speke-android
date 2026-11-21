package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaFilesTemporaryTest {

    @Test
    public void uiReferencedFilesAreResumable() {
        assertTrue(MediaFiles.isResumableTemporary("PHOTO_0b8c.jpg"));
        assertTrue(MediaFiles.isResumableTemporary("VIDEO_0b8c.mp4"));
        assertTrue(MediaFiles.isResumableTemporary("EDIT_0b8c.jpg"));
        assertTrue(MediaFiles.isResumableTemporary("SHARE_1700000000-0.pdf"));
        assertTrue(MediaFiles.isResumableTemporary("RECORDING_2026-11-20_10-00-00.opus"));
    }

    @Test
    public void deadWorkIsNotResumable() {
        // transcoder output, stripped and decrypted copies, partial writes
        assertFalse(MediaFiles.isResumableTemporary("3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b.mp4"));
        assertFalse(MediaFiles.isResumableTemporary("3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b.strip"));
        assertFalse(MediaFiles.isResumableTemporary("3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b.zip"));
        assertFalse(MediaFiles.isResumableTemporary("3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b"));
        assertFalse(MediaFiles.isResumableTemporary("PHOTO_0b8c.jpg.part"));
        assertFalse(MediaFiles.isResumableTemporary("EDIT_0b8c.jpg.part"));
    }

    @Test
    public void uploadCopiesInTheCacheAreRecognised() {
        final String uuid = "3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b";
        assertTrue(MediaFiles.isCacheTemporaryName(uuid));
        assertTrue(MediaFiles.isCacheTemporaryName(uuid + ".mp4"));
        assertTrue(MediaFiles.isCacheTemporaryName(uuid + ".jpeg"));
        assertTrue(MediaFiles.isCacheTemporaryName(uuid + ".strip"));
    }

    @Test
    public void otherCacheFilesAreKept() {
        final String uuid = "3f2a9c1e-7d3b-4c55-9a1e-0c1d2e3f4a5b";
        assertFalse(MediaFiles.isCacheTemporaryName(uuid + ".pgp")); // ciphertext to decrypt
        assertFalse(MediaFiles.isCacheTemporaryName("crash.log"));
        assertFalse(MediaFiles.isCacheTemporaryName("bg.jpg"));
        assertFalse(MediaFiles.isCacheTemporaryName("zb2rhe5P4gXftAwvA4eXQ5HJwsER2owDyS9sKaQRRVQPn93bA.jpg"));
        assertFalse(MediaFiles.isCacheTemporaryName(uuid + ".jpg.part"));
        assertFalse(MediaFiles.isCacheTemporaryName(uuid + "-copy.jpg"));
        assertFalse(MediaFiles.isCacheTemporaryName("image_manager_disk_cache"));
        assertFalse(MediaFiles.isCacheTemporaryName(null));
    }
}
