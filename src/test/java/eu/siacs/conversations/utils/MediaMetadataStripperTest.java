package eu.siacs.conversations.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers the lossless image metadata removal. The parsers only drop whole segments/chunks, so the
 * tests build minimal files out of those and check which survive.
 */
public class MediaMetadataStripperTest {

    // ---- JPEG ----

    private static byte[] segment(final int marker, final byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        final int length = payload.length + 2;
        out.write(length >> 8);
        out.write(length & 0xff);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static boolean contains(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; ++i) {
            for (int j = 0; j < needle.length; ++j) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};
    // Entropy data containing a stuffed 0xFF00 and a restart marker, which must not end the scan.
    private static final byte[] SCAN = {
        0x12, (byte) 0xFF, 0x00, 0x34, (byte) 0xFF, (byte) 0xD0, 0x56
    };

    private static byte[] jpeg(final byte[]... metadataSegments) {
        return concat(
                SOI,
                concat(metadataSegments),
                segment(0xDB, new byte[] {0, 1, 2}), // DQT
                segment(0xC0, new byte[] {8, 0, 1, 0, 1, 1, 1, 0x11, 0}), // SOF0
                segment(0xDA, new byte[] {1, 1, 0, 0, 0x3F, 0}), // SOS
                SCAN,
                EOI);
    }

    @Test
    public void jpegDropsExifXmpCommentAndKeepsImageData() throws IOException {
        final byte[] exif = segment(0xE1, concat(ascii("Exif\0\0"), ascii("GPSSECRET")));
        final byte[] xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<gps>SECRET</gps>"));
        final byte[] comment = segment(0xFE, ascii("COMMENTSECRET"));
        final byte[] iptc = segment(0xED, ascii("Photoshop 3.0\0IPTCSECRET"));
        final byte[] icc = segment(0xE2, ascii("ICC_PROFILE\0\1\1profile"));
        final byte[] in = jpeg(exif, xmp, comment, iptc, icc);

        final byte[] out = MediaMetadataStripper.stripJpeg(in, 1);

        Assert.assertFalse(contains(out, ascii("SECRET")));
        Assert.assertTrue("ICC profile kept", contains(out, icc));
        Assert.assertTrue("scan data kept", contains(out, SCAN));
        Assert.assertArrayEquals(SOI, new byte[] {out[0], out[1]});
        Assert.assertArrayEquals(EOI, new byte[] {out[out.length - 2], out[out.length - 1]});
    }

    @Test
    public void jpegDropsTrailerAfterEoi() throws IOException {
        final byte[] trailer = ascii("ftypmp42MOTIONPHOTOVIDEO");
        final byte[] out = MediaMetadataStripper.stripJpeg(concat(jpeg(), trailer), 1);
        Assert.assertFalse(contains(out, trailer));
        Assert.assertEquals((byte) 0xD9, out[out.length - 1]);
    }

    @Test
    public void jpegKeepsOrientationInMinimalExif() throws IOException {
        final byte[] exif = segment(0xE1, concat(ascii("Exif\0\0"), ascii("GPSSECRET")));
        final byte[] out = MediaMetadataStripper.stripJpeg(jpeg(exif), 6);
        Assert.assertFalse(contains(out, ascii("SECRET")));
        // APP1 right after SOI, with the orientation tag (0x0112, SHORT, 1, value 6).
        Assert.assertEquals((byte) 0xE1, out[3]);
        Assert.assertTrue(contains(out, new byte[] {0x01, 0x12, 0x00, 0x03, 0, 0, 0, 1, 0, 6}));
    }

    @Test
    public void jpegKeepsJfifWithoutThumbnail() throws IOException {
        final byte[] jfif =
                segment(0xE0, concat(ascii("JFIF\0"), new byte[] {1, 2, 0, 0, 1, 0, 1, 0, 0}));
        final byte[] jfifWithThumb =
                segment(
                        0xE0,
                        concat(ascii("JFIF\0"), new byte[] {1, 2, 0, 0, 1, 0, 1, 1, 1, 9, 9, 9}));
        Assert.assertTrue(contains(MediaMetadataStripper.stripJpeg(jpeg(jfif), 1), jfif));
        Assert.assertFalse(
                contains(MediaMetadataStripper.stripJpeg(jpeg(jfifWithThumb), 1), jfifWithThumb));
    }

    @Test(expected = IOException.class)
    public void jpegRejectsNonJpeg() throws IOException {
        MediaMetadataStripper.stripJpeg(ascii("not a jpeg at all"), 1);
    }

    // ---- PNG ----

    private static byte[] chunk(final String type, final byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int length = data.length;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        final byte[] t = ascii(type);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        final long c = crc.getValue();
        out.write((int) (c >>> 24));
        out.write((int) (c >>> 16));
        out.write((int) (c >>> 8));
        out.write((int) c);
        return out.toByteArray();
    }

    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Test
    public void pngDropsTextAndExifChunks() throws IOException {
        final byte[] ihdr = chunk("IHDR", new byte[13]);
        final byte[] srgb = chunk("sRGB", new byte[] {0});
        final byte[] text = chunk("tEXt", ascii("Comment\0TEXTSECRET"));
        final byte[] itxt = chunk("iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0ITXTSECRET"));
        final byte[] exif = chunk("eXIf", ascii("MM\0*EXIFSECRET"));
        final byte[] idat = chunk("IDAT", new byte[] {1, 2, 3});
        final byte[] iend = chunk("IEND", new byte[0]);
        final byte[] in =
                concat(PNG_SIG, ihdr, srgb, text, itxt, exif, idat, iend, ascii("TRAILERSECRET"));

        final byte[] out = MediaMetadataStripper.stripPng(in);

        Assert.assertArrayEquals(concat(PNG_SIG, ihdr, srgb, idat, iend), out);
    }

