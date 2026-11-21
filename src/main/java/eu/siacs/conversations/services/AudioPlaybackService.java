package eu.siacs.conversations.services;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.service.BackgroundAudioController;

/**
 * Background playback engine for audio (voice) messages. Built on media3, so the system media
 * notification, lock-screen / Bluetooth / Android-Auto transport controls and audio focus are
 * managed automatically while playback is ongoing.
 *
 * <p>The notification shows play/pause (central slot, auto) plus a stop (✕) button, and tapping it
 * opens the app.
 *
 * <p>Privacy: the per-track {@code MediaMetadata} that drives the notification is set generically
 * ("Audio message", no artist/artwork/body) by {@link
 * eu.siacs.conversations.ui.service.BackgroundAudioController}; nothing sensitive is exposed here.
 * The service is not exported and only reads the already-decrypted local cache file the in-app
 * player has always used.
 */
// Deliberately uses media3 @UnstableApi (CommandButton slots, setMediaButtonPreferences,
// DEFAULT_SESSION_COMMANDS): re-check this file's API usage on media3 upgrades.
@OptIn(markerClass = UnstableApi.class)
public class AudioPlaybackService extends MediaSessionService {

    /**
     * Custom session command: route output to the earpiece (raise-to-ear) or back to the speaker.
     */
    public static final String CMD_SET_EARPIECE = "eu.siacs.conversations.SET_EARPIECE";

    public static final String EXTRA_EARPIECE = "earpiece";

    /** Custom session command: stop playback and dismiss the media notification. */
    public static final String CMD_STOP = "eu.siacs.conversations.STOP_PLAYBACK";

    private static final SessionCommand STOP_COMMAND = new SessionCommand(CMD_STOP, Bundle.EMPTY);

    /**
     * Custom session command: change the playback speed on the session's own player. Unlike
     * {@code MediaController.setPlaybackSpeed}, which masks the new speed against a position
     * snapshot that can be up to 3 s old (making the position estimate jump backwards when slowing
     * down), the session then publishes the new speed together with a fresh position.
     */
    public static final String CMD_SET_SPEED = "eu.siacs.conversations.SET_PLAYBACK_SPEED";

    public static final String EXTRA_SPEED = "speed";

    private static final float MIN_SPEED = 0.25f;
    private static final float MAX_SPEED = 4f;

    private static final AudioAttributes SPEAKER_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build();

    private static final AudioAttributes EARPIECE_ATTRIBUTES =
            new AudioAttributes.Builder()
                    .setUsage(C.USAGE_VOICE_COMMUNICATION)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build();

    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        final ExoPlayer player =
                new ExoPlayer.Builder(this)
                        // voice messages may be encrypted at rest
                        .setMediaSourceFactory(
                                eu.siacs.conversations.persistance.EncryptedMediaDataSource
                                        .mediaSourceFactory(this))
                        .setAudioAttributes(SPEAKER_ATTRIBUTES, /* handleAudioFocus= */ true)
                        .setHandleAudioBecomingNoisy(true)
                        .build();
        final CommandButton stopButton =
                new CommandButton.Builder(CommandButton.ICON_STOP)
                        .setSessionCommand(STOP_COMMAND)
                        .setDisplayName(getString(R.string.stop_playback))
                        .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
                        .build();
        this.mediaSession =
                new MediaSession.Builder(this, player)
                        .setCallback(new Callback())
                        .setMediaButtonPreferences(ImmutableList.of(stopButton))
                        .setSessionActivity(buildSessionActivityIntent())
                        .build();
        // Keep the "tap notification" target pointed at the currently-playing message.
        player.addListener(
                new Player.Listener() {
                    @Override
                    public void onMediaItemTransition(
                            @Nullable final MediaItem mediaItem, final int reason) {
                        if (mediaSession != null) {
                            mediaSession.setSessionActivity(buildSessionActivityIntent());
                        }
                    }
                });
    }

    /**
     * Tapping the media notification opens the app and jumps to the playing audio message. The
     * conversation/message UUIDs are read from the in-process {@link BackgroundAudioController} and
     * placed only in this PendingIntent (which targets our own, non-exported activity) — they are
     * never written to the media metadata, so nothing is exposed to other apps or media displays.
     */
    private PendingIntent buildSessionActivityIntent() {
        final Intent intent = new Intent(this, ConversationsActivity.class);
        final Message message = BackgroundAudioController.getInstance(this).getCurrentMessage();
        if (message != null) {
            final Conversational conversation = message.getConversation();
            if (conversation != null) {
                intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
                intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
                intent.putExtra(ConversationsActivity.EXTRA_MESSAGE_UUID, message.getUuid());
            }
        }
        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        final Player player = mediaSession.getPlayer();
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    private void applyEarpiece(final boolean earpiece) {
        final Player player = mediaSession.getPlayer();
        if (player instanceof ExoPlayer) {
            // Do not re-request audio focus for the routing switch; just change the output route.
            ((ExoPlayer) player)
                    .setAudioAttributes(
                            earpiece ? EARPIECE_ATTRIBUTES : SPEAKER_ATTRIBUTES,
                            /* handleAudioFocus= */ false);
        }
    }

    private final class Callback implements MediaSession.Callback {

        @Override
        public MediaSession.ConnectionResult onConnect(
                MediaSession session, MediaSession.ControllerInfo controller) {
            final SessionCommands.Builder commands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            // the system media controls (other uid) use this for the stop button
                            .add(STOP_COMMAND);
            // The service isn't exported, but the platform media session is reachable by SystemUI,
            // Bluetooth/Auto and notification-listener apps. Earpiece routing and the speed command
            // are only for our own BackgroundAudioController; other controllers keep the standard
            // player commands (play/pause/seek/speed, validated by media3).
            if (controller.getUid() == Process.myUid()) {
                commands.add(new SessionCommand(CMD_SET_EARPIECE, Bundle.EMPTY))
                        .add(new SessionCommand(CMD_SET_SPEED, Bundle.EMPTY));
            }
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands.build())
                    .build();
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                MediaSession session,
                MediaSession.ControllerInfo controller,
                SessionCommand customCommand,
                Bundle args) {
            if (CMD_SET_EARPIECE.equals(customCommand.customAction)) {
                final boolean earpiece;
                try {
                    earpiece = args.getBoolean(EXTRA_EARPIECE, false);
                } catch (final RuntimeException e) {
                    // defense in depth: a malformed Bundle must not crash the app
                    return Futures.immediateFuture(
                            new SessionResult(SessionError.ERROR_BAD_VALUE));
                }
                applyEarpiece(earpiece);
            } else if (CMD_SET_SPEED.equals(customCommand.customAction)) {
                // never pass an unchecked value to ExoPlayer, which throws on speed <= 0, and
                // never let a malformed Bundle crash the app
                float speed;
                try {
                    speed = args.getFloat(EXTRA_SPEED, Float.NaN);
                } catch (final RuntimeException e) {
                    speed = Float.NaN;
                }
                if (Float.isNaN(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
                    return Futures.immediateFuture(
                            new SessionResult(SessionError.ERROR_BAD_VALUE));
                }
                mediaSession.getPlayer().setPlaybackSpeed(speed);
            } else if (CMD_STOP.equals(customCommand.customAction)) {
                final Player player = mediaSession.getPlayer();
                player.stop();
                player.clearMediaItems();
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
    }
}
