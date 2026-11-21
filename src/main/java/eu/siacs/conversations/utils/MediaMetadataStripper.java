package eu.siacs.conversations.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes copies of media files without their identifying metadata (GPS position, device, owner,
 * capture time, embedded thumbnails/previews, XMP/IPTC, appended trailers), without re-encoding.
 *
 * <ul>
 *   <li>JPEG: drops every APPn/COM segment except JFIF (without thumbnail), the ICC profile and the
 *       Adobe color transform marker, and anything after EOI (motion-photo videos, vendor
 *       trailers). The EXIF orientation is carried over in a minimal EXIF block so the picture
 *       isn't shown rotated.
 *   <li>PNG: keeps only rendering-relevant chunks (no tEXt/zTXt/iTXt/eXIf/tIME...).
 *   <li>WebP: drops the EXIF and XMP chunks.
 *   <li>GIF: drops comments and application extensions other than the loop control.
 *   <li>MP4/MOV/3GP/WebM: remuxes the audio and video tracks into a fresh container, which leaves
 *       location, user data and metadata tracks behind; the rotation is kept.
 * </ul>
 */
public final class MediaMetadataStripper {

    private static final int MAX_IMAGE_SIZE = 64 * 1024 * 1024;

    private static final Set<String> VIDEO_TYPES =
            new HashSet<>(
                    Arrays.asList("video/mp4", "video/quicktime", "video/3gpp", "video/webm"));

    private MediaMetadataStripper() {}

    public static boolean canStrip(final String mime) {
        if (mime == null) {
            return false;
        }
        switch (mime) {
            case "image/jpeg":
            case "image/png":
            case "image/webp":
            case "image/gif":
                return true;
            default:
                return VIDEO_TYPES.contains(mime);
        }
    }