    // ---- WebP ----

    private static byte[] riffChunk(final String fourcc, final byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ascii(fourcc), 0, 4);
        out.write(data.length);
        out.write(data.length >> 8);
        out.write(data.length >> 16);
        out.write(data.length >> 24);
        out.write(data, 0, data.length);
        if ((data.length & 1) == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] webp(final byte[]... chunks) {
        final byte[] body = concat(chunks);
        final int size = body.length + 4;
        return concat(
                ascii("RIFF"),
                new byte[] {
                    (byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)
                },
                ascii("WEBP"),
                body);
    }

    @Test
    public void webpDropsExifAndXmpAndClearsFlags() throws IOException {
        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x08 | 0x04 | 0x20; // EXIF, XMP, ICC
        final byte[] image = riffChunk("VP8 ", new byte[] {1, 2, 3});
        final byte[] in =
                webp(
                        riffChunk("VP8X", vp8x),
                        image,
                        riffChunk("EXIF", ascii("EXIFSECRET")),
                        riffChunk("XMP ", ascii("XMPSECRET!")));

        final byte[] out = MediaMetadataStripper.stripWebp(in);

        final byte[] cleanVp8x = new byte[10];
        cleanVp8x[0] = 0x20;
        Assert.assertArrayEquals(webp(riffChunk("VP8X", cleanVp8x), image), out);
    }

    // ---- GIF ----

    @Test
    public void gifDropsCommentAndXmpButKeepsLoop() throws IOException {
        final byte[] header =
                concat(ascii("GIF89a"), new byte[] {1, 0, 1, 0, 0, 0, 0}); // no global table
        final byte[] loop =
                concat(
                        new byte[] {0x21, (byte) 0xFF, 0x0B},
                        ascii("NETSCAPE2.0"),
                        new byte[] {3, 1, 0, 0, 0});
        final byte[] gce = new byte[] {0x21, (byte) 0xF9, 4, 0, 0, 0, 0, 0};
        final byte[] comment =
                concat(new byte[] {0x21, (byte) 0xFE, 13}, ascii("COMMENTSECRET"), new byte[] {0});
        final byte[] xmp =
                concat(
                        new byte[] {0x21, (byte) 0xFF, 0x0B},
                        ascii("XMP DataXMP"),
                        new byte[] {9},
                        ascii("XMPSECRET"),
                        new byte[] {0});
        final byte[] image = new byte[] {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0};
        final byte[] in = concat(header, loop, comment, xmp, gce, image, new byte[] {0x3B});

        final byte[] out = MediaMetadataStripper.stripGif(in);

        Assert.assertArrayEquals(concat(header, loop, gce, image, new byte[] {0x3B}), out);
    }

    // ---- animation detection ----

    private static final byte[] GIF_HEADER =
            concat(ascii("GIF89a"), new byte[] {1, 0, 1, 0, 0, 0, 0});
    private static final byte[] GIF_IMAGE =
            new byte[] {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4C, 0x01, 0};

    private static byte[] gce(final int hundredths) {
        return new byte[] {
            0x21, (byte) 0xF9, 4, 0, (byte) hundredths, (byte) (hundredths >> 8), 0, 0
        };
    }

