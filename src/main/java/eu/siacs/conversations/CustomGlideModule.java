package eu.siacs.conversations;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.google.common.base.Supplier;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.http.RoutedUrl;
import java.io.InputStream;
import okhttp3.OkHttpClient;

@GlideModule
public class CustomGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(
            @NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {

        // Start with the app's base OkHttpClient, which is already configured with the app's custom
        // trust managers.
        // This is the crucial step that makes direct connections (without a proxy) work correctly.
        final OkHttpClient baseClient = HttpConnectionManager.okHttpClient(context);

        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        final Supplier<Boolean> useTor =
                () ->
                        preferences.getBoolean(
                                "use_tor", context.getResources().getBoolean(R.bool.use_tor));
        final Supplier<Boolean> useI2p =
                () ->
                        preferences.getBoolean(
                                "use_i2p", context.getResources().getBoolean(R.bool.use_i2p));

        // Host-aware, per-request selector: .onion/.i2p images always go through their overlay,
        // everything else follows the current Tor/I2P preference (fail-closed, never direct).
        final OkHttpClient clientWithProxy =
                HttpConnectionManager.applyProxyPolicy(baseClient.newBuilder(), useTor, useI2p)
                        .build();

        final OkHttpUrlLoader.Factory factory = new OkHttpUrlLoader.Factory(clientWithProxy);

        registry.replace(GlideUrl.class, InputStream.class, factory);

        // URLs loaded for a .onion/.i2p account (RoutedUrl.of): Tor/I2P even with the global
        // setting off.
        registry.prepend(
                RoutedUrl.class,
                InputStream.class,
                new RoutedUrl.Factory(baseClient, useTor, useI2p));

        // Encrypted files as descriptors, which Glide's video decoder needs for thumbnails.
        // Registered before the stream loaders below: prepend puts each at the front, so the
        // streams are tried first (images decode from them; a descriptor costs a full decrypted
        // copy on Android 7) and only what they can't decode (videos) falls through to these.
        registry.prepend(
                java.io.File.class,
                android.os.ParcelFileDescriptor.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.fileDescriptorFactory(
                        context));
        registry.prepend(
                String.class,
                android.os.ParcelFileDescriptor.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.pathDescriptorFactory(
                        context));
        registry.prepend(
                android.net.Uri.class,
                android.os.ParcelFileDescriptor.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.uriDescriptorFactory(
                        context));
        // Attachments encrypted at rest: decrypted on the fly (only claims encrypted files).
        registry.prepend(
                java.io.File.class,
                InputStream.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.fileFactory(context));
        registry.prepend(
                String.class,
                InputStream.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.pathFactory(context));
        registry.prepend(
                android.net.Uri.class,
                InputStream.class,
                eu.siacs.conversations.persistance.EncryptedFileModelLoader.uriFactory(context));
    }

    @Override
    public void applyOptions(
            @NonNull final Context context, @NonNull final com.bumptech.glide.GlideBuilder builder) {
        // Only original data from the network goes into the disk cache. DiskCacheStrategy.DATA
        // would also cache LOCAL data: the decrypted bytes of every image stored encrypted at
        // rest (attachments, avatars, stickers, wallpapers) would end up there as plaintext.
        builder.setDefaultRequestOptions(
                new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(REMOTE_DATA_ONLY));
    }

    /** Caches remote originals (link previews, remote images) only; never local or decoded data. */
    public static final com.bumptech.glide.load.engine.DiskCacheStrategy REMOTE_DATA_ONLY =
            new com.bumptech.glide.load.engine.DiskCacheStrategy() {
                @Override
                public boolean isDataCacheable(
                        final com.bumptech.glide.load.DataSource dataSource) {
                    return dataSource == com.bumptech.glide.load.DataSource.REMOTE;
                }

                @Override
                public boolean isResourceCacheable(
                        final boolean isFromAlternateCacheKey,
                        final com.bumptech.glide.load.DataSource dataSource,
                        final com.bumptech.glide.load.EncodeStrategy encodeStrategy) {
                    return false;
                }

                @Override
                public boolean decodeCachedResource() {
                    return false;
                }

                @Override
                public boolean decodeCachedData() {
                    return true;
                }
            };
}
