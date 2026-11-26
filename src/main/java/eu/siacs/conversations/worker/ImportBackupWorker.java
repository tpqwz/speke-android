package eu.siacs.conversations.worker;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingInputStream;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import de.monocles.chat.pinnedmessage.PinnedMessage;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.AttachmentStorage;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.MediaCrypto;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.BackupKeys;
import eu.siacs.conversations.utils.FileHelper;
import eu.siacs.conversations.xmpp.Jid;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import javax.crypto.BadPaddingException;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.io.InvalidCipherTextIOException;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.json.JSONException;
import org.json.JSONObject;

public class ImportBackupWorker extends Worker {

    public static final String TAG_IMPORT_BACKUP = "tag-import-backup";

    /**
     * WorkManager keeps its input {@link Data} unencrypted on disk
     * (no_backup/androidx.work.workdb), outside the encrypted database, until it prunes the
     * finished work. The backup password (often the account password) must not go there: {@code
     * Data} only carries a random token, and the password waits in memory until the worker takes
     * it. If the process dies before that, the import fails and the user enters the password again.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> PENDING_PASSWORDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final String DATA_KEY_PASSWORD_TOKEN = "password_token";
    private static final String DATA_KEY_URI = "uri";
    private static final String DATA_KEY_INCLUDE_OMEMO = "omemo";

    private static final Collection<String> OMEMO_TABLE_LIST =
            Arrays.asList(
                    // PQ OMEMO2 tables (org.signal.libsignal).
                    SQLiteAxolotlStore.PREKEY_TABLENAME, // omemo2_prekeys
                    SQLiteAxolotlStore.SIGNED_PREKEY_TABLENAME, // omemo2_signed_prekeys
                    SQLiteAxolotlStore.SESSION_TABLENAME, // omemo2_sessions
                    SQLiteAxolotlStore.KYBER_PREKEY_TABLENAME, // kyber_prekeys (PQXDH)
                    SQLiteAxolotlStore
                            .KYBER_LAST_RESORT_SESSIONS_TABLENAME, // kyber_last_resort_sessions
                    SQLiteAxolotlStore.IDENTITIES_TABLENAME, // shared trust
                    DatabaseBackend.OMEMO2_PQ_IDENTITIES_TABLE, // own ML-DSA-87 + peer pq_ik pins
                    // Original/legacy OMEMO tables (org.whispersystems). These are
                    // also the table names used by pre-PQ (master) backups, so they
                    // MUST stay accepted or restoring an old backup would fail.
                    "sessions",
                    "prekeys",
                    "signed_prekeys");

    private static final List<String> TABLE_ALLOW_LIST =
            new ImmutableList.Builder<String>()
                    .add(
                            Account.TABLENAME,
                            Conversation.TABLENAME,
                            Message.TABLENAME,
                            "webxdc_updates",
                            "files",
                            "muted_participants",
                            PinnedMessage.TABLENAME)
                    .addAll(OMEMO_TABLE_LIST)
                    .build();

    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[a-zA-Z_]+$");

    private static final int NOTIFICATION_ID = 21;

    private final String password;
    private final String passwordToken;
    private final Uri uri;
    private final boolean includeOmemo;

    private long lastNotificationUpdate = 0;

    // Columns that actually exist in our schema, per table. Backups from other clients (e.g.
    // Conversations' messages.sharedStorage, or the obsolete identities.trusted) carry columns we
    // don't have, and an insert naming an unknown column throws and aborts the whole restore.
    private final Map<String, Set<String>> tableColumns = new HashMap<>();
    private final Set<String> droppedColumns = new HashSet<>();

    public ImportBackupWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        final var inputData = workerParams.getInputData();
        // taken out only when the work is done, so a run the system stops and restarts in
        // this process still has it
        this.passwordToken = inputData.getString(DATA_KEY_PASSWORD_TOKEN);
        this.password = passwordToken == null ? null : PENDING_PASSWORDS.get(passwordToken);
        this.uri = Uri.parse(inputData.getString(DATA_KEY_URI));
        this.includeOmemo = inputData.getBoolean(DATA_KEY_INCLUDE_OMEMO, true);
    }

    @NonNull
    @Override
    public Result doWork() {
        setForegroundAsync(getForegroundInfo());
        if (this.password == null) {
            Log.d(Config.LOGTAG, "backup password not available (process restarted?)");
            return failure(Reason.PASSWORD_MISSING);
        }
        Result result = null;
        try {
            result = importBackup(this.uri, this.password);
        } catch (final FileNotFoundException e) {
            return failure(Reason.FILE_NOT_FOUND);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "error restoring backup " + uri, e);
            final Throwable throwable = e.getCause();
            if (throwable instanceof BadPaddingException
                    || throwable instanceof InvalidCipherTextException
                    || e instanceof InvalidCipherTextIOException
                    || e instanceof ZipException) {
                return failure(Reason.DECRYPTION_FAILED);
            } else {
                return failure(Reason.GENERIC);
            }
        } finally {
            if (!(result instanceof Result.Success)) {
                abortCurrentFile();
                deleteCreatedFiles();
            }
            if (passwordToken != null && !isStopped()) {
                // a stopped run is rescheduled by WorkManager and needs it again
                PENDING_PASSWORDS.remove(passwordToken);
            }
            getApplicationContext()
                    .getSystemService(NotificationManager.class)
                    .cancel(NOTIFICATION_ID);
        }

        return result;
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final var notification = createImportBackupNotification(1, 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(NOTIFICATION_ID, notification);
        }
    }

    /** Fewer binder/FUSE round trips than the 512-byte gzip and 2 KiB cipher defaults. */
    private static final int STREAM_BUFFER = 64 * 1024;

