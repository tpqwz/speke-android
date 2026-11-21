package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.stream.JsonWriter;
import de.monocles.chat.pinnedmessage.PinnedMessage;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.AttachmentStorage;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.BackupKeys;
import eu.siacs.conversations.utils.CursorUtils;
import eu.siacs.conversations.xmpp.Jid;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class ExportBackupWorker extends Worker {

    /** Fewer binder/FUSE round trips than the 512-byte gzip default. */
    private static final int STREAM_BUFFER = 64 * 1024;

    /**
     * Standard gzip, only with the fastest level: attachments are already compressed media (as
     * base64), where the default level costs most of the export time for little gain. Every
     * importer reads it unchanged.
     */
    private static final class FastGzipOutputStream extends GZIPOutputStream {
        FastGzipOutputStream(final OutputStream out) throws IOException {
            super(out, STREAM_BUFFER);
            def.setLevel(java.util.zip.Deflater.BEST_SPEED);
        }
    }

    /** Per thread: SimpleDateFormat is not thread-safe and two backups may run at once. */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US));

    /**
     * One backup at a time: a one-off and a recurring backup are separate works and could
     * otherwise write the same {@code <account>.<minute>.ceb} and clean up each other's files.
     */
    private static final Object EXPORT_LOCK = new Object();

    /** A backup of this account was already written this minute; it is kept, not replaced. */
    private static final class BackupFileExistsException extends IOException {
        BackupFileExistsException(final String message) {
            super(message);
        }
    }

    private static final String KEY_TYPE = "AES";
    private static final String CIPHER_MODE = "AES/GCM/NoPadding";
    private static final String PROVIDER = "BC";

    public static final String MIME_TYPE = "application/vnd.conversations.backup";

    private static final String MESSAGE_STRING_FORMAT = "(%s) %s: %s\n";

    private static final int NOTIFICATION_ID = 19;
    private static final int BACKUP_CREATED_NOTIFICATION_ID = 23;

    private static final int PENDING_INTENT_FLAGS =
            s()
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;

    private final boolean recurringBackup;

    private long lastNotificationUpdate = 0;
    boolean ReadableLogsEnabled = false;
    private DatabaseBackend mDatabaseBackend;
    private List<Account> mAccounts;

    public ExportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        this.recurringBackup = inputData.getBoolean("recurring_backup", false);
    }

    @NonNull
    @Override
    public Result doWork() {
        setForegroundAsync(getForegroundInfo());
        final List<Uri> files;
        try {
            synchronized (EXPORT_LOCK) {
                files = export();
            }
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "could not create backup", e);
            showToast(R.string.could_not_create_backup);
            return Result.failure();
        } finally {
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "done creating " + files.size() + " backup files");
        if (files.isEmpty() || recurringBackup) {
            return Result.success();
        }
        notifySuccess(files);
        return Result.success();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        Log.d(Config.LOGTAG, "getForegroundInfo()");
        final NotificationCompat.Builder notification = getNotification();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID,
                    notification.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification.build());
        }
    }

    private List<Uri> export() throws Exception {
        final Context context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final var backupLocation = appSettings.getBackupLocation();
        final var database = DatabaseBackend.getInstance(context);
        final var accounts = database.getAccounts();
        // with a recovery key every backup is encrypted with it, not with the account password
        final byte[] recoveryKey = database.getBackupRecoveryKey();

        int currentCount = 0;
        final int max = accounts.size();
        final ImmutableList.Builder<Uri> locations = new ImmutableList.Builder<>();
        Log.d(Config.LOGTAG, "starting backup for " + max + " accounts");
        for (final Account account : accounts) {
            if (isStopped()) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            }
            final String password = account.getPassword();
            if (recoveryKey == null && Strings.nullToEmpty(password).trim().isEmpty()) {
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "skipping backup for %s because password is empty. unable to"
                                        + " encrypt",
                                account.getJid().asBareJid()));
                currentCount++;
                continue;
            }
            final Uri uri;
            try {
                uri =
                        export(
                                database,
                                account,
                                password,
                                recoveryKey,
                                backupLocation,
                                max,
                                currentCount);
            } catch (final WorkStoppedException e) {
                Log.d(Config.LOGTAG, "ExportBackupWorker has stopped. Returning what we have");
                return locations.build();
            } catch (final BackupFileExistsException e) {
                // two backups within one minute: the earlier one stays, the others go on
                Log.w(Config.LOGTAG, "keeping the existing backup of " + account.getJid(), e);
                currentCount++;
                continue;
            }
            locations.add(uri);
            currentCount++;
        }
        exportPlainTextIfEnabled(backupLocation);
        return locations.build();
    }

    private Uri export(
            final DatabaseBackend database,
            final Account account,
            final String password,
            final byte[] recoveryKey,
            final Uri backupLocation,
            final int max,
            final int count)
            throws Exception {
        final var context = getApplicationContext();
        final SecureRandom secureRandom = new SecureRandom();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "exporting data for account %s (%s)",
                        account.getJid().asBareJid(), account.getUuid()));
        final byte[] IV = new byte[12];
        final byte[] salt = new byte[16];
        secureRandom.nextBytes(IV);
        secureRandom.nextBytes(salt);
        final BackupFileHeader backupFileHeader =
                new BackupFileHeader(
                        recoveryKey != null
                                ? BackupFileHeader.RECOVERY_KEY_VERSION
                                : BackupFileHeader.VERSION,
                        context.getString(R.string.app_name),
                        account.getJid(),
                        System.currentTimeMillis(),
                        IV,
                        salt);
        final var notification = getNotification();
        final var cancelPendingIntent =
                WorkManager.getInstance(context).createCancelPendingIntent(getId());
        notification.addAction(
                new NotificationCompat.Action.Builder(
                                R.drawable.ic_cancel_24dp,
                                context.getString(R.string.cancel),
                                cancelPendingIntent)
                        .build());
        final Progress progress = new Progress(notification, max, count);
        final com.google.common.base.Stopwatch exportTime =
                com.google.common.base.Stopwatch.createStarted();
        final String filename =
                String.format(
                        "%s.%s.ceb",
                        account.getJid().asBareJid().toString(),
                        DATE_FORMAT.get().format(new Date()));
        final Uri location;
        if ("file".equalsIgnoreCase(backupLocation.getScheme())) {
            final File file = new File(backupLocation.getPath(), filename);
            final File directory = file.getParentFile();
            if (directory != null && directory.mkdirs()) {
                Log.d(Config.LOGTAG, "created backup directory " + directory.getAbsolutePath());
            }
            // never truncate an existing backup: if this one failed, it would be deleted too
            if (!file.createNewFile()) {
                throw new BackupFileExistsException(file + " already exists");
            }
            location = Uri.fromFile(file);
        } else {
            final var tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                throw new IOException(
                        String.format(
                                "DocumentFile.fromTreeUri returned null for %s", backupLocation));
            }
            final var file = tree.createFile(MIME_TYPE, filename);
            if (file == null) {
                throw new IOException(
                        String.format("Could not create %s in %s", filename, backupLocation));
            }
            location = file.getUri();
        }

        try (final OutputStream outputStream =
                context.getContentResolver().openOutputStream(location)) {
            final DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
            backupFileHeader.write(dataOutputStream);
            dataOutputStream.flush();

            final AEADBlockCipher aeadBlockCipher =
                    GCMBlockCipher.newInstance(AESEngine.newInstance());
            final byte[] key =
                    recoveryKey != null
                            ? BackupKeys.fileKey(
                                    recoveryKey, salt, account.getJid().asBareJid().toString())
                            : getKey(password, salt);
            aeadBlockCipher.init(true, new AEADParameters(new KeyParameter(key), 128, IV));
            final org.bouncycastle.crypto.io.CipherOutputStream cipherOutputStream =
                    new org.bouncycastle.crypto.io.CipherOutputStream(
                            new BufferedOutputStream(outputStream, STREAM_BUFFER),
                            aeadBlockCipher);
            final GZIPOutputStream gzipOutputStream = new FastGzipOutputStream(cipherOutputStream);
            try (final JsonWriter jsonWriter =
                    new JsonWriter(
                            new OutputStreamWriter(gzipOutputStream, StandardCharsets.UTF_8))) {
                jsonWriter.beginArray();
                database.ensureOmemo2PqTablesExist();
                final SQLiteDatabase db = database.getReadableDatabase();
                final String uuid = account.getUuid();
                accountExport(db, uuid, jsonWriter);
                simpleExport(db, Conversation.TABLENAME, Conversation.ACCOUNT, uuid, jsonWriter);
                fileExport(db, uuid, jsonWriter, progress);
                messageExport(db, uuid, jsonWriter, progress);
                webxdcExport(db, uuid, jsonWriter, progress);
                pinnedMessageExport(db, uuid, jsonWriter);
                mutedParticipantsExport(db, uuid, jsonWriter);
                for (final String table :
                        Arrays.asList(
                                // PQ OMEMO2 tables (org.signal.libsignal)
                                SQLiteAxolotlStore.PREKEY_TABLENAME, // omemo2_prekeys
                                SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, // omemo2_signed_prekeys
                                SQLiteAxolotlStore.SESSION_TABLENAME, // omemo2_sessions
                                SQLiteAxolotlStore.KYBER_PREKEY_TABLENAME, // kyber_prekeys (PQXDH)
                                SQLiteAxolotlStore
                                        .KYBER_LAST_RESORT_SESSIONS_TABLENAME, // kyber_last_resort_sessions
                                SQLiteAxolotlStore.IDENTITIES_TABLENAME, // shared trust
                                // Our ML-DSA-87 key pair + peers' pinned pq_ik. Without it a
                                // restored OMEMO2 identity comes back with a new PQ half and
                                // peers who pinned the old one refuse the session.
                                DatabaseBackend.OMEMO2_PQ_IDENTITIES_TABLE,
                                // Original/legacy OMEMO tables (org.whispersystems)
                                "sessions",
                                "prekeys",
                                "signed_prekeys")) {
                    simpleExport(db, table, SQLiteAxolotlStore.ACCOUNT, uuid, jsonWriter);
                }
                jsonWriter.endArray();
                jsonWriter.flush();
            }
        } catch (Exception e) {
            deleteFile(context, location);
            throw e;
        }
        if ("file".equalsIgnoreCase(location.getScheme())) {
            mediaScannerScanFile(new File(location.getPath()));
        }
        Log.d(Config.LOGTAG, "written backup to " + location + " in " + exportTime.stop());

        if (getFileSize(context, location) > 80) {
            cleanup(backupLocation, account.getJid());
        } else {
            Log.w(Config.LOGTAG, "Backup file " + location + " is too small. Skipping cleanup.");
            showToast(R.string.could_not_create_backup);
        }

        return location;
    }

    /**
     * Writes the optional plain-text logs once per backup run (not once per account). Each
     * account gets a new {@code <account>.<date>.plaintext} folder in the backup location, which
     * goes through SAF for a picked folder like the .ceb files. Older exports are never
     * overwritten: see {@link PlainTextExport}. A failure here never fails the encrypted backup.
     */
    private void exportPlainTextIfEnabled(final Uri backupLocation) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        ReadableLogsEnabled =
                preferences.getBoolean(
                        "export_plain_text_logs",
                        getApplicationContext().getResources().getBoolean(R.bool.plain_text_logs));
        if (!ReadableLogsEnabled || isStopped()) {
            return;
        }
        try {
            mDatabaseBackend = DatabaseBackend.getInstance(Conversations.getContext());
            mAccounts = mDatabaseBackend.getAccounts();
            final List<Conversation> conversations =
                    mDatabaseBackend.getConversations(Conversation.STATUS_AVAILABLE);
            conversations.addAll(mDatabaseBackend.getConversations(Conversation.STATUS_ARCHIVED));
            final Date date = new Date();
            for (final Account account : mAccounts) {
                if (isStopped()) {
                    return;
                }
                final List<Conversation> ofAccount = new ArrayList<>();
                for (final Conversation conversation : conversations) {
                    if (account.getUuid().equals(conversation.getAccountUuid())) {
                        ofAccount.add(conversation);
                    }
                }
                exportPlainText(backupLocation, account.getJid(), ofAccount, date);
            }
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "could not export plain text logs", e);
        }
    }

    private void exportPlainText(
            final Uri backupLocation,
            final Jid accountJid,
            final List<Conversation> conversations,
            final Date date) {
        // see PlainTextExport.LOCK: a concurrent run must never see this run's partial folder
        synchronized (PlainTextExport.LOCK) {
            final PlainTextExport export =
                    new PlainTextExport(
                            getApplicationContext(),
                            backupLocation,
                            accountJid.asBareJid().toString(),
                            date);
            try {
                export.begin();
            } catch (final IOException | RuntimeException e) {
                Log.w(Config.LOGTAG, "could not start plain text export for " + accountJid, e);
                export.abort();
                return;
            }
            for (final Conversation conversation : conversations) {
                Log.d(Config.LOGTAG, "Exporting readable logs for " + conversation.getJid());
                if (isStopped() || !writeToFile(conversation, accountJid, export)) {
                    // keep the previous exports exactly as they were
                    export.abort();
                    return;
                }
            }
            if (export.commit()) {
                Log.d(Config.LOGTAG, "finished plain text export for " + accountJid);
            }
        }
    }

    private void showToast(final int resId) {
        new Handler(Looper.getMainLooper())
                .post(() ->
                        Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show());
    }

    private long getFileSize(Context context, Uri uri) {
        final String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            final String path = uri.getPath();
            return path == null ? 0 : new File(path).length();
        } else {
            final DocumentFile file = DocumentFile.fromSingleUri(context, uri);
            return file == null ? 0 : file.length();
        }
    }

    /**
     * Exact match of {@code <account>.<yyyy-MM-dd-HH-mm>.ceb}: a prefix check would also take the
     * backups of {@code kim@example.org.uk} for those of {@code kim@example.org} and delete them.
     */
    static boolean isBackupFileOf(final String name, final String account) {
        return name != null
                && Pattern.matches(
                        Pattern.quote(account) + "\\.\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.ceb", name);
    }

    private void cleanup(Uri backupLocation, Jid jid) {
        final Context context = getApplicationContext();
        final String account = jid.asBareJid().toString();
        final String scheme = backupLocation.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            final String path = backupLocation.getPath();
            if (path == null) {
                return;
            }
            final File directory = new File(path);
            final File[] files =
                    directory.listFiles(
                            (dir, name) -> isBackupFileOf(name, account));
            if (files != null && files.length > 3) {
                Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                for (int i = 0; i < files.length - 3; i++) {
                    if (files[i].delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + files[i].getName());
                    }
                }
            }
        } else {
            final DocumentFile tree = DocumentFile.fromTreeUri(context, backupLocation);
            if (tree == null) {
                return;
            }
            final DocumentFile[] files = tree.listFiles();
            final List<DocumentFile> backups = new ArrayList<>();
            for (DocumentFile file : files) {
                final String name = file.getName();
                if (isBackupFileOf(name, account)) {
                    backups.add(file);
                }
            }
            if (backups.size() > 3) {
                Collections.sort(
                        backups,
                        (f1, f2) -> {
                            String n1 = f1.getName();
                            String n2 = f2.getName();
                            if (n1 == null) return -1;
                            if (n2 == null) return 1;
                            return n1.compareTo(n2);
                        });
                for (int i = 0; i < backups.size() - 3; i++) {
                    final DocumentFile fileToDelete = backups.get(i);
                    if (fileToDelete.delete()) {
                        Log.d(Config.LOGTAG, "deleted old backup " + fileToDelete.getName());
                    }
                }
            }
        }
    }

    private NotificationCompat.Builder getNotification() {
        final var context = getApplicationContext();
        final NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, "backup");
        notification
                .setContentTitle(context.getString(R.string.notification_create_backup_title))
                .setSmallIcon(R.drawable.ic_archive_24dp)
                .setProgress(1, 0, false);
        notification.setOngoing(true);
        notification.setLocalOnly(true);
        return notification;
    }

    private void throwIfWorkStopped() throws WorkStoppedException {
        if (isStopped()) {
            throw new WorkStoppedException();
        }
    }

    private void deleteFile(Context context, Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            final var file = new File(uri.getPath());
            if (file.delete()) {
                Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath());
            }
        } else {
            final var documentFile = DocumentFile.fromSingleUri(context, uri);
            if (documentFile != null && documentFile.delete()) {
                Log.d(Config.LOGTAG, "deleted " + uri);
            }
        }
    }

    private void mediaScannerScanFile(final File file) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(Uri.fromFile(file));
        getApplicationContext().sendBroadcast(intent);
    }

    private void webxdcExport(
            final SQLiteDatabase db,
            final String uuid,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);

        try (final Cursor cursor =
                db.rawQuery(
                        "select webxdc_updates.* from "
                                + Conversation.TABLENAME
                                + " join webxdc_updates webxdc_updates on "
                                + Conversation.TABLENAME
                                + ".uuid=webxdc_updates."
                                + Message.CONVERSATION
                                + " where conversations.accountUuid=?"
                                // the restore renumbers serial, so keep the original order
                                + " order by webxdc_updates.serial",
                        new String[] {uuid})) {
            int size = cursor != null ? cursor.getCount() : 0;
            Log.d(Config.LOGTAG, "exporting " + size + " WebXDC updates for account " + uuid);
            int i = 0;
            int p = Integer.MIN_VALUE;
            while (cursor != null && cursor.moveToNext()) {
                throwIfWorkStopped();
                writer.beginObject();
                writer.name("table");
                writer.value("webxdc_updates");
                writer.name("values");
                writer.beginObject();
                for (int j = 0; j < cursor.getColumnCount(); ++j) {
                    final String name = cursor.getColumnName(j);
                    writer.name(name);
                    writer.value(columnValue(cursor, j, "webxdc_updates"));
                }
                writer.endObject();
                writer.endObject();
                final int percentage = i * 100 / (size == 0 ? 1 : size);
                if (p < percentage
                        && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                    p = percentage;
                    lastNotificationUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
            }
        }
    }

    private static void accountExport(
            final SQLiteDatabase db, final String uuid, final JsonWriter writer)
            throws IOException {
        try (final Cursor accountCursor =
                db.query(
                        Account.TABLENAME,
                        null,
                        Account.UUID + "=?",
                        new String[] {uuid},
                        null,
                        null,
                        null)) {
            while (accountCursor != null && accountCursor.moveToNext()) {
                writer.beginObject();
                writer.name("table");
                writer.value(Account.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int i = 0; i < accountCursor.getColumnCount(); ++i) {
                    final String name = accountCursor.getColumnName(i);
                    writer.name(name);
                    final String value = accountCursor.getString(i);
                    if (value == null
                            || Account.ROSTERVERSION.equals(accountCursor.getColumnName(i))) {
                        writer.nullValue();
                    } else if (Account.OPTIONS.equals(accountCursor.getColumnName(i))
                            && value.matches("\\d+")) {
                        int intValue = Integer.parseInt(value);
                        intValue |= 1 << Account.OPTION_DISABLED;
                        writer.value(intValue);
                    } else {
                        writer.value(value);
                    }
                }
                writer.endObject();
                writer.endObject();
            }
        }
    }

    private void simpleExport(
            final SQLiteDatabase db,
            final String table,
            final String column,
            final String uuid,
            final JsonWriter writer)
            throws IOException, WorkStoppedException {
        final String selection = column != null ? column + "=?" : null;
        final String[] selectionArgs = uuid != null ? new String[] {uuid} : null;
        try (final Cursor cursor =
                db.query(table, null, selection, selectionArgs, null, null, null)) {
            writeRows(cursor, table, writer);
        }
    }

    /**
     * The muted occupants of this account's group chats only. The table has no account column,
     * so exporting it as a whole would put every other account's entries into this backup.
     */
    private void mutedParticipantsExport(
            final SQLiteDatabase db, final String accountUuid, final JsonWriter writer)
            throws IOException, WorkStoppedException {
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT m.* FROM muted_participants m WHERE m.muc_jid IN (SELECT "
                                + Conversation.CONTACTJID
                                + " FROM "
                                + Conversation.TABLENAME
                                + " WHERE "
                                + Conversation.ACCOUNT
                                + "=?) OR m.muc_jid IN (SELECT substr("
                                + Conversation.CONTACTJID
                                + ", 1, instr("
                                + Conversation.CONTACTJID
                                + ", '/') - 1) FROM "
                                + Conversation.TABLENAME
                                + " WHERE "
                                + Conversation.ACCOUNT
                                + "=? AND instr("
                                + Conversation.CONTACTJID
                                + ", '/') > 0)",
                        new String[] {accountUuid, accountUuid})) {
            writeRows(cursor, "muted_participants", writer);
        }
    }

    /**
     * The account's pins, except those of disappearing messages: a pin holds a copy of the text,
     * and a disappearing message's content never goes into a backup (see {@link
     * #backupColumnValue}).
     */
    private void pinnedMessageExport(
            final SQLiteDatabase db, final String accountUuid, final JsonWriter writer)
            throws IOException, WorkStoppedException {
        try (final Cursor cursor =
                db.rawQuery(
                        "SELECT p.* FROM "
                                + PinnedMessage.TABLENAME
                                + " p WHERE p."
                                + PinnedMessage.ACCOUNT_UUID
                                + "=? AND NOT EXISTS (SELECT 1 FROM "
                                + Message.TABLENAME
                                + " m WHERE m."
                                + Message.UUID
                                + "=p."
                                + PinnedMessage.MESSAGE_UUID
                                + " AND m."
                                + Message.EPHEMERAL_TIMER
                                + " > 0)",
                        new String[] {accountUuid})) {
            writeRows(cursor, PinnedMessage.TABLENAME, writer);
        }
    }

    private final Set<String> loggedBlobColumns = new HashSet<>();

    /**
     * A column value as written into the backup. {@code getString} on a BLOB throws and would
     * fail the whole backup for one value (e.g. an identity's cached X.509 certificate), so
     * BLOBs are left out as null.
     */
    private String columnValue(final Cursor cursor, final int column, final String table) {
        final String value =
                exportableColumnValue(cursor.getType(column), () -> cursor.getString(column));
        if (value == null && cursor.getType(column) == Cursor.FIELD_TYPE_BLOB) {
            final String key = table + "." + cursor.getColumnName(column);
            if (loggedBlobColumns.add(key)) {
                Log.w(Config.LOGTAG, "leaving binary column " + key + " out of the backup");
            }
        }
        return value;
    }

    static String exportableColumnValue(
            final int type, final java.util.function.Supplier<String> value) {
        return type == Cursor.FIELD_TYPE_BLOB ? null : value.get();
    }

    private void writeRows(final Cursor cursor, final String table, final JsonWriter writer)
            throws IOException, WorkStoppedException {
        while (cursor != null && cursor.moveToNext()) {
            throwIfWorkStopped();
            writer.beginObject();
            writer.name("table");
            writer.value(table);
            writer.name("values");
            writer.beginObject();
            for (int i = 0; i < cursor.getColumnCount(); ++i) {
                final String name = cursor.getColumnName(i);
                writer.name(name);
                writer.value(columnValue(cursor, i, table));
            }
            writer.endObject();
            writer.endObject();
        }
    }

    private void messageExport(
            final SQLiteDatabase db,
            final String uuid,
            final JsonWriter writer,
            final Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        try (final Cursor cursor =
                db.rawQuery(
                        "select messages.* from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid where"
                                + " conversations.accountUuid=?",
                        new String[] {uuid})) {
            // a single large message row must not fail the whole backup at the 2 MB default
            CursorUtils.upgradeCursorWindowSize(cursor);
            final int size = cursor != null ? cursor.getCount() : 0;
            Log.d(Config.LOGTAG, "exporting " + size + " messages for account " + uuid);
            int i = 0;
            int p = Integer.MIN_VALUE;
            final int timerColumn =
                    cursor == null ? -1 : cursor.getColumnIndex(Message.EPHEMERAL_TIMER);
            while (cursor != null && cursor.moveToNext()) {
                throwIfWorkStopped();
                final boolean disappearing = timerColumn >= 0 && cursor.getInt(timerColumn) > 0;
                writer.beginObject();
                writer.name("table");
                writer.value(Message.TABLENAME);
                writer.name("values");
                writer.beginObject();
                for (int j = 0; j < cursor.getColumnCount(); ++j) {
                    final String name = cursor.getColumnName(j);
                    writer.name(name);
                    String value =
                            backupColumnValue(
                                    name, columnValue(cursor, j, Message.TABLENAME), disappearing);
                    if (Message.RELATIVE_FILE_PATH.equals(name)) {
                        value = toPortablePath(value);
                    }
                    writer.value(value);
                }
                writer.endObject();
                writer.endObject();
                final int percentage = i * 100 / (size == 0 ? 1 : size);
                if (p < percentage
                        && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                    p = percentage;
                    lastNotificationUpdate = SystemClock.elapsedRealtime();
                    notificationManager.notify(NOTIFICATION_ID, progress.build(p));
                }
                i++;
            }
        }
    }

    /**
     * What a backup stores for one column of a message row. A disappearing message never goes into
     * a backup with its content: the backup file outlives the message on the device, and a restore
     * would bring it back. It is written as a content-free deleted row instead, which keeps its ids
     * (so after a restore the server archive cannot deliver it again as a new message) and its
     * {@code expire_at} (so it shows as expired). Its files are left out too (see {@link
     * #getFilesForAccount}).
     */
    static String backupColumnValue(
            final String column, final String value, final boolean disappearing) {
        if (!disappearing) {
            return value;
        }
        switch (column) {
            case Message.BODY:
            case Message.SUBJECT:
            case Message.PAYLOADS:
            case Message.FILE_PARAMS:
            case Message.RELATIVE_FILE_PATH:
            case Message.REACTIONS:
            case Message.BODY_LANGUAGE:
                return null;
            case Message.DELETED:
            case "file_deleted":
                return "1";
            case Message.ENCRYPTION:
                return String.valueOf(Message.ENCRYPTION_NONE);
            default:
                return value;
        }
    }

    public static byte[] getKey(final String password, final byte[] salt) {
        Argon2Parameters params =
                new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withIterations(3)
                        .withMemoryAsKB(65536) // 64MB
                        .withParallelism(4)
                        .withSalt(salt)
                        .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] result = new byte[32]; // 256-bit key for AES-256
        gen.generateBytes(password.toCharArray(), result);
        return result;
    }

    public static byte[] getLegacyKey(final String password, final byte[] salt)
            throws InvalidKeySpecException {
        final SecretKeyFactory factory;
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, 1024, 128))
                .getEncoded();
    }

    private void notifySuccess(final List<Uri> locations) {
        final var context = getApplicationContext();
        final var appSettings = new AppSettings(context);
        final String path = appSettings.getBackupLocationAsPath();
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final ArrayList<Uri> uris = new ArrayList<>();
        for (final Uri uri : locations) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                uris.add(
                        FileBackend.getUriForFile(
                                context,
                                new File(uri.getPath()),
                                new File(uri.getPath()).getName()));
            } else {
                uris.add(uri);
            }
        }
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType(MIME_TYPE);
        final Intent chooser =
                Intent.createChooser(intent, context.getString(R.string.share_backup_files));
        final var shareFilesIntent =
                PendingIntent.getActivity(context, 190, chooser, PENDING_INTENT_FLAGS);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_backup_created_title))
                .setContentText(
                        context.getString(R.string.notification_backup_created_subtitle, path))
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(
                                        context.getString(
                                                R.string.notification_backup_created_subtitle,
                                                path)))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_archive_24dp);

        builder.addAction(
                R.drawable.ic_share_24dp,
                context.getString(R.string.share_backup_files),
                shareFilesIntent);
        builder.setLocalOnly(true);
        final var notificationManager = context.getSystemService(NotificationManager.class);
        notificationManager.notify(BACKUP_CREATED_NOTIFICATION_ID, builder.build());
    }

    private static class Progress {
        private final NotificationCompat.Builder notification;
        private final int max;
        private final int count;

        private Progress(
                final NotificationCompat.Builder notification, final int max, final int count) {
            this.notification = notification;
            this.max = max;
            this.count = count;
        }

        private Notification build(int percentage) {
            notification.setProgress(max * 100, count * 100 + percentage, false);
            return notification.build();
        }
    }

    private static class WorkStoppedException extends Exception {}

    /** Writes one chat into {@code export}. False if it could not be written completely. */
    private boolean writeToFile(
            final Conversation conversation, final Jid accountJid, final PlainTextExport export) {
        final Jid contactJid = conversation.getJid();

        BufferedWriter bw = null;
        try {
            // closed on every way out of this method (done, stopped, write error)
            try (final DatabaseBackend.MessageCursor messages =
                    mDatabaseBackend.getMessagesIterable(conversation)) {
                for (final Message message : messages) {
                    if (isStopped()) return false;
                    final String line;
                    try {
                        line = plainTextLine(message, accountJid);
                    } catch (final RuntimeException e) {
                        // one malformed message must not cost the rest of the chat
                        Log.w(Config.LOGTAG, "skipping malformed message in " + contactJid, e);
                        continue;
                    }
                    if (line == null) {
                        continue;
                    }
                    if (bw == null) {
                        bw =
                                new BufferedWriter(
                                        new OutputStreamWriter(
                                                export.open(contactJid.asBareJid().toString()),
                                                StandardCharsets.UTF_8));
                    }
                    bw.write(line); // an output error aborts the run (IOException below)
                }
                if (messages.skippedCount() > 0) {
                    Log.w(
                            Config.LOGTAG,
                            "left out "
                                    + messages.skippedCount()
                                    + " unreadable messages of "
                                    + contactJid);
                }
            }
        } catch (final IOException | RuntimeException e) {
            Log.w(Config.LOGTAG, "could not export plain text log for " + contactJid, e);
            return false;
        } finally {
            try {
                if (bw != null) {
                    bw.close();
                }
            } catch (final IOException e) {
                // a provider may only report a failed write when the file is closed
                Log.w(Config.LOGTAG, "could not close plain text log for " + contactJid, e);
                return false;
            }
        }
        return true;
    }

    /** The plain-text line of {@code message}, or null if it is not exported. */
    private String plainTextLine(final Message message, final Jid accountJid) {
        // Disappearing messages never leave the app in a backup (see backupColumnValue),
        // least of all in plain text; a file link may also carry its key.
        if (message == null || message.isEphemeral()) {
            return null;
        }
        if (message.getType() != Message.TYPE_TEXT && !message.hasFileOnRemoteHost()) {
            return null;
        }
        final String jid =
                switch (message.getStatus()) {
                    case Message.STATUS_RECEIVED -> getMessageCounterpart(message);
                    case Message.STATUS_SEND,
                            Message.STATUS_SEND_RECEIVED,
                            Message.STATUS_SEND_DISPLAYED,
                            Message.STATUS_SEND_FAILED -> accountJid.asBareJid().toString();
                    default -> null;
                };
        if (jid == null) {
            return null;
        }
        final String body =
                message.hasFileOnRemoteHost()
                        ? message.getFileParams().url.toString()
                        : message.getBody();
        return String.format(
                MESSAGE_STRING_FORMAT,
                DATE_FORMAT.get().format(new Date(message.getTimeSent())),
                jid,
                body.replace("\\\n", "\\ \n").replace("\n", "\\ \n"));
    }

    private String getMessageCounterpart(Message message) {
        String trueCounterpart = (String) message.getContentValues().get(Message.TRUE_COUNTERPART);
        if (trueCounterpart != null) {
            return trueCounterpart;
        } else {
            return message.getCounterpart().toString();
        }
    }

    private String toPortablePath(String path) {
        if (path == null) return null;
        final String cacheDir = getApplicationContext().getCacheDir().getAbsolutePath();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        final String externalDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .getAbsolutePath();

        if (path.startsWith(cacheDir)) {
            return "${CACHE}" + path.substring(cacheDir.length());
        } else if (path.startsWith(filesDir)) {
            return "${FILES}" + path.substring(filesDir.length());
        } else if (path.startsWith(externalDir)) {
            return "${EXTERNAL}" + path.substring(externalDir.length());
        } else if (path.startsWith(documentsDir)) {
            return "${DOCUMENTS}" + path.substring(documentsDir.length());
        }
        return path;
    }

    private Set<File> getFilesForAccount(SQLiteDatabase db, String accountUuid) {
        Set<File> files = new HashSet<>();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        // Message attachments
        try (Cursor cursor =
                db.rawQuery(
                        "select "
                                + Message.RELATIVE_FILE_PATH
                                + " from "
                                + Message.TABLENAME
                                + " join "
                                + Conversation.TABLENAME
                                + " on "
                                + Conversation.TABLENAME
                                + ".uuid="
                                + Message.TABLENAME
                                + "."
                                + Message.CONVERSATION
                                + " where "
                                + Conversation.TABLENAME
                                + "."
                                + Conversation.ACCOUNT
                                + "=? and "
                                + Message.RELATIVE_FILE_PATH
                                + " is not null"
                                // not the files of disappearing messages (see backupColumnValue);
                                // a file a normal message shares is exported through that one
                                + " and ("
                                + Message.EPHEMERAL_TIMER
                                + " is null or "
                                + Message.EPHEMERAL_TIMER
                                + " = 0)",
                        new String[] {accountUuid})) {
            while (cursor != null && cursor.moveToNext()) {
                String path = cursor.getString(0);
                if (path != null && path.startsWith(filesDir)) {
                    files.add(new File(path));
                }
            }
        }

        // Avatars are left out on purpose: they are public server data (XEP-0084 / vCard) and are
        // fetched again after the restore, and a restored avatar file would be trusted by its
        // name alone (FileBackend.isAvatarCached only checks that it exists).

        return files;
    }

    private void fileExport(SQLiteDatabase db, String uuid, JsonWriter writer, Progress progress)
            throws IOException, WorkStoppedException {
        final var notificationManager =
                getApplicationContext().getSystemService(NotificationManager.class);
        Set<File> files = getFilesForAccount(db, uuid);
        Log.d(Config.LOGTAG, "exporting " + files.size() + " files for account " + uuid);
        int i = 0;
        int p = Integer.MIN_VALUE;
        int skipped = 0;
        for (File file : files) {
            throwIfWorkStopped();
            if (file.exists() && file.canRead()) {
                try {
                    exportFile(file, writer);
                } catch (final SourceFileUnreadableException e) {
                    // only this file is left out; an error writing the backup itself is not
                    // caught here and fails the backup instead of corrupting it
                    skipped++;
                    Log.w(Config.LOGTAG, "skipping unreadable file " + file.getAbsolutePath(), e);
                }
            }
            i++;
            final int percentage = i * 100 / Math.max(1, files.size());
            if (p < percentage
                    && (SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                p = percentage;
                lastNotificationUpdate = SystemClock.elapsedRealtime();
                notificationManager.notify(NOTIFICATION_ID, progress.build(p));
            }
        }
        if (skipped > 0) {
            Log.w(Config.LOGTAG, "left " + skipped + " unreadable files out of the backup");
        }
    }

    /** Reading an attachment failed; unlike other IOExceptions this is not a backup failure. */
    private static final class SourceFileUnreadableException extends IOException {
        SourceFileUnreadableException(final IOException cause) {
            super(cause);
        }
    }

    /**
     * Writes {@code file} in 1 MB chunks. If reading fails after chunks were written already, a
     * {@code truncated} marker follows so that the restore deletes the partial file instead of
     * keeping a silently cut-off attachment.
     */
    private void exportFile(File file, JsonWriter writer) throws IOException {
        final String portablePath = toPortablePath(file.getAbsolutePath());
        final byte[] buffer = new byte[1024 * 1024]; // 1MB chunks
        final InputStream is;
        try {
            // plaintext through the storage layer (the seam for encryption at rest)
            is = AttachmentStorage.forDevice(getApplicationContext()).openForBackup(file);
        } catch (final IOException e) {
            throw new SourceFileUnreadableException(e);
        }
        try {
            int sequence = 0;
            while (true) {
                final int length;
                try {
                    length = is.read(buffer);
                } catch (final IOException e) {
                    if (sequence > 0) {
                        writer.beginObject();
                        writer.name("table");
                        writer.value("files");
                        writer.name("values");
                        writer.beginObject();
                        writer.name("path");
                        writer.value(portablePath);
                        writer.name("truncated");
                        writer.value(true);
                        writer.endObject();
                        writer.endObject();
                    }
                    throw new SourceFileUnreadableException(e);
                }
                if (length <= 0) {
                    break;
                }
                writer.beginObject();
                writer.name("table");
                writer.value("files");
                writer.name("values");
                writer.beginObject();
                writer.name("path");
                writer.value(portablePath);
                writer.name("sequence");
                writer.value(sequence++);
                writer.name("content");
                if (length == buffer.length) {
                    writer.value(Base64.encodeToString(buffer, Base64.NO_WRAP));
                } else {
                    byte[] smallBuffer = new byte[length];
                    System.arraycopy(buffer, 0, smallBuffer, 0, length);
                    writer.value(Base64.encodeToString(smallBuffer, Base64.NO_WRAP));
                }
                writer.endObject();
                writer.endObject();
            }
        } finally {
            try {
                is.close();
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "could not close " + file, e);
            }
        }
    }
}
