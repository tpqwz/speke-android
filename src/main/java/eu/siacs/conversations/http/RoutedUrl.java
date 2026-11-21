package eu.siacs.conversations.http;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.google.common.base.Supplier;
import eu.siacs.conversations.entities.Account;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import okhttp3.OkHttpClient;

/**
 * A Glide model: an http(s) URL loaded on behalf of an account that is itself a .onion/.i2p
 * account, so it has to go through Tor/I2P even where the global Tor/I2P settings are off (same
 * rule as {@code buildHttpClient} and {@code CreatePostActivity.needsProxy}). Plain URL strings
 * keep the global settings (CustomGlideModule).
 *
 * <p>Deliberately not a {@link GlideUrl} subclass: Glide would then also offer it to the global
 * GlideUrl loader and, after a failed proxied load (e.g. Orbot not running), retry it there, i.e.
 * directly. Only {@link Loader} handles this model.
 */
public final class RoutedUrl {

    private final String url;
    private final boolean tor;
    private final boolean i2p;

    private RoutedUrl(final String url, final boolean tor, final boolean i2p) {
        this.url = url;
        this.tor = tor;
        this.i2p = i2p;
    }

    /**
     * The model to hand to {@code Glide.load()} for {@code url} on behalf of {@code account}: a
     * RoutedUrl if the account forces Tor/I2P for an http(s) URL, otherwise {@code url} itself.
     */
    @Nullable
    public static Object of(@Nullable final String url, @Nullable final Account account) {
        if (account == null) return url;
        return of(url, account.isOnion(), account.isI2P());
    }

    /** As {@link #of(String, Account)}, with the account's forced routing given directly. */
    @Nullable
    public static Object of(@Nullable final String url, final boolean tor, final boolean i2p) {
        if (url == null || (!tor && !i2p)) return url;
        final String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return url;
        return new RoutedUrl(url, tor, i2p);
    }

    public String getUrl() {
        return url;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof RoutedUrl other)) return false;
        return tor == other.tor && i2p == other.i2p && url.equals(other.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, tor, i2p);
    }

    @NonNull
    @Override
    public String toString() {
        return url;
    }

    /**
     * Loads a {@link RoutedUrl} with an OkHttp client whose routing is the account's forced Tor/I2P
     * OR the current global settings, through the same {@link
     * HttpConnectionManager#applyProxyPolicy} as everything else (fail-closed, no direct fallback).
     */
    public static final class Loader implements ModelLoader<RoutedUrl, InputStream> {
        private final ModelLoader<GlideUrl, InputStream> torLoader;
        private final ModelLoader<GlideUrl, InputStream> i2pLoader;
        private final ModelLoader<GlideUrl, InputStream> bothLoader;

        private Loader(
                final OkHttpClient base,
                final Supplier<Boolean> globalTor,
                final Supplier<Boolean> globalI2p) {
            this.torLoader = loader(base, () -> true, globalI2p);
            this.i2pLoader = loader(base, globalTor, () -> true);
            this.bothLoader = loader(base, () -> true, () -> true);
        }

        private static ModelLoader<GlideUrl, InputStream> loader(
                final OkHttpClient base, final Supplier<Boolean> tor, final Supplier<Boolean> i2p) {
            final OkHttpClient client =
                    HttpConnectionManager.applyProxyPolicy(base.newBuilder(), tor, i2p).build();
            return new OkHttpUrlLoader(client);
        }

        @Nullable
        @Override
        public LoadData<InputStream> buildLoadData(
                @NonNull final RoutedUrl model,
                final int width,
                final int height,
                @NonNull final Options options) {
            final ModelLoader<GlideUrl, InputStream> loader =
                    model.tor && model.i2p ? bothLoader : model.tor ? torLoader : i2pLoader;
            return loader.buildLoadData(new GlideUrl(model.url), width, height, options);
        }

        @Override
        public boolean handles(@NonNull final RoutedUrl model) {
            return true;
        }
    }

    public static final class Factory implements ModelLoaderFactory<RoutedUrl, InputStream> {
        private final Loader loader;

        public Factory(
                final OkHttpClient base,
                final Supplier<Boolean> globalTor,
                final Supplier<Boolean> globalI2p) {
            this.loader = new Loader(base, globalTor, globalI2p);
        }

        @NonNull
        @Override
        public ModelLoader<RoutedUrl, InputStream> build(
                @NonNull final MultiModelLoaderFactory multiFactory) {
            return loader;
        }

        @Override
        public void teardown() {}
    }
}
