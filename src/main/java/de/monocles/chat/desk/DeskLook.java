package de.monocles.chat.desk;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import androidx.annotation.ColorInt;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;

/**
 * Colors for one shelf theme. Night is the dark page. Original and Plain use the app's own
 * Material colors, so the shelf sits in the same family as the floating bars.
 */
public final class DeskLook {

    public enum Theme {
        ORIGINAL("original"),
        PLAIN("plain"),
        NIGHT("night");

        public final String key;

        Theme(String key) {
            this.key = key;
        }

        public static Theme parse(String raw) {
            for (Theme theme : values()) {
                if (theme.key.equals(raw)) {
                    return theme;
                }
            }
            return NIGHT;
        }
    }

    public final Theme theme;
    /** The dark page, including the conversation bar and the composer. */
    public final boolean night;
    /** The chat wallpaper shows behind the shelf. */
    public final boolean wallpaper;
    @ColorInt public final int text;
    @ColorInt public final int muted;
    @ColorInt public final int accent;
    @ColorInt public final int ink;
    @ColorInt public final int page;
    @ColorInt public final int card;
    @ColorInt public final int control;
    @ColorInt public final int hair;
    @ColorInt public final int incoming;
    @ColorInt public final int outgoing;
    @ColorInt public final int onIncoming;
    @ColorInt public final int onOutgoing;
    @ColorInt public final int chipOn;
    @ColorInt public final int quoteFill;

    private DeskLook(
            Theme theme,
            boolean night,
            boolean wallpaper,
            int text,
            int muted,
            int accent,
            int ink,
            int page,
            int card,
            int control,
            int hair,
            int incoming,
            int outgoing,
            int onIncoming,
            int onOutgoing,
            int chipOn,
            int quoteFill) {
        this.theme = theme;
        this.night = night;
        this.wallpaper = wallpaper;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.ink = ink;
        this.page = page;
        this.card = card;
        this.control = control;
        this.hair = hair;
        this.incoming = incoming;
        this.outgoing = outgoing;
        this.onIncoming = onIncoming;
        this.onOutgoing = onOutgoing;
        this.chipOn = chipOn;
        this.quoteFill = quoteFill;
    }

    public static DeskLook of(Context context, Theme theme) {
        if (theme == Theme.NIGHT) {
            return new DeskLook(
                    Theme.NIGHT,
                    true,
                    false,
                    context.getColor(R.color.desk_text),
                    context.getColor(R.color.desk_muted),
                    context.getColor(R.color.desk_accent),
                    context.getColor(R.color.desk_ink),
                    context.getColor(R.color.desk_page),
                    context.getColor(R.color.desk_elev),
                    context.getColor(R.color.desk_control),
                    context.getColor(R.color.desk_hair),
                    context.getColor(R.color.desk_in),
                    context.getColor(R.color.desk_out),
                    context.getColor(R.color.desk_text),
                    context.getColor(R.color.desk_text),
                    context.getColor(R.color.desk_accent),
                    context.getColor(R.color.desk_quote));
        }
        int text = attr(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F);
        int muted =
                attr(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F);
        int accent = attr(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4);
        int surface = attr(context, com.google.android.material.R.attr.colorSurface, 0xFFFFFBFE);
        int container =
                attr(
                        context,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                        surface);
        int primaryContainer =
                attr(context, com.google.android.material.R.attr.colorPrimaryContainer, container);
        int onPrimaryContainer =
                attr(context, com.google.android.material.R.attr.colorOnPrimaryContainer, text);
        int secondaryContainer =
                attr(context, com.google.android.material.R.attr.colorSecondaryContainer, container);
        int onSecondaryContainer =
                attr(context, com.google.android.material.R.attr.colorOnSecondaryContainer, text);
        int outline = attr(context, com.google.android.material.R.attr.colorOutline, 0xFF79747E);
        boolean plain = theme == Theme.PLAIN;
        return new DeskLook(
                plain ? Theme.PLAIN : Theme.ORIGINAL,
                false,
                !plain,
                text,
                muted,
                accent,
                onPrimaryContainer,
                plain ? surface : 0,
                surface,
                container,
                outline,
                secondaryContainer,
                primaryContainer,
                onSecondaryContainer,
                onPrimaryContainer,
                primaryContainer,
                (accent & 0x00FFFFFF) | 0x1A000000);
    }

    public Drawable chip(Context context, boolean selected) {
        if (selected) {
            return round(chipOn, dp(context, 8), 0, 0);
        }
        if (night) {
            return round(control, dp(context, 8), dp(context, 1), hair);
        }
        return round(0, dp(context, 8), 0, 0);
    }

    public Drawable card(Context context, boolean focused) {
        return round(card, dp(context, 12), dp(context, focused ? 2 : 1), focused ? accent : hair);
    }

    public Drawable reply(Context context, boolean focused) {
        return round(
                card, dp(context, 8), dp(context, focused ? 2 : 1), focused ? accent : hair);
    }

    public Drawable bubble(Context context, boolean outgoing, boolean focused) {
        return round(
                outgoing ? this.outgoing : incoming,
                dp(context, 16),
                focused ? dp(context, 2) : 0,
                accent);
    }

    public Drawable field(Context context) {
        return round(night ? control : card, dp(context, 8), dp(context, 1), hair);
    }

    public Drawable day(Context context) {
        return round(night ? 0x33000000 : control, dp(context, 999), 0, 0);
    }

    public Drawable fileChip(Context context) {
        return round(night ? 0x2E000000 : control, dp(context, 10), dp(context, 1), hair);
    }

    public Drawable quote(Context context) {
        float radius = dp(context, 8);
        GradientDrawable fill = round(quoteFill, radius, 0, 0);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(accent);
        bar.setCornerRadii(new float[] {radius, radius, 0, 0, 0, 0, radius, radius});
        LayerDrawable layer = new LayerDrawable(new Drawable[] {fill, bar});
        layer.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
        layer.setLayerWidth(1, dp(context, 3));
        return layer;
    }

    private static GradientDrawable round(int fill, float radius, int stroke, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (stroke > 0) {
            drawable.setStroke(stroke, strokeColor);
        }
        return drawable;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int attr(Context context, int attribute, int fallback) {
        return MaterialColors.getColor(context, attribute, fallback);
    }
}
