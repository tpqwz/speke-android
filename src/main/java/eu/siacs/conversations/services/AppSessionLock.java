package eu.siacs.conversations.services;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.persistance.MediaCrypto;
import java.util.Collection;

/**
 * Locks the app like Molly: only available with the startup password (the database key is
 * derived from it and never stored). Locking ends the process, which reliably drops the
 * database key, the media keyset, the session password and every decoded message or image from
 * memory, something a JVM can't zero by itself. The next start asks for the password again;
 * until then the service finds the database locked and stays offline (the server keeps the
 * messages).
 */
public final class AppSessionLock {

    /** Minutes in the background before the app locks itself; "0" = never. */
    public static final String AUTO_LOCK = "auto_lock_minutes";

    private AppSessionLock() {}

    /** Only with the startup password: without it the key is stored and a lock wipes nothing. */
    public static boolean isAvailable(final Context context) {
        return new AppSettings(context).isPasswordOnStartupRequired();
    }

    /** The configured auto-lock delay, or 0 for never. */
    public static long autoLockDelayMillis(final Context context) {
        if (!isAvailable(context)) {
            return 0;
        }
        final String value =
                PreferenceManager.getDefaultSharedPreferences(context).getString(AUTO_LOCK, "0");
        try {
            return Math.max(0, Long.parseLong(value)) * 60_000L;
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Whether the app should lock now: it went to the background at {@code backgroundSince}
     * (elapsed-realtime ms, 0 = not in the background) and {@code delay} has passed since.
     */
    public static boolean isDue(final long backgroundSince, final long now, final long delay) {
        return delay > 0 && backgroundSince > 0 && now - backgroundSince >= delay;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TERMINATING =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean relaunchOnTerminate = false;

    /** How long a clean shutdown (disconnect, pending writes) may take before the hard stop. */
    private static final long TERMINATE_TIMEOUT = 5_000;

    /**
     * Locks the app. Does nothing without the startup password. With {@code relaunch} (the user
     * is looking at the app) it comes back up at the password prompt.
     */
    public static void lock(
            final Context context, final Collection<Activity> activities, final boolean relaunch) {
        final Context app = context.getApplicationContext();
        if (!isAvailable(app)) {
            return;
        }
        Log.d(Config.LOGTAG, "locking the app: wiping keys from memory");
        relaunchOnTerminate = relaunch;
        AppSettings.clearSessionPassword();
        MediaCrypto.reset();
        // notifications show senders and message content
        NotificationManagerCompat.from(app).cancelAll();
        try {
            com.bumptech.glide.Glide.get(app).clearMemory();
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "could not clear image memory cache", e);
        }
        for (final Activity activity : activities) {
            activity.finishAndRemoveTask();
        }
        // never wait for the network longer than this
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> terminate(app), TERMINATE_TIMEOUT);
        try {
            // go offline cleanly and finish pending writes, then the service terminates
            app.startService(
                    new Intent(app, XmppConnectionService.class)
                            .setAction(XmppConnectionService.ACTION_LOCK));
        } catch (final RuntimeException e) {
            // e.g. not allowed to start the service from the background
            terminate(app);
        }
    }

    /**
     * Deletes the files below {@code directory}. Only descends into folders that really lie
     * inside it: a link pointing elsewhere is removed itself, never what it points to. (No
     * java.nio.file: it does not exist before Android 8.)
     */
    private static void deleteTree(final java.io.File directory) {
        final java.io.File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        final String root;
        try {
            root = directory.getCanonicalPath() + java.io.File.separator;
        } catch (final java.io.IOException e) {
            return;
        }
        for (final java.io.File child : children) {
            if (child.isDirectory()) {
                try {
                    if (child.getCanonicalPath().startsWith(root)) {
                        deleteTree(child);
                    }
                } catch (final java.io.IOException e) {
                    Log.w(Config.LOGTAG, "could not resolve " + child, e);
                }
            }
            if (!child.delete()) {
                Log.w(Config.LOGTAG, "could not delete " + child);
            }
        }
    }

    /**
     * Ends the process (once): the only reliable way to drop all key material and decrypted
     * content from memory.
     */
    static void terminate(final Context context) {
        if (!TERMINATING.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        try {
            // recordings, transcoder output and decrypted copies: all of that work ends now
            eu.siacs.conversations.persistance.MediaFiles.wipeAllTemporary(app);
            // copies prepared for uploads (stories) that the lock interrupts
            eu.siacs.conversations.persistance.MediaFiles.wipeCacheTemporaries(app);
            // downloads in progress are written decrypted; remote images Glide keeps on disk
            deleteTree(new java.io.File(app.getCacheDir(), "partial"));
            // the system camera's plain captures: after a lock nothing can still receive them
            deleteTree(new java.io.File(app.getFilesDir(), "Camera"));
            deleteTree(
                    new java.io.File(
                            app.getCacheDir(),
                            com.bumptech.glide.load.engine.cache.DiskCache.Factory
                                    .DEFAULT_DISK_CACHE_DIR));
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "could not wipe temporary media", e);
        }
        if (relaunchOnTerminate) {
            final Intent launch =
                    app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch != null) {
                // started in a fresh process, which asks for the password
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                app.startActivity(launch);
            }
        }
        Process.killProcess(Process.myPid());
    }
}
