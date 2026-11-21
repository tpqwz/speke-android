package eu.siacs.conversations.persistance;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * The app's FileProvider (sharing, "open with", camera targets), serving attachments that are
 * encrypted at rest as their plaintext: a read-only, seekable descriptor without a decrypted
 * copy on disk, and the plaintext size in {@link OpenableColumns#SIZE}. Plain files and write
 * access (camera apps writing a capture) are handled by FileProvider as before.
 *
 * <p>The roots mirror {@code res/xml/file_paths.xml}.
 */
public final class MediaFileProvider extends FileProvider {

    @Nullable
    private File fileOf(@NonNull final Uri uri) {
        final List<String> segments = uri.getPathSegments();
        if (segments.size() < 2 || getContext() == null) {
            return null;
        }
        final File filesDir = getContext().getFilesDir();
        final File root =
                switch (segments.get(0)) {
                    case "secure_media" -> new File(filesDir, "media");
                    case "avatars" -> new File(filesDir, "avatars");
                    case "stories" -> new File(filesDir, "stories");
                    case "stickers" -> new File(filesDir, "Stickers");
                    case "internal_camera" -> new File(filesDir, "Camera");
                    case "ext_documents" ->
                            new File(Environment.getExternalStorageDirectory(), "Documents");
                    default -> null;
                };
        if (root == null) {
            return null;
        }
        File file = root;
        for (final String segment : segments.subList(1, segments.size())) {
            file = new File(file, segment);
        }
        try {
            // SECURITY: never leave the root (e.g. "..")
            final File canonical = file.getCanonicalFile();
            final String rootPath = root.getCanonicalPath() + File.separator;
            return canonical.getPath().startsWith(rootPath) ? canonical : null;
        } catch (final IOException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode)
            throws FileNotFoundException {
        final File file = fileOf(uri);
        if (file != null && MediaCrypto.isEncrypted(file)) {
            if (!"r".equals(mode)) {
                // SECURITY: never hand out the ciphertext or let it be overwritten
                throw new FileNotFoundException("encrypted media is read only");
            }
            try {
                return MediaFiles.openReadOnly(getContext(), file);
            } catch (final IOException e) {
                Log.w(Config.LOGTAG, "could not open encrypted media for sharing", e);
                throw new FileNotFoundException(e.getMessage());
            }
        }
        return super.openFile(uri, mode);
    }

    @Override
    public Cursor query(
            @NonNull final Uri uri,
            @Nullable final String[] projection,
            @Nullable final String selection,
            @Nullable final String[] selectionArgs,
            @Nullable final String sortOrder) {
        final Cursor cursor = super.query(uri, projection, selection, selectionArgs, sortOrder);
        final File file = fileOf(uri);
        if (cursor == null || file == null || !MediaCrypto.isEncrypted(file)) {
            return cursor;
        }
        // report the plaintext size, not the size of the ciphertext
        final long size = MediaCrypto.plainLength(getContext(), file);
        final String[] columns = cursor.getColumnNames();
        final MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            if (cursor.moveToFirst()) {
                final Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    row[i] =
                            OpenableColumns.SIZE.equals(columns[i])
                                    ? (Object) size
                                    : cursor.getString(i);
                }
                result.addRow(row);
            }
        } finally {
            cursor.close();
        }
        return result;
    }
}
