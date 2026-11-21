package eu.siacs.conversations.http;

import static eu.siacs.conversations.http.HttpConnectionManager.EXECUTOR;

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileWriterException;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.PartialFiles;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpDownloadConnection implements Transferable {

    private final Message message;
    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private HttpUrl mUrl;
    private DownloadableFile file;
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private boolean acceptedAutomatically = false;
    private int mProgress = 0;
    private Call mostRecentCall;
    private final Consumer<DownloadableFile> cb;

    /**
     * {@link #cancel} sets {@link #canceled} under it; whatever writes the download's result into
     * the message (its URL after the size check, the file and its path when done) does so under it
     * and only if not canceled. A cancel (e.g. the message was retracted) therefore either comes
     * after the whole result is in place, which the canceller then removes as usual, or nothing of
     * it is written at all: the network call is not the only thing still running, decrypting a
     * large file takes a while.
     */
    private final Object commitLock = new Object();

    private boolean canceled = false;

    HttpDownloadConnection(
            Message message, HttpConnectionManager manager, Consumer<DownloadableFile> cb) {
        this.message = message;
        this.mHttpConnectionManager = manager;
        this.mXmppConnectionService = manager.getXmppConnectionService();
        this.cb = cb;
    }

    @Override
    public boolean start() {
        if (mXmppConnectionService.hasInternetConnection()) {
            if (this.mStatus == STATUS_OFFER_CHECK_FILESIZE) {
                checkFileSize(true);
            } else {
                download(true);
            }
            return true;
        } else {
            return false;
        }
    }

    public void init(boolean interactive) {
        final Message.FileParams fileParams = message.getFileParams();
        if (message.isDeleted()) {
            if (message.getType() == Message.TYPE_PRIVATE_FILE) {
                message.setType(Message.TYPE_PRIVATE);
            } else if (message.isFileOrImage()) {
                message.setType(Message.TYPE_TEXT);
            }
            message.setDeleted(false);
            mXmppConnectionService.updateMessage(message);
        }
        this.message.setTransferable(this);
        try {
            if (message.hasFileOnRemoteHost()) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else if (message.isOOb() && fileParams.url != null) {
                mUrl = AesGcmURL.of(fileParams.url);
            } else {
                mUrl = AesGcmURL.of(message.getRawBody().split("\n")[0]);
            }
            final AbstractConnectionManager.Extension extension =
                    AbstractConnectionManager.Extension.of(mUrl.encodedPath());
            if (VALID_CRYPTO_EXTENSIONS.contains(extension.main)) {
                this.message.setEncryption(Message.ENCRYPTION_PGP);
            } else if (message.getEncryption() != Message.ENCRYPTION_AXOLOTL
                    && message.getEncryption() != Message.ENCRYPTION_AXOLOTL_OMEMO2
                    && message.getEncryption() != Message.ENCRYPTION_PGP
                    && message.getEncryption() != Message.ENCRYPTION_DECRYPTED) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            String ext = extension.getExtension();
            if (ext == null && fileParams.getMediaType() != null) {
                ext = MimeUtils.guessExtensionFromMimeType(fileParams.getMediaType());
            }
            final String filename =
                    Strings.isNullOrEmpty(ext)
                            ? message.getUuid()
                            : String.format("%s.%s", message.getUuid(), ext);
            mXmppConnectionService.getFileBackend().setupRelativeFilePath(message, filename);
            setupFile();
            if ((this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                            || this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2)
                    && this.file.getKey() == null) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            final Long knownFileSize;
            if (message.getEncryption() == Message.ENCRYPTION_PGP
                    || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                knownFileSize = null;
            } else {
                knownFileSize = message.getFileParams().size;
            }
            // Never log the body: for encrypted files it is aesgcm://…#<key+iv>.
            Log.d(
                    Config.LOGTAG,
                    "knownFileSize: " + knownFileSize + ", message=" + message.getUuid());
            if (knownFileSize != null && interactive) {
                if ((message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2)
                        && this.file.getKey() != null) {
                    this.file.setExpectedSize(knownFileSize + 16);
                } else {
                    this.file.setExpectedSize(knownFileSize);
                }
                download(true);
            } else {
                checkFileSize(interactive);
            }
        } catch (final IllegalArgumentException e) {
            this.cancel();
        }
    }

    private void setupFile() {
        final String reference = mUrl.fragment();
        if (reference != null && AesGcmURL.IV_KEY.matcher(reference).matches()) {
            this.file = mXmppConnectionService.getFileBackend().getPartialFile(message.getUuid());
            this.file.setKeyAndIv(CryptoHelper.hexToBytes(reference));
            Log.d(
                    Config.LOGTAG,
                    "create temporary OMEMO encrypted file: "
                            + this.file.getAbsolutePath()
                            + "("
                            + message.getMimeType()
                            + ")");
        } else if (isPgp()) {
            // already a cache file (<name>.pgp) that PgpDecryptionService picks up
            this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        } else {
            // never write an unfinished download to the media folders (see getPartialFile)
            this.file = mXmppConnectionService.getFileBackend().getPartialFile(message.getUuid());
        }
    }

    /**
     * Only a download that is itself OpenPGP data. A message that is already DECRYPTED (an
     * OpenPGP text that turned out to be a plain https link, or a link shared into an OpenPGP
     * chat) downloads plain content: it must take the normal path, so it is stored encrypted at
     * rest instead of being renamed from the cache into media as plaintext.
     */
    private boolean isPgp() {
        return message.getEncryption() == Message.ENCRYPTION_PGP;
    }

    /**
     * A plaintext download is complete: move it from the cache to where the message keeps it.
     * finish() then renames it to its content-addressed name in the same directory.
     */
    private void moveCompletedDownload() throws FileWriterException {
        // the real storage path (for a DECRYPTED message getFile(message, false) would be the
        // cache path of OpenPGP data)
        final DownloadableFile target =
                mXmppConnectionService.getFileBackend().getFile(message, true);
        try {
            PartialFiles.move(file, target);
        } catch (final IOException e) {
            if (file.exists() && !file.delete()) {
                Log.w(Config.LOGTAG, "unable to delete " + file.getAbsolutePath());
            }
            throw new FileWriterException(target);
        }
        file = target;
    }

    private void download(final boolean interactive) {
        EXECUTOR.execute(new FileDownloader(interactive));
    }

    private void checkFileSize(final boolean interactive) {
        EXECUTOR.execute(new FileSizeChecker(interactive));
    }

    @Override
    public void cancel() {
        synchronized (commitLock) {
            canceled = true;
        }
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        mHttpConnectionManager.finishConnection(this);
        message.setTransferable(null);
        if (message.isFileOrImage()) {
            message.setDeleted(true);
        }
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void decryptFile() throws IOException {
        final DownloadableFile outputFile =
                mXmppConnectionService.getFileBackend().getFile(message, true);

        if (outputFile.getParentFile().mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for " + outputFile.getAbsolutePath());
        }

        // The file only counts as downloaded once it decrypted completely (closing the stream
        // finishes the decryption), so decrypt next to the target and move it in afterwards.
        final DownloadableFile partFile =
                new DownloadableFile(outputFile.getAbsolutePath() + ".part");
        partFile.setKey(this.file.getKey());
        partFile.setIv(this.file.getIv());
        try (final InputStream is = new FileInputStream(this.file);
                final OutputStream os =
                        AbstractConnectionManager.createDecryptingStorageStream(
                                partFile, outputFile)) {
            if (os == null) {
                throw new IOException("unable to open " + partFile.getAbsolutePath());
            }
            ByteStreams.copy(is, os);
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "unable to decrypt downloaded file for " + message.getUuid(), e);
            discardDecryption(partFile);
            throw new InvalidFileException("decryption failed");
        }
        synchronized (commitLock) {
            if (canceled) {
                discardDecryption(partFile);
                throw new java.io.InterruptedIOException("download canceled");
            }
            if (!partFile.renameTo(outputFile)) {
                discardDecryption(partFile);
                throw new IOException(
                        "unable to move decrypted file to " + outputFile.getAbsolutePath());
            }
        }

        if (!file.delete()) {
            Log.w(
                    Config.LOGTAG,
                    "unable to delete temporary OMEMO encrypted file " + file.getAbsolutePath());
        }

        file = outputFile;
    }

    private void discardDecryption(final File partFile) {
        if (partFile.exists() && !partFile.delete()) {
            Log.w(Config.LOGTAG, "unable to delete " + partFile.getAbsolutePath());
        }
        if (this.file.exists() && !this.file.delete()) {
            Log.w(Config.LOGTAG, "unable to delete " + this.file.getAbsolutePath());
        }
    }

    private void finish() {
        boolean notify = acceptedAutomatically && !message.isRead() && cb == null;
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            // Let PgpDecryptionService handle file placement: it reads from getFile(message,false)
            // (cacheDir/<uuid>.jpg.pgp) and writes decrypted output to getFile(message,true).
            // Renaming here would move the encrypted file to the wrong location and break that
            // lookup.
            synchronized (commitLock) {
                if (canceled) {
                    discardCanceled(file);
                    return;
                }
                message.getConversation()
                        .getAccount()
                        .getPgpDecryptionService()
                        .decrypt(message, notify);
                message.setTransferable(null);
                mXmppConnectionService.updateMessage(message);
            }
            mHttpConnectionManager.finishConnection(this);
            return;
        }
        final DownloadableFile tmp = file;
        final String extension = MimeUtils.extractRelevantExtension(tmp.getName());
        // hashing a large file takes a while: outside the lock, so a cancel never waits for it
        String hashedPath = null;
        boolean blocked = false;
        try {
            mXmppConnectionService
                    .getFileBackend()
                    .setupRelativeFilePath(
                            message,
                            mXmppConnectionService.getFileBackend().openInputStream(tmp),
                            extension);
            hashedPath = message.getRelativeFilePath();
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "Failed to hash downloaded file: " + e);
        } catch (final XmppConnectionService.BlockedMediaException e) {
            blocked = true;
        }
        synchronized (commitLock) {
            if (canceled) {
                // the canceller already cleared the message; leave neither file behind
                discardCanceled(tmp);
                return;
            }
            if (blocked) {
                file = tmp;
                tmp.delete();
                message.setDeleted(true);
            } else {
                try {
                    if (hashedPath == null) throw new IOException("no file path");
                    file = mXmppConnectionService.getFileBackend().getFile(message);
                    boolean didRename = tmp.renameTo(file);
                    if (!didRename) throw new IOException("rename failed");
                } catch (final IOException e) {
                    Log.w(Config.LOGTAG, "Failed to rename downloaded file: " + e);
                    file = tmp;
                    message.setRelativeFilePath(file.getAbsolutePath());
                }
            }
            message.setTransferable(null);
            mXmppConnectionService.updateMessage(message);
        }
        mHttpConnectionManager.finishConnection(this);
        final boolean notifyAfterScan = notify;
        mXmppConnectionService
                .getFileBackend()
                .updateMediaScanner(
                        file,
                        () -> {
                            if (notifyAfterScan) {
                                mXmppConnectionService.getNotificationService().push(message);
                            }
                        });
    }

    /** A download canceled after it completed: its (decrypted) file goes, nothing is stored. */
    private void discardCanceled(final File downloaded) {
        if (downloaded != null && downloaded.exists() && !downloaded.delete()) {
            Log.w(
                    Config.LOGTAG,
                    "unable to delete canceled download " + downloaded.getAbsolutePath());
        }
    }

    private void decryptIfNeeded() throws IOException, FileWriterException {
        if (file.getKey() != null && file.getIv() != null) {
            decryptFile();
        } else if (!isPgp()) {
            moveCompletedDownload();
        }
    }

    private void changeStatus(int status) {
        this.mStatus = status;
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void showToastForException(final Exception e) {
        final Call call = mostRecentCall;
        final boolean cancelled = call != null && call.isCanceled();
        if (e == null || cancelled) {
            return;
        }
        if (e instanceof java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (e instanceof java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else if (e instanceof FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(
                    R.string.download_failed_could_not_write_file);
        } else if (e instanceof InvalidFileException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_invalid_file);
        } else {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_file_not_found);
        }
    }

    private void updateProgress(long i) {
        this.mProgress = (int) i;
        mHttpConnectionManager.updateConversationUi(false);
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public Long getFileSize() {
        if (this.file != null) {
            return this.file.getExpectedSize();
        } else {
            return null;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    public Message getMessage() {
        return message;
    }

    private class FileSizeChecker implements Runnable {

        private final boolean interactive;

        FileSizeChecker(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            check();
        }

        private void retrieveFailed(@Nullable final Exception e) {
            changeStatus(STATUS_OFFER_CHECK_FILESIZE);
            if (interactive) {
                showToastForException(e);
            } else {
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this
                        .mXmppConnectionService
                        .getNotificationService()
                        .push(message);
            }
            cancel();
        }

        private void check() {
            long size;
            try {
                size = retrieveFileSize();
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "io exception in http file size checker: " + e.getMessage());
                retrieveFailed(e);
                return;
            }
            synchronized (commitLock) {
                // retracted meanwhile: its URL (and key) must not be written back
                if (canceled) {
                    return;
                }
                final Message.FileParams fileParams = message.getFileParams();
                FileBackend.updateFileParams(message, fileParams.url, size);
                mXmppConnectionService.databaseBackend.updateMessage(message, true);
            }
            file.setExpectedSize(size);
            message.setFileParams(null);
            if (mHttpConnectionManager.hasStoragePermission()
                    && size <= mHttpConnectionManager.getAutoAcceptFileSize()
                    && mXmppConnectionService.isDataSaverDisabled()) {
                HttpDownloadConnection.this.acceptedAutomatically = true;
                download(interactive);
            } else {
                changeStatus(STATUS_OFFER);
                HttpDownloadConnection.this.acceptedAutomatically = false;
                if (cb == null) {
                    HttpDownloadConnection.this
                            .mXmppConnectionService
                            .getNotificationService()
                            .push(message);
                } else {
                    cb.accept(null);
                }
            }
        }

        private long retrieveFileSize() throws IOException {
            Log.d(Config.LOGTAG, "retrieve file size. interactive:" + interactive);
            changeStatus(STATUS_CHECKING);
            final OkHttpClient client =
                    mHttpConnectionManager.buildHttpClient(
                            mUrl, message.getConversation().getAccount(), interactive);
            final Request request =
                    new Request.Builder()
                            .url(URL.stripFragment(mUrl))
                            .addHeader("Accept-Encoding", "identity")
                            .head()
                            .build();
            mostRecentCall = client.newCall(request);
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                final String contentLength = response.header("Content-Length");
                final String contentType = response.header("Content-Type");
                final AbstractConnectionManager.Extension extension =
                        AbstractConnectionManager.Extension.of(mUrl.encodedPath());
                // A type from the encrypted <file-sharing/> description (PQ OMEMO2 uploads carry
                // no extension and a generic Content-Type) wins over what the server says;
                // a generic type says nothing about the file.
                final String describedType = message.getFileParams().getMediaType();
                if (Strings.isNullOrEmpty(extension.getExtension())
                        && contentType != null
                        && Strings.isNullOrEmpty(describedType)
                        && !contentType.startsWith("application/octet-stream")) {
                    final String fileExtension = MimeUtils.guessExtensionFromMimeType(contentType);
                    if (fileExtension != null) {
                        mXmppConnectionService
                                .getFileBackend()
                                .setupRelativeFilePath(
                                        message,
                                        String.format("%s.%s", message.getUuid(), fileExtension),
                                        contentType);
                        Log.d(
                                Config.LOGTAG,
                                "rewriting name after not finding extension in url but in content"
                                        + " type");
                        setupFile();
                    }
                }
                if (Strings.isNullOrEmpty(contentLength)) {
                    throw new IOException("no content-length found in HEAD response");
                }
                final long size = Long.parseLong(contentLength, 10);
                if (size < 0) {
                    throw new IOException("Server reported negative file size");
                }
                return size;
            } catch (final IOException e) {
                Log.d(Config.LOGTAG, "io exception during HEAD " + e.getMessage());
                throw e;
            } catch (final NumberFormatException e) {
                throw new IOException(e);
            }
        }
    }

    private class FileDownloader implements Runnable {

        private final boolean interactive;

        public FileDownloader(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            try {
                changeStatus(STATUS_DOWNLOADING);
                download();
                decryptIfNeeded();
                finish();
                updateImageBounds();
            } catch (final SSLHandshakeException e) {
                changeStatus(STATUS_OFFER);
            } catch (final Exception e) {
                Log.d(
                        Config.LOGTAG,
                        message.getConversation().getAccount().getJid().asBareJid()
                                + ": unable to download file",
                        e);
                if (interactive) {
                    showToastForException(e);
                } else {
                    HttpDownloadConnection.this.acceptedAutomatically = false;
                    HttpDownloadConnection.this
                            .mXmppConnectionService
                            .getNotificationService()
                            .push(message);
                }
                cancel();
            } finally {
                if (cb != null) cb.accept(file);
            }
        }

        private void download() throws Exception {
            final OkHttpClient client =
                    mHttpConnectionManager.buildHttpClient(
                            mUrl, message.getConversation().getAccount(), interactive);

            final Request.Builder requestBuilder =
                    new Request.Builder().url(URL.stripFragment(mUrl));

            final long expected = file.getExpectedSize();
            final boolean tryResume =
                    file.exists() && file.getSize() > 0 && file.getSize() < expected;
            final long resumeSize;
            if (tryResume) {
                resumeSize = file.getSize();
                Log.d(
                        Config.LOGTAG,
                        "http download trying resume after " + resumeSize + " of " + expected);
                requestBuilder.addHeader(
                        "Range", String.format(Locale.ENGLISH, "bytes=%d-", resumeSize));
            } else {
                resumeSize = 0;
            }
            final Request request = requestBuilder.build();
            mostRecentCall = client.newCall(request);
            // closing the response also closes its body stream
            try (final Response response = mostRecentCall.execute()) {
                throwOnInvalidCode(response);
                final String contentRange = response.header("Content-Range");
                final boolean serverResumed =
                        tryResume
                                && contentRange != null
                                && contentRange.startsWith("bytes " + resumeSize + "-");
                final InputStream inputStream = response.body().byteStream();
                final OutputStream outputStream;
                long transmitted = 0;
                if (tryResume && serverResumed) {
                    Log.d(Config.LOGTAG, "server resumed");
                    transmitted = file.getSize();
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    outputStream = AbstractConnectionManager.createOutputStream(file, true, false);
                } else {
                    final String contentLength = response.header("Content-Length");
                    // only logged; a malformed header must not fail the download
                    final Long parsed =
                            Strings.isNullOrEmpty(contentLength)
                                    ? null
                                    : Longs.tryParse(contentLength);
                    final long size = parsed == null ? 0 : parsed;
                    if (expected != size) {
                        Log.d(
                                Config.LOGTAG,
                                "content-length reported on GET ("
                                        + size
                                        + ") did not match Content-Length reported on HEAD ("
                                        + expected
                                        + ")");
                    }
                    file.getParentFile().mkdirs();
                    Log.d(Config.LOGTAG, "creating file: " + file.getAbsolutePath());
                    if (!file.exists() && !file.createNewFile()) {
                        throw new FileWriterException(file);
                    }
                    outputStream = AbstractConnectionManager.createOutputStream(file, false, false);
                }
                if (outputStream == null) {
                    throw new FileWriterException(file);
                }
                try (final OutputStream os = outputStream) {
                    int count;
                    final byte[] buffer = new byte[4096];
                    while ((count = inputStream.read(buffer)) != -1) {
                        transmitted += count;
                        try {
                            os.write(buffer, 0, count);
                        } catch (final IOException e) {
                            throw new FileWriterException(file);
                        }
                        if (transmitted > expected) {
                            throw new InvalidFileException(
                                    String.format("File exceeds expected size of %d", expected));
                        }
                        updateProgress(Math.round(((double) transmitted / expected) * 100));
                    }
                    os.flush();
                }
            }
        }

        private void updateImageBounds() {
            final boolean privateMessage = message.isPrivateMessage();
            message.setType(privateMessage ? Message.TYPE_PRIVATE_FILE : Message.TYPE_FILE);
            final String url;
            final String ref = mUrl.fragment();
            if (ref != null && AesGcmURL.IV_KEY.matcher(ref).matches()) {
                url = AesGcmURL.toAesGcmUrl(mUrl);
            } else {
                url = mUrl.toString();
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url);
            mXmppConnectionService.updateMessage(message);
        }
    }

    private static void throwOnInvalidCode(final Response response) throws IOException {
        final int code = response.code();
        if (code < 200 || code >= 300) {
            throw new IOException(String.format(Locale.ENGLISH, "HTTP Status code was %d", code));
        }
    }

    private static class InvalidFileException extends IOException {

        private InvalidFileException(final String message) {
            super(message);
        }
    }
}
