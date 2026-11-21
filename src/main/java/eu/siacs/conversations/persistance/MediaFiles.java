package eu.siacs.conversations.persistance;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.ImageDecoder;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.UUID;

/**
 * Adapters for components that need more than a stream: random access through a {@link
 * MediaDataSource}, a seekable {@link ParcelFileDescriptor} or an {@link ImageDecoder.Source}.
 * They all serve the plaintext of attachments that may be encrypted at rest, without ever
 * writing it to a named file (on API 24/25 a temporary file is unlinked right after opening).
 */
public final class MediaFiles {

    private static HandlerThread proxyThread;

    private MediaFiles() {}

    /** Points {@code retriever} at the plaintext of {@code file}. */
    public static void setDataSource(
            final Context context, final MediaMetadataRetriever retriever, final File file)
            throws IOException {
        if (!MediaCrypto.isEncrypted(file)) {
            retriever.setDataSource(file.getAbsolutePath());
            return;
        }
        retriever.setDataSource(new ChannelMediaDataSource(MediaCrypto.openSeekable(context, file)));
    }

    /** Points {@code extractor} at the plaintext of {@code file}. */
    public static void setDataSource(
            final Context context, final android.media.MediaExtractor extractor, final File file)
            throws IOException {
        if (!MediaCrypto.isEncrypted(file)) {
            extractor.setDataSource(file.getAbsolutePath());
            return;
        }
        extractor.setDataSource(new ChannelMediaDataSource(MediaCrypto.openSeekable(context, file)));
    }

    /** Like {@link #setDataSource(Context, android.media.MediaExtractor, File)} for any Uri. */
    public static void setDataSource(
            final Context context,
            final android.media.MediaExtractor extractor,
            final android.net.Uri uri)
            throws IOException {
        final File file = fileOf(uri);
        if (file != null) {
            setDataSource(context, extractor, file);
        } else {
            extractor.setDataSource(context, uri, null);
        }
    }

    /**
     * A transcoder source reading the plaintext of {@code uri} (an attachment or temp file may
     * be encrypted at rest), without the source's location.
     */
    public static com.otaliastudios.transcoder.source.DataSource transcoderSource(
            final Context context, final android.net.Uri uri) {
        final Context app = context.getApplicationContext();
        return new com.otaliastudios.transcoder.source.DefaultDataSource() {
            @Override
            protected void initializeExtractor(final android.media.MediaExtractor extractor)
                    throws IOException {
                setDataSource(app, extractor, uri);
            }

            @Override
            protected void initializeRetriever(final MediaMetadataRetriever retriever) {
                try {
                    setDataSource(app, retriever, uri);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public double[] getLocation() {
                return null; // never copy the recording location into the result
            }
        };
    }

    private static File fileOf(final android.net.Uri uri) {
        return uri != null && "file".equals(uri.getScheme()) && uri.getPath() != null
                ? new File(uri.getPath())
                : null;
    }

    /** Like {@link #setDataSource(Context, MediaMetadataRetriever, File)} for any Uri. */
    public static void setDataSource(
            final Context context,
            final MediaMetadataRetriever retriever,
            final android.net.Uri uri)
            throws IOException {
        final File file = fileOf(uri);
        if (file != null) {
            setDataSource(context, retriever, file);
        } else {
            retriever.setDataSource(context, uri);
        }
    }

    /** Like {@link #openReadOnly(Context, File)} for any Uri. */
    public static ParcelFileDescriptor openReadOnly(final Context context, final android.net.Uri uri)
            throws IOException {
        final File file = fileOf(uri);
        if (file != null) {
            return openReadOnly(context, file);
        }
        return context.getContentResolver().openFileDescriptor(uri, "r");
    }

    /**
     * The plaintext of {@code uri}: a {@code file://} file that is encrypted at rest is decrypted,
     * anything else is read through the content resolver.
     */
    public static InputStream openInput(final Context context, final android.net.Uri uri)
            throws IOException {
        final File file = fileOf(uri);
        if (file != null) {
            return MediaCrypto.openInput(context, file);
        }
        final InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new java.io.FileNotFoundException("could not open " + uri);
        }
        return in;
    }

    /** Like {@link #imageSource(Context, File)} for any Uri. */
    @RequiresApi(api = Build.VERSION_CODES.P)
    public static ImageDecoder.Source imageSource(final Context context, final android.net.Uri uri)
            throws IOException {
        final File file = fileOf(uri);
        if (file != null) {
            return imageSource(context, file);
        }
        return ImageDecoder.createSource(context.getContentResolver(), uri);
    }

    /**
     * Releases {@code retriever}, which also closes its (decrypting) data source. Without it the
     * decrypting channel and its descriptor would stay open until garbage collection.
     */
    public static void release(final MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (final Exception ignored) {
            // nothing more to free
        }
    }

    /** A read-only, seekable descriptor of the plaintext of {@code file}. */
    public static ParcelFileDescriptor openReadOnly(final Context context, final File file)
            throws IOException {
        if (!MediaCrypto.isEncrypted(file)) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return openProxy(context, MediaCrypto.openSeekable(context, file));
        }
        return openUnlinkedCopy(context, file);
    }

