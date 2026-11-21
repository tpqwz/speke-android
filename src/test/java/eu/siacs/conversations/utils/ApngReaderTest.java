package eu.siacs.conversations.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.Test;

public class ApngReaderTest {

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    private static byte[] ihdr(final int w, final int h) {
        return ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6)
                .put((byte) 0).put((byte) 0).put((byte) 0).array();
    }

    private static byte[] actl(final int frames, final int plays) {
        return ByteBuffer.allocate(8).putInt(frames).putInt(plays).array();
    }

    private static byte[] fctl(final int seq, final int w, final int h, final int x, final int y,
            final int num, final int den, final int dispose, final int blend) {
        return ByteBuffer.allocate(26).putInt(seq).putInt(w).putInt(h).putInt(x).putInt(y)
                .putShort((short) num).putShort((short) den).put((byte) dispose)
                .put((byte) blend).array();
    }

    private static byte[] fdat(final int seq, final byte[] data) {
        return ByteBuffer.allocate(4 + data.length).putInt(seq).put(data).array();
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 4x4 canvas: frame 0 is the default image, frame 1 a 2x2 patch at (1,1). */
    private static byte[] twoFrameApng(final int frame1X) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(4, 4));
        ApngReader.writeChunk(out, "acTL", actl(2, 0));
        ApngReader.writeChunk(out, "tRNS", new byte[] {1, 2});
        ApngReader.writeChunk(out, "fcTL", fctl(0, 4, 4, 0, 0, 1, 30, 0, 0));
        ApngReader.writeChunk(out, "IDAT", ascii("abc"));
        ApngReader.writeChunk(out, "fcTL", fctl(1, 2, 2, frame1X, 1, 0, 0, 1, 1));
        ApngReader.writeChunk(out, "fdAT", fdat(2, ascii("de")));
        ApngReader.writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] stillPng() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(4, 4));
        ApngReader.writeChunk(out, "IDAT", ascii("abc"));
        ApngReader.writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    @Test
    public void detectsAnimatedPng() throws IOException {
        assertTrue(ApngReader.isApng(twoFrameApng(1)));
        assertFalse(ApngReader.isApng(stillPng()));
        assertFalse(ApngReader.isApng(ascii("GIF89a not a png")));
        assertFalse(ApngReader.isApng(null));
    }

    @Test
    public void splitsFrames() throws IOException {
        final ApngReader.Apng apng = ApngReader.parse(twoFrameApng(1));
        assertEquals(4, apng.width);
        assertEquals(4, apng.height);
        assertEquals(0, apng.loops);
        assertEquals(2, apng.frames.size());

        final ApngReader.Frame first = apng.frames.get(0);
        assertEquals(33, first.delayMs);
        assertEquals(ApngReader.BLEND_SOURCE, first.blend);
        assertEquals(4, first.width);

        final ApngReader.Frame second = apng.frames.get(1);
        assertEquals(1, second.x);
        assertEquals(1, second.y);
        assertEquals(2, second.width);
        assertEquals(100, second.delayMs); // 0/0 is shown for the default time
        assertEquals(ApngReader.DISPOSE_BACKGROUND, second.dispose);
        assertEquals(ApngReader.BLEND_OVER, second.blend);

        // a standalone PNG: the frame's own size in IHDR, valid CRC, shared tRNS, data as IDAT
        final byte[] png = second.png;
        assertArrayEquals(SIGNATURE, Arrays.copyOf(png, 8));
        final ByteBuffer b = ByteBuffer.wrap(png);
        assertEquals(13, b.getInt(8));
        assertEquals("IHDR", new String(png, 12, 4, StandardCharsets.ISO_8859_1));
        assertEquals(2, b.getInt(16));
        assertEquals(2, b.getInt(20));
        final CRC32 crc = new CRC32();
        crc.update(png, 12, 4 + 13);
        assertEquals((int) crc.getValue(), b.getInt(12 + 4 + 13));
        final String text = new String(png, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("tRNS"));
        assertTrue(text.contains("IDATde"));
        assertFalse(text.contains("fdAT"));
        assertTrue(text.endsWith("IEND" + new String(Arrays.copyOfRange(png, png.length - 4,
                png.length), StandardCharsets.ISO_8859_1)));
    }

    @Test(expected = IOException.class)
    public void refusesFrameOutsideCanvas() throws IOException {
        ApngReader.parse(twoFrameApng(3)); // 2 wide at x=3 on a 4 wide canvas
    }

    @Test(expected = IOException.class)
    public void refusesTruncatedFile() throws IOException {
        final byte[] apng = twoFrameApng(1);
        ApngReader.parse(Arrays.copyOf(apng, apng.length - 20));
    }

    @Test(expected = IOException.class)
    public void refusesStillPng() throws IOException {
        ApngReader.parse(stillPng());
    }

    @Test(expected = IOException.class)
    public void refusesOversizedCanvas() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(ApngReader.MAX_DIMENSION + 1, 4));
        ApngReader.writeChunk(out, "acTL", actl(1, 0));
        ApngReader.parse(out.toByteArray());
    }

    @Test(expected = IOException.class)
    public void refusesTooManyFrames() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(4, 4));
        ApngReader.writeChunk(out, "acTL", actl(ApngReader.MAX_FRAMES + 1, 0));
        ApngReader.parse(out.toByteArray());
    }

    @Test(expected = IOException.class)
    public void refusesFrameCountMismatch() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(4, 4));
        ApngReader.writeChunk(out, "acTL", actl(3, 0));
        ApngReader.writeChunk(out, "fcTL", fctl(0, 4, 4, 0, 0, 1, 10, 0, 0));
        ApngReader.writeChunk(out, "IDAT", ascii("abc"));
        ApngReader.writeChunk(out, "IEND", new byte[0]);
        ApngReader.parse(out.toByteArray());
    }

    @Test(expected = IOException.class)
    public void refusesOutOfSequenceChunks() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ApngReader.writeChunk(out, "IHDR", ihdr(4, 4));
        ApngReader.writeChunk(out, "acTL", actl(2, 0));
        ApngReader.writeChunk(out, "fcTL", fctl(0, 4, 4, 0, 0, 1, 10, 0, 0));
        ApngReader.writeChunk(out, "IDAT", ascii("abc"));
        ApngReader.writeChunk(out, "fcTL", fctl(5, 2, 2, 0, 0, 1, 10, 0, 0));
        ApngReader.parse(out.toByteArray());
    }

    @Test
    public void delays() {
        assertEquals(100, ApngReader.delayMs(1, 10));
        assertEquals(10, ApngReader.delayMs(1, 100));
        assertEquals(10, ApngReader.delayMs(1, 0)); // den 0 means 1/100 s
        assertEquals(100, ApngReader.delayMs(0, 30));
    }
}
