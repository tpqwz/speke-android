package eu.siacs.conversations.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class AnimatedWebpWriterTest {

    private static void chunk(final ByteArrayOutputStream out, final String type,
            final byte[] data) throws IOException {
        out.write(type.getBytes(StandardCharsets.ISO_8859_1));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length)
                .array());
        out.write(data);
        if ((data.length & 1) != 0) out.write(0);
    }

    private static byte[] riff(final ByteArrayOutputStream chunks) throws IOException {
        final byte[] body = chunks.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length + 4)
                .array());
        out.write("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        return out.toByteArray();
    }

    /** What Bitmap.compress(WEBP_LOSSLESS) looks like: one VP8L chunk (odd size: padded). */
    private static byte[] lossless() throws IOException {
        final ByteArrayOutputStream c = new ByteArrayOutputStream();
        chunk(c, "VP8L", new byte[] {0x2f, 1, 2, 3, 4});
        return riff(c);
    }

    /** What lossy compression with alpha looks like: VP8X, ALPH and VP8. */
    private static byte[] lossyWithAlpha() throws IOException {
        final ByteArrayOutputStream c = new ByteArrayOutputStream();
        chunk(c, "VP8X", new byte[10]);
        chunk(c, "ALPH", new byte[] {9, 9, 9});
        chunk(c, "VP8 ", new byte[] {7, 7, 7, 7});
        chunk(c, "EXIF", new byte[] {1, 1});
        return riff(c);
    }

    @Test
    public void muxesAnimation() throws IOException {
        final byte[] webp =
                AnimatedWebpWriter.mux(
                        512,
                        256,
                        0,
                        Arrays.asList(
                                new AnimatedWebpWriter.Frame(lossless(), 40),
                                new AnimatedWebpWriter.Frame(lossyWithAlpha(), 60)));
        final ByteBuffer b = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(webp, 0, 4, StandardCharsets.ISO_8859_1));
        assertEquals(webp.length - 8, b.getInt(4));
        assertEquals("WEBP", new String(webp, 8, 4, StandardCharsets.ISO_8859_1));
        assertEquals("VP8X", new String(webp, 12, 4, StandardCharsets.ISO_8859_1));
        assertTrue((webp[20] & 0x02) != 0); // animation flag
        assertEquals(511, (webp[24] & 0xff) | ((webp[25] & 0xff) << 8) | ((webp[26] & 0xff) << 16));
        assertEquals(255, (webp[27] & 0xff) | ((webp[28] & 0xff) << 8) | ((webp[29] & 0xff) << 16));
        assertEquals("ANIM", new String(webp, 30, 4, StandardCharsets.ISO_8859_1));

        final String text = new String(webp, StandardCharsets.ISO_8859_1);
        assertEquals(2, text.split("ANMF", -1).length - 1);
        assertTrue(text.contains("VP8L"));
        assertTrue(text.contains("ALPH"));
        assertTrue(text.contains("VP8 "));
        assertEquals(-1, text.indexOf("EXIF")); // metadata of the stills is dropped
        assertEquals(1, text.split("VP8X", -1).length - 1); // only the container's own

        final MediaMetadataStripper.Animation animation =
                MediaMetadataStripper.inspectAnimation(webp);
        assertNotNull(animation);
        assertEquals(2, animation.frames);
        assertEquals(100, animation.loopDurationMs);
    }

    @Test
    public void writesFrameRegion() throws IOException {
        final byte[] webp =
                AnimatedWebpWriter.mux(
                        512,
                        512,
                        0,
                        Arrays.asList(
                                new AnimatedWebpWriter.Frame(lossless(), 40),
                                new AnimatedWebpWriter.Frame(lossless(), 50, 100, 20, 31, 7)));
        final String text = new String(webp, StandardCharsets.ISO_8859_1);
        final int second = text.indexOf("ANMF", text.indexOf("ANMF") + 4) + 8;
        assertEquals(50, uint24(webp, second)); // x / 2
        assertEquals(10, uint24(webp, second + 3)); // y / 2
        assertEquals(30, uint24(webp, second + 6)); // width - 1
        assertEquals(6, uint24(webp, second + 9)); // height - 1
        assertEquals(50, uint24(webp, second + 12)); // duration
        assertEquals(0x02, webp[second + 15]); // do not blend, no disposal
    }

    private static int uint24(final byte[] b, final int pos) {
        return (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16);
    }

    @Test(expected = IOException.class)
    public void refusesOddOffset() throws IOException {
        AnimatedWebpWriter.mux(
                8, 8, 0, Arrays.asList(new AnimatedWebpWriter.Frame(lossless(), 10, 1, 0, 2, 2)));
    }

    @Test(expected = IOException.class)
    public void refusesRegionOutsideCanvas() throws IOException {
        AnimatedWebpWriter.mux(
                8, 8, 0, Arrays.asList(new AnimatedWebpWriter.Frame(lossless(), 10, 6, 0, 4, 2)));
    }

    @Test(expected = IOException.class)
    public void refusesNonWebpFrame() throws IOException {
        AnimatedWebpWriter.mux(
                1, 1, 0,
                Arrays.asList(new AnimatedWebpWriter.Frame(
                        "GIF89a......".getBytes(StandardCharsets.ISO_8859_1), 10)));
    }

    @Test(expected = IOException.class)
    public void refusesFrameWithoutImage() throws IOException {
        final ByteArrayOutputStream c = new ByteArrayOutputStream();
        chunk(c, "EXIF", new byte[] {1, 1});
        AnimatedWebpWriter.mux(1, 1, 0, Arrays.asList(new AnimatedWebpWriter.Frame(riff(c), 10)));
    }

    @Test(expected = IOException.class)
    public void refusesTruncatedFrame() throws IOException {
        final byte[] still = lossless();
        AnimatedWebpWriter.mux(
                1, 1, 0,
                Arrays.asList(new AnimatedWebpWriter.Frame(
                        Arrays.copyOf(still, still.length - 3), 10)));
    }
}