    /** The backup file together with its size, for the progress notification. */
    private static final class BackupStream {
        private final InputStream inputStream;
        private final long size;

        private BackupStream(final InputStream inputStream, final long size) {
            this.inputStream = inputStream;
            this.size = size;
        }

        InputStream inputStream() {
            return inputStream;
        }

        long size() {
            return size;
        }
    }

    private BackupStream openBackup(final Uri uri) throws IOException {
        final var context = getApplicationContext();
        final String path = uri.getPath();
        if ("file".equals(uri.getScheme()) && path != null) {
            final File file = new File(path);
            return new BackupStream(
                    new BufferedInputStream(new FileInputStream(file), STREAM_BUFFER),
                    file.length());
        }
        long size = 0;
        try (final Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                size = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE));
            }
        }
        final InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new FileNotFoundException("could not open " + uri);
        }
        return new BackupStream(new BufferedInputStream(inputStream, STREAM_BUFFER), size);
    }

    private static JsonReader jsonOf(final GZIPInputStream gzip) {
        return new JsonReader(
                new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8)));
    }

    /**
     * SECURITY: reads the whole backup once without writing anything — decrypts it, walks its
     * structure, checks the account row against the header and finally the AES-GCM tag. Only a
     * backup that passes may touch the device (deleting an unreadable database, writing files,
     * inserting rows); the import pass still checks everything again, as the file could have
     * changed in between.
     */
    private Result verifyBackup(
            final CountingInputStream countingInputStream,
            final long fileSize,
            final BackupFileHeader header,
            final byte[] key,
            final String password)
            throws IOException {
        final Stopwatch verifying = Stopwatch.createStarted();
        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(false, new AEADParameters(new KeyParameter(key), 128, header.getIv()));
        try (final CipherInputStream cipherInputStream =
                new CipherInputStream(countingInputStream, cipher)) {
            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream, STREAM_BUFFER);
            final JsonReader jsonReader = jsonOf(gzipInputStream);
            if (jsonReader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new IOException("Backup file did not begin with array");
            }
            jsonReader.beginArray();
            boolean accountSeen = false;
            while (jsonReader.hasNext()) {
                if (isStopped()) {
                    return failure(Reason.GENERIC);
                }
                if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                    throw new IOException("Unexpected token in backup: " + jsonReader.peek());
                }
                jsonReader.beginObject();
                if (!"table".equals(jsonReader.nextName())) {
                    throw new IOException("Expected key 'table'");
                }
                final String table = jsonReader.nextString();
                if (!"values".equals(jsonReader.nextName())
                        || jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                    throw new IOException("Expected object 'values' in " + table);
                }
                if (Account.TABLENAME.equals(table)) {
                    if (accountSeen) {
                        throw new IOException("Backup contains more than one account");
                    }
                    accountSeen = true;
                    verifyAccountRow(
                            jsonReader, header.getJid(), recoveryMode ? null : password);
                } else {
                    if (!accountSeen && TABLE_ALLOW_LIST.contains(table)) {
                        throw new IOException("Backup row in " + table + " before its account");
                    }
                    jsonReader.skipValue();
                }
                while (jsonReader.hasNext()) {
                    jsonReader.nextName();
                    jsonReader.skipValue();
                }
                jsonReader.endObject();
                if ((SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                    lastNotificationUpdate = SystemClock.elapsedRealtime();
                    updateImportBackupNotification(2 * fileSize, countingInputStream.getCount());
                }
            }
            jsonReader.endArray();
            if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Unexpected data after backup array");
            }
            if (!accountSeen) {
                throw new IOException("Backup does not contain its account");
            }
            ByteStreams.exhaust(gzipInputStream);
            ByteStreams.exhaust(cipherInputStream); // checks the GCM tag
        }
        Log.d(Config.LOGTAG, "backup file verified in " + verifying.stop());
        return null;
    }

    private static void verifyAccountRow(
            final JsonReader jsonReader, final Jid account, final String passphrase)
            throws IOException {
        String username = null;
        String server = null;
        String password = null;
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            final String name = jsonReader.nextName();
            if (jsonReader.peek() == JsonToken.STRING
                    && (Account.USERNAME.equals(name)
                            || Account.SERVER.equals(name)
                            || Account.PASSWORD.equals(name))) {
                final String value = jsonReader.nextString();
                switch (name) {
                    case Account.USERNAME -> username = value;
                    case Account.SERVER -> server = value;
                    default -> password = value;
                }
            } else {
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        final Jid jid;
        try {
            jid = Jid.of(username, server, null);
        } catch (final RuntimeException e) {
            throw new IOException("invalid account in backup", e);
        }
        // with a recovery key, the GCM tag under that key proves the backup; with the account
        // password, the row must also carry that password
        if (!jid.equals(account) || (passphrase != null && !passphrase.equals(password))) {
            throw new IOException("jid or password in table did not match backup");
        }
    }

    private Result importBackup(final Uri uri, final String password)
            throws IOException, InvalidKeySpecException {
        final var context = getApplicationContext();
        final var database = DatabaseBackend.getInstance(context);
        Log.d(Config.LOGTAG, "importing backup from " + uri);
        final Stopwatch stopwatch = Stopwatch.createStarted();

        // Nothing on the device is changed before the backup has been verified below.
        boolean unopenableDatabase = false;
        List<Jid> accounts;
        try {
            accounts = database.getAccountJids(false);
        } catch (final net.zetetic.database.sqlcipher.SQLiteNotADatabaseException e) {
            unopenableDatabase = true;
            accounts = Collections.emptyList();
        }

        final BackupFileHeader backupFileHeader;
        final byte[] key;
        final long fileSize;
        {
            final BackupStream backup = openBackup(uri);
            fileSize = backup.size();
            final CountingInputStream countingInputStream =
                    new CountingInputStream(backup.inputStream());
            try {
                backupFileHeader =
                        BackupFileHeader.read(new DataInputStream(countingInputStream));
                Log.d(Config.LOGTAG, backupFileHeader.toString());
                if ("Conversations".equals(backupFileHeader.getApp())
                        || "Quicksy".equals(backupFileHeader.getApp())) {
                    Log.d(
                            Config.LOGTAG,
                            "importing backup created by " + backupFileHeader.getApp());
                }
                if (QuickConversationsService.isQuicksy() && !accounts.isEmpty()) {
                    return failure(Reason.ACCOUNT_ALREADY_EXISTS);
                }
                if (accounts.contains(backupFileHeader.getJid())) {
                    return failure(Reason.ACCOUNT_ALREADY_EXISTS);
                }
                if (backupFileHeader.usesRecoveryKey()) {
                    recoveryMode = true;
                    final byte[] recoveryKey = BackupKeys.parse(password);
                    if (recoveryKey == null) {
                        return failure(Reason.DECRYPTION_FAILED);
                    }
                    key =
                            BackupKeys.fileKey(
                                    recoveryKey,
                                    backupFileHeader.getSalt(),
                                    backupFileHeader.getJid().asBareJid().toString());
                } else if (backupFileHeader.getVersion() >= 4) {
                    key = ExportBackupWorker.getKey(password, backupFileHeader.getSalt());
                } else {
                    key = ExportBackupWorker.getLegacyKey(password, backupFileHeader.getSalt());
                }
                final Result verification =
                        verifyBackup(countingInputStream, fileSize, backupFileHeader, key, password);
                if (verification != null) {
                    return verification;
                }
            } finally {
                countingInputStream.close();
            }
        }

        SQLiteDatabase db;
        if (unopenableDatabase) {
            Log.d(
                    Config.LOGTAG,
                    "database is encrypted but we don't have the password. deleting it to proceed"
                            + " with the verified import");
            DatabaseBackend.closeInstance();
            final File dbFile = context.getDatabasePath("history");
            FileHelper.secureDelete(dbFile);
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-wal"));
            FileHelper.secureDelete(new File(dbFile.getAbsolutePath() + "-shm"));
            if (dbFile.exists()) {
                throw new IOException("could not delete unopenable database");
            }
            // the media key was in that database: encrypted attachments can never be read
            // again, so remove them (plain files stay)
            deleteUnreadableMedia(context);
            // Re-create via getInstance so the new DB uses the current AppSettings password,
            // not the stale constructor-time password from the closed helper.
            db = DatabaseBackend.getInstance(context).getWritableDatabase();
        } else {
            db = database.getWritableDatabase();
        }

        final BackupStream backup = openBackup(uri);
        final CountingInputStream countingInputStream =
                new CountingInputStream(backup.inputStream());
        final BackupFileHeader secondHeader =
                BackupFileHeader.read(new DataInputStream(countingInputStream));
        if (secondHeader.getVersion() != backupFileHeader.getVersion()
                || !secondHeader.getJid().equals(backupFileHeader.getJid())
                || !Arrays.equals(secondHeader.getSalt(), backupFileHeader.getSalt())
                || !Arrays.equals(secondHeader.getIv(), backupFileHeader.getIv())) {
            countingInputStream.close();
            throw new IOException("backup file changed after it was verified");
        }

        final AEADBlockCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(
                false, new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
        // The GCM tag is only checked once the cipher stream has been read to its end, so nothing
        // may be committed before that: a truncated or modified backup must not be imported.
        try (final CipherInputStream cipherInputStream =
                new CipherInputStream(countingInputStream, cipher)) {
            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream, STREAM_BUFFER);
            final JsonReader jsonReader = jsonOf(gzipInputStream);
            if (jsonReader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new IllegalStateException("Backup file did not begin with array");
            }
            jsonReader.beginArray();
            // Created lazily elsewhere; the backup may carry rows for it.
            database.ensureOmemo2PqTablesExist();
            if (MediaCrypto.shouldEncrypt(
                    context, new File(new File(context.getFilesDir(), "media"), "x"))) {
                // create the media key outside the import transaction: a rollback must never
                // take away the key of files encrypted with it
                MediaCrypto.cipher(context);
            }
            db.beginTransaction();
            try {
                // Indexing each message as it is inserted would be thrown away by the full
                // rebuild below; inside the transaction, so a rollback restores the trigger.
                DatabaseBackend.suspendMessageIndexTrigger(db);
                while (jsonReader.hasNext()) {
                    if (isStopped()) {
                        return failure(Reason.GENERIC);
                    }
                    if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                        throw new IOException("Unexpected token in backup: " + jsonReader.peek());
                    }
                    importRow(db, jsonReader, backupFileHeader.getJid(), password);
                    if ((SystemClock.elapsedRealtime() - lastNotificationUpdate) > 2_000) {
                        lastNotificationUpdate = SystemClock.elapsedRealtime();
                        updateImportBackupNotification(
                                2 * fileSize, fileSize + countingInputStream.getCount());
                    }
                }
                jsonReader.endArray();
                finishCurrentFile();
                if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("Unexpected data after backup array");
                }
                ByteStreams.exhaust(gzipInputStream);
                ByteStreams.exhaust(cipherInputStream);
                updateImportBackupNotification(2 * fileSize, 2 * fileSize);
                final Stopwatch indexing = Stopwatch.createStarted();
                DatabaseBackend.restoreMessageIndexTrigger(db); // committed with the messages
                Log.d(Config.LOGTAG, "rebuilt message index in " + indexing.stop());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        // Committed: the restored messages now reference these files, so a later error (index
        // rebuild, notification) must not delete them any more.
        createdFiles.clear();
        if (outOfScopeRows > 0) {
            Log.w(Config.LOGTAG, "left out " + outOfScopeRows + " rows of other accounts");
        }
        // A backup from an older build may point messages at (or bring back files into) the old
        // cache media folder; let the next service start move and relocate them again.
        android.preference.PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .edit()
                .remove(XmppConnectionService.CACHE_MIGRATED_TO_INTERNAL)
                .remove(XmppConnectionService.MEDIA_PATHS_MIGRATED_TO_INTERNAL)
                .commit();
        final Jid jid = backupFileHeader.getJid();
        final Cursor countCursor =
                db.rawQuery(
                        "select count(messages.uuid) from messages join conversations on"
                                + " conversations.uuid=messages.conversationUuid join accounts on"
                                + " conversations.accountUuid=accounts.uuid where"
                                + " accounts.username=? and accounts.server=?",
                        new String[] {jid.getLocal(), jid.getDomain().toString()});
        countCursor.moveToFirst();
        final int count = countCursor.getInt(0);
        Log.d(Config.LOGTAG, String.format("restored %d messages in %s", count, stopwatch.stop()));
        countCursor.close();
        stopBackgroundService();
        notifySuccess();
        return Result.success();
    }

    private void importRow(
            final SQLiteDatabase db,
            final JsonReader jsonReader,
            final Jid account,
            final String passphrase)
            throws IOException {
        jsonReader.beginObject();
        final String firstParameter = jsonReader.nextName();
        if (!firstParameter.equals("table")) {
            throw new IllegalStateException("Expected key 'table'");
        }
        final String table = jsonReader.nextString();
        if (!TABLE_ALLOW_LIST.contains(table)) {
            // Newer versions of other clients may export tables we don't know. Nothing is written
            // for them, so skipping is safe and keeps the rest of the backup restorable.
            Log.w(Config.LOGTAG, "skipping unrecognized table " + table + " in backup");
            while (jsonReader.hasNext()) {
                jsonReader.nextName();
                jsonReader.skipValue();
            }
            jsonReader.endObject();
            return;
        }

        if ("files".equals(table)) {
            importFile(jsonReader);
            jsonReader.endObject();
            return;
        }
        finishCurrentFile(); // a file's chunks are consecutive; any other row ends it

        final ContentValues contentValues = new ContentValues();
        final String secondParameter = jsonReader.nextName();
        if (!secondParameter.equals("values")) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            if (COLUMN_PATTERN.matcher(name).matches()) {
                if (jsonReader.peek() == JsonToken.NULL) {
                    jsonReader.nextNull();
                    contentValues.putNull(name);
                } else if (jsonReader.peek() == JsonToken.NUMBER) {
                    contentValues.put(name, jsonReader.nextLong());
                } else {
                    String value = jsonReader.nextString();
                    if (Message.TABLENAME.equals(table)
                            && Message.RELATIVE_FILE_PATH.equals(name)) {
                        // The app later reads, re-shares and *deletes* the file a message points
                        // at, so a backup must not be able to point it anywhere else.
                        value = confinedMessagePath(value);
                        if (value == null) {
                            contentValues.putNull(name);
                            continue;
                        }
                    }
                    contentValues.put(name, value);
                }
            } else {
                throw new IOException(String.format("Unexpected column name %s", name));
            }
        }
        jsonReader.endObject();
        jsonReader.endObject();
        final Set<String> knownColumns = knownColumns(db, table);
        for (final String name : new ArrayList<>(contentValues.keySet())) {
            if (!knownColumns.contains(name)) {
                contentValues.remove(name);
                if (droppedColumns.add(table + "." + name)) {
                    Log.d(Config.LOGTAG, "dropping unknown column " + table + "." + name);
                }
            }
        }
        if ("webxdc_updates".equals(table)) {
            // serial is a device-local AUTOINCREMENT key: keeping the backup's value collides
            // with updates already on this device and the row would be dropped as a duplicate.
            // A new serial is assigned in export order, which is all the app relies on.
            contentValues.remove("serial");
        }
        if (contentValues.size() == 0) {
            Log.w(Config.LOGTAG, "skipping row without any known columns in " + table);
            return;
        }
        if (Account.TABLENAME.equals(table)) {
            final Jid jid =
                    Jid.of(
                            contentValues.getAsString(Account.USERNAME),
                            contentValues.getAsString(Account.SERVER),
                            null);
            final String password = contentValues.getAsString(Account.PASSWORD);
            if (QuickConversationsService.isQuicksy()) {
                if (!jid.getDomain().equals(Config.QUICKSY_DOMAIN)) {
                    throw new IOException("Trying to restore non Quicksy account on Quicksy");
                }
            }
            if (jid.equals(account) && (recoveryMode || passphrase.equals(password))) {
                Log.d(Config.LOGTAG, "jid and password from backup header had matching row");
            } else {
                throw new IOException("jid or password in table did not match backup");
            }
            if (backupAccountUuid != null) {
                throw new IOException("Backup contains more than one account");
            }
            if (Strings.isNullOrEmpty(contentValues.getAsString(Account.UUID))) {
                throw new IOException("Backup account has no uuid");
            }
            // backupAccountUuid is only set once the row was really inserted (below)
            final var keys = Account.parseKeys(contentValues.getAsString(Account.KEYS));
            final var deviceId = keys.optString(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID);
            final var importReadyKeys = new JSONObject();
            if (!Strings.isNullOrEmpty(deviceId) && this.includeOmemo) {
                try {
                    importReadyKeys.put(SQLiteAxolotlStore.JSONKEY_REGISTRATION_ID, deviceId);
                } catch (final JSONException e) {
                    Log.e(Config.LOGTAG, "error writing omemo registration id", e);
                }
            }
            contentValues.put(Account.KEYS, importReadyKeys.toString());
        }
        // SECURITY: only rows of the backup's own account (and its conversations) get in, so a
        // crafted backup can't add conversations, messages or OMEMO trust to another account.
        if (!Account.TABLENAME.equals(table)
                && !rowInScope(
                        table,
                        contentValues::getAsString,
                        backupAccountUuid,
                        importedConversations,
                        importedConversationJids)) {
            if (outOfScopeRows++ == 0) {
                Log.w(Config.LOGTAG, "skipping rows that do not belong to the backup account");
            }
            return;
        }
        final long rowId;
        if (this.includeOmemo) {
            rowId =
                    db.insertWithOnConflict(
                            table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
        } else {
            if (OMEMO_TABLE_LIST.contains(table)) {
                // Contact trust is kept either way; only our own key material is dropped.
                // Keeping the peers' pq_ik pins matters: without them every peer's post-quantum
                // key would be pinned afresh on first contact after the restore.
                // a row without "own" counts as ours and is dropped (fail safe; also no NPE)
                final Integer own = contentValues.getAsInteger(SQLiteAxolotlStore.OWN);
                if ((SQLiteAxolotlStore.IDENTITIES_TABLENAME.equals(table)
                                && own != null
                                && own == 0)
                        || (DatabaseBackend.OMEMO2_PQ_IDENTITIES_TABLE.equals(table)
                                && !DatabaseBackend.isOwnOmemo2PqRow(
                                        contentValues.getAsString(SQLiteAxolotlStore.NAME),
                                        contentValues.getAsString(
                                                SQLiteAxolotlStore.FINGERPRINT)))) {
                    rowId =
                            db.insertWithOnConflict(
                                    table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
                } else {
                    Log.d(Config.LOGTAG, "skipping over omemo key material in table " + table);
                    rowId = 0;
                }
            } else {
                rowId =
                        db.insertWithOnConflict(
                                table, null, contentValues, SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
        if (Account.TABLENAME.equals(table)) {
            // SECURITY: accounts.uuid is the primary key and the insert ignores conflicts. A
            // backup reusing the uuid of an account already on this device would be ignored
            // here while its rows would then count as "own" and land in that other account.
            if (rowId == -1) {
                throw new IOException("account of the backup already exists");
            }
            backupAccountUuid = contentValues.getAsString(Account.UUID);
        } else if (rowId == -1) {
            Log.w(Config.LOGTAG, "skipped duplicate row in " + table);
        } else if (Conversation.TABLENAME.equals(table)) {
            // only a conversation this restore really created may receive its messages: one
            // ignored as duplicate (its uuid already exists) may belong to another account
            importedConversations.add(contentValues.getAsString(Conversation.UUID));
            final String contactJid = contentValues.getAsString(Conversation.CONTACTJID);
            if (contactJid != null) {
                importedConversationJids.add(bare(contactJid));
            }
        }
    }

    private String backupAccountUuid = null;

    /** The backup is encrypted with a recovery key, not with its account's password. */
    private boolean recoveryMode = false;
    private final Set<String> importedConversations = new HashSet<>();
    private final Set<String> importedConversationJids = new HashSet<>();
    private int outOfScopeRows = 0;

    private static String bare(final String jid) {
        final int slash = jid.indexOf('/');
        return slash < 0 ? jid : jid.substring(0, slash);
    }

    /**
     * Whether a (non-account) row belongs to the backup's account: conversations and OMEMO rows
     * by their account column, messages/WebXDC/pinned rows by one of the backup's own
     * conversations, muted participants by one of its group chats.
     */
    static boolean rowInScope(
            final String table,
            final java.util.function.Function<String, String> value,
            final String accountUuid,
            final Set<String> conversations,
            final Set<String> conversationJids) {
        if (accountUuid == null) {
            return false;
        }
        if (Conversation.TABLENAME.equals(table)) {
            return accountUuid.equals(value.apply(Conversation.ACCOUNT))
                    && value.apply(Conversation.UUID) != null;
        }
        if (Message.TABLENAME.equals(table) || "webxdc_updates".equals(table)) {
            return conversations.contains(value.apply(Message.CONVERSATION));
        }
        if (PinnedMessage.TABLENAME.equals(table)) {
            return accountUuid.equals(value.apply(PinnedMessage.ACCOUNT_UUID))
                    && conversations.contains(value.apply(PinnedMessage.CONVERSATION_UUID));
        }
        if ("muted_participants".equals(table)) {
            final String muc = value.apply("muc_jid");
            return muc != null && conversationJids.contains(bare(muc));
        }
        if (OMEMO_TABLE_LIST.contains(table)) {
            return accountUuid.equals(value.apply(SQLiteAxolotlStore.ACCOUNT));
        }
        return false;
    }

    private Set<String> knownColumns(final SQLiteDatabase db, final String table) {
        final Set<String> cached = tableColumns.get(table);
        if (cached != null) {
            return cached;
        }
        final Set<String> columns = new HashSet<>();
        // table is from TABLE_ALLOW_LIST, so it is safe to concatenate
        try (final Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            final int nameIndex = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex));
            }
        }
        tableColumns.put(table, columns);
        return columns;
    }

    /**
     * The (canonical) files this restore wrote. Only these may be deleted by a later {@code
     * truncated} marker, and none of them is written twice, so a crafted backup can't grow or
     * delete attachments that were already on the device.
     */
    private final Set<File> restoredFiles = new HashSet<>();

    /**
     * The restored files that did not exist before. Chunks are written while the backup is
     * still being read, before its GCM tag is checked, so if the restore fails (truncated,
     * tampered, stopped…) these are removed again — like the rolled back database rows.
     */
    private final Set<File> createdFiles = new HashSet<>();

    /**
     * The reset database took the media key with it: files encrypted with it (attachments,
     * avatars, wallpapers, stickers) can never be read again. Removing them lets avatars be
     * fetched again instead of staying broken (they count as cached while the file exists).
     */
    private static void deleteUnreadableMedia(final Context context) {
        MediaCrypto.reset();
        int deleted = 0;
        for (final String area : MediaCrypto.ENCRYPTED_AREAS) {
            deleted += deleteEncrypted(new File(context.getFilesDir(), area), 0);
        }
        if (deleted > 0) {
            Log.d(Config.LOGTAG, "removed " + deleted + " files of the reset database");
        }
    }

    private static int deleteEncrypted(final File directory, final int depth) {
        final File[] files = directory.listFiles();
        if (files == null || depth > 4) {
            return 0;
        }
        int deleted = 0;
        for (final File file : files) {
            if (file.isDirectory()) {
                deleted += deleteEncrypted(file, depth + 1);
            } else if (eu.siacs.conversations.persistance.MediaCipher.isEncrypted(file)
                    && file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private void deleteCreatedFiles() {
        for (final File file : createdFiles) {
            if (file.exists() && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete file of failed restore " + file);
            }
        }
        if (!createdFiles.isEmpty()) {
            Log.d(Config.LOGTAG, "removed " + createdFiles.size() + " files of a failed restore");
        }
        createdFiles.clear();
        restoredFiles.clear();
    }

    private void importFile(JsonReader jsonReader) throws IOException {
        final String valuesKey = jsonReader.nextName();
        if (!"values".equals(valuesKey)) {
            throw new IllegalStateException("Expected key 'values'");
        }
        jsonReader.beginObject();
        String portablePath = null;
        int sequence = -1;
        byte[] content = null;
        boolean truncated = false;
        while (jsonReader.peek() != JsonToken.END_OBJECT) {
            final String name = jsonReader.nextName();
            switch (name) {
                case "path":
                    portablePath = jsonReader.nextString();
                    break;
                case "sequence":
                    sequence = jsonReader.nextInt();
                    break;
                case "content":
                    content = Base64.decode(jsonReader.nextString(), Base64.NO_WRAP);
                    break;
                case "truncated":
                    if (jsonReader.peek() == JsonToken.BOOLEAN) {
                        truncated = jsonReader.nextBoolean();
                    } else {
                        jsonReader.skipValue();
                    }
                    break;
                default:
                    jsonReader.skipValue();
                    break;
            }
        }
        jsonReader.endObject();

        if (portablePath == null) {
            return;
        }
        // SECURITY: the path comes from the (possibly crafted) backup. Without this, an
        // absolute path or `${FILES}/../shared_prefs/...` would overwrite arbitrary app data.
        final File file = confine(fromPortablePath(portablePath));
        if (file == null) {
            Log.w(Config.LOGTAG, "skipping backup file outside attachment storage: " + portablePath);
            return;
        }

        if (truncated) {
            // the source file could not be read completely when the backup was made: drop
            // what was restored of it instead of keeping a silently cut-off attachment
            if (file.equals(currentFile)) {
                abortCurrentFile();
            } else if (restoredFiles.remove(file)) {
                createdFiles.remove(file);
                if (!file.delete()) {
                    Log.w(Config.LOGTAG, "could not delete truncated file " + file);
                }
            }
            return;
        }
        if (content == null) {
            return;
        }

        if (sequence <= 0) {
            // first chunk (a missing sequence means a single-chunk file of an older backup)
            finishCurrentFile();
            if (restoredFiles.contains(file)) {
                Log.w(Config.LOGTAG, "skipping second copy of " + file + " in backup");
                return;
            }
            currentIsNew = !file.exists();
            currentWriter = attachmentStorage.beginRestore(file);
            currentFile = file;
            nextSequence = 1;
            currentWriter.write(content);
            return;
        }
        // SECURITY: a later chunk only continues the file this restore is writing right now, in
        // order — never one that was already on the device or already finished.
        if (!file.equals(currentFile) || sequence != nextSequence) {
            Log.w(Config.LOGTAG, "dropping " + file + ": chunk " + sequence + " out of order");
            if (file.equals(currentFile)) {
                abortCurrentFile();
            }
            return;
        }
        currentWriter.write(content);
        nextSequence++;
    }

    /** Where restored attachments are written; the seam for encryption at rest. */
    private final AttachmentStorage attachmentStorage =
            AttachmentStorage.forDevice(getApplicationContext());

    private File currentFile = null;
    private AttachmentStorage.RestoreWriter currentWriter = null;
    private boolean currentIsNew = false;
    private int nextSequence = 0;

    /** Moves the file being restored into place, once all its chunks have been read. */
    private void finishCurrentFile() throws IOException {
        if (currentWriter == null) {
            return;
        }
        final File file = currentFile;
        final boolean isNew = currentIsNew;
        final AttachmentStorage.RestoreWriter writer = currentWriter;
        currentWriter = null;
        currentFile = null;
        try {
            writer.commit();
        } catch (final IOException e) {
            writer.abort();
            throw e;
        }
        restoredFiles.add(file);
        if (isNew) {
            createdFiles.add(file);
        }
    }

    private void abortCurrentFile() {
        if (currentWriter != null) {
            currentWriter.abort();
        }
        currentWriter = null;
        currentFile = null;
    }

    private void stopBackgroundService() {
        final var intent = new Intent(getApplicationContext(), XmppConnectionService.class);
        getApplicationContext().stopService(intent);
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, createImportBackupNotification(max, progress));
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(getApplicationContext(), "backup");
        builder.setContentTitle(context.getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return builder.build();
    }

    private void notifySuccess() {
        final var context = getApplicationContext();
        final var builder = new NotificationCompat.Builder(context, "backup");
        builder.setContentTitle(context.getString(R.string.notification_restored_backup_title))
                .setContentText(context.getString(R.string.notification_restored_backup_subtitle))
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_unarchive_24dp);
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            builder.setContentText(
                    context.getString(R.string.notification_restored_backup_subtitle));
            builder.setContentIntent(
                    PendingIntent.getActivity(
                            context,
                            145,
                            new Intent(context, AccountUtils.MANAGE_ACCOUNT_ACTIVITY),
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT));
        }
        getApplicationContext()
                .getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID + 2, builder.build());
    }

    public static Data data(final String password, final Uri uri, final boolean includeOmemo) {
        final String token = java.util.UUID.randomUUID().toString();
        PENDING_PASSWORDS.put(token, password);
        return new Data.Builder()
                .putString(DATA_KEY_PASSWORD_TOKEN, token)
                .putString(DATA_KEY_URI, uri.toString())
                .putBoolean(DATA_KEY_INCLUDE_OMEMO, includeOmemo)
                .build();
    }

    private static Result failure(final Reason reason) {
        return Result.failure(new Data.Builder().putString("reason", reason.toString()).build());
    }

    public enum Reason {
        ACCOUNT_ALREADY_EXISTS,
        DECRYPTION_FAILED,
        FILE_NOT_FOUND,
        PASSWORD_MISSING,
        GENERIC;

        public static Reason valueOfOrGeneric(final String value) {
            if (Strings.isNullOrEmpty(value)) {
                return GENERIC;
            }
            try {
                return valueOf(value);
            } catch (final IllegalArgumentException e) {
                return GENERIC;
            }
        }
    }

    /**
     * Where restored attachments may live: the places this app stores message files (see
     * FileBackend.getStorageLocation / getLegacyStorageLocation). Nothing else under our private
     * storage (databases, shared_prefs, keys) and nothing else in shared storage.
     */
    private List<File> attachmentRoots() {
        final Context context = getApplicationContext();
        final String appName = context.getString(R.string.app_name);
        final File filesDir = context.getFilesDir();
        final ImmutableList.Builder<File> roots = new ImmutableList.Builder<>();
        roots.add(new File(filesDir, "media"));
        // Stickers (StickersMigration.getStickersDir): messages may point at them via their cid.
        roots.add(new File(filesDir, "Stickers"));
        for (final String legacy : new String[] {"Files", "Images", "Videos", "Audios"}) {
            roots.add(new File(filesDir, legacy));
        }
        roots.add(context.getCacheDir());
        for (final String name : new String[] {appName, "LaNoxe", "monocles chat"}) {
            roots.add(new File(Environment.getExternalStorageDirectory(), name));
            for (final String type :
                    new String[] {
                        Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_DOWNLOADS,
                        Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES,
                        Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DCIM
                    }) {
                roots.add(new File(Environment.getExternalStoragePublicDirectory(type), name));
            }
        }
        return roots.build();
    }

    /**
     * The canonical form of {@code path} if it lies strictly inside an attachment root, else null.
     */
    private File confine(final String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        try {
            final File canonical = new File(path).getCanonicalFile();
            for (final File root : attachmentRoots()) {
                final String rootPath = root.getCanonicalPath() + File.separator;
                if (canonical.getPath().startsWith(rootPath)) {
                    return canonical;
                }
            }
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "could not canonicalize backup path " + path, e);
        }
        return null;
    }

    /**
     * A message's file path from the backup: absolute paths must stay inside the attachment roots;
     * legacy bare file names (resolved against the legacy media folders) must not contain any path
     * separators or parent references. Returns null if the path is not acceptable.
     */
    private String confinedMessagePath(final String portablePath) {
        final String path = fromPortablePath(portablePath);
        if (path == null) {
            return null;
        }
        if (path.startsWith("/")) {
            // Keep the path as written (e.g. /sdcard/... vs /storage/emulated/0/...); only its
            // canonical form has to lie inside the roots.
            return confine(path) == null ? null : path;
        }
        if (path.contains("/") || path.contains("\\") || path.equals("..") || path.equals(".")) {
            return null;
        }
        return path;
    }

    private String fromPortablePath(String path) {
        if (path == null) return null;
        final String cacheDir = getApplicationContext().getCacheDir().getAbsolutePath();
        final String filesDir = getApplicationContext().getFilesDir().getAbsolutePath();
        final String externalDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        final String documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                        .getAbsolutePath();

        if (path.startsWith("${CACHE}")) {
            return cacheDir + path.substring("${CACHE}".length());
        } else if (path.startsWith("${FILES}")) {
            return filesDir + path.substring("${FILES}".length());
        } else if (path.startsWith("${EXTERNAL}")) {
            return externalDir + path.substring("${EXTERNAL}".length());
        } else if (path.startsWith("${DOCUMENTS}")) {
            return documentsDir + path.substring("${DOCUMENTS}".length());
        }
        return path;
    }
}
