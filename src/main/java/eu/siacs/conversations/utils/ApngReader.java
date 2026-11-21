package eu.siacs.conversations.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Splits an animated PNG (APNG) into its frames, each as a standalone still PNG that the platform
 * decoder can read. Pure Java: only chunks are taken apart and reassembled here, the pixel data is
 * never decoded. The input comes from whoever made the file: anything malformed or beyond the
 * limits is an {@link IOException}, never a runtime exception.
 */
public final class ApngReader {

    public static final int MAX_DIMENSION = 1024;
    public static final int MAX_FRAMES = 300;

    public static final int DISPOSE_NONE = 0;
    public static final int DISPOSE_BACKGROUND = 1;
    public static final int DISPOSE_PREVIOUS = 2;
    public static final int BLEND_SOURCE = 0;
    public static final int BLEND_OVER = 1;

    private static final int DEFAULT_DELAY_MS = 100;

    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    /** Chunks before the image data that every frame needs to be decoded the same way. */
    private static final Set<String> SHARED_CHUNKS =
            new HashSet<>(Arrays.asList("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT"));

    private ApngReader() {}

    public static final class Frame {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final int delayMs;
        public final int dispose;
        public final int blend;

        /** The frame alone, as a complete still PNG. */
        public final byte[] png;

        Frame(
                final int x,
                final int y,
                final int width,
                final int height,
                final int delayMs,
                final int dispose,
                final int blend,
                final byte[] png) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.delayMs = delayMs;
            this.dispose = dispose;
            this.blend = blend;
            this.png = png;
        }
    }

    public static final class Apng {
        public final int width;
        public final int height;

        /** 0 = forever. */
        public final int loops;

        public final List<Frame> frames;

        Apng(final int width, final int height, final int loops, final List<Frame> frames) {
            this.width = width;
            this.height = height;
            this.loops = loops;
            this.frames = Collections.unmodifiableList(frames);
        }
    }

    /** Whether {@code data} is a PNG with an animation control chunk before its image data. */
    public static boolean isApng(final byte[] data) {
        if (data == null || !hasSignature(data)) {
            return false;
        }
        int pos = SIGNATURE.length;
        while (pos + 8 <= data.length) {
            final long length = readUInt32(data, pos);
            final String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if ("acTL".equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            if (length > data.length - pos - 12L) {
                return false;
            }
            pos += 12 + (int) length;
        }
        return false;
    }

    /** One frame being collected: its control chunk and image data so far. */
    private static final class Pending {
        final int x, y, width, height, delayMs, dispose, blend;
        final List<byte[]> dataChunks = new ArrayList<>();

        Pending(final int x, final int y, final int width, final int height, final int delayMs,
                final int dispose, final int blend) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.delayMs = delayMs;
            this.dispose = dispose;
            this.blend = blend;
        }
    }

    public static Apng parse(final byte[] data) throws IOException {
        if (data == null || !hasSignature(data)) {
            throw new IOException("not a PNG");
        }
        byte[] ihdr = null;
        int width = 0;
        int height = 0;
        int declaredFrames = -1;
        int loops = 0;
        boolean seenImageData = false;
        boolean seenEnd = false;
        long expectedSequence = 0;
        final List<byte[]> shared = new ArrayList<>(); // complete chunks, header and CRC included
        final List<Frame> frames = new ArrayList<>();
        Pending pending = null;

        int pos = SIGNATURE.length;
        while (pos < data.length) {
            if (data.length - pos < 12) {
                throw new IOException("truncated chunk");
            }
            final long length = readUInt32(data, pos);
            if (length > data.length - pos - 12L) {
                throw new IOException("chunk longer than the file");
            }
            final int len = (int) length;
            final String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
            final int body = pos + 8;
            switch (type) {
                case "IHDR":
                    if (ihdr != null || len != 13) throw new IOException("bad IHDR");
                    ihdr = Arrays.copyOfRange(data, body, body + len);
                    width = readInt(data, body);
                    height = readInt(data, body + 4);
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION
                            || height > MAX_DIMENSION) {
                        throw new IOException("unsupported size " + width + "x" + height);
                    }
                    break;
                case "acTL":
                    if (ihdr == null || seenImageData || len != 8 || declaredFrames >= 0) {
                        throw new IOException("bad acTL");
                    }
                    final long numFrames = readUInt32(data, body);
                    if (numFrames < 1 || numFrames > MAX_FRAMES) {
                        throw new IOException("unsupported frame count " + numFrames);
                    }
                    declaredFrames = (int) numFrames;
                    final long numPlays = readUInt32(data, body + 4);
                    loops = numPlays > 0xffff ? 0 : (int) numPlays;
                    break;
                case "fcTL": {
                    if (ihdr == null || len != 26) throw new IOException("bad fcTL");
                    if (readUInt32(data, body) != expectedSequence++) {
                        throw new IOException("fcTL out of sequence");
                    }
                    if (pending != null) {
                        frames.add(finish(pending, ihdr, shared));
                    }
                    final int fw = readInt(data, body + 4);
                    final int fh = readInt(data, body + 8);
                    final int fx = readInt(data, body + 12);
                    final int fy = readInt(data, body + 16);
                    if (fw <= 0 || fh <= 0 || fx < 0 || fy < 0
                            || (long) fx + fw > width || (long) fy + fh > height) {
                        throw new IOException("frame outside of the canvas");
                    }
                    final int delayNum = readUInt16(data, body + 20);
                    final int delayDen = readUInt16(data, body + 22);
                    final int dispose = data[body + 24] & 0xff;
                    final int blend = data[body + 25] & 0xff;
                    if (dispose > DISPOSE_PREVIOUS || blend > BLEND_OVER) {
                        throw new IOException("bad dispose or blend op");
                    }
                    pending = new Pending(fx, fy, fw, fh, delayMs(delayNum, delayDen),
                            dispose, blend);
                    if (frames.size() >= MAX_FRAMES) throw new IOException("too many frames");
                    break;
                }
                case "IDAT":
                    if (ihdr == null) throw new IOException("IDAT before IHDR");
                    seenImageData = true;
                    // the default image is the first frame only when a fcTL came before it
                    if (pending != null && frames.isEmpty()) {
                        if (pending.x != 0 || pending.y != 0 || pending.width != width
                                || pending.height != height) {
                            throw new IOException("first frame must fill the canvas");
                        }
                        pending.dataChunks.add(Arrays.copyOfRange(data, body, body + len));
                    }
                    break;
                case "fdAT":
                    if (pending == null || len < 4) throw new IOException("bad fdAT");
                    if (readUInt32(data, body) != expectedSequence++) {
                        throw new IOException("fdAT out of sequence");
                    }
                    pending.dataChunks.add(Arrays.copyOfRange(data, body + 4, body + len));
                    break;
                case "IEND":
                    seenEnd = true;
                    break;
                default:
                    if (!seenImageData && SHARED_CHUNKS.contains(type)) {
                        shared.add(Arrays.copyOfRange(data, pos, pos + 12 + len));
                    }
                    break;
            }
            pos += 12 + len;
            if (seenEnd) break;
        }
        if (ihdr == null || declaredFrames < 0 || !seenImageData) {
            throw new IOException("not an animated PNG");
        }
        if (pending != null) {
            frames.add(finish(pending, ihdr, shared));
        }
        if (frames.size() != declaredFrames) {
            throw new IOException(
                    "frame count " + frames.size() + " does not match " + declaredFrames);
        }
        return new Apng(width, height, loops, frames);
    }

    private static Frame finish(final Pending p, final byte[] ihdr, final List<byte[]> shared)
            throws IOException {
        if (p.dataChunks.isEmpty()) {
            throw new IOException("frame without image data");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        final byte[] header = ihdr.clone();
        writeInt(header, 0, p.width);
        writeInt(header, 4, p.height);
        writeChunk(out, "IHDR", header);
        for (final byte[] chunk : shared) {
            out.write(chunk);
        }
        for (final byte[] chunk : p.dataChunks) {
            writeChunk(out, "IDAT", chunk);
        }
        writeChunk(out, "IEND", new byte[0]);
        return new Frame(p.x, p.y, p.width, p.height, p.delayMs, p.dispose, p.blend,
                out.toByteArray());
    }

    static int delayMs(final int numerator, final int denominator) {
        final int den = denominator == 0 ? 100 : denominator;
        final long ms = numerator * 1000L / den;
        return ms <= 0 ? DEFAULT_DELAY_MS : (int) Math.min(ms, 0xffffff);
    }

    static void writeChunk(final ByteArrayOutputStream out, final String type, final byte[] body)
            throws IOException {
        final byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] length = new byte[4];
        writeInt(length, 0, body.length);
        out.write(length);
        out.write(typeBytes);
        out.write(body);
        final CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(body);
        final byte[] checksum = new byte[4];
        writeInt(checksum, 0, (int) crc.getValue());
        out.write(checksum);
    }

    private static boolean hasSignature(final byte[] data) {
        if (data.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    private static long readUInt32(final byte[] in, final int pos) {
        return ((in[pos] & 0xffL) << 24)
                | ((in[pos + 1] & 0xffL) << 16)
                | ((in[pos + 2] & 0xffL) << 8)
                | (in[pos + 3] & 0xffL);
    }

    /** A PNG 31-bit value; larger ones (invalid) come back negative and are rejected. */
    private static int readInt(final byte[] in, final int pos) {
        final long value = readUInt32(in, pos);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static int readUInt16(final byte[] in, final int pos) {
        return ((in[pos] & 0xff) << 8) | (in[pos + 1] & 0xff);
    }

    private static void writeInt(final byte[] out, final int pos, final int value) {
        out[pos] = (byte) (value >>> 24);
        out[pos + 1] = (byte) (value >>> 16);
        out[pos + 2] = (byte) (value >>> 8);
        out[pos + 3] = (byte) value;
    }
}
