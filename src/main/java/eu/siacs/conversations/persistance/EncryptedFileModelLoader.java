package eu.siacs.conversations.persistance;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Glide loading of attachments encrypted at rest (File, path String or {@code file://} Uri).
 * Only claims encrypted files; plain ones keep Glide's default loaders. Reports {@link
 * DataSource#LOCAL}: the app's default disk-cache strategy ({@code
 * CustomGlideModule.REMOTE_DATA_ONLY}) caches remote data only, so the decrypted bytes never
 * reach Glide's disk cache.
 */
public final class EncryptedFileModelLoader<T> implements ModelLoader<T, InputStream> {

    private final Context context;
    private final Resolver<T> resolver;

    private interface Resolver<T> {
        @Nullable
        File fileOf(T model);
    }

    private EncryptedFileModelLoader(final Context context, final Resolver<T> resolver) {
        this.context = context.getApplicationContext();
        this.resolver = resolver;
    }

    @Override
    public boolean handles(@NonNull final T model) {
        return MediaCrypto.isEncrypted(resolver.fileOf(model));
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(
            @NonNull final T model, final int width, final int height, @NonNull final Options options) {
        final File file = resolver.fileOf(model);
        if (file == null) {
            return null;
        }
        // the key changes with the file, so a replaced file is not served from memory
        final String key = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        return new LoadData<>(new ObjectKey(key), new Fetcher(context, file));
    }

    private static final class Fetcher implements DataFetcher<InputStream> {
        private final Context context;
        private final File file;
        private InputStream stream;

        private Fetcher(final Context context, final File file) {
            this.context = context;
            this.file = file;
        }

        @Override
        public void loadData(
                @NonNull final Priority priority,
                @NonNull final DataCallback<? super InputStream> callback) {
            try {
                stream = MediaCrypto.openInput(context, file);
                callback.onDataReady(stream);
            } catch (final IOException e) {
                callback.onLoadFailed(e);
            }
        }

        @Override
        public void cleanup() {
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException ignored) {
                    // nothing to do
                }
            }
        }

        @Override
        public void cancel() {}

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

    /**
     * The same files as a {@link android.os.ParcelFileDescriptor} over their plaintext (see
     * {@link MediaFiles#openReadOnly}): Glide's video decoder needs a descriptor to extract a
     * frame, and would otherwise fall back to the raw file, i.e. the ciphertext.
     */
    private static final class Descriptors<T>
            implements ModelLoader<T, android.os.ParcelFileDescriptor> {
        private final Context context;
        private final Resolver<T> resolver;

        private Descriptors(final Context context, final Resolver<T> resolver) {
            this.context = context.getApplicationContext();
            this.resolver = resolver;
        }

        @Override
        public boolean handles(@NonNull final T model) {
            return MediaCrypto.isEncrypted(resolver.fileOf(model));
        }

        @Nullable
        @Override
        public LoadData<android.os.ParcelFileDescriptor> buildLoadData(
                @NonNull final T model,
                final int width,
                final int height,
                @NonNull final Options options) {
            final File file = resolver.fileOf(model);
            if (file == null) {
                return null;
            }
            final String key =
                    file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
            return new LoadData<>(new ObjectKey(key), new DescriptorFetcher(context, file));
        }
    }

    private static final class DescriptorFetcher
            implements DataFetcher<android.os.ParcelFileDescriptor> {
        private final Context context;
        private final File file;
        private android.os.ParcelFileDescriptor descriptor;

        private DescriptorFetcher(final Context context, final File file) {
            this.context = context;
            this.file = file;
        }

        @Override
        public void loadData(
                @NonNull final Priority priority,
                @NonNull final DataCallback<? super android.os.ParcelFileDescriptor> callback) {
            try {
                descriptor = MediaFiles.openReadOnly(context, file);
                callback.onDataReady(descriptor);
            } catch (final IOException | RuntimeException e) {
                callback.onLoadFailed(e instanceof IOException ? (IOException) e : new IOException(e));
            }
        }

        @Override
        public void cleanup() {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (final IOException ignored) {
                    // nothing to do
                }
            }
        }

        @Override
        public void cancel() {}

        @NonNull
        @Override
        public Class<android.os.ParcelFileDescriptor> getDataClass() {
            return android.os.ParcelFileDescriptor.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.LOCAL;
        }
    }

    public static ModelLoaderFactory<File, android.os.ParcelFileDescriptor> fileDescriptorFactory(
            final Context context) {
        return descriptorFactory(context, file -> file);
    }

    public static ModelLoaderFactory<String, android.os.ParcelFileDescriptor>
            pathDescriptorFactory(final Context context) {
        return descriptorFactory(context, EncryptedFileModelLoader::fileOfPath);
    }

    public static ModelLoaderFactory<Uri, android.os.ParcelFileDescriptor> uriDescriptorFactory(
            final Context context) {
        return descriptorFactory(context, EncryptedFileModelLoader::fileOfUri);
    }

    private static <T> ModelLoaderFactory<T, android.os.ParcelFileDescriptor> descriptorFactory(
            final Context context, final Resolver<T> resolver) {
        return new ModelLoaderFactory<>() {
            @NonNull
            @Override
            public ModelLoader<T, android.os.ParcelFileDescriptor> build(
                    @NonNull final MultiModelLoaderFactory multiFactory) {
                return new Descriptors<>(context, resolver);
            }

            @Override
            public void teardown() {}
        };
    }

    private static File fileOfUri(final Uri uri) {
        return uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null
                ? new File(uri.getPath())
                : null;
    }

    private static File fileOfPath(final String path) {
        return path != null && path.startsWith("/") ? new File(path) : null;
    }

    public static ModelLoaderFactory<File, InputStream> fileFactory(final Context context) {
        return factory(context, file -> file);
    }

    public static ModelLoaderFactory<String, InputStream> pathFactory(final Context context) {
        return factory(context, EncryptedFileModelLoader::fileOfPath);
    }

    public static ModelLoaderFactory<Uri, InputStream> uriFactory(final Context context) {
        return factory(context, EncryptedFileModelLoader::fileOfUri);
    }

    private static <T> ModelLoaderFactory<T, InputStream> factory(
            final Context context, final Resolver<T> resolver) {
        return new ModelLoaderFactory<>() {
            @NonNull
            @Override
            public ModelLoader<T, InputStream> build(
                    @NonNull final MultiModelLoaderFactory multiFactory) {
                return new EncryptedFileModelLoader<>(context, resolver);
            }

            @Override
            public void teardown() {}
        };
    }
}