    @Test
    public void gifWithOneFrameIsNotAnimated() {
        final byte[] in = concat(GIF_HEADER, gce(50), GIF_IMAGE, new byte[] {0x3B});
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(in));
    }

    @Test
    public void gifWithSeveralFramesIsAnimated() {
        final byte[] in =
                concat(
                        GIF_HEADER,
                        gce(50),
                        GIF_IMAGE,
                        gce(0), // shown for 100 ms like in browsers
                        GIF_IMAGE,
                        gce(300),
                        GIF_IMAGE,
                        new byte[] {0x3B});
        final MediaMetadataStripper.Animation a = MediaMetadataStripper.inspectAnimation(in);
        Assert.assertNotNull(a);
        Assert.assertEquals("image/gif", a.mime);
        Assert.assertEquals(3, a.frames);
        Assert.assertEquals(500 + 100 + 3000, a.loopDurationMs);
    }

    @Test
    public void truncatedGifIsNotAnimated() {
        final byte[] in =
                concat(GIF_HEADER, gce(50), GIF_IMAGE, gce(50), new byte[] {0x2C, 0, 0});
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(in));
    }

    private static byte[] anmf(final int durationMs) {
        final byte[] header = new byte[16];
        header[12] = (byte) durationMs;
        header[13] = (byte) (durationMs >> 8);
        header[14] = (byte) (durationMs >> 16);
        return riffChunk("ANMF", concat(header, riffChunk("VP8 ", new byte[] {1, 2, 3})));
    }

    @Test
    public void staticWebpIsNotAnimated() {
        final byte[] vp8x = new byte[10];
        Assert.assertNull(
                MediaMetadataStripper.inspectAnimation(
                        webp(riffChunk("VP8X", vp8x), riffChunk("VP8 ", new byte[] {1, 2, 3}))));
        Assert.assertNull(
                MediaMetadataStripper.inspectAnimation(
                        webp(riffChunk("VP8 ", new byte[] {1, 2, 3}))));
    }

    @Test
    public void animatedWebpIsAnimated() {
        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x02;
        final byte[] in =
                webp(
                        riffChunk("VP8X", vp8x),
                        riffChunk("ANIM", new byte[6]),
                        anmf(40),
                        anmf(5),
                        anmf(70000));
        final MediaMetadataStripper.Animation a = MediaMetadataStripper.inspectAnimation(in);
        Assert.assertNotNull(a);
        Assert.assertEquals("image/webp", a.mime);
        Assert.assertEquals(3, a.frames);
        Assert.assertEquals(40 + 100 + 70000, a.loopDurationMs);
    }

    @Test
    public void animatedWebpSurvivesStripping() throws IOException {
        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x02 | 0x08;
        final byte[] in =
                webp(
                        riffChunk("VP8X", vp8x),
                        riffChunk("ANIM", new byte[6]),
                        anmf(40),
                        anmf(40),
                        riffChunk("EXIF", ascii("EXIFSECRET")));
        final byte[] out = MediaMetadataStripper.stripWebp(in);
        final MediaMetadataStripper.Animation a = MediaMetadataStripper.inspectAnimation(out);
        Assert.assertNotNull(a);
        Assert.assertEquals(2, a.frames);
    }

    @Test
    public void otherDataIsNotAnimated() {
        Assert.assertNull(MediaMetadataStripper.inspectAnimation((byte[]) null));
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(new byte[0]));
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(ascii("GIF89a")));
        Assert.assertNull(
                MediaMetadataStripper.inspectAnimation(
                        ascii("RIFF\u00ff\u00ff\u00ff\u007fWEBPVP8X")));
    }

    @Test
    public void webpChunkClaimingHugeSizeIsNotAnimated() {
        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x02;
        final byte[] huge = concat(ascii("ANMF"), new byte[] {-1, -1, -1, -1}, new byte[16]);
        // RIFF size also claims ~4 GB, so only the missing data ends the scan.
        final byte[] in =
                concat(
                        ascii("RIFF"),
                        new byte[] {-1, -1, -1, -1},
                        ascii("WEBP"),
                        riffChunk("VP8X", vp8x),
                        anmf(40),
                        huge);
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(in));
    }

    @Test
    public void gifSubBlockPastEndIsNotAnimated() {
        final byte[] in =
                concat(
                        GIF_HEADER,
                        gce(50),
                        GIF_IMAGE,
                        gce(50),
                        GIF_IMAGE,
                        new byte[] {0x21, 0x01, 100, 1, 2});
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(in));
    }

    @Test
    public void jpegIsRejectedAfterTheSignature() {
        final int[] read = {0};
        final java.io.InputStream jpeg =
                new java.io.InputStream() {
                    @Override
                    public int read() {
                        read[0]++;
                        return read[0] == 1 ? 0xFF : read[0] == 2 ? 0xD8 : 0;
                    }

                    @Override
                    public int read(final byte[] b, final int off, final int len) {
                        final int n = Math.min(len, 64);
                        for (int i = 0; i < n; i++) {
                            b[off + i] = (byte) read();
                        }
                        return n;
                    }
                };
        Assert.assertNull(MediaMetadataStripper.inspectAnimation(jpeg));
        Assert.assertTrue("read " + read[0] + " bytes", read[0] <= 8192);
    }
}
