package eu.siacs.conversations.persistance;

import android.content.Context;
import android.util.Log;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.security.GeneralSecurityException;

/**
 * Access to attachments that may be encrypted at rest. The key is a Tink keyset kept in the
 * encrypted database ({@link DatabaseBackend#SECRET_MEDIA_KEYSET}): locked database = locked
 * media, and the keyset never goes into a backup (backups carry plaintext under their own key,
 * see {@link AttachmentStorage}).
 *
 * <p>Plain files never need the key, so reading them works even before the database is open.
 */
public final class MediaCrypto {

    private static volatile MediaCipher cipher = null;

    private MediaCrypto() {}

    /** The device's media cipher, creating its keyset on first use. */
    public static MediaCipher cipher(final Context context) throws IOException {
        MediaCipher current = cipher;
        if (current != null) {
            return current;
        }
        synchronized (MediaCrypto.class) {
            if (cipher == null) {
                cipher = load(context.getApplicationContext());
            }
            return cipher;
        }
    }

    private static MediaCipher load(final Context context) throws IOException {
        final DatabaseBackend database;
        try {
            database = DatabaseBackend.getInstance(context);
            String keyset = database.getLocalSecret(DatabaseBackend.SECRET_MEDIA_KEYSET);
            if (keyset == null) {
                keyset =
                        database.setLocalSecretIfAbsent(
                                DatabaseBackend.SECRET_MEDIA_KEYSET, MediaCipher.newKeyset());
                Log.d(Config.LOGTAG, "created media encryption keyset");
            }
            return MediaCipher.fromKeyset(keyset);
        } catch (final GeneralSecurityException e) {
            throw new IOException("media keyset unusable", e);
        } catch (final RuntimeException e) {
            // e.g. the database is still locked by the startup password
            throw new IOException("media key not available", e);
        }
    }

    /** Forgets the key, e.g. when the database is closed or locked. */
    public static void reset() {
        cipher = null;
    }

    /** Preference key; on by default. Off only stops encrypting new files. */
    public static final String PREFERENCE = "encrypt_media_at_rest";

    /**
     * The private folders whose files are stored encrypted: attachments, avatars, chat
     * wallpapers and stickers.
     */
    public static final java.util.List<String> ENCRYPTED_AREAS =
            java.util.List.of("media", "avatars", "backgrounds", "Stickers");

    /**
     * Whether a file stored at {@code target} gets encrypted: only the app's private folders in
     * {@link #ENCRYPTED_AREAS}, and only while the setting is on. Files in public storage (the
     * user's explicit choice) or caches for public uploads stay as they are.
     */
    public static boolean shouldEncrypt(final Context context, final File target) {
        if (target == null || !isEnabled(context)) {
            return false;
        }
        return isEncryptedArea(context.getFilesDir(), target);
    }

    /** Whether {@code target} lies inside one of the {@link #ENCRYPTED_AREAS} of {@code filesDir}. */
    public static boolean isEncryptedArea(final File filesDir, final File target) {
        if (filesDir == null || target == null) {
            return false;
        }
        try {
            final String path = target.getCanonicalPath();
            for (final String area : ENCRYPTED_AREAS) {
                final String root = new File(filesDir, area).getCanonicalPath() + File.separator;
                if (path.startsWith(root)) {
                    return true;
                }
            }
            return false;
        } catch (final IOException e) {
            return false;
        }
    }

    /** Whether the user wants media encrypted at rest. */
    public static boolean isEnabled(final Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFERENCE, true);
    }

    /**
     * Stores a temporary file (share pre-copy, stripped copy, in-app photo) encrypted whenever
     * the feature is on, wherever it lives; atomically. Readers detect it like any attachment.
     */
    public static void storeTemporary(
            final Context context, final InputStream plain, final File target) throws IOException {
        if (isEnabled(context)) {
            cipher(context).encryptFile(plain, target);
        } else {
            store(context, plain, target);
        }
    }

    /** Stores {@code plain} at {@code target}, encrypted if it belongs there; atomically. */
    public static void store(final Context context, final InputStream plain, final File target)
            throws IOException {
        if (shouldEncrypt(context, target)) {
            cipher(context).encryptFile(plain, target);
            return;
        }
        final File part = MediaCipher.partFileFor(target);
        try (final java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = plain.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        } catch (final IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(target) && !(target.delete() && part.renameTo(target))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not move " + part + " to " + target);
        }
    }

    /**
     * Moves a finished plain file (download, received transfer, staged recording) to {@code
     * target}: encrypted into place if it belongs there, then the plain source is deleted.
     */
    public static void moveIntoStorage(final Context context, final File source, final File target)
            throws IOException {
        if (!shouldEncrypt(context, target) || MediaCipher.isEncrypted(source)) {
            // keep the bytes as they are (already encrypted, or meant to stay plain)
            if (!source.renameTo(target)) {
                copyRaw(source, target);
                //noinspection ResultOfMethodCallIgnored
                source.delete();
            }
            return;
        }
        try (final InputStream in = new FileInputStream(source)) {
            cipher(context).encryptFile(in, target);
        }
        if (!source.delete()) {
            Log.w(Config.LOGTAG, "could not delete plain source " + source);
        }
    }

    /** Byte-for-byte copy via {@code target.part} (never re-encrypts). */
    private static void copyRaw(final File source, final File target) throws IOException {
        final File part = MediaCipher.partFileFor(target);
        try (final InputStream in = new FileInputStream(source);
                final java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        } catch (final IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(target) && !(target.delete() && part.renameTo(target))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("Could not move " + part + " to " + target);
        }
    }

    /** {@code raw}, wrapped in encryption if a file at {@code target} should be encrypted. */
    public static java.io.OutputStream wrapForStorage(
            final Context context, final File target, final java.io.OutputStream raw)
            throws IOException {
        return shouldEncrypt(context, target) ? cipher(context).encryptTo(raw) : raw;
    }

    public static boolean isEncrypted(final File file) {
        return MediaCipher.isEncrypted(file);
    }

    /** The plaintext of {@code file}, whether it is stored encrypted or not. */
    public static InputStream openInput(final Context context, final File file)
            throws IOException {
        if (MediaCipher.isEncrypted(file)) {
            return cipher(context).openInput(file);
        }
        return new FileInputStream(file);
    }

    /** Random access to the plaintext of {@code file}, whether stored encrypted or not. */
    public static SeekableByteChannel openSeekable(final Context context, final File file)
            throws IOException {
        if (MediaCipher.isEncrypted(file)) {
            return cipher(context).openSeekable(file);
        }
        return new FileInputStream(file).getChannel();
    }

    /** The plaintext size of {@code file}; for a plain file simply its length. */
    public static long plainLength(final Context context, final File file) {
        if (!MediaCipher.isEncrypted(file)) {
            return file.length();
        }
        try {
            return cipher(context).plainLength(file);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "could not read size of " + file, e);
            return file.length();
        }
    }
}
