package de.monocles.chat;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.MediaCrypto;
import eu.siacs.conversations.services.XmppConnectionService;
import io.ipfs.cid.Cid;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class DownloadDefaultStickers extends Service {

    private static final int NOTIFICATION_ID = 20;

    /** Extras naming a Signal sticker pack (lower-case hex) to fetch from Signal's CDN. */
    public static final String EXTRA_SIGNAL_PACK_ID = "signal_pack_id";

    public static final String EXTRA_SIGNAL_PACK_KEY = "signal_pack_key";

    private static final String DEFAULT_STICKERS_INDEX = "https://stickers.cheogram.com/index.json";

    private static String lower(final String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
    }

    private DatabaseBackend mDatabaseBackend;
    private NotificationManager notificationManager;
    private File mStickerDir;
    private OkHttpClient http = null;

    /**
     * Index JSON {@link Uri}s (default packs) and {@link SignalPackJob}s. Its lock also guards
     * {@link #running} transitions and {@link #lastStartId}, so a pack queued while the worker
     * finishes is never lost.
     */
    private final HashSet<Object> pendingPacks = new HashSet<>();

    /** Whether this instance's worker thread runs. Per instance, like the queue it works on. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private int lastStartId;

    /**
     * Shared by the main thread (onStartCommand) and the worker: only touched while holding its
     * monitor, NotificationCompat.Builder is not thread-safe.
     */
    private NotificationCompat.Builder mBuilder;

    @Override
    public void onCreate() {
        mDatabaseBackend = DatabaseBackend.getInstance(getBaseContext());
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mStickerDir = stickerDir();
        mBuilder =
                new NotificationCompat.Builder(getBaseContext(), "backup")
                        .setContentTitle("Downloading Stickers")
                        .setSmallIcon(R.drawable.ic_archive_24dp)
                        .setProgress(1, 0, false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every caller uses startForegroundService(): be in the foreground before anything can
        // return, or Android kills the app for a start that never called startForeground().
        final Notification notification;
        synchronized (mBuilder) {
            notification = mBuilder.build();
        }
        startForeground(NOTIFICATION_ID, notification);
        if (http == null) {
            http =
                    HttpConnectionManager.newBuilder(
                                    intent == null
                                            ? getResources().getBoolean(R.bool.use_tor)
                                            : intent.getBooleanExtra(
                                                    "tor",
                                                    getResources().getBoolean(R.bool.use_tor)),
                                    intent == null
                                            ? getResources().getBoolean(R.bool.use_i2p)
                                            : intent.getBooleanExtra(
                                                    "i2p",
                                                    getResources().getBoolean(R.bool.use_i2p)))
                            .build();
        }
        synchronized (pendingPacks) {
            lastStartId = startId;
            // A null intent would be a system restart: never download anything unasked.
            if (intent != null) {
                final String signalId = lower(intent.getStringExtra(EXTRA_SIGNAL_PACK_ID));
                final String signalKey = lower(intent.getStringExtra(EXTRA_SIGNAL_PACK_KEY));
                if (signalId != null || signalKey != null) {
                    if (signalId != null
                            && signalKey != null
                            && SignalStickerPack.PACK_ID.matcher(signalId).matches()
                            && SignalStickerPack.PACK_KEY.matcher(signalKey).matches()) {
                        pendingPacks.add(new SignalPackJob(signalId, signalKey));
                    } else {
                        Log.d(Config.LOGTAG, "ignoring invalid Signal sticker pack");
                    }
                } else {
                    pendingPacks.add(Uri.parse(DEFAULT_STICKERS_INDEX));
                }
            }
            if (running.compareAndSet(false, true)) {
                // also with nothing queued: the worker then stops the service right away
                new Thread(this::work).start();
            } else {
                Log.d(Config.LOGTAG, "DownloadDefaultStickers: queued for the running download");
            }
        }
        // a pack the system killed us during is not resumed: the user can open the link again
        return START_NOT_STICKY;
    }

    private void work() {
        boolean finished = false;
        try {
            while (true) {
                final Object job;
                synchronized (pendingPacks) {
                    if (pendingPacks.isEmpty()) {
                        finishWork();
                        finished = true;
                        return;
                    }
                    job = pendingPacks.iterator().next();
                }
                try {
                    synchronized (mBuilder) {
                        mBuilder.setContentText(null).setProgress(1, 0, false);
                        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());
                    }
                    final int added;
                    if (job instanceof SignalPackJob) {
                        added = downloadSignalPack((SignalPackJob) job, mBuilder);
                        if (added == 0) notifyFailure();
                    } else {
                        added = downloadIndex((Uri) job, mBuilder);
                    }
                    // the picker reads the folder on resume; this lets emoji search find them now
                    // too
                    if (added > 0) XmppConnectionService.stickersChanged();
                } catch (final Exception e) {
                    Log.d(Config.LOGTAG, "unable to download sticker pack: " + e);
                    if (job instanceof SignalPackJob) notifyFailure();
                } finally {
                    synchronized (pendingPacks) {
                        pendingPacks.remove(job);
                    }
                }
            }
        } finally {
            // also after an Error: a stuck flag would block every later download. Only when this
            // thread did not finish normally - by now a new start may own the flag again.
            if (!finished) {
                synchronized (pendingPacks) {
                    finishWork();
                }
            }
        }
    }

    /** Stops the worker and the service. Caller holds the {@code pendingPacks} lock. */
    private void finishWork() {
        running.set(false);
        pendingPacks.clear();
        stopForeground(true);
        stopSelf(lastStartId);
    }

    /** Most bytes one sticker may have; Signal stickers are well below this. */
    private static final long MAX_STICKER_BYTES = 1024 * 1024;

    /** Most bytes of a pack's JSON description, and most stickers taken from one pack. */
    private static final long MAX_PACK_JSON_BYTES = 2 * 1024 * 1024;

    private static final int MAX_STICKERS_PER_PACK = 500;

    /**
     * A file or folder name from the pack JSON, made safe to use as one path component: no
     * separators, no control characters, no leading dots ("..", hidden files), bounded length. Null
     * if nothing usable is left. The JSON comes from a server and, through it, from the pack's
     * author - it must never choose where in our private storage a file is written.
     */
    public static String safePathComponent(final String raw) {
        if (raw == null) return null;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c < 0x20 || c == 0x7f) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String name = sb.toString().trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 64) name = name.substring(0, 64);
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    /** File extension for the image types a sticker may have, or null for anything else. */
    static String stickerExtension(final String contentType) {
        if (contentType == null) return null;
        final String type = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "image/png":
            case "image/apng":
                return "png";
            case "image/webp":
                return "webp";
            case "image/gif":
                return "gif";
            case "image/jpeg":
                return "jpg";
            default:
                return null;
        }
    }

    /** Whether {@code file} lies inside {@code dir}, after resolving links and "..". */
    public static boolean isInside(final File dir, final File file) throws java.io.IOException {
        final String base = dir.getCanonicalPath() + File.separator;
        return file.getCanonicalPath().startsWith(base);
    }

    private boolean oneSticker(final JSONObject sticker) throws Exception {
        final String pack = safePathComponent(sticker.optString("pack", null));
        final String name = safePathComponent(sticker.optString("name", null));
        final okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(sticker.optString("url", ""));
        if (pack == null || name == null || url == null || !url.isHttps()) {
            Log.d(Config.LOGTAG, "skipping sticker with unusable pack, name or url");
            return false;
        }
        final byte[] data;
        final String extension;
        try (Response r = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                return false;
            }
            extension = stickerExtension(r.header("content-type"));
            if (extension == null) {
                Log.d(Config.LOGTAG, "skipping sticker that is not an image");
                return false;
            }
            data = readLimited(r, MAX_STICKER_BYTES);
        }
        return store(
                pack, name, data, extension, url.toString(), sticker.optString("copyright", ""));
    }

    /** The body, or an IOException if it is larger than {@code max} bytes. */
    private static byte[] readLimited(final Response r, final long max) throws java.io.IOException {
        final byte[] data =
                ByteStreams.toByteArray(ByteStreams.limit(r.body().byteStream(), max + 1));
        if (data.length > max) {
            throw new java.io.IOException("response too large");
        }
        return data;
    }

    /**
     * Writes one sticker into {@code pack} and registers it. {@code pack} and {@code name} must
     * already have passed {@link #safePathComponent}.
     *
     * @return whether the sticker was stored
     */
    private boolean store(
            final String pack,
            final String name,
            final byte[] original,
            final String originalExtension,
            final String sourceUrl,
            final String copyrightText)
            throws Exception {
        byte[] data = original;
        String extension = originalExtension;
        // Animated PNG (all of Signal's animated stickers): Android only plays animated WebP
        final byte[] webp =
                "png".equals(extension)
                        ? eu.siacs.conversations.utils.ApngConverter.toAnimatedWebp(original)
                        : null;
        if (webp != null) {
            data = webp;
            extension = "webp";
        }
        final File packDir = new File(mStickerDir, pack);
        final File file = new File(packDir, name + "." + extension);
        if (!isInside(mStickerDir, file)) {
            Log.w(Config.LOGTAG, "refusing sticker outside of the sticker directory");
            return false;
        }
        packDir.mkdirs();
        // encrypted at rest and atomic (written to a .part file, renamed when complete)
        MediaCrypto.store(this, new java.io.ByteArrayInputStream(data), file);
        if (webp != null) {
            // the same pack imported before the conversion existed: drop its still copy
            final File still = new File(packDir, name + ".png");
            if (isInside(mStickerDir, still) && still.isFile() && !still.delete()) {
                Log.d(Config.LOGTAG, "could not remove the still copy of " + name);
            }
        }

        // The cids are what the sticker's bytes hash to - never ones a pack description claims,
        // which could bind this file to another sticker's (or any file's) content id.
        final Cid[] cids =
                eu.siacs.conversations.utils.CryptoHelper.cid(
                        new java.io.ByteArrayInputStream(data),
                        new String[] {"SHA-256", "SHA-1", "SHA-512"});
        for (final Cid cid : cids) {
            mDatabaseBackend.saveCid(cid, file, sourceUrl);
        }

        try {
            final File copyright = new File(packDir, "copyright.txt");
            if (!isInside(mStickerDir, copyright)) return true;
            // stored encrypted too, so it can't be appended to: read, add the line, rewrite
            final java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
            if (copyright.isFile()) {
                try (java.io.InputStream in = MediaCrypto.openInput(this, copyright)) {
                    ByteStreams.copy(in, text);
                }
            }
            final String line =
                    pack
                            + "/"
                            + name
                            + ": "
                            + (copyrightText == null ? "" : copyrightText)
                                    .replace('\n', ' ')
                                    .replace('\r', ' ')
                            + "\n";
            text.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MediaCrypto.store(this, new java.io.ByteArrayInputStream(text.toByteArray()), copyright);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not update sticker copyright notes", e);
        }
        return true;
    }

    /**
     * An empty folder marking which Signal pack a sticker folder was made for. A folder, not a
     * file: rescanStickers only lists files, so it never shows up as a sticker.
     */
    private static final String SIGNAL_PACK_MARKER_PREFIX = ".signal-";

    /**
     * The sticker folder for a Signal pack. The title is chosen by the pack's author: a pack must
     * never write into a folder of another pack (and replace its stickers by emoji name), so a
     * taken name gets a number. The same pack imported again keeps its folder.
     */
    static String chooseSignalPackDir(
            final File stickerDir, final String title, final String packId) {
        final String marker = SIGNAL_PACK_MARKER_PREFIX + packId;
        final String base = safePathComponent(title);
        if (base != null) {
            for (int n = 1; n <= 20; n++) {
                final String name = n == 1 ? base : base + " " + n;
                final File dir = new File(stickerDir, name);
                if (!dir.exists() || new File(dir, marker).isDirectory()) return name;
            }
        }
        return packId;
    }

    /** Most bytes of an encrypted Signal pack manifest. */
    private static final long MAX_SIGNAL_MANIFEST_BYTES = 256 * 1024;

    /** IV, CBC padding and MAC that encryption adds to each Signal file. */
    private static final long SIGNAL_OVERHEAD_BYTES = 16 + 16 + 32;

    /**
     * Signal's CDN needs no identification: don't tell it which app (and version) asks. Cookies are
     * not kept (no cookie jar) and no Referer is sent.
     */
    private static final String NEUTRAL_USER_AGENT = "Mozilla/5.0";

    private byte[] fetchSignal(final String url, final long max) throws java.io.IOException {
        final Request request =
                new Request.Builder().url(url).header("User-Agent", NEUTRAL_USER_AGENT).build();
        try (Response r = http.newCall(request).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                throw new java.io.IOException("HTTP " + r.code());
            }
            return readLimited(r, max);
        }
    }

    /**
     * Downloads a Signal pack from Signal's CDN and decrypts it here. The pack key is never sent
     * anywhere and never logged.
     *
     * @return how many stickers were added
     */
    private int downloadSignalPack(
            final SignalPackJob job, final NotificationCompat.Builder builder) throws Exception {
        final byte[] key = SignalStickerPack.hexToBytes(job.key);
        try {
            final SignalStickerPack.Manifest manifest =
                    SignalStickerPack.parseManifest(
                            SignalStickerPack.decrypt(
                                    fetchSignal(
                                            SignalStickerPack.manifestUrl(job.id),
                                            MAX_SIGNAL_MANIFEST_BYTES),
                                    key),
                            MAX_STICKERS_PER_PACK);
            final String pack = chooseSignalPackDir(mStickerDir, manifest.title, job.id);
            final File packDir = new File(mStickerDir, pack);
            final File marker = new File(packDir, SIGNAL_PACK_MARKER_PREFIX + job.id);
            if (!isInside(mStickerDir, marker)) {
                throw new java.io.IOException("pack folder outside of the sticker directory");
            }
            marker.mkdirs();
            synchronized (builder) {
                builder.setContentText(pack);
            }
            final Progress progress = new Progress(builder, 1, 0);
            final HashSet<String> names = new HashSet<>();
            final int total = manifest.stickers.size();
            int added = 0;
            for (int i = 0; i < total; i++) {
                final SignalStickerPack.Sticker sticker = manifest.stickers.get(i);
                final String url = SignalStickerPack.stickerUrl(job.id, sticker.id);
                try {
                    final byte[] image =
                            SignalStickerPack.decrypt(
                                    fetchSignal(url, MAX_STICKER_BYTES + SIGNAL_OVERHEAD_BYTES),
                                    key);
                    final String extension = SignalStickerPack.sniffExtension(image);
                    if (extension == null || image.length > MAX_STICKER_BYTES) {
                        Log.d(Config.LOGTAG, "skipping Signal sticker " + sticker.id);
                    } else {
                        // the file name is what the emoji search finds a sticker by
                        String name = safePathComponent(sticker.emoji);
                        if (name == null || !names.add(name)) {
                            name = (name == null ? "" : name + "_") + sticker.id;
                            names.add(name);
                        }
                        if (store(pack, name, image, extension, url, manifest.author)) added++;
                    }
                } catch (final Exception e) {
                    Log.d(Config.LOGTAG, "unable to add Signal sticker " + sticker.id + ": " + e);
                }
                notificationManager.notify(NOTIFICATION_ID, progress.build((i + 1) * 100 / total));
            }
            Log.d(
                    Config.LOGTAG,
                    "added " + added + "/" + total + " stickers of Signal pack " + job.id);
            return added;
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private void notifyFailure() {
        notificationManager.notify(
                NOTIFICATION_ID + 1,
                new NotificationCompat.Builder(getBaseContext(), "backup")
                        .setSmallIcon(R.drawable.ic_archive_24dp)
                        .setContentTitle(getString(R.string.sticker_pack_download_failed))
                        .setAutoCancel(true)
                        .build());
    }

    /**
     * @return how many stickers were added
     */
    private int downloadIndex(final Uri jsonUri, final NotificationCompat.Builder mBuilder)
            throws Exception {
        JSONArray stickers = new JSONArray();
        final okhttp3.HttpUrl packUrl = okhttp3.HttpUrl.parse(jsonUri.toString());
        if (packUrl != null && packUrl.isHttps()) {
            try (Response r = http.newCall(new Request.Builder().url(packUrl).build()).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    stickers =
                            new JSONArray(
                                    new String(
                                            readLimited(r, MAX_PACK_JSON_BYTES),
                                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }

        final Progress progress = new Progress(mBuilder, 1, 0);
        final int total = Math.min(stickers.length(), MAX_STICKERS_PER_PACK);
        int added = 0;
        for (int i = 0; i < total; i++) {
            try {
                if (oneSticker(stickers.getJSONObject(i))) added++;
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "unable to add sticker: " + e);
            }
            notificationManager.notify(NOTIFICATION_ID, progress.build((i + 1) * 100 / total));
        }
        return added;
    }

    /** A Signal pack to fetch; equal by id so a pack queued twice is downloaded once. */
    private static final class SignalPackJob {
        final String id;
        final String key;

        SignalPackJob(final String id, final String key) {
            this.id = id;
            this.key = key;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof SignalPackJob && ((SignalPackJob) o).id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "SignalPackJob{" + id + "}"; // never the key
        }
    }

    private File stickerDir() {
        /*
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        final String dir = p.getString("sticker_directory", "Stickers");
        if (dir.startsWith("content://")) {
        	Uri uri = Uri.parse(dir);
        	uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        	return new File(FileUtils.getPath(getBaseContext(), uri));
        } else {
        	return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/monocles chat" + "/" + dir);
        }
        */
        return StickersMigration.getStickersDir(getApplicationContext());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class Progress {
        private final NotificationCompat.Builder builder;
        private final int max;
        private final int count;

        private Progress(NotificationCompat.Builder builder, int max, int count) {
            this.builder = builder;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            synchronized (builder) {
                builder.setProgress(max * 100, count * 100 + percentage, false);
                return builder.build();
            }
        }
    }
}
