package eu.siacs.conversations.ui.service;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.ui.ConversationsActivity;

/**
 * Binds the now-playing bar (view_audio_miniplayer.xml) to the app-scoped {@link
 * BackgroundAudioController}. A voice message keeps the private title off the bar. An episode uses
 * its public name. The bar can pause, stop, and skip 15 seconds.
 */
public class AudioMiniPlayer implements BackgroundAudioController.Listener {

    private static final long SKIP_MS = 15_000L;

    private final Activity activity;
    private final BackgroundAudioController controller;
    private final View root;
    private final MaterialButton playPause;
    private final MaterialButton speed;
    private final TextView title;
    private final TextView subtitle;
    private final LinearProgressIndicator progress;

    public AudioMiniPlayer(final Activity activity, final View root) {
        this.activity = activity;
        this.root = root;
        this.controller = BackgroundAudioController.getInstance(activity);
        this.playPause = root.findViewById(R.id.miniplayer_play_pause);
        this.title = root.findViewById(R.id.miniplayer_title);
        this.subtitle = root.findViewById(R.id.miniplayer_subtitle);
        this.progress = root.findViewById(R.id.miniplayer_progress);
        final MaterialButton close = root.findViewById(R.id.miniplayer_close);
        this.speed = root.findViewById(R.id.miniplayer_speed);
        root.findViewById(R.id.miniplayer_back)
                .setOnClickListener(v -> controller.seekBy(-SKIP_MS));
        this.playPause.setOnClickListener(v -> controller.toggleCurrentPlayPause());
        root.findViewById(R.id.miniplayer_forward)
                .setOnClickListener(v -> controller.seekBy(SKIP_MS));
        this.speed.setOnClickListener(v -> controller.cyclePlaybackSpeed());
        close.setOnClickListener(v -> controller.stop());
        this.root.setOnClickListener(v -> jumpToMessage());
        update();
    }

    /** Hook from the host activity's onResume — start observing and allow earpiece routing. */
    public void onResume() {
        controller.addListener(this);
        controller.setForeground(true);
        update();
    }

    /** Hook from the host activity's onPause — stop earpiece routing and observing. */
    public void onPause() {
        controller.setForeground(false);
        controller.removeListener(this);
    }

    private void jumpToMessage() {
        final Message message = controller.getCurrentMessage();
        if (message == null || !(activity instanceof ConversationsActivity)) {
            return;
        }
        final Conversational conversation = message.getConversation();
        if (conversation instanceof Conversation) {
            ((ConversationsActivity) activity)
                    .openConversationAtMessage((Conversation) conversation, message.getUuid());
        }
    }

    @Override
    public void onAudioStateChanged() {
        update();
    }

    @Override
    public void onAudioProgress(final int currentMs, final int durationMs, final float fraction) {
        progress.setProgress(durationMs > 0 ? Math.round(fraction * 100) : 0);
    }

    private void update() {
        final Message message = controller.getCurrentMessage();
        final String playingTitle = controller.getCurrentTitle();
        if (message == null && (playingTitle == null || playingTitle.isEmpty())) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        final boolean playing = controller.isPlaying();
        playPause.setIconResource(
                playing ? R.drawable.rounded_pause_36 : R.drawable.rounded_play_arrow_36);
        playPause.setContentDescription(
                activity.getString(playing ? R.string.pause_audio : R.string.play_audio));
        AudioPlayer.applySpeed(speed, controller.getPlaybackSpeed());
        if (message == null) {
            title.setText(playingTitle);
            subtitle.setVisibility(View.GONE);
        } else {
            title.setText(R.string.voice_message);
            if (playingTitle != null && !playingTitle.isEmpty()) {
                subtitle.setText(playingTitle);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }
        }
        final int duration = controller.getDurationMs();
        final int current = controller.getCurrentPositionMs();
        progress.setProgress(duration > 0 ? Math.min(100, (int) (current * 100L / duration)) : 0);
    }
}
