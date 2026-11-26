package eu.siacs.conversations.ui.widget;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;
import eu.siacs.conversations.R;

/**
 * Fades a scrolling view's own pixels out into its padding, so content passing under a floating bar
 * dissolves rather than sliding under a hard edge.
 *
 * <p>It masks the view's alpha instead of painting a scrim over the top, because whatever is behind
 * the list has to keep showing through: in a chat that is a tiled wallpaper, and a scrim in any
 * single colour would sit on it as a visible band.
 *
 * <p>This is deliberately a fade and not a blur. Android has no backdrop blur for a view in a
 * layout, so a real one means re-rendering the content beneath every frame — Telegram does exactly
 * that, into a bitmap downscaled twelvefold, blurred on a background thread, and still gates it
 * behind a user setting and a device performance class that rules out weaker hardware entirely.
 * This costs no re-render at all.
 *
 * <p>The faded region is the view's own padding, which the floating bars already keep to their own
 * height, so this needs no configuration and follows a bar appearing, growing or being hidden. Only
 * the last {@code edge_fade_length} of it is a ramp, which keeps a tall composer from washing out
 * messages far above it, and the ramp stops at {@code edge_fade_strength} rather than at nothing,
 * so content stays faintly legible through the bar instead of vanishing under it.
 */
public final class EdgeFade {

    /** What the view draws of itself, so the fade can render it a region at a time. */
    public interface Content {
        void draw(Canvas canvas);
    }

    private final Paint paint = new Paint();
    private final int fadeLength;
    private final int maskAlpha;

    private int shaderWidth;
    private int shaderHeight;
    private int shaderTop;
    private int shaderBottom;
    private LinearGradient top;
    private LinearGradient bottom;

    public EdgeFade(final View view) {
        final var resources = view.getResources();
        this.fadeLength = resources.getDimensionPixelSize(R.dimen.edge_fade_length);
        this.maskAlpha =
                Math.round(
                        255f
                                * Math.min(100, resources.getInteger(R.integer.edge_fade_strength))
                                / 100f);
        this.paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    /**
     * Draws {@code content} with its top and bottom padding faded out.
     *
     * <p>Only the two faded bands go through an offscreen layer; the body between them is drawn
     * straight to the screen. Each region is clipped to itself, so the content is still rasterized
     * exactly once over the view — what the split costs is walking the child list three times
     * instead of once, and what it saves is an offscreen buffer the size of the whole view.
     */
    public void draw(final Canvas canvas, final View view, final Content content) {
        final int width = view.getWidth();
        final int height = view.getHeight();
        final int topPadding = view.getPaddingTop();
        final int bottomPadding = view.getPaddingBottom();
        if (maskAlpha <= 0
                || (topPadding <= 0 && bottomPadding <= 0)
                || topPadding + bottomPadding >= height) {
            // switched off, nothing to fade, or the bars leave no body between them worth splitting
            content.draw(canvas);
            return;
        }
        final boolean fadeTop = topPadding > 0;
        final boolean fadeBottom = bottomPadding > 0;
        updateShaders(width, height, topPadding, bottomPadding);
        final int body = canvas.save();
        canvas.clipRect(
                0, fadeTop ? topPadding : 0, width, fadeBottom ? height - bottomPadding : height);
        content.draw(canvas);
        canvas.restoreToCount(body);
        if (fadeTop) {
            band(canvas, content, width, 0, topPadding, top);
        }
        if (fadeBottom) {
            band(canvas, content, width, height - bottomPadding, height, bottom);
        }
    }

    /**
     * Draws nothing in the top padding, then dissolves the first {@code fadeLength} of the body so
     * a row meeting the shelf fades out instead of ending on a cut glyph. The bottom padding still
     * dissolves under the composer.
     */
    public void drawSettled(final Canvas canvas, final View view, final Content content) {
        final int width = view.getWidth();
        final int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final int bodyTop = Math.min(view.getPaddingTop(), height);
        final int bodyBottom = Math.max(bodyTop, height - view.getPaddingBottom());
        final int fade = Math.min(fadeLength, bodyBottom - bodyTop);
        if (fade > 0) {
            final int solid = canvas.save();
            canvas.clipRect(0, bodyTop + fade, width, bodyBottom);
            content.draw(canvas);
            canvas.restoreToCount(solid);
            final int layer = canvas.saveLayer(0, bodyTop, width, bodyTop + fade, null);
            canvas.clipRect(0, bodyTop, width, bodyTop + fade);
            content.draw(canvas);
            paint.setShader(settledRamp(bodyTop, fade));
            canvas.drawRect(0, bodyTop, width, bodyTop + fade, paint);
            canvas.restoreToCount(layer);
        } else {
            final int solid = canvas.save();
            canvas.clipRect(0, bodyTop, width, bodyBottom);
            content.draw(canvas);
            canvas.restoreToCount(solid);
        }
        if (bodyBottom < height) {
            updateShaders(width, height, view.getPaddingTop(), view.getPaddingBottom());
            band(canvas, content, width, bodyBottom, height, bottom);
        }
    }

    private int settledFrom;
    private int settledSpan;
    private LinearGradient settled;

    /** Full mask at the shelf edge, none at the end of the ramp. A partial mask would leave the cut. */
    private LinearGradient settledRamp(final int from, final int span) {
        if (settled != null && settledFrom == from && settledSpan == span) {
            return settled;
        }
        settledFrom = from;
        settledSpan = span;
        settled =
                new LinearGradient(
                        0,
                        from,
                        0,
                        from + span,
                        new int[] {0xFF000000, 0x00000000},
                        null,
                        Shader.TileMode.CLAMP);
        return settled;
    }

    private void band(
            final Canvas canvas,
            final Content content,
            final int width,
            final int from,
            final int to,
            final LinearGradient shader) {
        if (shader == null || to <= from) {
            return;
        }
        final int layer = canvas.saveLayer(0, from, width, to, null);
        canvas.clipRect(0, from, width, to);
        content.draw(canvas);
        paint.setShader(shader);
        canvas.drawRect(0, from, width, to, paint);
        canvas.restoreToCount(layer);
    }

    private void updateShaders(
            final int width, final int height, final int topPadding, final int bottomPadding) {
        if (width == shaderWidth
                && height == shaderHeight
                && topPadding == shaderTop
                && bottomPadding == shaderBottom) {
            return;
        }
        shaderWidth = width;
        shaderHeight = height;
        shaderTop = topPadding;
        shaderBottom = bottomPadding;
        top = topPadding > 0 ? gradient(0, topPadding, true) : null;
        bottom = bottomPadding > 0 ? gradient(height - bottomPadding, height, false) : null;
    }

    /**
     * Faded to {@code maskAlpha} at the outer edge, untouched where the bar ends, ramping between
     * the two over the last {@code fadeLength} of the band.
     *
     * <p>The ends are canvas coordinates, not offsets within the band: a shader is positioned in
     * the canvas it is painted into, so the bottom band's gradient has to start where that band
     * actually sits.
     */
    private LinearGradient gradient(final int from, final int to, final boolean fromTop) {
        final float ramp = Math.min(1f, (float) fadeLength / (to - from));
        final int masked = maskAlpha << 24;
        final int[] colors =
                fromTop
                        ? new int[] {masked, masked, 0x00000000}
                        : new int[] {0x00000000, masked, masked};
        final float[] stops =
                fromTop ? new float[] {0f, 1f - ramp, 1f} : new float[] {0f, ramp, 1f};
        return new LinearGradient(0, from, 0, to, colors, stops, Shader.TileMode.CLAMP);
    }
}
