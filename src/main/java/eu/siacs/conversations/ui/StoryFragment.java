package eu.siacs.conversations.ui;

import android.content.Context;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.google.common.base.Strings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.MediaMetadataStripper;
import eu.siacs.conversations.utils.MimeUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class StoryFragment extends Fragment {

    private static final String ARG_URL = "url";
    private static final String ARG_MIME_TYPE = "mime_type";
    private static final String ARG_POSITION = "position";
    private static final long STORY_DURATION_MS = 6000;

    /** Animated images play one loop, but at least {@link #STORY_DURATION_MS} and at most this. */
    private static final long MAX_ANIMATED_STORY_DURATION_MS = 15000;

    private static final Executor ANIMATION_INSPECT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long LONG_PRESS_THRESHOLD_MS = 300;
    private static final long TICK_MS = 16;
    private static final int PROGRESS_MAX = 1000;

    private OnStoryInteractionListener mListener;
    private VideoView videoView;
    private ProgressBar loadingIndicator;
    private LinearLayout progressBarContainer;
    private int position;
    private boolean isVideo;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable touchPauseRunnable;

    // Playback state. Image stories are timed with a wall-clock ticker rather than an
    // ObjectAnimator: animators complete instantly when the system animator duration scale is 0
    // ("Remove animations", battery savers), which skipped every image story and closed the viewer.
    private boolean mediaReady = false;
    private boolean loadFailed = false;
    private boolean running = false;
    private boolean finished = false;
    private boolean pausedByTouch = false;
    private boolean pausedByDialog = false;
    private long elapsedMs = 0;
    private long resumedAt = 0;
    private long durationMs = STORY_DURATION_MS;

    // Animated GIF/WebP shown by an image story; started and stopped together with the timer.
    @Nullable private Animatable animation;
    private boolean rewindAnimation = false;
    private int loadGeneration = 0;

    private final Runnable ticker =
            new Runnable() {
                @Override
                public void run() {
                    if (!running) {
                        return;
                    }
                    if (isVideo) {
                        final VideoView video = videoView;
                        final int duration = video == null ? -1 : video.getDuration();
                        if (duration > 0) {
                            setOwnProgress(
                                    (int)
                                            (Math.min(
                                                            1f,
                                                            (float) video.getCurrentPosition()
                                                                    / duration)
                                                    * PROGRESS_MAX));
                        }
                    } else {
                        final long elapsed = currentElapsed();
                        if (elapsed >= durationMs) {
                            onPlaybackFinished();
                            return;
                        }
                        setOwnProgress((int) (elapsed * PROGRESS_MAX / durationMs));
                    }
                    handler.postDelayed(this, TICK_MS);
                }
            };

    public interface OnStoryInteractionListener {
        void onPreviousStory();

        void onNextStory();

        /**
         * The story at {@code position} played to its end. Ignored unless it is the current one.
         */
        void onStoryFinished(int position);

        /**
         * Whether the story at {@code position} is the visible one and the viewer is in the
         * foreground.
         */
        boolean isPlaybackAllowed(int position);

        void pauseStory();

        void resumeStory();
    }

    public static StoryFragment newInstance(String url, String mimeType, int position) {
        StoryFragment fragment = new StoryFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_MIME_TYPE, mimeType);
        args.putInt(ARG_POSITION, position);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnStoryInteractionListener) {
            mListener = (OnStoryInteractionListener) context;
        } else {
            throw new RuntimeException(
                    context.toString() + " must implement OnStoryInteractionListener");
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_story, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    cancelTouchPause();
                    touchPauseRunnable =
                            () -> {
                                touchPauseRunnable = null;
                                pausedByTouch = true;
                                updatePlayback();
                                if (mListener != null) {
                                    mListener.pauseStory();
                                }
                            };
                    handler.postDelayed(touchPauseRunnable, LONG_PRESS_THRESHOLD_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                    cancelTouchPause();
                    if (pausedByTouch) {
                        releaseTouchPause();
                    } else if (mListener != null) {
                        if (event.getX() < v.getWidth() / 2f) {
                            mListener.onPreviousStory();
                        } else {
                            mListener.onNextStory();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelTouchPause();
                    if (pausedByTouch) {
                        releaseTouchPause();
                    }
                    return true;
            }
            return false;
        });

        progressBarContainer = requireActivity().findViewById(R.id.progress_bar_container);
        loadingIndicator = view.findViewById(R.id.story_progress);
        videoView = view.findViewById(R.id.story_video_view);
        final Bundle args = getArguments();
        position = args == null ? 0 : args.getInt(ARG_POSITION, 0);
        loadStory();
    }

    /** The Glide model for {@code url}: through Tor/I2P if the story's account forces it. */
    private Object routed(final String url) {
        final android.app.Activity activity = getActivity();
        final android.content.Intent intent = activity == null ? null : activity.getIntent();
        if (intent == null) return url;
        return eu.siacs.conversations.http.RoutedUrl.of(
                url,
                intent.getBooleanExtra(StoryViewActivity.EXTRA_ROUTE_TOR, false),
                intent.getBooleanExtra(StoryViewActivity.EXTRA_ROUTE_I2P, false));
    }

    private void loadStory() {
        final View view = getView();
        final Bundle args = getArguments();
        if (!isAdded() || view == null || args == null) {
            return;
        }
        final ImageView imageView = view.findViewById(R.id.story_image_view);
        final String url = args.getString(ARG_URL);
        String mimeType = args.getString(ARG_MIME_TYPE);
        if (Strings.isNullOrEmpty(mimeType)) {
            // Other clients may omit the enclosure type; don't treat every such video as an image.
            mimeType =
                    MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(url));
        }
        isVideo = mimeType != null && mimeType.startsWith("video/");
        mediaReady = false;
        loadFailed = false;
        animation = null;
        durationMs = STORY_DURATION_MS;
        final int generation = ++loadGeneration;
        loadingIndicator.setVisibility(View.VISIBLE);

        if (isVideo) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            videoView.setOnCompletionListener(mp -> onPlaybackFinished());
            videoView.setOnErrorListener((mp, what, extra) -> {
                // Returning true suppresses VideoView's own error dialog, whose dismissal would
                // invoke the completion listener and skip (or close) the story.
                onLoadFailed();
                return true;
            });
            videoView.setOnPreparedListener(mp -> {
                // Also called again when the surface is re-created (e.g. page re-attached).
                mp.setLooping(false);
                loadingIndicator.setVisibility(View.GONE);
                mediaReady = true;
                updatePlayback();
            });
            Glide.with(this)
                    .asFile()
                    .load(routed(url))
                    .into(
                            new CustomTarget<File>() {
                                @Override
                                public void onResourceReady(
                                        @NonNull File resource,
                                        @Nullable Transition<? super File> transition) {
                                    if (!isAdded() || videoView == null) return;
                                    videoView.setVideoURI(Uri.fromFile(resource));
                                }

                                @Override
                                public void onLoadCleared(@Nullable Drawable placeholder) {
                                    // Do nothing
                                }

                                @Override
                                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                    StoryFragment.this.onLoadFailed();
                                }
                            });
        } else {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(routed(url))
                    .listener(
                            new RequestListener<Drawable>() {
                                @Override
                                public boolean onLoadFailed(
                                        @Nullable GlideException e,
                                        Object model,
                                        Target<Drawable> target,
                                        boolean isFirstResource) {
                                    StoryFragment.this.onLoadFailed();
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(
                                        Drawable resource,
                                        Object model,
                                        Target<Drawable> target,
                                        DataSource dataSource,
                                        boolean isFirstResource) {
                                    if (!isAdded() || generation != loadGeneration) {
                                        return false;
                                    }
                                    if (resource instanceof Animatable) {
                                        onAnimationReady(resource, url, generation);
                                    } else {
                                        onImageReady();
                                    }
                                    return false;
                                }
                            })
                    .into(imageView);
        }
    }

    private void onImageReady() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.GONE);
        }
        mediaReady = true;
        // Posted so the image is on screen before the timer starts.
        handler.post(this::updatePlayback);
    }

    /**
     * An animated GIF/WebP: loop it and time the story to one loop (clamped). The timer only starts
     * once the length is known, so a long animation isn't cut off at the default duration.
     */
    private void onAnimationReady(final Drawable resource, final String url, final int generation) {
        animation = (Animatable) resource;
        rewindAnimation = false;
        if (resource instanceof GifDrawable) {
            ((GifDrawable) resource).setLoopCount(GifDrawable.LOOP_FOREVER);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && resource instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) resource)
                    .setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        }
        // Glide starts animations itself once they are set; hold it until playback is allowed.
        handler.post(this::syncAnimation);
        // Served from Glide's disk cache, which already holds the downloaded file.
        Glide.with(this)
                .asFile()
                .load(routed(url))
                .into(
                        new CustomTarget<File>() {
                            @Override
                            public void onResourceReady(
                                    @NonNull File file,
                                    @Nullable Transition<? super File> transition) {
                                ANIMATION_INSPECT_EXECUTOR.execute(() -> {
                                    MediaMetadataStripper.Animation info;
                                    try {
                                        info =
                                                MediaMetadataStripper.inspectAnimation(
                                                        new FileInputStream(file));
                                    } catch (final IOException e) {
                                        info = null;
                                    }
                                    final MediaMetadataStripper.Animation result = info;
                                    handler.post(() -> onAnimationDuration(result, generation));
                                });
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                                // Do nothing
                            }

                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                onAnimationDuration(null, generation);
                            }
                        });
    }

    private void onAnimationDuration(
            @Nullable final MediaMetadataStripper.Animation info, final int generation) {
        if (!isAdded() || getView() == null || generation != loadGeneration || mediaReady) {
            return;
        }
        if (info != null) {
            durationMs =
                    Math.max(
                            STORY_DURATION_MS,
                            Math.min(MAX_ANIMATED_STORY_DURATION_MS, info.loopDurationMs));
        }
        onImageReady();
    }

    /** Runs the animation exactly while the story timer runs. */
    private void syncAnimation() {
        final Animatable a = animation;
        if (a == null) {
            return;
        }
        if (running) {
            if (rewindAnimation) {
                rewindAnimation = false;
                if (a instanceof GifDrawable) {
                    a.stop();
                    ((GifDrawable) a).startFromFirstFrame();
                    return;
                }
                // AnimatedImageDrawable starts over on start() after stop().
                a.stop();
            }
            if (!a.isRunning()) {
                a.start();
            }
        } else if (a.isRunning()) {
            a.stop();
        }
    }

    private void onLoadFailed() {
        if (!isAdded()) {
            return;
        }
        mediaReady = false;
        loadFailed = true;
        stopRunning();
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.GONE);
        }
        // Only the visible story reports the failure; the user can tap on to the next one.
        if (mListener != null && mListener.isPlaybackAllowed(position)) {
            Toast.makeText(
                            getContext(),
                            R.string.download_failed_file_not_found,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    /** Starts or stops playback so that it runs exactly when it is allowed to. */
    void updatePlayback() {
        final boolean shouldRun =
                isAdded()
                        && getView() != null
                        && mListener != null
                        && mediaReady
                        && !finished
                        && !pausedByTouch
                        && !pausedByDialog
                        && mListener.isPlaybackAllowed(position);
        if (shouldRun && !running) {
            running = true;
            resumedAt = SystemClock.uptimeMillis();
            if (isVideo && videoView != null) {
                videoView.start();
            }
            syncAnimation();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } else if (!shouldRun && running) {
            stopRunning();
        }
    }

    private void stopRunning() {
        if (!running) {
            return;
        }
        if (!isVideo) {
            elapsedMs += SystemClock.uptimeMillis() - resumedAt;
        }
        running = false;
        handler.removeCallbacks(ticker);
        if (isVideo && videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
        syncAnimation();
    }

    private long currentElapsed() {
        return running ? elapsedMs + (SystemClock.uptimeMillis() - resumedAt) : elapsedMs;
    }

    private void onPlaybackFinished() {
        if (finished) {
            return;
        }
        stopRunning();
        finished = true;
        setOwnProgress(PROGRESS_MAX);
        if (mListener != null) {
            mListener.onStoryFinished(position);
        }
    }

    private void setOwnProgress(int progress) {
        if (progressBarContainer != null && position < progressBarContainer.getChildCount()) {
            final View child = progressBarContainer.getChildAt(position);
            if (child instanceof ProgressBar) {
                ((ProgressBar) child).setProgress(progress);
            }
        }
    }

    private void resetPlayback() {
        cancelTouchPause();
        stopRunning();
        pausedByTouch = false;
        pausedByDialog = false;
        finished = false;
        elapsedMs = 0;
        rewindAnimation = animation != null;
        if (isVideo && videoView != null) {
            videoView.seekTo(0);
        }
    }

    private void cancelTouchPause() {
        if (touchPauseRunnable != null) {
            handler.removeCallbacks(touchPauseRunnable);
            touchPauseRunnable = null;
        }
    }

    private void releaseTouchPause() {
        pausedByTouch = false;
        updatePlayback();
        if (mListener != null) {
            mListener.resumeStory();
        }
    }

    /** The page was left: stop and rewind so it starts from the beginning when shown again. */
    public void stopStory() {
        resetPlayback();
    }

    /** The page was selected: play from the beginning. */
    public void restartStory() {
        resetPlayback();
        setOwnProgress(0);
        if (loadFailed) {
            loadStory();
        } else {
            updatePlayback();
        }
    }

    /** Pause without touching the system UI (e.g. while a dialog is shown). */
    public void pausePlayback() {
        pausedByDialog = true;
        updatePlayback();
    }

    public void resumePlayback() {
        pausedByDialog = false;
        updatePlayback();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Glide restarts animations when the page comes back; keep them in step with the timer.
        handler.post(this::syncAnimation);
    }

    @Override
    public void onDestroyView() {
        cancelTouchPause();
        stopRunning();
        handler.removeCallbacksAndMessages(null);
        animation = null;
        loadGeneration++;
        videoView = null;
        loadingIndicator = null;
        progressBarContainer = null;
        mediaReady = false;
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }
}
