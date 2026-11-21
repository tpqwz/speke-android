package eu.siacs.conversations.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.MediaCrypto;
import eu.siacs.conversations.persistance.MediaFiles;
import eu.siacs.conversations.utils.CameraFrame;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The in-app camera (like Signal's): a photo is captured into memory and stored as an encrypted
 * temporary file, so no plaintext copy of it is ever written. A video has to be recorded into a
 * file; it goes into the private, never backed up temporary folder and is deleted once it was
 * stored (encrypted) as an attachment.
 *
 * <p>Pinch to zoom, tap to focus, and a choice of format (full screen, 4:3, 16:9, 1:1).
 *
 * <p>Result: {@code RESULT_OK} with the file as data; {@link #RESULT_FALLBACK} if the camera
 * can't be used here, so the caller can use the system camera app instead.
 */
public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "video";
    public static final int RESULT_FALLBACK = RESULT_FIRST_USER;

    private static final String PREFERENCE_FORMAT = "in_app_camera_format";
    private static final String PREFERENCE_FORMAT_VIDEO = "in_app_camera_format_video";

    /** Picture formats; {@link #ratio} is long side / short side (0 = the screen's). */
    private enum Format {
        FULL(0, R.string.camera_format_full),
        RATIO_4_3(4.0 / 3, R.string.camera_format_4_3),
        RATIO_16_9(16.0 / 9, R.string.camera_format_16_9),
        RATIO_1_1(1.0, R.string.camera_format_1_1);

        final double ratio;
        final int label;

        Format(final double ratio, final int label) {
            this.ratio = ratio;
            this.label = label;
        }

        /** The sensor ratio to capture with; 1:1 is cropped from 4:3, full from 16:9. */
        int sensorRatio() {
            return this == RATIO_4_3 || this == RATIO_1_1
                    ? AspectRatio.RATIO_4_3
                    : AspectRatio.RATIO_16_9;
        }
    }

    private final ExecutorService storeExecutor = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private PreviewView previewView;
    private FloatingActionButton shutter;
    private ImageButton flash;
    private Button formatButton;
    private TextView zoomLabel;
    private boolean video;
    private boolean back = true;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private Format format = Format.FULL;
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File videoFile;
    private OrientationEventListener orientationListener;
    private boolean busy = false;

    private final ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (hasCameraPermission()) {
                            startCamera();
                        } else {
                            setResult(RESULT_CANCELED);
                            finish();
                        }
                    });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        format = loadFormat();
        setContentView(buildLayout());
        if (hasCameraPermission() && (!video || hasAudioPermission())) {
            startCamera();
        } else {
            permissions.launch(
                    video
                            ? new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}
                            : new String[] {Manifest.permission.CAMERA});
        }
        orientationListener =
                new OrientationEventListener(this) {
                    @Override
                    public void onOrientationChanged(final int orientation) {
                        if (orientation == ORIENTATION_UNKNOWN) {
                            return;
                        }
                        final int rotation;
                        if (orientation >= 45 && orientation < 135) {
                            rotation = Surface.ROTATION_270;
                        } else if (orientation >= 135 && orientation < 225) {
                            rotation = Surface.ROTATION_180;
                        } else if (orientation >= 225 && orientation < 315) {
                            rotation = Surface.ROTATION_90;
                        } else {
                            rotation = Surface.ROTATION_0;
                        }
                        if (imageCapture != null) {
                            imageCapture.setTargetRotation(rotation);
                        }
                        if (videoCapture != null && recording == null) {
                            videoCapture.setTargetRotation(rotation);
                        }
                    }
                };
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
    }

    @Override
    protected void onStop() {
        orientationListener.disable();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (recording != null) {
            recording.stop();
            recording = null;
        }
        storeExecutor.shutdown();
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // --- format ---

    private Format loadFormat() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Format stored;
        try {
            stored =
                    Format.valueOf(
                            preferences.getString(
                                    video ? PREFERENCE_FORMAT_VIDEO : PREFERENCE_FORMAT,
                                    Format.FULL.name()));
        } catch (final IllegalArgumentException e) {
            stored = Format.FULL;
        }
        return video ? videoFormat(stored) : stored;
    }

    /** Video can't be cropped without re-encoding: only the native sensor ratios. */
    private static Format videoFormat(final Format format) {
        return format == Format.RATIO_4_3 ? Format.RATIO_4_3 : Format.RATIO_16_9;
    }

    private void cycleFormat() {
        if (recording != null || busy) {
            return;
        }
        if (video) {
            format = format == Format.RATIO_4_3 ? Format.RATIO_16_9 : Format.RATIO_4_3;
        } else {
            final Format[] all = Format.values();
            format = all[(format.ordinal() + 1) % all.length];
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(video ? PREFERENCE_FORMAT_VIDEO : PREFERENCE_FORMAT, format.name())
                .apply();
        formatButton.setText(format.label);
        layoutPreview();
        bindUseCases();
    }

    /** The long/short ratio of the screen area the preview fills. */
    private double screenRatio() {
        final int width = root.getWidth();
        final int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            return 16.0 / 9;
        }
        return (double) Math.max(width, height) / Math.min(width, height);
    }

    /** Shows exactly the frame that will be captured: centred in the format's ratio. */
    private void layoutPreview() {
        final int width = root.getWidth();
        final int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final FrameLayout.LayoutParams params;
        if (format == Format.FULL) {
            params =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            final int[] frame = CameraFrame.centerCropRect(width, height, format.ratio);
            params = new FrameLayout.LayoutParams(frame[2], frame[3], Gravity.CENTER);
        }
        previewView.setLayoutParams(params);
    }

    // --- layout ---

    private View buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(
                previewView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        root.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                        v.post(this::layoutPreview);
                    }
                });
        setupGestures();

        final int padding = dp(16);

        // top: close, format
        final LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x66000000);
        final ImageButton close = iconButton(R.drawable.rounded_close_24, R.string.cancel);
        close.setOnClickListener(
                v -> {
                    setResult(RESULT_CANCELED);
                    finish();
                });
        topBar.addView(close);
        topBar.addView(new Space(this), new LinearLayout.LayoutParams(0, 0, 1f));
        formatButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        formatButton.setTextColor(Color.WHITE);
        formatButton.setAllCaps(false);
        formatButton.setText(format.label);
        formatButton.setContentDescription(getString(R.string.camera_format));
        formatButton.setOnClickListener(v -> cycleFormat());
        topBar.addView(formatButton);
        root.addView(
                topBar,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP));
        applyInsets(topBar, padding / 2, true);

        // bottom: zoom label, then switch camera / shutter / flash
        final LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.setBackgroundColor(0x66000000);

        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        zoomLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        zoomLabel.setText(formatZoom(1f));
        zoomLabel.setContentDescription(getString(R.string.camera_zoom_reset));
        zoomLabel.setOnClickListener(
                v -> {
                    if (camera != null) {
                        camera.getCameraControl().setZoomRatio(1f);
                    }
                });
        bottomBar.addView(zoomLabel);

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        final ImageButton flip =
                iconButton(R.drawable.ic_flip_camera_android_24dp, R.string.switch_camera);
        flip.setOnClickListener(
                v -> {
                    if (recording == null && !busy) {
                        back = !back;
                        bindUseCases();
                    }
                });
        buttons.addView(flip, weighted());

        shutter = new FloatingActionButton(this);
        shutter.setImageResource(video ? R.drawable.ic_videocam_24dp : R.drawable.ic_camera_alt_24dp);
        shutter.setContentDescription(getString(video ? R.string.record_video : R.string.take_photo));
        shutter.setOnClickListener(v -> onShutter());
        // the button keeps its own (square) size: centre it in its third of the row
        final FrameLayout shutterSlot = new FrameLayout(this);
        shutterSlot.addView(
                shutter,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
        buttons.addView(shutterSlot, weighted());

        if (video) {
            // keeps the shutter centred; a plain View would take the whole height
            buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 0, 1f));
        } else {
            flash = iconButton(R.drawable.ic_flash_off_24dp, R.string.flash);
            flash.setOnClickListener(v -> toggleFlash());
            buttons.addView(flash, weighted());
        }
        bottomBar.addView(
                buttons,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(
                bottomBar,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM));
        applyInsets(bottomBar, padding, false);
        return root;
    }

    /**
     * Keeps the controls clear of the status bar, the navigation bar (three-button navigation
     * too) and display cutouts; the preview itself stays edge to edge.
     */
    private static void applyInsets(final View bar, final int basePadding, final boolean top) {
        ViewCompat.setOnApplyWindowInsetsListener(
                bar,
                (v, windowInsets) -> {
                    final Insets insets =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(
                            basePadding + insets.left,
                            top ? basePadding + insets.top : basePadding,
                            basePadding + insets.right,
                            top ? basePadding : basePadding + insets.bottom);
                    return windowInsets;
                });
        ViewCompat.requestApplyInsets(bar);
    }

    private int dp(final int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private ImageButton iconButton(final int icon, final int description) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);
        button.setBackground(null);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(getString(description));
        return button;
    }

    // --- zoom and focus ---

    @SuppressLint("ClickableViewAccessibility") // gestures only add zoom and focus
    private void setupGestures() {
        final ScaleGestureDetector scale =
                new ScaleGestureDetector(
                        this,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScale(@NonNull final ScaleGestureDetector detector) {
                                zoomBy(detector.getScaleFactor());
                                return true;
                            }
                        });
        final GestureDetector tap =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapUp(@NonNull final MotionEvent event) {
                                focusAt(event.getX(), event.getY());
                                return true;
                            }
                        });
        previewView.setOnTouchListener(
                (v, event) -> {
                    scale.onTouchEvent(event);
                    if (!scale.isInProgress()) {
                        tap.onTouchEvent(event);
                    }
                    return true;
                });
    }

    private void zoomBy(final float factor) {
        if (camera == null) {
            return;
        }
        final ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) {
            return;
        }
        final float ratio =
                Math.max(
                        state.getMinZoomRatio(),
                        Math.min(state.getMaxZoomRatio(), state.getZoomRatio() * factor));
        camera.getCameraControl().setZoomRatio(ratio);
    }

    private void focusAt(final float x, final float y) {
        if (camera == null) {
            return;
        }
        final FocusMeteringAction action =
                new FocusMeteringAction.Builder(
                                previewView.getMeteringPointFactory().createPoint(x, y))
                        .build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    private static String formatZoom(final float ratio) {
        return String.format(Locale.US, "%.1f×", ratio);
    }

    // --- camera ---

    private void toggleFlash() {
        flashMode =
                flashMode == ImageCapture.FLASH_MODE_OFF
                        ? ImageCapture.FLASH_MODE_AUTO
                        : flashMode == ImageCapture.FLASH_MODE_AUTO
                                ? ImageCapture.FLASH_MODE_ON
                                : ImageCapture.FLASH_MODE_OFF;
        flash.setImageResource(
                flashMode == ImageCapture.FLASH_MODE_OFF
                        ? R.drawable.ic_flash_off_24dp
                        : flashMode == ImageCapture.FLASH_MODE_AUTO
                                ? R.drawable.ic_flash_auto_24dp
                                : R.drawable.ic_flash_on_24dp);
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(
                () -> {
                    try {
                        provider = future.get();
                        bindUseCases();
                    } catch (final Exception e) {
                        fallback(e);
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private ResolutionSelector resolutionSelector() {
        return resolutionSelector(false);
    }

    /**
     * The format's sensor ratio; photos are also capped at about 12 MP: the editor and sending
     * never use more than 4096 px, and a 50+ MP frame would not fit into memory to be cropped
     * and re-encoded.
     */
    private ResolutionSelector resolutionSelector(final boolean photo) {
        final boolean fourToThree = format.sensorRatio() == AspectRatio.RATIO_4_3;
        final ResolutionSelector.Builder builder =
                new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                fourToThree
                                        ? AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                        : AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY);
        if (photo) {
            builder.setResolutionStrategy(
                    new ResolutionStrategy(
                            fourToThree ? new Size(4032, 3024) : new Size(4032, 2268),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER));
        }
        return builder.build();
    }

    private void bindUseCases() {
        if (provider == null) {
            return;
        }
        try {
            if (camera != null) {
                camera.getCameraInfo().getZoomState().removeObservers(this);
            }
            provider.unbindAll();
            final CameraSelector selector =
                    back ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
            final Preview preview =
                    new Preview.Builder().setResolutionSelector(resolutionSelector()).build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
            if (video) {
                final Recorder recorder =
                        new Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(Quality.HD))
                                .setAspectRatio(format.sensorRatio())
                                .build();
                videoCapture = VideoCapture.withOutput(recorder);
                camera = provider.bindToLifecycle(this, selector, preview, videoCapture);
            } else {
                imageCapture =
                        new ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setFlashMode(flashMode)
                                .setResolutionSelector(resolutionSelector(true))
                                .build();
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
            }
            camera.getCameraInfo()
                    .getZoomState()
                    .observe(this, state -> zoomLabel.setText(formatZoom(state.getZoomRatio())));
        } catch (final RuntimeException e) {
            if (!back) {
                // no front camera: go back to the rear one
                back = true;
                bindUseCases();
                return;
            }
            fallback(e);
        }
    }

    private void fallback(final Exception e) {
        Log.w(Config.LOGTAG, "in-app camera unavailable, using the system camera", e);
        setResult(RESULT_FALLBACK);
        finish();
    }

    private void onShutter() {
        if (busy) {
            return;
        }
        if (video) {
            toggleRecording();
        } else {
            takePhoto();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) {
            return;
        }
        busy = true;
        shutter.setEnabled(false);
        // the frame the user saw: full screen uses the screen's ratio
        final double ratio = format == Format.FULL ? screenRatio() : format.ratio;
        imageCapture.takePicture(
                storeExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull final ImageProxy image) {
                        final byte[] jpeg;
                        final int rotation;
                        try {
                            final ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            jpeg = new byte[buffer.remaining()];
                            buffer.get(jpeg);
                            rotation = image.getImageInfo().getRotationDegrees();
                        } finally {
                            image.close();
                        }
                        storePhoto(jpeg, rotation, ratio);
                    }

                    @Override
                    public void onError(@NonNull final ImageCaptureException exception) {
                        runOnUiThread(() -> failed(exception));
                    }
                });
    }

    /**
     * Rotates upright, crops to the chosen format and re-encodes in memory: no camera metadata
     * (EXIF, device data) survives, and the only file written is the encrypted one.
     */
    private void storePhoto(final byte[] jpeg, final int rotation, final double ratio) {
        try {
            final Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (source == null) {
                throw new IllegalStateException("could not decode the captured photo");
            }
            // crop and rotate in one step (one copy less in memory): the crop is centred and
            // keeps the orientation, so cropping before rotating gives the same frame
            final int[] crop =
                    CameraFrame.centerCropRect(source.getWidth(), source.getHeight(), ratio);
            final Bitmap bitmap;
            if (rotation == 0 && crop[2] == source.getWidth() && crop[3] == source.getHeight()) {
                bitmap = source;
            } else {
                final Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap =
                        replace(
                                source,
                                Bitmap.createBitmap(
                                        source, crop[0], crop[1], crop[2], crop[3], matrix, true));
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            bitmap.recycle();
            final File file =
                    new File(
                            MediaFiles.temporaryDirectory(this),
                            "PHOTO_" + UUID.randomUUID() + ".jpg");
            MediaCrypto.storeTemporary(this, new ByteArrayInputStream(out.toByteArray()), file);
            runOnUiThread(() -> finishWith(file));
        } catch (final Exception | OutOfMemoryError e) {
            runOnUiThread(() -> failed(e));
        }
    }

    private static Bitmap replace(final Bitmap old, final Bitmap replacement) {
        if (replacement != old) {
            old.recycle();
        }
        return replacement;
    }

    @SuppressLint("MissingPermission") // checked before the camera is started
    private void toggleRecording() {
        if (videoCapture == null) {
            return;
        }
        if (recording != null) {
            busy = true;
            shutter.setEnabled(false);
            recording.stop();
            return;
        }
        videoFile =
                new File(MediaFiles.temporaryDirectory(this), "VIDEO_" + UUID.randomUUID() + ".mp4");
        PendingRecording pending =
                videoCapture
                        .getOutput()
                        .prepareRecording(this, new FileOutputOptions.Builder(videoFile).build());
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled();
        }
        shutter.setImageResource(R.drawable.ic_live_stopped);
        formatButton.setEnabled(false);
        recording =
                pending.start(
                        ContextCompat.getMainExecutor(this),
                        event -> {
                            if (event instanceof VideoRecordEvent.Finalize finalize) {
                                recording = null;
                                if (finalize.getError() == VideoRecordEvent.Finalize.ERROR_NONE) {
                                    finishWith(videoFile);
                                } else {
                                    deleteVideo();
                                    failed(finalize.getCause());
                                }
                            }
                        });
    }

    private void deleteVideo() {
        if (videoFile != null && videoFile.exists() && !videoFile.delete()) {
            Log.w(Config.LOGTAG, "could not delete " + videoFile);
        }
    }

    private void finishWith(final File file) {
        setResult(RESULT_OK, new Intent().setData(Uri.fromFile(file)));
        finish();
    }

    private void failed(final Throwable e) {
        Log.w(Config.LOGTAG, "in-app capture failed", e);
        busy = false;
        shutter.setEnabled(true);
        formatButton.setEnabled(true);
        if (video) {
            shutter.setImageResource(R.drawable.ic_videocam_24dp);
        }
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void finish() {
        if (isFinishing()) {
            return;
        }
        if (recording != null) {
            recording.stop();
            recording = null;
            deleteVideo();
        }
        super.finish();
    }
}
