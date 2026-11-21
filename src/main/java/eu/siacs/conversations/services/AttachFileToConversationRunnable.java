package eu.siacs.conversations.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.TranscoderStrategies;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class AttachFileToConversationRunnable implements Runnable, TranscoderListener {

    private final XmppConnectionService mXmppConnectionService;
    private final Message message;
    private final Uri uri;
    private final String mimeType;
    private final String type;
    private final UiCallback<Message> callback;
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;

    /**
     * The transcoder writes by path, so it writes plaintext into the private, never backed up
     * temporary folder; the result is then encrypted into media and this copy deleted.
     */
    private java.io.File transcodeStaging = null;

    private void deleteTranscodeStaging() {
        if (transcodeStaging != null && transcodeStaging.exists() && !transcodeStaging.delete()) {
            Log.w(Config.LOGTAG, "could not delete transcoder output " + transcodeStaging);
        }
    }

    AttachFileToConversationRunnable(
            XmppConnectionService xmppConnectionService,
            Uri uri,
            String type,
            Message message,
            UiCallback<Message> callback) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.message = message;
        this.callback = callback;
        mimeType = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize =
                mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage =
                (mimeType != null && mimeType.startsWith("video/"))
                        && originalFileSize > autoAcceptFileSize
                        && !"uncompressed".equals(getVideoCompression());
    }

    boolean isVideoMessage() {
        return this.isVideoMessage;
    }

    private void processAsFile() {
        final String path = mXmppConnectionService.getFileBackend().getOriginalPath(uri);
        if ("https".equals(uri.getScheme())) {
            message.getFileParams().url = uri.toString();
            message.getFileParams().setMediaType(mimeType);
            final int encryption = message.getEncryption();
            mXmppConnectionService
                    .getHttpConnectionManager()
                    .createNewDownloadConnection(
                            message,
                            false,
                            file -> {
                                message.setEncryption(encryption);
                                // For end-to-end encrypted conversations a public https URL would
                                // leak the
                                // file contents to anyone with the link. Drop the public URL so the
                                // downloaded file is re-uploaded under aesgcm with a per-message
                                // key.
                                if (encryption == Message.ENCRYPTION_AXOLOTL
                                        || encryption == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                                    message.resetFileParams();
                                    message.setType(Message.TYPE_FILE);
                                    mXmppConnectionService
                                            .getFileBackend()
                                            .updateFileParams(message);
                                }
                                mXmppConnectionService.sendMessage(
                                        message, () -> callback.success(message));
                            });
            /*      // TODO for now we copy all files to private storage
            } else if (path != null && !FileBackend.isPathBlacklisted(path)) {
                message.setRelativeFilePath(path);
                mXmppConnectionService.getFileBackend().updateFileParams(message);
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    mXmppConnectionService.getPgpEngine().encrypt(message, callback);
                } else {
                    mXmppConnectionService.sendMessage(message, () -> callback.success(message));
                }
                 */
        } else {
            try {
                mXmppConnectionService
                        .getFileBackend()
                        .copyFileToPrivateStorage(message, uri, type);
                mXmppConnectionService.getFileBackend().updateFileParams(message);
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    final PgpEngine pgpEngine = mXmppConnectionService.getPgpEngine();
                    if (pgpEngine != null) {
                        pgpEngine.encrypt(message, callback);
                    } else if (callback != null) {
                        callback.error(R.string.unable_to_connect_to_keychain, null);
                    }
                } else {
                    mXmppConnectionService.sendMessage(message, () -> callback.success(message));
                }
            } catch (final FileBackend.FileCopyException e) {
                callback.error(e.getResId(), message);
            }
        }
    }

    private void fallbackToProcessAsFile() {
        final var file = mXmppConnectionService.getFileBackend().getFile(message);
        if (file.exists() && file.delete()) {
            Log.d(Config.LOGTAG, "deleted preexisting file " + file.getAbsolutePath());
        }
        XmppConnectionService.FILE_ATTACHMENT_EXECUTOR.execute(this::processAsFile);
    }

    /**
     * Transcoder source for {@code uri} that hides the recording location. The transcoder otherwise
     * copies the source's GPS position into the output file.
     */
    public static com.otaliastudios.transcoder.source.DataSource locationStrippedSource(
            final android.content.Context context, final Uri uri) {
        // also reads attachments and temp files that are encrypted at rest
        return eu.siacs.conversations.persistance.MediaFiles.transcoderSource(context, uri);
    }

    private void processAsVideo() throws FileNotFoundException {
        Log.d(Config.LOGTAG, "processing file as video");
        mXmppConnectionService.startOngoingVideoTranscodingForegroundNotification();
        mXmppConnectionService
                .getFileBackend()
                .setupRelativeFilePath(message, String.format("%s.%s", message.getUuid(), "mp4"));
        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message);
        if (Objects.requireNonNull(file.getParentFile()).mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory for video file");
        }

        final boolean highQuality = "720".equals(getVideoCompression());
        transcodeStaging =
                new java.io.File(
                        eu.siacs.conversations.persistance.MediaFiles.temporaryDirectory(
                                mXmppConnectionService),
                        message.getUuid() + ".mp4");

        final Future<Void> future;
        try {
            future =
                    Transcoder.into(transcodeStaging.getAbsolutePath())
                            .addDataSource(locationStrippedSource(mXmppConnectionService, uri))
                            .setVideoTrackStrategy(
                                    highQuality
                                            ? TranscoderStrategies.VIDEO_720P
                                            : TranscoderStrategies.VIDEO_360P)
                            .setAudioTrackStrategy(
                                    highQuality
                                            ? TranscoderStrategies.AUDIO_HQ
                                            : TranscoderStrategies.AUDIO_MQ)
                            .setListener(this)
                            .transcode();
        } catch (final RuntimeException e) {
            // transcode can already throw if there is an invalid file format or a platform bug
            mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
            fallbackToProcessAsFile();
            return;
        }
        try {
            future.get();
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
                fallbackToProcessAsFile();
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Handled by onTranscodeFiled()");
            }
        }
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService
                    .getNotificationService()
                    .updateFileAddingNotification(p, message);
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        final File file = mXmppConnectionService.getFileBackend().getFile(message);
        long convertedFileSize = transcodeStaging == null ? 0 : transcodeStaging.length();
        Log.d(
                Config.LOGTAG,
                "originalFileSize=" + originalFileSize + " convertedFileSize=" + convertedFileSize);
        if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
            Log.d(Config.LOGTAG, "original file size was smaller. processing as file");
            deleteTranscodeStaging();
            fallbackToProcessAsFile();
            return;
        }
        try {
            // encrypted at rest into media; the plain transcoder output is deleted
            eu.siacs.conversations.persistance.MediaCrypto.moveIntoStorage(
                    mXmppConnectionService, transcodeStaging, file);
            // the plain camera original is not needed any more
            mXmppConnectionService.getFileBackend().deleteIngestedSource(uri);
        } catch (final java.io.IOException e) {
            Log.w(Config.LOGTAG, "could not store transcoded video", e);
            deleteTranscodeStaging();
            fallbackToProcessAsFile();
            return;
        }
        mXmppConnectionService.getFileBackend().updateFileParams(message);
        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            mXmppConnectionService.getPgpEngine().encrypt(message, callback);
        } else {
            mXmppConnectionService.sendMessage(message, () -> callback.success(message));
        }
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        deleteTranscodeStaging();
        fallbackToProcessAsFile();
    }

    @Override
    public void onTranscodeFailed(@NonNull final Throwable exception) {
        mXmppConnectionService.stopOngoingVideoTranscodingForegroundNotification();
        Log.d(Config.LOGTAG, "video transcoding failed", exception);
        deleteTranscodeStaging();
        fallbackToProcessAsFile();
    }

    @Override
    public void run() {
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
            } catch (final FileNotFoundException e) {
                processAsFile();
            }
        } else {
            processAsFile();
        }
    }

    private String getVideoCompression() {
        return getVideoCompression(mXmppConnectionService);
    }

    public static String getVideoCompression(final Context context) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(
                "video_compression", context.getResources().getString(R.string.video_compression));
    }
}
