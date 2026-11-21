package eu.siacs.conversations.worker;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Stopwatch;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.persistance.MediaCipher;
import eu.siacs.conversations.persistance.MediaCrypto;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Encrypts files in the private content folders ({@link MediaCrypto#ENCRYPTED_AREAS}:
 * attachments, avatars, wallpapers, stickers) that are still stored as plain files (from before
 * encryption at rest), one by one. Each file is encrypted next to itself and only then renamed over the original,
 * so the work is crash-safe, resumable and idempotent: readers handle plain and encrypted files,
 * and an encrypted file is never touched again.
 */
public class MediaEncryptionWorker extends Worker {

    private static final String WORK_NAME = "encrypt-media-at-rest";
    /**
     * The worker's encrypted copy of {@code X} is {@code X.<uuid>.mcenc}: a shape no received
     * attachment has by accident (a file sent as "photo.enc" is an ordinary attachment and gets
     * encrypted like any other).
     */
    private static final java.util.regex.Pattern WORKER_COPY =
            java.util.regex.Pattern.compile(
                    "(.+)\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mcenc");

    /** The name of the original {@code name} is a worker copy of, or null. */
    static String originalOfWorkerCopy(final String name) {
        if (name == null) {
            return null;
        }
        final java.util.regex.Matcher matcher = WORKER_COPY.matcher(name);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Files the worker leaves alone: its own copies and unfinished writes. */
    static boolean isWorkFile(final String name) {
        return originalOfWorkerCopy(name) != null || MediaCipher.isPartFile(name);
    }

    /**
     * Cleans up after a crash between writing {@code X.<uuid>.mcenc} and renaming it over
     * {@code X}: if X still exists, the copy is dropped (X gets encrypted again); if X is gone,
     * the encrypted copy is the only one and takes its place.
     */
    static void recoverInterrupted(final File directory) {
        final File[] leftovers =
                directory.listFiles((dir, name) -> originalOfWorkerCopy(name) != null);
        if (leftovers == null) {
            return;
        }
        for (final File leftover : leftovers) {
            final File original = new File(directory, originalOfWorkerCopy(leftover.getName()));
            if (original.exists()) {
                if (!leftover.delete()) {
                    Log.w(Config.LOGTAG, "could not delete " + leftover);
                }
            } else if (MediaCipher.isEncrypted(leftover) && !leftover.renameTo(original)) {
                Log.w(Config.LOGTAG, "could not restore " + original);
            }
        }
    }

    public MediaEncryptionWorker(
            @NonNull final Context context, @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    /** Starts (or keeps) the migration; a no-op when the setting is off. */
    public static void schedule(final Context context) {
        if (!isEnabled(context)) {
            return;
        }
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(MediaEncryptionWorker.class)
                        .setConstraints(
                                new Constraints.Builder()
                                        .setRequiresStorageNotLow(true)
                                        .setRequiresBatteryNotLow(true)
                                        .build())
                        .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request);
    }

    private static boolean isEnabled(final Context context) {
        return MediaCrypto.shouldEncrypt(context, new File(mediaDirectory(context), "probe"));
    }

    private static File mediaDirectory(final Context context) {
        return new File(context.getFilesDir(), "media");
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context context = getApplicationContext();
        if (!isEnabled(context)) {
            return Result.success();
        }
        final MediaCipher cipher;
        try {
            cipher = MediaCrypto.cipher(context);
        } catch (final IOException e) {
            // e.g. the database is still locked: try again later
            Log.d(Config.LOGTAG, "media key not available yet, retrying later", e);
            return Result.retry();
        }
        final java.util.List<File> files = new java.util.ArrayList<>();
        for (final String area : MediaCrypto.ENCRYPTED_AREAS) {
            collect(new File(context.getFilesDir(), area), files, 0);
        }
        final Stopwatch stopwatch = Stopwatch.createStarted();
        int encrypted = 0;
        int failed = 0;
        for (final File file : files) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "media encryption stopped after " + encrypted + " files");
                return Result.retry();
            }
            if (!isEnabled(context)) {
                return Result.success();
            }
            if (!file.isFile()
                    || isWorkFile(file.getName())
                    || file.getName().startsWith(".")
                    || MediaCipher.isEncrypted(file)) {
                continue;
            }
            try {
                if (encrypt(cipher, file)) {
                    encrypted++;
                }
            } catch (final IOException e) {
                failed++;
                Log.w(Config.LOGTAG, "could not encrypt " + file.getName(), e);
            }
        }
        Log.d(
                Config.LOGTAG,
                "encrypted "
                        + encrypted
                        + " stored attachments in "
                        + stopwatch.stop()
                        + (failed > 0 ? " (" + failed + " failed)" : ""));
        return Result.success();
    }

    /** Sticker packs have subfolders; nothing nests deeper than this. */
    private static final int MAX_DEPTH = 4;

    /** All files below {@code directory}, after cleaning up interrupted runs in each folder. */
    private static void collect(final File directory, final java.util.List<File> out, final int depth) {
        if (depth > MAX_DEPTH || !directory.isDirectory()) {
            return;
        }
        recoverInterrupted(directory);
        final File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        final long staleBefore = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        for (final File child : children) {
            if (child.isDirectory()) {
                collect(child, out, depth + 1);
            } else if (MediaCipher.isPartFile(child.getName())) {
                // an unfinished write left by a crash (writers use unique names)
                if (child.lastModified() < staleBefore && !child.delete()) {
                    Log.w(Config.LOGTAG, "could not delete " + child);
                }
            } else {
                out.add(child);
            }
        }
    }

    /**
     * Encrypts {@code file} in place. Returns false (and leaves it alone) if it changed while
     * being encrypted, e.g. because it was replaced by a newly stored version.
     */
    private static boolean encrypt(final MediaCipher cipher, final File file) throws IOException {
        final long length = file.length();
        final long modified = file.lastModified();
        final File target =
                new File(file.getPath() + "." + java.util.UUID.randomUUID() + ".mcenc");
        final MediaCipher.EncryptedFileOutputStream out = cipher.create(target);
        try (final InputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.commit();
        } finally {
            out.abort(); // no-op after commit
        }
        // gone (deleted, or renamed to its content name) or changed meanwhile: drop the copy, so
        // a deleted attachment doesn't come back
        if (!file.exists() || file.length() != length || file.lastModified() != modified) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
        // an open reader keeps reading the old plain file; new readers get the encrypted one
        if (!target.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("could not replace " + file);
        }
        return true;
    }
}
