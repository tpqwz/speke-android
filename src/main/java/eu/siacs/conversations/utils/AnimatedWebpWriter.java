package eu.siacs.conversations.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Assembles an animated WebP from still WebPs (one {@code Bitmap.compress(WEBP)} result per
 * frame, covering the whole canvas or the region that changed). Pure Java: the frames' bitstreams are copied into the
 * animation container unchanged, nothing is encoded here.
 */
public final class AnimatedWebpWriter {

    private static final int MAX_DIMENSION = 1 << 24;
    private static final int MAX_DURATION_MS = 0xffffff;

    private AnimatedWebpWriter() {}

    public static final class Frame {
        final byte[] stillWebp;
        final int durationMs;

        /** Region of the canvas the still covers; -1 = the whole canvas. */
        final int x, y, width, height;

        /** A still the size of the whole canvas. */
        public Frame(final byte[] stillWebp, final int durationMs) {
            this(stillWebp, durationMs, 0, 0, -1, -1);
        }

        /**
         * A still covering {@code width}×{@code height} at ({@code x}, {@code y}); it replaces
         * those pixels (no blending), the rest of the canvas stays as it was.
         */
        public Frame(
                final byte[] stillWebp,
                final int durationMs,
                final int x,
                final int y,
                final int width,
                final int height) {
            this.stillWebp = stillWebp;
            this.durationMs = durationMs;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public static byte[] mux(
            final int width, final int height, final int loops, final List<Frame> frames)
            throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("bad canvas size");
        }
        if (frames == null || frames.isEmpty()) {
            throw new IOException("no frames");
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(ascii("WEBP"));

        final byte[] vp8x = new byte[10];
        vp8x[0] = 0x10 | 0x02; // alpha, animation
        writeUInt24(vp8x, 4, width - 1);
        writeUInt24(vp8x, 7, height - 1);
        writeChunk(body, "VP8X", vp8x);

        final byte[] anim = new byte[6];
        // background colour 0 (transparent); loop count, 0 = forever
        anim[4] = (byte) (loops & 0xff);
        anim[5] = (byte) ((loops >>> 8) & 0xff);
        writeChunk(body, "ANIM", anim);

        for (final Frame frame : frames) {
            final int w = frame.width < 0 ? width : frame.width;
            final int h = frame.height < 0 ? height : frame.height;
            // WebP stores offsets halved: they must be even
            if (frame.x < 0 || frame.y < 0 || (frame.x & 1) != 0 || (frame.y & 1) != 0
                    || w <= 0 || h <= 0
                    || (long) frame.x + w > width || (long) frame.y + h > height) {
                throw new IOException("frame outside of the canvas");
            }
            final byte[] bitstream = frameData(frame.stillWebp);
            final byte[] anmf = new byte[16 + bitstream.length];
            writeUInt24(anmf, 0, frame.x / 2);
            writeUInt24(anmf, 3, frame.y / 2);
            writeUInt24(anmf, 6, w - 1);
            writeUInt24(anmf, 9, h - 1);
            writeUInt24(anmf, 12, Math.max(0, Math.min(frame.durationMs, MAX_DURATION_MS)));
            anmf[15] = 0x02; // do not blend (the still holds the exact pixels), no disposal
            System.arraycopy(bitstream, 0, anmf, 16, bitstream.length);
            writeChunk(body, "ANMF", anmf);
        }

        final byte[] payload = body.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.write(ascii("RIFF"));
        out.write(uint32LE(payload.length));
        out.write(payload);
        return out.toByteArray();
    }

    /** The image chunks (ALPH, VP8, VP8L) of a still WebP, each with its header and padding. */
    static byte[] frameData(final byte[] still) throws IOException {
        if (still == null
                || still.length < 12
                || !"RIFF".equals(new String(still, 0, 4, StandardCharsets.ISO_8859_1))
                || !"WEBP".equals(new String(still, 8, 4, StandardCharsets.ISO_8859_1))) {
            throw new IOException("not a WebP");
        }
        final long riffEnd = Math.min(still.length, 8 + readUInt32LE(still, 4));
        final ByteArrayOutputStream out = new ByteArrayOutputStream(still.length);
        boolean image = false;
        int pos = 12;
        while (pos + 8 <= riffEnd) {
            final String type = new String(still, pos, 4, StandardCharsets.ISO_8859_1);
            final long size = readUInt32LE(still, pos + 4);
            final long padded = size + (size & 1);
            if (padded > riffEnd - pos - 8) {
                throw new IOException("chunk longer than the file");
            }
            if ("ANIM".equals(type) || "ANMF".equals(type)) {
                throw new IOException("frame is animated itself");
            }
            if ("ALPH".equals(type) || "VP8 ".equals(type) || "VP8L".equals(type)) {
                out.write(still, pos, 8 + (int) padded);
                if (!"ALPH".equals(type)) image = true;
            }
            pos += 8 + (int) padded;
        }
        if (!image) {
            throw new IOException("WebP without image data");
        }
        return out.toByteArray();
    }

    private static void writeChunk(
            final ByteArrayOutputStream out, final String type, final byte[] data)
            throws IOException {
        out.write(ascii(type));
        out.write(uint32LE(data.length));
        out.write(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] uint32LE(final long value) {
        return new byte[] {
            (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)
        };
    }

    private static long readUInt32LE(final byte[] in, final int pos) {
        return (in[pos] & 0xffL)
                | ((in[pos + 1] & 0xffL) << 8)
                | ((in[pos + 2] & 0xffL) << 16)
                | ((in[pos + 3] & 0xffL) << 24);
    }

    private static void writeUInt24(final byte[] out, final int pos, final int value) {
        out[pos] = (byte) value;
        out[pos + 1] = (byte) (value >>> 8);
        out[pos + 2] = (byte) (value >>> 16);
    }
}
