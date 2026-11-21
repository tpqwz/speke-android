package eu.siacs.conversations.persistance;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * ExoPlayer access to attachments encrypted at rest: plays and seeks in the plaintext through
 * the seekable decrypting channel, without a decrypted copy. Anything else (plain files, other
 * schemes) goes to the default data source.
 */
@UnstableApi
public final class EncryptedMediaDataSource extends BaseDataSource {

    private final Context context;
    private final DataSource fallback;
    private DataSource openFallback = null;
    private SeekableByteChannel channel = null;
    private Uri uri = null;
    private long remaining = 0;

    private EncryptedMediaDataSource(final Context context) {
        super(false);
        this.context = context.getApplicationContext();
        this.fallback = new DefaultDataSource.Factory(this.context).createDataSource();
    }

    /** A media source factory for players that may play encrypted attachments. */
    public static MediaSource.Factory mediaSourceFactory(final Context context) {
        final DataSource.Factory factory = () -> new EncryptedMediaDataSource(context);
        return new DefaultMediaSourceFactory(factory);
    }

    @Override
    public long open(@NonNull final DataSpec dataSpec) throws IOException {
        final Uri requested = dataSpec.uri;
        final File file =
                "file".equals(requested.getScheme()) && requested.getPath() != null
                        ? new File(requested.getPath())
                        : null;
        if (file == null || !MediaCrypto.isEncrypted(file)) {
            openFallback = fallback;
            return fallback.open(dataSpec);
        }
        transferInitializing(dataSpec);
        uri = requested;
        channel = MediaCrypto.openSeekable(context, file);
        final long size = channel.size();
        if (dataSpec.position > size) {
            throw new IOException("position beyond end of " + file);
        }
        channel.position(dataSpec.position);
        remaining =
                dataSpec.length == C.LENGTH_UNSET
                        ? size - dataSpec.position
                        : Math.min(dataSpec.length, size - dataSpec.position);
        transferStarted(dataSpec);
        return remaining;
    }

    @Override
    public int read(@NonNull final byte[] buffer, final int offset, final int length)
            throws IOException {
        if (openFallback != null) {
            return openFallback.read(buffer, offset, length);
        }
        if (length == 0) {
            return 0;
        }
        if (remaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        final int wanted = (int) Math.min(length, remaining);
        final int read = channel.read(ByteBuffer.wrap(buffer, offset, wanted));
        if (read < 0) {
            return C.RESULT_END_OF_INPUT;
        }
        remaining -= read;
        bytesTransferred(read);
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return openFallback != null ? openFallback.getUri() : uri;
    }

    @Override
    public void close() throws IOException {
        if (openFallback != null) {
            try {
                openFallback.close();
            } finally {
                openFallback = null;
            }
            return;
        }
        uri = null;
        if (channel != null) {
            try {
                channel.close();
            } finally {
                channel = null;
                transferEnded();
            }
        }
    }
}