    /**
     * Writes a metadata-free copy of the media at {@code source} to {@code target}.
     *
     * @param input stream of {@code source} (consumed and closed for images; unused for videos)
     * @return the type of the written file (a remuxed QuickTime file is an MP4)
     * @throws IOException if the file can't be parsed or rewritten; {@code target} is then deleted
     */
    public static String strip(
            final Context context,
            final Uri source,
            final InputStream input,
            final String mime,
            final File target)
            throws IOException {
        try {
            if (VIDEO_TYPES.contains(mime)) {
                if (input != null) {
                    input.close();
                }
                return remuxVideo(context, source, mime, target);
            }
            final byte[] data;
            try (final InputStream is = input) {
                data = readLimited(is);
            }
            final byte[] stripped;
            switch (mime) {
                case "image/jpeg":
                    stripped = stripJpeg(data, readOrientation(data));
                    break;
                case "image/png":
                    stripped = stripPng(data);
                    break;
                case "image/webp":
                    stripped = stripWebp(data);
                    break;
                case "image/gif":
                    stripped = stripGif(data);
                    break;
                default:
                    throw new IOException("unsupported type " + mime);
            }
            // the stripped copy is a temp file: encrypted at rest like other temp files
            eu.siacs.conversations.persistance.MediaCrypto.storeTemporary(
                    context, new java.io.ByteArrayInputStream(stripped), target);
            return mime;
        } catch (final IOException | RuntimeException e) {
            if (target.exists() && !target.delete()) {
                target.deleteOnExit();
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static byte[] readLimited(final InputStream is) throws IOException {
        if (is == null) {
            throw new IOException("no input");
        }
        final byte[] data = ByteStreams.toByteArray(ByteStreams.limit(is, MAX_IMAGE_SIZE + 1L));
        if (data.length > MAX_IMAGE_SIZE) {
            throw new IOException("image too large to strip");
        }
        return data;
    }

    // ---------------------------------------------------------------- JPEG

    private static int readOrientation(final byte[] jpeg) {
        try {
            return new ExifInterface(new ByteArrayInputStream(jpeg))
                    .getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (final IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    static byte[] stripJpeg(final byte[] in, final int orientation) throws IOException {
        if (in.length < 4 || (in[0] & 0xff) != 0xFF || (in[1] & 0xff) != 0xD8) {
            throw new IOException("not a JPEG");
        }
        final boolean writeOrientation =
                orientation > ExifInterface.ORIENTATION_NORMAL
                        && orientation <= ExifInterface.ORIENTATION_ROTATE_270;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        if (writeOrientation) {
            writeOrientationExif(out, orientation);
        }
        int pos = 2;
        while (true) {
            // Find the next marker, skipping fill bytes.
            if (pos >= in.length || (in[pos] & 0xff) != 0xFF) {
                throw new IOException("JPEG marker expected");
            }
            while (pos < in.length && (in[pos] & 0xff) == 0xFF) {
                pos++;
            }
            if (pos >= in.length) {
                throw new IOException("truncated JPEG");
            }
            final int marker = in[pos++] & 0xff;
            if (marker == 0xD9) { // EOI: drop whatever trails the image
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            if (pos + 2 > in.length) {
                throw new IOException("truncated JPEG segment");
            }
            final int length = ((in[pos] & 0xff) << 8) | (in[pos + 1] & 0xff);
            if (length < 2 || pos + length > in.length) {
                throw new IOException("invalid JPEG segment length");
            }
            final int payload = pos + 2;
            final int end = pos + length;
            if (keepJpegSegment(marker, in, payload, end, writeOrientation)) {
                out.write(0xFF);
                out.write(marker);
                out.write(in, pos, length);
            }
            pos = end;
            if (marker == 0xDA) { // SOS: copy the entropy-coded data up to the next real marker
                final int dataStart = pos;
                while (pos + 1 < in.length) {
                    if ((in[pos] & 0xff) == 0xFF) {
                        final int next = in[pos + 1] & 0xff;
                        if (next == 0x00 || (next >= 0xD0 && next <= 0xD7) || next == 0xFF) {
                            pos += next == 0xFF ? 1 : 2;
                            continue;
                        }
                        break;
                    }
                    pos++;
                }
                if (pos + 1 >= in.length) {
                    throw new IOException("JPEG without EOI");
                }
                out.write(in, dataStart, pos - dataStart);
            }
        }
    }

    private static boolean keepJpegSegment(
            final int marker,
            final byte[] in,
            final int payload,
            final int end,
            final boolean exifWritten) {
        if (marker == 0xE0) {
            // JFIF without a thumbnail. Dropped when we wrote an EXIF block, which must come first.
            return !exifWritten
                    && startsWith(in, payload, end, "JFIF\0")
                    && end - payload >= 14
                    && in[payload + 12] == 0
                    && in[payload + 13] == 0;
        }
        if (marker == 0xE2) {
            return startsWith(in, payload, end, "ICC_PROFILE\0");
        }
        if (marker == 0xEE) {
            return startsWith(in, payload, end, "Adobe");
        }
        // Other APPn (EXIF, XMP, MPF, IPTC/Photoshop, vendor data) and comments.
        return !((marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE);
    }

    private static boolean startsWith(
            final byte[] in, final int from, final int end, final String prefix) {
        final byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (end - from < p.length) {
            return false;
        }
        for (int i = 0; i < p.length; ++i) {
            if (in[from + i] != p[i]) {
                return false;
            }
        }
        return true;
    }

    /** An APP1 EXIF block holding nothing but IFD0 with the orientation tag. */
    private static void writeOrientationExif(
            final ByteArrayOutputStream out, final int orientation) {
        final byte[] block = {
            (byte) 0xFF,
            (byte) 0xE1,
            0x00,
            0x22, // segment length: 34
            'E',
            'x',
            'i',
            'f',
            0x00,
            0x00,
            'M',
            'M',
            0x00,
            0x2A, // big-endian TIFF header
            0x00,
            0x00,
            0x00,
            0x08, // IFD0 offset
            0x00,
            0x01, // one entry
            0x01,
            0x12, // Orientation
            0x00,
            0x03, // SHORT
            0x00,
            0x00,
            0x00,
            0x01, // count
            0x00,
            (byte) orientation,
            0x00,
            0x00, // value
            0x00,
            0x00,
            0x00,
            0x00 // no next IFD
        };
        out.write(block, 0, block.length);
    }

    // ---------------------------------------------------------------- PNG

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private static final Set<String> PNG_ANCILLARY_KEPT =
            new HashSet<>(
                    Arrays.asList(
                            "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCv", "cLLi",
                            "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT"));

    static byte[] stripPng(final byte[] in) throws IOException {
        if (in.length < PNG_SIGNATURE.length
                || !Arrays.equals(Arrays.copyOf(in, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            throw new IOException("not a PNG");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, PNG_SIGNATURE.length);
        int pos = PNG_SIGNATURE.length;
        while (pos + 12 <= in.length) {
            final long length = readUInt32BE(in, pos);
            if (length > in.length - pos - 12) {
                throw new IOException("invalid PNG chunk length");
            }
            final String type = new String(in, pos + 4, 4, StandardCharsets.ISO_8859_1);
            final int chunkSize = (int) length + 12;
            final boolean critical = Character.isUpperCase(type.charAt(0));
            if (critical || PNG_ANCILLARY_KEPT.contains(type)) {
                out.write(in, pos, chunkSize);
            }
            pos += chunkSize;
            if ("IEND".equals(type)) {
                return out.toByteArray();
            }
        }
        throw new IOException("PNG without IEND");
    }

    // ---------------------------------------------------------------- WebP

    static byte[] stripWebp(final byte[] in) throws IOException {
        if (in.length < 12
                || !startsWith(in, 0, in.length, "RIFF")
                || !startsWith(in, 8, in.length, "WEBP")) {
            throw new IOException("not a WebP");
        }
        final ByteArrayOutputStream chunks = new ByteArrayOutputStream(in.length);
        final long riffEnd = Math.min(in.length, 8 + readUInt32LE(in, 4));
        int pos = 12;
        while (pos + 8 <= riffEnd) {
            final String fourcc = new String(in, pos, 4, StandardCharsets.ISO_8859_1);
            final long size = readUInt32LE(in, pos + 4);
            final long padded = size + (size & 1);
            if (padded > riffEnd - pos - 8) {
                throw new IOException("invalid WebP chunk size");
            }
            final int total = (int) (8 + padded);
            if (!"EXIF".equals(fourcc) && !"XMP ".equals(fourcc)) {
                final byte[] chunk = Arrays.copyOfRange(in, pos, pos + total);
                if ("VP8X".equals(fourcc) && size >= 1) {
                    // Clear the "has EXIF" (0x08) and "has XMP" (0x04) flags.
                    chunk[8] = (byte) (chunk[8] & ~0x0C);
                }
                chunks.write(chunk, 0, chunk.length);
            }
            pos += total;
        }
        final byte[] body = chunks.toByteArray();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
        out.write(in, 0, 4);
        writeUInt32LE(out, body.length + 4L);
        out.write(in, 8, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- GIF

    static byte[] stripGif(final byte[] in) throws IOException {
        if (in.length < 13
                || !(startsWith(in, 0, in.length, "GIF87a")
                        || startsWith(in, 0, in.length, "GIF89a"))) {
            throw new IOException("not a GIF");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        int pos = 13;
        final int lsdFlags = in[10] & 0xff;
        if ((lsdFlags & 0x80) != 0) {
            pos += 3 * (1 << ((lsdFlags & 0x07) + 1));
        }
        if (pos > in.length) {
            throw new IOException("truncated GIF");
        }
        out.write(in, 0, pos);
        while (pos < in.length) {
            final int block = in[pos] & 0xff;
            if (block == 0x3B) { // trailer
                out.write(0x3B);
                return out.toByteArray();
            } else if (block == 0x2C) { // image descriptor
                int p = pos + 10;
                if (p > in.length) {
                    throw new IOException("truncated GIF image");
                }
                final int flags = in[pos + 9] & 0xff;
                if ((flags & 0x80) != 0) {
                    p += 3 * (1 << ((flags & 0x07) + 1));
                }
                p += 1; // LZW minimum code size
                p = skipSubBlocks(in, p);
                out.write(in, pos, p - pos);
                pos = p;
            } else if (block == 0x21) { // extension
                if (pos + 2 > in.length) {
                    throw new IOException("truncated GIF extension");
                }
                final int label = in[pos + 1] & 0xff;
                final int end = skipSubBlocks(in, pos + 2);
                final boolean keep;
                if (label == 0xF9 || label == 0x01) { // graphic control, plain text
                    keep = true;
                } else if (label == 0xFF) { // application: keep only the loop count
                    keep =
                            startsWith(in, pos + 2, end, "\u000bNETSCAPE2.0")
                                    || startsWith(in, pos + 2, end, "\u000bANIMEXTS1.0");
                } else { // comment (0xFE) and unknown extensions
                    keep = false;
                }
                if (keep) {
                    out.write(in, pos, end - pos);
                }
                pos = end;
            } else {
                throw new IOException("unexpected GIF block " + block);
            }
        }
        throw new IOException("GIF without trailer");
    }

    private static int skipSubBlocks(final byte[] in, int pos) throws IOException {
        while (true) {
            if (pos >= in.length) {
                throw new IOException("truncated GIF sub-blocks");
            }
            final int size = in[pos] & 0xff;
            pos += 1 + size;
            if (size == 0) {
                return pos;
            }
        }
    }

    // ---------------------------------------------------------------- animation

    /** Frames shown for this long or shorter are displayed for 100 ms, as browsers do. */
    private static final int MIN_FRAME_DELAY_MS = 10;

    private static final int DEFAULT_FRAME_DELAY_MS = 100;

    /** An animated GIF or WebP, as found in the file's own bytes. */
    public static final class Animation {
        /** {@code image/gif} or {@code image/webp}, from the file signature. */
        public final String mime;

        public final int frames;

        /** Length of one pass through all frames. */
        public final long loopDurationMs;

        Animation(final String mime, final int frames, final long loopDurationMs) {
            this.mime = mime;
            this.frames = frames;
            this.loopDurationMs = loopDurationMs;
        }
    }

    /**
     * Whether {@code input} is an animated GIF or WebP. The type is taken from the file signature,
     * never from a claimed MIME type. The file is read as a stream, never buffered as a whole (it
     * may come from any contact), and at most {@link #MAX_IMAGE_SIZE} bytes of it are looked at.
     * Closes {@code input}. Never throws.
     *
     * @return the animation, or {@code null} for anything else (still images, other formats,
     *     malformed or unreadable files)
     */
    public static Animation inspectAnimation(final InputStream input) {
        try (final InputStream source = input) {
            if (source == null) {
                return null;
            }
            final InputStream in =
                    new BufferedInputStream(ByteStreams.limit(source, MAX_IMAGE_SIZE));
            final byte[] signature = new byte[12];
            final int n = ByteStreams.read(in, signature, 0, signature.length);
            if (n < signature.length) {
                return null;
            }
            if (startsWith(signature, 0, n, "GIF87a") || startsWith(signature, 0, n, "GIF89a")) {
                return inspectGif(in, signature[10] & 0xff);
            }
            if (startsWith(signature, 0, n, "RIFF") && startsWith(signature, 8, n, "WEBP")) {
                return inspectWebp(in, readUInt32LE(signature, 4));
            }
            return null;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@link #inspectAnimation(InputStream)} for a file already in memory. */
    public static Animation inspectAnimation(final byte[] data) {
        return data == null ? null : inspectAnimation(new ByteArrayInputStream(data));
    }

    private static int frameDelay(final int delayMs) {
        return delayMs <= MIN_FRAME_DELAY_MS ? DEFAULT_FRAME_DELAY_MS : delayMs;
    }

    private static int readByte(final InputStream in) throws IOException {
        final int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static void skipSubBlocks(final InputStream in) throws IOException {
        int size;
        while ((size = readByte(in)) != 0) {
            ByteStreams.skipFully(in, size);
        }
    }

    /**
     * @param in positioned after the first 12 bytes of the header
     * @param lsdFlags the packed fields of the logical screen descriptor
     */
    private static Animation inspectGif(final InputStream in, final int lsdFlags)
            throws IOException {
        ByteStreams.skipFully(in, 1); // pixel aspect ratio, the rest of the 13 byte header
        if ((lsdFlags & 0x80) != 0) {
            ByteStreams.skipFully(in, 3L * (1 << ((lsdFlags & 0x07) + 1)));
        }
        int frames = 0;
        long duration = 0;
        int pendingDelayMs = 0;
        final byte[] descriptor = new byte[9];
        while (true) {
            final int block = in.read();
            if (block < 0 || block == 0x3B) { // end of file, trailer
                break;
            } else if (block == 0x2C) { // image descriptor
                ByteStreams.readFully(in, descriptor);
                final int flags = descriptor[8] & 0xff;
                if ((flags & 0x80) != 0) {
                    ByteStreams.skipFully(in, 3L * (1 << ((flags & 0x07) + 1)));
                }
                ByteStreams.skipFully(in, 1); // LZW minimum code size
                skipSubBlocks(in);
                frames++;
                duration += frameDelay(pendingDelayMs);
                pendingDelayMs = 0;
            } else if (block == 0x21) { // extension
                final int label = readByte(in);
                if (label == 0xF9) { // graphic control
                    final byte[] gce = new byte[readByte(in)];
                    ByteStreams.readFully(in, gce);
                    if (gce.length >= 4) {
                        // Delay in hundredths of a second, little endian.
                        pendingDelayMs = ((gce[1] & 0xff) | ((gce[2] & 0xff) << 8)) * 10;
                    }
                }
                skipSubBlocks(in);
            } else {
                return null;
            }
        }
        return frames >= 2 ? new Animation("image/gif", frames, duration) : null;
    }

    /**
     * @param in positioned after {@code RIFF<size>WEBP}
     * @param riffSize the RIFF size field, which counts the {@code WEBP} tag
     */
    private static Animation inspectWebp(final InputStream in, final long riffSize)
            throws IOException {
        long remaining = riffSize - 4;
        boolean animationFlag = false;
        int frames = 0;
        long duration = 0;
        final byte[] header = new byte[8];
        final byte[] frameHeader = new byte[15];
        try {
            while (remaining >= 8) {
                ByteStreams.readFully(in, header);
                final String fourcc = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
                final long size = readUInt32LE(header, 4);
                final long padded = size + (size & 1);
                if (padded > remaining - 8) {
                    break;
                }
                long payloadLeft = padded;
                if ("VP8X".equals(fourcc) && size >= 1) {
                    animationFlag = (readByte(in) & 0x02) != 0;
                    payloadLeft -= 1;
                } else if ("ANMF".equals(fourcc) && size >= 16) {
                    // Frame header: X, Y, width, height (3 bytes each), then a 24 bit duration in
                    // ms.
                    ByteStreams.readFully(in, frameHeader);
                    payloadLeft -= frameHeader.length;
                    frames++;
                    duration +=
                            frameDelay(
                                    (frameHeader[12] & 0xff)
                                            | ((frameHeader[13] & 0xff) << 8)
                                            | ((frameHeader[14] & 0xff) << 16));
                }
                ByteStreams.skipFully(in, payloadLeft);
                remaining -= 8 + padded;
            }
        } catch (final EOFException e) {
            // Truncated file: judge it by the chunks that were complete.
        }
        return animationFlag && frames >= 2 ? new Animation("image/webp", frames, duration) : null;
    }

    // ---------------------------------------------------------------- video

    private static String remuxVideo(
            final Context context, final Uri source, final String mime, final File target)
            throws IOException {
        final int format;
        final String outMime;
        if ("video/webm".equals(mime)) {
            format = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
            outMime = "video/webm";
        } else if ("video/3gpp".equals(mime) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            format = MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
            outMime = "video/3gpp";
        } else {
            format = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            outMime = "video/mp4";
        }
        int rotation = 0;
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            eu.siacs.conversations.persistance.MediaFiles.setDataSource(context, retriever, source);
            final String value =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (value != null) {
                rotation = Integer.parseInt(value);
            }
        } catch (final RuntimeException e) {
            // keep 0
        } finally {
            try {
                retriever.release();
            } catch (final Exception ignored) {
            }
        }

        final MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            eu.siacs.conversations.persistance.MediaFiles.setDataSource(context, extractor, source);
            muxer = new MediaMuxer(target.getAbsolutePath(), format);
            if (rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation);
            }
            final int[] trackMap = new int[extractor.getTrackCount()];
            Arrays.fill(trackMap, -1);
            int bufferSize = 2 * 1024 * 1024;
            int tracks = 0;
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                final MediaFormat trackFormat = extractor.getTrackFormat(i);
                final String trackMime = trackFormat.getString(MediaFormat.KEY_MIME);
                // Only audio and video; metadata/timed-text/location tracks stay behind.
                if (trackMime == null
                        || !(trackMime.startsWith("video/") || trackMime.startsWith("audio/"))) {
                    continue;
                }
                extractor.selectTrack(i);
                trackMap[i] = muxer.addTrack(trackFormat);
                tracks++;
                if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize =
                            Math.max(
                                    bufferSize,
                                    trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                }
            }
            if (tracks == 0) {
                throw new IOException("no audio or video tracks");
            }
            muxer.start();
            started = true;
            final ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buffer.clear();
                final int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                final int track = extractor.getSampleTrackIndex();
                if (track >= 0 && track < trackMap.length && trackMap[track] >= 0) {
                    final int flags =
                            (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                                    : 0;
                    info.set(0, size, Math.max(0, extractor.getSampleTime()), flags);
                    muxer.writeSampleData(trackMap[track], buffer, info);
                }
                extractor.advance();
            }
            muxer.stop();
            started = false;
            if (target.length() == 0) {
                throw new IOException("remux produced an empty file");
            }
            return outMime;
        } catch (final IllegalStateException | IllegalArgumentException e) {
            throw new IOException("unable to remux video", e);
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    if (started) {
                        muxer.stop();
                    }
                } catch (final RuntimeException ignored) {
                }
                try {
                    muxer.release();
                } catch (final RuntimeException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static long readUInt32BE(final byte[] in, final int pos) {
        return ((long) (in[pos] & 0xff) << 24)
                | ((in[pos + 1] & 0xff) << 16)
                | ((in[pos + 2] & 0xff) << 8)
                | (in[pos + 3] & 0xff);
    }

    private static long readUInt32LE(final byte[] in, final int pos) {
        return ((long) (in[pos + 3] & 0xff) << 24)
                | ((in[pos + 2] & 0xff) << 16)
                | ((in[pos + 1] & 0xff) << 8)
                | (in[pos] & 0xff);
    }

    private static void writeUInt32LE(final OutputStream out, final long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }
}
