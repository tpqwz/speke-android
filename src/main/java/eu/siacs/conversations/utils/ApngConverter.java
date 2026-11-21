package eu.siacs.conversations.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import eu.siacs.conversations.Config;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts an animated PNG to an animated WebP. Android plays animated WebP everywhere (and so do
 * other clients a sticker is sent to), but no APNG: Signal's animated stickers would only ever
 * show their first frame. Frames are decoded and encoded by the platform only; everything stays
 * in memory.
 */
public final class ApngConverter {

    /** Converted files larger than this are not used. */
    public static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private static final int QUALITY = 85;
    private static final int FALLBACK_QUALITY = 70;

    private ApngConverter() {}

    /**
     * @return the animated WebP, or {@code null} when {@code apng} is no APNG or can't be
     *     converted (the caller keeps the original then)
     */
    @Nullable
    public static byte[] toAnimatedWebp(final byte[] apng) {
        if (!ApngReader.isApng(apng)) {
            return null;
        }
        try {
            final ApngReader.Apng animation = ApngReader.parse(apng);
            byte[] webp = encode(animation, QUALITY);
            if (webp.length > MAX_OUTPUT_BYTES) {
                webp = encode(animation, FALLBACK_QUALITY);
            }
            if (webp.length > MAX_OUTPUT_BYTES) {
                Log.d(Config.LOGTAG, "converted APNG too large: " + webp.length);
                return null;
            }
            return webp;
        } catch (final IOException | RuntimeException | OutOfMemoryError e) {
            Log.d(Config.LOGTAG, "could not convert APNG: " + e);
            return null;
        }
    }

    private static byte[] encode(final ApngReader.Apng animation, final int quality)
            throws IOException {
        final Bitmap canvasBitmap =
                Bitmap.createBitmap(animation.width, animation.height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(canvasBitmap);
        // pixel for pixel: never scaled by screen density
        canvas.setDensity(Bitmap.DENSITY_NONE);
        final Paint over = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Paint clear = new Paint();
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        final int width = animation.width;
        final int height = animation.height;
        // what the previous frame showed, and what this one shows
        int[] shown = new int[width * height];
        int[] current = new int[width * height];
        final List<Out> out = new ArrayList<>();
        try {
            boolean first = true;
            for (final ApngReader.Frame frame : animation.frames) {
                final Rect region =
                        new Rect(
                                frame.x,
                                frame.y,
                                frame.x + frame.width,
                                frame.y + frame.height);
                final Bitmap pixels = decode(frame);
                Bitmap saved = null;
                try {
                    int dispose = frame.dispose;
                    if (first && dispose == ApngReader.DISPOSE_PREVIOUS) {
                        dispose = ApngReader.DISPOSE_BACKGROUND;
                    }
                    if (dispose == ApngReader.DISPOSE_PREVIOUS) {
                        saved =
                                Bitmap.createBitmap(
                                        canvasBitmap, frame.x, frame.y, frame.width, frame.height);
                    }
                    if (frame.blend == ApngReader.BLEND_SOURCE) {
                        canvas.drawRect(region, clear);
                    }
                    canvas.drawBitmap(pixels, frame.x, frame.y, over);

                    // Only the region that changed is encoded (as APNG does, which keeps the
                    // file small): it replaces those pixels, so every frame shows exactly the
                    // composited canvas whatever the APNG's blend and dispose ops were.
                    canvasBitmap.getPixels(current, 0, width, 0, 0, width, height);
                    final int[] box =
                            first
                                    ? new int[] {0, 0, width, height}
                                    : changedRegion(shown, current, width, height);
                    final Rect changed =
                            box == null ? null : new Rect(box[0], box[1], box[2], box[3]);
                    if (changed == null) {
                        // the same picture again: show the previous frame longer
                        out.get(out.size() - 1).durationMs += frame.delayMs;
                    } else {
                        final Bitmap part =
                                Bitmap.createBitmap(
                                        canvasBitmap,
                                        changed.left,
                                        changed.top,
                                        changed.width(),
                                        changed.height());
                        try {
                            out.add(new Out(compress(part, quality), frame.delayMs, changed));
                        } finally {
                            if (part != canvasBitmap) part.recycle();
                        }
                    }
                    final int[] swap = shown;
                    shown = current;
                    current = swap;

                    if (dispose == ApngReader.DISPOSE_BACKGROUND) {
                        canvas.drawRect(region, clear);
                    } else if (saved != null) {
                        canvas.drawRect(region, clear);
                        canvas.drawBitmap(saved, frame.x, frame.y, null);
                    }
                } finally {
                    pixels.recycle();
                    if (saved != null) saved.recycle();
                }
                first = false;
            }
        } finally {
            canvasBitmap.recycle();
        }
        final List<AnimatedWebpWriter.Frame> frames = new ArrayList<>(out.size());
        for (final Out o : out) {
            frames.add(
                    new AnimatedWebpWriter.Frame(
                            o.still,
                            o.durationMs,
                            o.region.left,
                            o.region.top,
                            o.region.width(),
                            o.region.height()));
        }
        return AnimatedWebpWriter.mux(width, height, animation.loops, frames);
    }

    /** One encoded frame, before muxing (its duration still grows over identical frames). */
    private static final class Out {
        final byte[] still;
        int durationMs;
        final Rect region;

        Out(final byte[] still, final int durationMs, final Rect region) {
            this.still = still;
            this.durationMs = durationMs;
            this.region = region;
        }
    }

    /**
     * The bounding box of the pixels that differ, its left and top rounded down to even values
     * (WebP stores frame offsets halved), or {@code null} if nothing changed.
     *
     * @return {left, top, right, bottom}, right and bottom exclusive
     */
    static int[] changedRegion(
            final int[] before, final int[] after, final int width, final int height) {
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                if (before[row + x] != after[row + x]) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < 0) {
            return null;
        }
        return new int[] {left & ~1, top & ~1, right + 1, bottom + 1};
    }

    private static Bitmap decode(final ApngReader.Frame frame) throws IOException {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        final Bitmap bitmap =
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.length, options);
        if (bitmap == null) {
            throw new IOException("undecodable APNG frame");
        }
        if (bitmap.getWidth() != frame.width || bitmap.getHeight() != frame.height) {
            bitmap.recycle();
            throw new IOException("APNG frame has the wrong size");
        }
        return bitmap;
    }

    @SuppressWarnings("deprecation")
    private static byte[] compress(final Bitmap bitmap, final int quality) throws IOException {
        final Bitmap.CompressFormat format =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? Bitmap.CompressFormat.WEBP_LOSSY
                        : Bitmap.CompressFormat.WEBP;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(format, quality, out)) {
            throw new IOException("could not encode WebP frame");
        }
        return out.toByteArray();
    }
}