    /** An {@link ImageDecoder} source for the plaintext of {@code file}. */
    @RequiresApi(api = Build.VERSION_CODES.P)
    public static ImageDecoder.Source imageSource(final Context context, final File file)
            throws IOException {
        if (!MediaCrypto.isEncrypted(file)) {
            return ImageDecoder.createSource(file);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ImageDecoder.createSource(
                    () ->
                            new AssetFileDescriptor(
                                    openReadOnly(context, file),
                                    0,
                                    AssetFileDescriptor.UNKNOWN_LENGTH));
        }
        // API 28: no descriptor source yet, so from memory - bounded, never an unbounded copy
        try (final InputStream in = MediaCrypto.openInput(context, file)) {
            return ImageDecoder.createSource(
                    ByteBuffer.wrap(
                            readBounded(
                                    in,
                                    MediaCrypto.plainLength(context, file),
                                    MAX_IN_MEMORY_IMAGE_P)));
        }
    }

    /** API 28 (no descriptor source): bigger encrypted images get no preview instead of an OOM. */
    private static final long MAX_IN_MEMORY_IMAGE_P = 64L * 1024 * 1024;

    /**
     * Reads exactly {@code expected} bytes into one array (no doubling copy). Fails when {@code
     * expected} exceeds {@code limit} or the stream does not hold exactly that many bytes.
     */
    static byte[] readBounded(final InputStream in, final long expected, final long limit)
            throws IOException {
        if (expected < 0 || expected > limit || expected > Integer.MAX_VALUE - 8) {
            throw new IOException("image too large to decode in memory: " + expected);
        }
        final byte[] data = new byte[(int) expected];
        int offset = 0;
        while (offset < data.length) {
            final int n = in.read(data, offset, data.length - offset);
            if (n < 0) {
                throw new IOException("image shorter than its recorded length");
            }
            offset += n;
        }
        if (in.read() >= 0) {
            throw new IOException("image longer than its recorded length");
        }
        return data;
    }

