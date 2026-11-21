package eu.siacs.conversations.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Type detection for files that arrive without one (PQ OMEMO2 Jingle file offers). */
public class MimeUtilsSniffTest {

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static byte[] bytes(final int... values) {
        final byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; ++i) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void assertSniff(final String expected, final byte[] head) {
        Assert.assertEquals(expected, MimeUtils.sniffExtension(head));
    }

    @Test
    public void images() {
        assertSniff("jpg", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00));
        assertSniff("png", bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0));
        assertSniff("gif", ascii("GIF89a\0\0"));
        assertSniff("webp", ascii("RIFF\0\0\0\0WEBPVP8 "));
        assertSniff("heic", ascii("\0\0\0\u0018ftypheic\0\0\0\0"));
        assertSniff("avif", ascii("\0\0\0\u001cftypavif\0\0\0\0"));
    }

    @Test
    public void audioAndVideo() {
        assertSniff("mp4", ascii("\0\0\0\u0018ftypisom\0\0\0\0"));
        assertSniff("m4a", ascii("\0\0\0\u0018ftypM4A \0\0\0\0"));
        assertSniff("mov", ascii("\0\0\0\u0014ftypqt  \0\0\0\0"));
        assertSniff("3gp", ascii("\0\0\0\u0018ftyp3gp4\0\0\0\0"));
        assertSniff("oga", ascii("OggS\0\u0002\0\0\0\0\0\0\0\0OpusHead"));
        assertSniff("ogg", ascii("OggS\0\u0002\0\0\0\0\0\0\0\0\u0080theora"));
        assertSniff("mp3", ascii("ID3\u0004\0\0"));
        assertSniff("mp3", bytes(0xFF, 0xFB, 0x90, 0x00));
        assertSniff(null, bytes(0xFF, 0xFE, 0x68, 0x00)); // UTF-16LE byte order mark + "h"
        assertSniff(null, bytes(0xFF, 0xFD, 0x90, 0x00)); // Layer II
        assertSniff(null, bytes(0xFF, 0xFB, 0xF0, 0x00)); // invalid bitrate
        assertSniff(null, bytes(0xFF, 0xFB)); // too short for a header
        assertSniff("aac", bytes(0xFF, 0xF1, 0x50, 0x80));
        assertSniff("wav", ascii("RIFF\0\0\0\0WAVEfmt "));
        assertSniff("flac", ascii("fLaC\0\0\0\""));
        assertSniff("webm", bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01));
    }

    @Test
    public void documents() {
        assertSniff("pdf", ascii("%PDF-1.7\n"));
        assertSniff("zip", bytes('P', 'K', 0x03, 0x04, 0x14, 0x00));
    }

    @Test
    public void unknownShortOrEmpty() {
        assertSniff(null, ascii("hello world"));
        assertSniff(null, new byte[0]);
        assertSniff(null, bytes(0xFF));
        assertSniff(null, ascii("RIFF\0\0"));
        assertSniff(null, ascii("\0\0\0\u0018ftyp"));
        assertSniff(null, null);
    }

    private File zip(final String... entries) throws Exception {
        final File file = folder.newFile();
        try (final ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (final String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write(ascii("x"));
                out.closeEntry();
            }
        }
        return file;
    }

    @Test
    public void webxdcNeedsIndexAtTheRoot() throws Exception {
        Assert.assertTrue(MimeUtils.isWebxdcArchive(zip("index.html", "manifest.toml")));
        Assert.assertFalse(MimeUtils.isWebxdcArchive(zip("word/document.xml")));
        Assert.assertFalse(MimeUtils.isWebxdcArchive(zip("app/index.html")));
        Assert.assertFalse(MimeUtils.isWebxdcArchive(folder.newFile()));
    }
}