    private static synchronized Handler proxyHandler() {
        if (proxyThread == null) {
            proxyThread = new HandlerThread("media-fd");
            proxyThread.start();
        }
        return new Handler(proxyThread.getLooper());
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static ParcelFileDescriptor openProxy(
            final Context context, final SeekableByteChannel channel) throws IOException {
        final StorageManager storage = context.getSystemService(StorageManager.class);
        final long size = channel.size();
        try {
            return storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override
                        public long onGetSize() {
                            return size;
                        }

                        @Override
                        public int onRead(final long offset, final int length, final byte[] data)
                                throws ErrnoException {
                            try {
                                return readFully(channel, offset, data, 0, length);
                            } catch (final IOException e) {
                                Log.w(Config.LOGTAG, "could not read encrypted media", e);
                                throw new ErrnoException("onRead", OsConstants.EIO);
                            }
                        }

                        @Override
                        public void onRelease() {
                            try {
                                channel.close();
                            } catch (final IOException ignored) {
                                // nothing to do
                            }
                        }
                    },
                    proxyHandler());
        } catch (final IOException e) {
            channel.close();
            throw e;
        }
    }

    /** API 24/25: a decrypted copy whose name is removed at once; only the descriptor sees it. */
    private static ParcelFileDescriptor openUnlinkedCopy(final Context context, final File file)
            throws IOException {
        final File directory = temporaryDirectory(context);
        final File copy = new File(directory, UUID.randomUUID().toString());
        try (final InputStream in = MediaCrypto.openInput(context, file);
                final FileOutputStream out = new FileOutputStream(copy)) {
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (final IOException e) {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            throw e;
        }
        try {
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /**
     * A {@link java.util.zip.ZipFile} (WebXDC) over the plaintext of {@code file}. ZipFile needs
     * a real file; for an encrypted one that is a decrypted copy whose name is removed right
     * after opening, so it exists only as long as the returned ZipFile is open.
     */
    public static java.util.zip.ZipFile openZip(final Context context, final File file)
            throws IOException {
        if (!MediaCrypto.isEncrypted(file)) {
            return new java.util.zip.ZipFile(file);
        }
        final File copy = new File(temporaryDirectory(context), UUID.randomUUID() + ".zip");
        try {
            try (final InputStream in = MediaCrypto.openInput(context, file);
                    final FileOutputStream out = new FileOutputStream(copy)) {
                final byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            return new java.util.zip.ZipFile(copy);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    /** Private, never backed up; wiped at startup ({@link #wipeTemporary}). */
    public static File temporaryDirectory(final Context context) {
        final File directory = new File(context.getNoBackupFilesDir(), "media-tmp");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_WIPE =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    /**
     * Whether a temporary file may still be wanted by a later process: camera and editor results,
     * shares and voice recordings can be referenced from UI state restored after a process death.
     * Everything else (transcoder output, metadata-stripped and decrypted copies, partial writes)
     * belongs to work that died with its process.
     */
    public static boolean isResumableTemporary(final String name) {
        if (name.endsWith(".part")) {
            return false; // an unfinished write
        }
        return name.startsWith("PHOTO_")
                || name.startsWith("VIDEO_")
                || name.startsWith("EDIT_")
                || name.startsWith("SHARE_")
                || name.startsWith("RECORDING_");
    }

    /**
     * Removes plaintext leftovers of a crash. The first run in a process removes, whatever their
     * age, the files no later process can resume; otherwise (the running process may be
     * recording or transcoding into this folder) only files untouched for hours are removed.
     */
    public static void wipeTemporary(final Context context) {
        final File[] files = temporaryDirectory(context).listFiles();
        final boolean first = FIRST_WIPE.getAndSet(false);
        final long cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L;
        if (first) {
            // upload copies a crash left behind; no upload of this process has started yet
            wipeCacheTemporaries(context);
            // interrupted downloads are plain; keep only recent ones, which may still resume
            final File[] partials = new File(context.getCacheDir(), "partial").listFiles();
            if (partials != null) {
                for (final File partial : partials) {
                    if (partial.isFile() && partial.lastModified() < cutoff && !partial.delete()) {
                        Log.w(Config.LOGTAG, "could not delete partial download " + partial);
                    }
                }
            }
        }
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (!file.isFile()) {
                continue;
            }
            final boolean stale =
                    file.lastModified() < cutoff
                            || (first && !isResumableTemporary(file.getName()));
            if (stale && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete temporary media " + file);
            }
        }
    }

    private static final java.util.regex.Pattern CACHE_TEMPORARY =
            java.util.regex.Pattern.compile(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\.[A-Za-z0-9]{1,8})?");

    /**
     * Whether a file directly in the cache folder is a temporary copy made for an upload
     * (stories and other published media: {@code FileBackend.getTemporaryFile}, {@code
     * <uuid>.strip}). Those are plain by design and only needed while the upload runs. PGP
     * ciphertext ({@code .pgp}, still to be decrypted) is never one.
     */
    public static boolean isCacheTemporaryName(final String name) {
        return name != null
                && !name.endsWith(".pgp")
                && CACHE_TEMPORARY.matcher(name).matches();
    }

    /** Deletes the upload copies directly in the cache folder (never folders). */
    public static void wipeCacheTemporaries(final Context context) {
        final File[] files = context.getCacheDir().listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (file.isFile() && isCacheTemporaryName(file.getName()) && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete temporary upload copy " + file);
            }
        }
    }

    /**
     * Deletes everything in the temporary folder: used when the app locks, which ends every
     * recording, transcode and editor session, so no plaintext copy stays on the device.
     */
    public static void wipeAllTemporary(final Context context) {
        final File[] files = temporaryDirectory(context).listFiles();
        if (files == null) {
            return;
        }
        for (final File file : files) {
            if (file.isFile() && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete temporary media " + file);
            }
        }
    }

    static int readFully(
            final SeekableByteChannel channel,
            final long position,
            final byte[] data,
            final int offset,
            final int length)
            throws IOException {
        synchronized (channel) {
            if (position >= channel.size()) {
                return 0;
            }
            channel.position(position);
            final ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            return buffer.position() - offset;
        }
    }

    /** Random access for MediaMetadataRetriever and players. */
    private static final class ChannelMediaDataSource extends MediaDataSource {
        private final SeekableByteChannel channel;

        private ChannelMediaDataSource(final SeekableByteChannel channel) {
            this.channel = channel;
        }

        @Override
        public int readAt(final long position, final byte[] buffer, final int offset, final int size)
                throws IOException {
            if (size == 0) {
                return 0;
            }
            final int read = readFully(channel, position, buffer, offset, size);
            return read == 0 ? -1 : read;
        }

        @Override
        public long getSize() throws IOException {
            return channel.size();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "MediaFiles";
    }
}
