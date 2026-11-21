package eu.siacs.conversations.ui;

import static eu.siacs.conversations.utils.PermissionUtils.getFirstDenied;
import static java.util.Arrays.asList;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.opengl.GLException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRtpSessionBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.BackHandler;
import eu.siacs.conversations.ui.util.CallGrid;
import eu.siacs.conversations.ui.util.MainThreadExecutor;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.PermissionUtils;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.ContentAddition;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.MujiConferenceManager;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

public class RtpSessionActivity extends XmppActivity
        implements XmppConnectionService.OnJingleRtpConnectionUpdate,
                eu.siacs.conversations.ui.widget.SurfaceViewRenderer.OnAspectRatioChanged {

    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_MUJI_ROOM = "muji_room";
    public static final String EXTRA_PROPOSED_SESSION_ID = "proposed_session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";

    private static final int CALL_DURATION_UPDATE_INTERVAL = 250;
    private static final int BUTTON_VISIBILITY_TIMEOUT = 10_000;

    public static final List<RtpEndUserState> END_CARD =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.SECURITY_ERROR,
                    RtpEndUserState.DECLINED_OR_BUSY,
                    RtpEndUserState.CONTACT_OFFLINE,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                    RtpEndUserState.RETRACTED);
    private static final List<RtpEndUserState> STATES_SHOWING_HELP_BUTTON =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.SECURITY_ERROR);
    private static final List<RtpEndUserState> STATES_SHOWING_SWITCH_TO_CHAT =
            Arrays.asList(
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED,
                    RtpEndUserState.RECONNECTING,
                    RtpEndUserState.INCOMING_CONTENT_ADD);
    private static final List<RtpEndUserState> STATES_CONSIDERED_CONNECTED =
            Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_PIP_PLACEHOLDER =
            Arrays.asList(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_SPEAKER_CONFIGURATION =
            new ImmutableList.Builder<RtpEndUserState>()
                    .add(RtpEndUserState.FINDING_DEVICE)
                    .add(RtpEndUserState.RINGING)
                    .add(RtpEndUserState.ACCEPTING_CALL)
                    .add(RtpEndUserState.CONNECTING)
                    .addAll(STATES_CONSIDERED_CONNECTED)
                    .build();
    private static final String PROXIMITY_WAKE_LOCK_TAG = "conversations:in-rtp-session";
    private static final int REQUEST_ACCEPT_CALL = 0x1111;
    private static final int REQUEST_ACCEPT_CONTENT = 0x1112;
    private static final int REQUEST_ADD_CONTENT = 0x1113;
    private WeakReference<JingleRtpConnection> rtpConnectionReference;

    private Account mujiAccount;
    private String mujiRoom;
    private Set<Media> mujiWaitingMedia;

    // XEP-0272 Muji: one tile per connected remote participant (keyed by per-pair session id),
    // shown in the muji_video_grid — their video if they send it, otherwise avatar + name. Empty
    // for 1:1 calls.
    private final Map<String, MujiTile> mujiTiles = new java.util.LinkedHashMap<>();
    // Group call: when the first participant connected (elapsedRealtime, 0 = not yet), and whether
    // any tile shows video — an audio-only grid covers the classic duration text, so the running
    // time goes into the toolbar subtitle instead.
    private long mujiConnectedSince = 0;
    private boolean mujiGridHasVideo = false;

    private static final class MujiTile {
        private final android.widget.FrameLayout root;
        private final android.widget.ImageView avatar;
        private final android.widget.TextView name;
        // This participant's leg: OMEMO lock/shield, GONE while its DTLS fingerprint is not
        // OMEMO-authenticated — so an unauthenticated participant stands out.
        private final android.widget.ImageView lock;
        @Nullable private SurfaceViewRenderer renderer;
        @Nullable private VideoTrack track;

        private MujiTile(
                final android.widget.FrameLayout root,
                final android.widget.ImageView avatar,
                final android.widget.TextView name,
                final android.widget.ImageView lock) {
            this.root = root;
            this.avatar = avatar;
            this.name = name;
            this.lock = lock;
        }
    }

    // Conference-wide call trust, recomputed on every grid pass: the LOWEST trust of all connected
    // legs (0 as soon as one leg's fingerprint is not OMEMO-authenticated). A group call must never
    // show the lock of whichever leg the activity happens to be bound to.
    private int mujiTrust = 0;
    private boolean mujiTrustLegacy = false;
    // Grid size at the last layout — so the 250ms tick only re-lays-out the grid when the
    // participant set or the orientation/size actually changed (not every tick).
    private int mujiLaidOutWidth = -1;
    private int mujiLaidOutHeight = -1;

    private ActivityRtpSessionBinding binding;
    private PowerManager.WakeLock mProximityWakeLock;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                    // Re-sync the Muji participant grid so a leg that connects without targeting
                    // this activity's session still shows up.
                    if (isMujiCall()) {
                        final JingleRtpConnection c =
                                rtpConnectionReference != null
                                        ? rtpConnectionReference.get()
                                        : null;
                        if (c != null) {
                            updateMujiGrid(c.getEndUserState());
                        } else if (RtpSessionActivity.this.mujiRoom != null) {
                            updateMujiGrid(RtpEndUserState.CONNECTED);
                        }
                    }
                    // Muji: an end card for the last leg arrives before the conference is torn
                    // down, so it kept the screen on; release it once the group call is over.
                    if (mujiRoom != null
                            && xmppConnectionService != null
                            && emptyReference(rtpConnectionReference)
                            && !isStillInMujiCall()) {
                        releaseScreenFromCallMode();
                        final Intent intent = getIntent();
                        if (intent == null || !intent.hasExtra(EXTRA_LAST_REPORTED_STATE)) {
                            // no end card to show (e.g. we left the room while waiting alone):
                            // don't leave a dead group-call screen behind
                            finish();
                            return;
                        }
                    }
                    mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
                }
            };
    private boolean buttonsHiddenAfterTimeout = false;
    private final Runnable mVisibilityToggleExecutor = this::updateButtonInVideoCallVisibility;

    public static Set<Media> actionToMedia(final String action) {
        if (ACTION_MAKE_VIDEO_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO, Media.VIDEO);
        } else if (ACTION_MAKE_VOICE_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO);
        } else {
            Log.w(
                    Config.LOGTAG,
                    "actionToMedia can not get media set from unknown action " + action);
            return Collections.emptySet();
        }
    }

    private static void addSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.addSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BackHandler.register(this, this::handleBack);
        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_rtp_session);
        this.binding.remoteVideo.setOnClickListener(this::onVideoScreenClick);
        this.binding.localVideo.setOnClickListener(this::onVideoScreenClick);
        this.binding.mujiVideoGrid.setOnClickListener(this::onVideoScreenClick);
        setSupportActionBar(binding.toolbar);

        binding.dialpad.setClickConsumer(tag -> {
            final JingleRtpConnection connection =
                    this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
            if (connection != null) connection.applyDtmfTone(tag);
        });

        if (savedInstanceState != null) {
            boolean dialpadVisible = savedInstanceState.getBoolean("dialpad_visible");
            binding.dialpad.setVisibility(dialpadVisible ? View.VISIBLE : View.GONE);
        }

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
    }

    private void onVideoScreenClick(final View view) {
        resetVisibilityExecutorShowButtons();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_rtp_session, menu);
        final MenuItem help = menu.findItem(R.id.action_help);
        final MenuItem gotoChat = menu.findItem(R.id.action_goto_chat);
        final MenuItem dialpad = menu.findItem(R.id.action_dialpad);
        final MenuItem switchToVideo = menu.findItem(R.id.action_switch_to_video);
        help.setVisible(Config.HELP != null && isHelpButtonVisible());
        gotoChat.setVisible(isSwitchToConversationVisible());
        switchToVideo.setVisible(isSwitchToVideoVisible());
        dialpad.setVisible(isAudioOnlyConversation());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (xmppConnectionService != null) {
                if (xmppConnectionService.getNotificationService().stopSoundAndVibration()) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isHelpButtonVisible() {
        try {
            return STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState());
        } catch (IllegalStateException e) {
            final Intent intent = getIntent();
            final String state =
                    intent != null ? intent.getStringExtra(EXTRA_LAST_REPORTED_STATE) : null;
            if (state != null) {
                return STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state));
            } else {
                return false;
            }
        }
    }

    private boolean isSwitchToConversationVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState());
    }

    private boolean isAudioOnlyConversation() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;

        return connection != null && !connection.getMedia().contains(Media.VIDEO);
    }

    private boolean isSwitchToVideoVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (isMujiCall()) {
            // Group call: the upgrade is per conference (all legs), not per bound leg.
            return connection != null
                    && STATES_CONSIDERED_CONNECTED.contains(connection.getEndUserState())
                    && !isSendingMujiVideo();
        }
        if (connection == null) {
            return false;
        }
        return connection.isSwitchToVideoAvailable();
    }

    @Nullable
    private Account currentMujiAccount() {
        final JingleRtpConnection primary =
                rtpConnectionReference == null ? null : rtpConnectionReference.get();
        return primary != null && primary.getMujiRoom() != null
                ? primary.getAccount()
                : mujiAccount;
    }

    @Nullable
    private String currentMujiRoom() {
        final JingleRtpConnection primary =
                rtpConnectionReference == null ? null : rtpConnectionReference.get();
        return primary != null && primary.getMujiRoom() != null ? primary.getMujiRoom() : mujiRoom;
    }

    private boolean isSendingMujiVideo() {
        final Account account = currentMujiAccount();
        final String room = currentMujiRoom();
        return account != null
                && room != null
                && xmppConnectionService != null
                && xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConferenceManager()
                        .isSendingVideo(account, room);
    }

    private void switchToConversation() {
        final Contact contact = getWith();
        if (contact == null) {
            return;
        }
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation);
    }

    private void toggleDialpadVisibility() {
        if (binding.dialpad.getVisibility() == View.VISIBLE) {
            binding.dialpad.setVisibility(View.GONE);
        } else {
            binding.dialpad.setVisibility(View.VISIBLE);
        }
    }

    public boolean onOptionsItemSelected(final MenuItem item) {
        final var itemItem = item.getItemId();
        if (itemItem == R.id.action_help) {
            launchHelpInBrowser();
            return true;
        } else if (itemItem == R.id.action_goto_chat) {
            switchToConversation();
            return true;
        } else if (itemItem == R.id.action_dialpad) {
            toggleDialpadVisibility();
            return true;
        } else if (itemItem == R.id.action_switch_to_video) {
            requestPermissionAndSwitchToVideo();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void launchHelpInBrowser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Config.HELP);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void endCall(View view) {
        endCall();
    }

    private void endCall() {
        if (this.rtpConnectionReference == null) {
            if (this.mujiRoom != null
                    && this.mujiAccount != null
                    && xmppConnectionService != null) {
                xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConferenceManager()
                        .leaveGroupCall(this.mujiAccount, this.mujiRoom);
                finish();
                return;
            }
            retractSessionProposal();
            finish();
        } else {
            try {
                final JingleRtpConnection connection = requireRtpConnection();
                final String room = connection.getMujiRoom();
                if (room != null) {
                    // XEP-0272 Muji: the call UI is per-leg, but hanging up means leaving the whole
                    // conference — end every leg + drop our <muji> presence (releasing the shared
                    // mic/factory). This is the ONLY place that leaves the conference; a single leg
                    // ending (peer left / re-mesh dropping a stuck leg) must not.
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getMujiConferenceManager()
                            .leaveGroupCall(connection.getId().account, room);
                    finish();
                } else {
                    connection.endCall();
                }
            } catch (final IllegalStateException e) {
                // No call, already done
                finish();
            }
        }
    }

    private void retractSessionProposal() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final Account account = extractAccount(intent);
        final String extraWith = intent.getStringExtra(EXTRA_WITH);
        if (account == null || Strings.isNullOrEmpty(extraWith)) {
            // e.g. a Muji group-call intent (EXTRA_MUJI_ROOM only): no 1:1 proposal to retract
            Log.d(Config.LOGTAG, "no session proposal to retract (intent has no account/with)");
            return;
        }
        final Jid with = Jid.of(extraWith);
        final String state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
        if (!Intent.ACTION_VIEW.equals(action)
                || state == null
                || !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
            resetIntent(account, with, RtpEndUserState.RETRACTED, media);
        }
        xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid());
    }

    private void rejectCall(View view) {
        requireRtpConnection().rejectCall();
        finish();
    }

    private void acceptCall(View view) {
        requestPermissionsAndAcceptCall();
    }

    private void acceptContentAdd() {
        try {
            final ContentAddition pendingContentAddition =
                    requireRtpConnection().getPendingContentAddition();
            if (pendingContentAddition == null) {
                Log.d(Config.LOGTAG, "content offer was gone after granting permission");
                return;
            }
            requireRtpConnection().acceptContentAdd(pendingContentAddition.summary);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, Strings.nullToEmpty(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermissionAndSwitchToVideo() {
        final List<String> permissions = permissions(ImmutableSet.of(Media.VIDEO, Media.AUDIO));
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ADD_CONTENT)) {
            switchToVideo();
        }
    }

    private void switchToVideo() {
        if (isMujiCall()) {
            final Account account = currentMujiAccount();
            final String room = currentMujiRoom();
            final boolean upgraded =
                    account != null
                            && room != null
                            && xmppConnectionService != null
                            && xmppConnectionService
                                    .getJingleConnectionManager()
                                    .getMujiConferenceManager()
                                    .upgradeToVideo(account, room);
            if (!upgraded) {
                Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show();
                return;
            }
            this.mujiWaitingMedia = ImmutableSet.of(Media.AUDIO, Media.VIDEO);
            lockOrientation(getMedia()); // we send video now: no portrait lock
            invalidateOptionsMenu();
            updateInCallButtonConfigurationVideo(true, false);
            return;
        }
        try {
            requireRtpConnection().addMedia(Media.VIDEO);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void acceptContentAdd(final ContentAddition contentAddition) {
        if (contentAddition == null
                || contentAddition.direction != ContentAddition.Direction.INCOMING) {
            Log.d(Config.LOGTAG, "ignore press on content-accept button");
            return;
        }
        requestPermissionAndAcceptContentAdd(contentAddition);
    }

    private void requestPermissionAndAcceptContentAdd(final ContentAddition contentAddition) {
        final List<String> permissions = permissions(contentAddition.media());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CONTENT)) {
            try {
                requireRtpConnection().acceptContentAdd(contentAddition.summary);
            } catch (final IllegalStateException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void rejectContentAdd(final View view) {
        requireRtpConnection().rejectContentAdd();
    }

    private void requestPermissionsAndAcceptCall() {
        final List<String> permissions = permissions(getMedia());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode();
            acceptCall();
        }
    }

    private static List<String> permissions(final Set<Media> media) {
        if (media.contains(Media.VIDEO)) {
            return PermissionUtils.rtpPermissions(true);
        } else {
            return PermissionUtils.rtpPermissions(false);
        }
    }

    private void acceptCall() {
        try {
            requireRtpConnection().acceptCall();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia());
    }

    private void putScreenInCallMode(final Set<Media> media) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Media.audioOnly(media)) {
            final JingleRtpConnection rtpConnection =
                    rtpConnectionReference != null ? rtpConnectionReference.get() : null;
            final CallIntegration callIntegration =
                    rtpConnection == null ? null : rtpConnection.getCallIntegration();
            final CallIntegration.AudioDevice audioDevice =
                    callIntegration != null
                            ? callIntegration.getSelectedAudioDevice()
                            : getMujiAudioDevice();
            if (audioDevice == null || audioDevice == CallIntegration.AudioDevice.EARPIECE) {
                acquireProximityWakeLock();
            }
        }
        lockOrientation(media);
    }

    private void lockOrientation(final Set<Media> media) {
        // Group call: portrait only while no video is shown at all — not decided by the bound
        // leg, which may stay audio-only while another participant turns their camera on.
        final boolean audioOnly =
                isMujiCall() ? !(isSendingMujiVideo() || mujiGridHasVideo) : Media.audioOnly(media);
        // Portrait only on phones (held to the ear, proximity sensor): a tablet isn't, and forcing
        // it into portrait just turns the screen away from how it's being held.
        if (audioOnly && getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireProximityWakeLock() {
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available");
            return;
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing");
            return;
        }
        if (this.mProximityWakeLock == null) {
            this.mProximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG);
        }
        if (!this.mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "acquiring proximity wake lock");
            this.mProximityWakeLock.acquire();
        }
    }

    private void releaseProximityWakeLock() {
        if (this.mProximityWakeLock != null && mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "releasing proximity wake lock");
            this.mProximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            this.mProximityWakeLock = null;
        }
    }

    private void clearMujiState() {
        this.mujiAccount = null;
        this.mujiRoom = null;
        this.mujiWaitingMedia = null;
    }

    private boolean isStillInMujiCall() {
        return this.mujiAccount != null
                && this.mujiRoom != null
                && xmppConnectionService != null
                && xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConferenceManager()
                        .isParticipating(this.mujiAccount, this.mujiRoom);
    }

    /** The call screen shows its end card: let the screen turn off again. */
    private void releaseScreenFromCallMode() {
        releaseProximityWakeLock();
        runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    }

    private void putProximityWakeLockInProperState(final CallIntegration.AudioDevice audioDevice) {
        if (audioDevice == CallIntegration.AudioDevice.EARPIECE) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onNewIntent(final Intent intent) {
        Log.d(Config.LOGTAG, this.getClass().getName() + ".onNewIntent()");
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        if (xmppConnectionService == null) {
            Log.d(
                    Config.LOGTAG,
                    "RtpSessionActivity: background service wasn't bound in onNewIntent()");
            return;
        }
        initializeWithIntent(Event.ON_NEW_INTENT, intent);
    }

    @Override
    protected void onBackendConnected() {
        final var intent = getIntent();
        if (intent == null) {
            return;
        }
        initializeWithIntent(Event.ON_BACKEND_CONNECTED, intent);
    }

    private void initializeWithIntent(final Event event, @NonNull final Intent intent) {
        final String action = intent.getAction();
        Log.d(Config.LOGTAG, "initializeWithIntent(" + event + "," + action + ")");
        final Account account = extractAccount(intent);
        if (account == null) {
            Log.e(Config.LOGTAG, "intent is missing account");
            return;
        }
        final String mujiRoomExtra = intent.getStringExtra(EXTRA_MUJI_ROOM);
        if (mujiRoomExtra != null) {
            initializeActivityWithMujiRoom(
                    account, Jid.of(mujiRoomExtra), intent.getStringExtra(EXTRA_LAST_ACTION));
            return;
        }
        // singleInstance: this screen may still carry a previous group call (e.g. its end card)
        // when a 1:1 call arrives — don't treat the new call as part of that group call
        clearMujiState();
        final var extraWith = intent.getStringExtra(EXTRA_WITH);
        final Jid with = Strings.isNullOrEmpty(extraWith) ? null : Jid.of(extraWith);
        if (with == null) {
            Log.e(Config.LOGTAG, "intent is missing with");
            return;
        }
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                Log.d(Config.LOGTAG, "intent action was accept");
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            final String proposedSessionId = intent.getStringExtra(EXTRA_PROPOSED_SESSION_ID);
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, proposedSessionId);
            if (terminatedRtpSession != null) {
                // termination (due to message error or 'busy' was faster than opening the activity
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
                return;
            }
            final String extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
            final RtpEndUserState state =
                    extraLastState == null ? null : RtpEndUserState.valueOf(extraLastState);
            final var contact = account.getRoster().getContact(with);
            if (state != null) {
                Log.d(Config.LOGTAG, "restored last state from intent extra");
                updateButtonConfiguration(state);
                updateVerifiedShield(0);
                updateStateDisplay(state);
                updateIncomingCallScreen(state);
                updateSupportWarning(state, contact);
                invalidateOptionsMenu();
            }
            setWith(state, contact);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return;
            }
            if (END_CARD.contains(state)) {
                return;
            }
            final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
            final Set<Media> media = actionToMedia(lastAction);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .hasMatchingProposal(account, with)) {
                putScreenInCallMode(media);
                return;
            }
            Log.d(Config.LOGTAG, "restored state (" + state + ") was not an end card. finishing");
            finish();
        }
    }

    private void initializeActivityWithMujiRoom(
            final Account account, final Jid room, final String lastAction) {
        this.mujiAccount = account;
        this.mujiRoom = room.asBareJid().toString();
        this.mujiWaitingMedia = actionToMedia(lastAction);

        final Conversation muc = xmppConnectionService.find(account, room.asBareJid());
        final Contact contact =
                muc != null ? muc.getContact() : account.getRoster().getContact(room);

        setWith(RtpEndUserState.CONNECTED, contact);
        updateUsingAccount(contact);

        putScreenInCallMode(this.mujiWaitingMedia);
        updateVideoViews(RtpEndUserState.CONNECTED);
        updateStateDisplay(RtpEndUserState.CONNECTED, this.mujiWaitingMedia, null);
        updateButtonConfiguration(RtpEndUserState.CONNECTED, this.mujiWaitingMedia, null);
        invalidateOptionsMenu();

        mHandler.post(this.mTickExecutor);
    }

    private void setWith(final RtpEndUserState state) {
        setWith(state, getWith());
    }

    private void setWith(final RtpEndUserState state, final Contact contact) {
        binding.with.setText(contact.getDisplayName());
        if (Arrays.asList(RtpEndUserState.INCOMING_CALL, RtpEndUserState.ACCEPTING_CALL)
                .contains(state)) {
            binding.withJid.setText(contact.getJid().asBareJid().toString());
            binding.withJid.setVisibility(View.VISIBLE);
        } else {
            binding.withJid.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeOptionalRtpPermissions(permissions, grantResults);
        if (PermissionUtils.allGranted(permissionResult.grantResults())) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                acceptCall();
            } else if (requestCode == REQUEST_ACCEPT_CONTENT) {
                acceptContentAdd();
            } else if (requestCode == REQUEST_ADD_CONTENT) {
                switchToVideo();
            }
        } else {
            @StringRes int res;
            final String firstDenied =
                    getFirstDenied(permissionResult.grantResults(), permissionResult.permissions());
            if (firstDenied == null) {
                return;
            }
            if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                res = R.string.no_microphone_permission;
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                res = R.string.no_camera_permission;
            } else {
                throw new IllegalStateException("Invalid permission result request");
            }
            Toast.makeText(this, getString(res, getString(R.string.app_name)), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
        mHandler.postDelayed(mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
        this.binding.remoteVideo.setOnAspectRatioChanged(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        resetVisibilityExecutorShowButtons();
    }

    @Override
    public void onStop() {
        mHandler.removeCallbacks(mTickExecutor);
        mHandler.removeCallbacks(mVisibilityToggleExecutor);
        binding.remoteVideo.release();
        binding.remoteVideo.setOnAspectRatioChanged(null);
        binding.localVideo.release();
        releaseMujiGrid();
        final WeakReference<JingleRtpConnection> weakReference = this.rtpConnectionReference;
        final JingleRtpConnection jingleRtpConnection =
                weakReference == null ? null : weakReference.get();
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection);
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    private void releaseVideoTracks(final JingleRtpConnection jingleRtpConnection) {
        final Optional<VideoTrack> remoteVideo = jingleRtpConnection.getRemoteVideoTrack();
        if (remoteVideo.isPresent()) {
            remoteVideo.get().removeSink(binding.remoteVideo);
        }
        final Optional<VideoTrack> localVideo = jingleRtpConnection.getLocalVideoTrack();
        if (localVideo.isPresent()) {
            localVideo.get().removeSink(binding.localVideo);
        }
    }

    private boolean handleBack() {
        if (isConnected()) {
            if (switchToPictureInPicture()) {
                return true;
            }
        } else {
            endCall();
        }
        return false;
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (switchToPictureInPicture()) {
            return;
        }
        // TODO apparently this method is not getting called on Android 10 when using the task
        // switcher
        // A Muji group call is only left via the explicit hang-up (endCall → leaveGroupCall);
        // moving the app to the background must not end the conference.
        if (emptyReference(rtpConnectionReference)
                && xmppConnectionService != null
                && mujiRoom == null) {
            retractSessionProposal();
        }
    }

    private boolean isConnected() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final RtpEndUserState endUserState =
                connection == null ? null : connection.getEndUserState();
        return STATES_CONSIDERED_CONNECTED.contains(endUserState)
                || endUserState == RtpEndUserState.INCOMING_CONTENT_ADD;
    }

    private boolean switchToPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture();
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startPictureInPicture() {
        try {
            final Rational rational = this.binding.remoteVideo.getAspectRatio();
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational " + rational + ". clipped to " + clippedRational);
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        } catch (final IllegalStateException e) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        }
    }

    @Override
    public void onAspectRatioChanged(final Rational rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational after aspect ratio change "
                            + rational
                            + ". clipped to "
                            + clippedRational);
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        }
    }

    private boolean deviceSupportsPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    private boolean shouldBePictureInPicture() {
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            return rtpConnection.getMedia().contains(Media.VIDEO)
                    && Arrays.asList(
                                    RtpEndUserState.ACCEPTING_CALL,
                                    RtpEndUserState.CONNECTING,
                                    RtpEndUserState.CONNECTED)
                            .contains(rtpConnection.getEndUserState());
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean isInConnectedVideoCall() {
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return false;
        }
        return rtpConnection.getMedia().contains(Media.VIDEO)
                && rtpConnection.getEndUserState() == RtpEndUserState.CONNECTED;
    }

    private boolean initializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        final WeakReference<JingleRtpConnection> reference =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .findJingleRtpConnection(account, with, sessionId);
        if (reference == null || reference.get() == null) {
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, sessionId);
            if (terminatedRtpSession == null) {
                throw new IllegalStateException(
                        "failed to initialize activity with running rtp session. session not"
                                + " found");
            }
            initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
            return true;
        }
        this.rtpConnectionReference = reference;
        final JingleRtpConnection connection = reference.get();
        if (connection != null && connection.getMujiRoom() != null) {
            this.mujiAccount = account;
            this.mujiRoom = connection.getMujiRoom();
        } else {
            clearMujiState();
        }
        final RtpEndUserState currentState = requireRtpConnection().getEndUserState();
        final boolean muji = isMujiCall();
        final int callTrust = muji ? mujiTrust : requireRtpConnection().getCallTrustLevel();
        final boolean callTrustLegacy =
                muji ? mujiTrustLegacy : requireRtpConnection().isCallVerifiedLegacy();
        if (currentState == RtpEndUserState.ENDED) {
            finish();
            return true;
        }
        final Set<Media> media = getMedia();
        final ContentAddition contentAddition = getPendingContentAddition();
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode();
        }
        setWith(currentState);
        updateVideoViews(currentState);
        updateStateDisplay(currentState, media, contentAddition);
        updateVerifiedShield(
                STATES_SHOWING_SWITCH_TO_CHAT.contains(currentState) ? callTrust : 0,
                callTrustLegacy);
        updateButtonConfiguration(currentState, media, contentAddition);
        updateIncomingCallScreen(currentState);
        invalidateOptionsMenu();
        return false;
    }

    private void initializeWithTerminatedSessionState(
            final Account account,
            final Jid with,
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession) {
        Log.d(Config.LOGTAG, "initializeWithTerminatedSessionState()");
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish();
            return;
        }
        final RtpEndUserState state = terminatedRtpSession.state;
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media);
        updateButtonConfiguration(state);
        updateStateDisplay(state);
        updateIncomingCallScreen(state);
        updateCallDuration();
        updateVerifiedShield(0);
        invalidateOptionsMenu();
        final var contact = account.getRoster().getContact(with);
        setWith(state, contact);
        updateSupportWarning(state, contact);
    }

    private void reInitializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        runOnUiThread(() -> initializeActivityWithRunningRtpSession(account, with, sessionId));
        resetIntent(account, with, sessionId);
    }

    private void resetIntent(final Account account, final Jid with, final String sessionId) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString());
        intent.putExtra(EXTRA_WITH, with.toString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        setIntent(intent);
    }

    private void ensureSurfaceViewRendererIsSetup(final SurfaceViewRenderer surfaceViewRenderer) {
        surfaceViewRenderer.setVisibility(View.VISIBLE);
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null);
        } catch (final IllegalStateException ignored) {
            // SurfaceViewRenderer was already initialized
        } catch (final RuntimeException e) {
            if (Throwables.getRootCause(e) instanceof GLException glException) {
                Log.w(Config.LOGTAG, "could not set up hardware renderer", glException);
            }
        }
        surfaceViewRenderer.setEnableHardwareScaler(true);
    }

    private void updateStateDisplay(final RtpEndUserState state) {
        updateStateDisplay(state, Collections.emptySet(), null);
    }

    private void updateStateDisplay(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        switch (state) {
            case INCOMING_CALL -> {
                Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
                if (media.contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_incoming_video_call);
                } else {
                    setTitle(R.string.rtp_state_incoming_call);
                }
            }
            case INCOMING_CONTENT_ADD -> {
                if (contentAddition != null && contentAddition.media().contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_content_add_video);
                } else {
                    setTitle(R.string.rtp_state_content_add);
                }
            }
            case CONNECTING -> setTitle(R.string.rtp_state_connecting);
            case CONNECTED -> setTitle(R.string.rtp_state_connected);
            case RECONNECTING -> setTitle(R.string.rtp_state_reconnecting);
            case ACCEPTING_CALL -> setTitle(R.string.rtp_state_accepting_call);
            case ENDING_CALL -> setTitle(R.string.rtp_state_ending_call);
            case FINDING_DEVICE -> setTitle(R.string.rtp_state_finding_device);
            case RINGING -> setTitle(R.string.rtp_state_ringing);
            case DECLINED_OR_BUSY -> setTitle(R.string.rtp_state_declined_or_busy);
            case CONTACT_OFFLINE -> setTitle(R.string.rtp_state_contact_offline);
            case CONNECTIVITY_ERROR -> setTitle(R.string.rtp_state_connectivity_error);
            case CONNECTIVITY_LOST_ERROR -> setTitle(R.string.rtp_state_connectivity_lost_error);
            case RETRACTED -> setTitle(R.string.rtp_state_retracted);
            case APPLICATION_ERROR -> setTitle(R.string.rtp_state_application_failure);
            case SECURITY_ERROR -> setTitle(R.string.rtp_state_security_error);
            case ENDED ->
                    throw new IllegalStateException(
                            "Activity should have called finishAndReleaseWakeLock();");
            default ->
                    throw new IllegalStateException(
                            String.format("State %s has not been handled in UI", state));
        }
    }

    private void updateVerifiedShield(final int trustLevel) {
        updateVerifiedShield(trustLevel, false);
    }

    private void updateVerifiedShield(final int trustLevel, final boolean legacyOmemo) {
        if (isPictureInPicture() || trustLevel <= 0) {
            this.binding.verified.setVisibility(View.GONE);
            return;
        }
        setTrustIcon(this.binding.verified, trustLevel, legacyOmemo);
        this.binding.verified.setVisibility(View.VISIBLE);
    }

    /**
     * Same icon convention as the message list: legacy OMEMO and PQ OMEMO2 each have their own lock
     * (trusted) and shield (manually verified) variants. Skips the reload when the icon is
     * unchanged — the Muji grid calls this on every 250ms tick.
     */
    private static void setTrustIcon(
            final android.widget.ImageView view, final int trustLevel, final boolean legacyOmemo) {
        final int icon;
        if (legacyOmemo) {
            icon = trustLevel >= 2 ? R.drawable.ic_verified_user_24dp : R.drawable.ic_lock_24dp;
        } else {
            icon =
                    trustLevel >= 2
                            ? R.drawable.ic_shield_omemo2_verified_24dp
                            : R.drawable.ic_lock_omemo2_24dp;
        }
        if (!Integer.valueOf(icon).equals(view.getTag())) {
            view.setImageResource(icon);
            view.setTag(icon);
        }
    }

    private void updateIncomingCallScreen(final RtpEndUserState state) {
        updateIncomingCallScreen(state, null);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state, final Contact contact) {
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            final boolean show = getResources().getBoolean(R.bool.is_portrait_mode);
            if (show) {
                binding.contactPhoto.setVisibility(View.VISIBLE);
                if (contact == null) {
                    AvatarWorkerTask.loadAvatar(
                            getWith(), binding.contactPhoto, R.dimen.publish_avatar_size);
                } else {
                    AvatarWorkerTask.loadAvatar(
                            contact, binding.contactPhoto, R.dimen.publish_avatar_size);
                }
            } else {
                binding.contactPhoto.setVisibility(View.GONE);
            }
        } else {
            binding.contactPhoto.setVisibility(View.GONE);
        }
        updateUsingAccount(contact);
    }

    private void updateUsingAccount(final Contact contact) {
        final Contact with = contact == null ? getWith() : contact;
        final Account account = with == null ? null : with.getAccount();
        if (account == null || isPictureInPicture()) {
            binding.usingAccount.setVisibility(View.GONE);
            return;
        }
        binding.usingAccount.setVisibility(View.VISIBLE);
        binding.usingAccount.setText(
                getString(R.string.using_account, account.getJid().asBareJid().toString()));
    }

    private void updateSupportWarning(final RtpEndUserState state, final Contact contact) {
        if (state == RtpEndUserState.CONNECTIVITY_ERROR
                && getResources().getBoolean(R.bool.is_portrait_mode)) {
            binding.supportWarning.setVisibility(
                    RtpCapability.check(contact) == RtpCapability.Capability.NONE
                            ? View.VISIBLE
                            : View.GONE);
        } else {
            binding.supportWarning.setVisibility(View.GONE);
        }
    }

    private Set<Media> getMedia() {
        if (this.rtpConnectionReference != null && this.rtpConnectionReference.get() != null) {
            return this.rtpConnectionReference.get().getMedia();
        }
        return this.mujiWaitingMedia != null ? this.mujiWaitingMedia : Collections.emptySet();
    }

    public ContentAddition getPendingContentAddition() {
        if (this.rtpConnectionReference != null && this.rtpConnectionReference.get() != null) {
            return this.rtpConnectionReference.get().getPendingContentAddition();
        }
        return null;
    }

    private void updateButtonConfiguration(final RtpEndUserState state) {
        updateButtonConfiguration(state, Collections.emptySet(), null);
    }

    @SuppressLint("RestrictedApi")
    private void updateButtonConfiguration(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        if (state == RtpEndUserState.ENDING_CALL
                || isPictureInPicture()
                || this.buttonsHiddenAfterTimeout) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            this.binding.rejectCall.setContentDescription(getString(R.string.dismiss_call));
            this.binding.rejectCall.setOnClickListener(this::rejectCall);
            this.binding.rejectCall.setImageResource(R.drawable.ic_call_end_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.answer_call));
            this.binding.acceptCall.setOnClickListener(this::acceptCall);
            this.binding.acceptCall.setImageResource(R.drawable.ic_call_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CONTENT_ADD) {
            this.binding.rejectCall.setContentDescription(
                    getString(R.string.reject_switch_to_video));
            this.binding.rejectCall.setOnClickListener(this::rejectContentAdd);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.accept));
            this.binding.acceptCall.setOnClickListener((v -> acceptContentAdd(contentAddition)));
            this.binding.acceptCall.setImageResource(R.drawable.ic_check_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (asList(RtpEndUserState.DECLINED_OR_BUSY, RtpEndUserState.CONTACT_OFFLINE)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.record_voice_mail));
            this.binding.acceptCall.setOnClickListener(this::recordVoiceMail);
            this.binding.acceptCall.setImageResource(R.drawable.ic_voicemail_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (asList(
                        RtpEndUserState.CONNECTIVITY_ERROR,
                        RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                        RtpEndUserState.APPLICATION_ERROR,
                        RtpEndUserState.RETRACTED,
                        RtpEndUserState.SECURITY_ERROR)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.try_again));
            this.binding.acceptCall.setOnClickListener(this::retry);
            this.binding.acceptCall.setImageResource(R.drawable.ic_replay_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_24dp);
            setVisibleAndShow(this.binding.endCall);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        }
        updateInCallButtonConfiguration(state, media);
    }

    private static void setVisibleAndShow(final FloatingActionButton button) {
        button.show();
        button.setVisibility(View.VISIBLE);
    }

    private boolean isPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return isInPictureInPictureMode();
        } else {
            return false;
        }
    }

    private void updateInCallButtonConfiguration() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference == null ? null : this.rtpConnectionReference.get();
        if (connection != null) {
            updateInCallButtonConfiguration(connection.getEndUserState(), connection.getMedia());
        } else if (this.mujiRoom != null) {
            // Unbound group call: waiting alone (in-call buttons), or showing a leg's end card
            // while the conference goes on (no in-call buttons over the end card).
            final Intent intent = getIntent();
            final boolean endCard = intent != null && intent.hasExtra(EXTRA_LAST_REPORTED_STATE);
            updateInCallButtonConfiguration(
                    endCard ? RtpEndUserState.ENDED : RtpEndUserState.CONNECTED, getMedia());
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfiguration(
            final RtpEndUserState state, final Set<Media> media) {
        final var showButtons = !isPictureInPicture() && !buttonsHiddenAfterTimeout;
        final JingleRtpConnection rtpConnection =
                this.rtpConnectionReference == null ? null : this.rtpConnectionReference.get();
        final boolean muji = isMujiCall();
        // A group call shows its buttons as soon as we're in it, also while its legs are still
        // connecting: what they control is conference-wide (see activeMujiConference).
        final boolean callButtons =
                muji
                        ? STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state)
                        : STATES_CONSIDERED_CONNECTED.contains(state) && !media.isEmpty();
        if (callButtons && showButtons) {
            if (showsVideoControls(media)) {
                if (muji) {
                    // group call: our camera state is conference-wide, not the bound leg's
                    updateInCallButtonConfigurationVideo(
                            isMujiVideoEnabled(),
                            rtpConnection != null && rtpConnection.isCameraSwitchable());
                } else if (rtpConnection != null) {
                    updateInCallButtonConfigurationVideo(
                            rtpConnection.isVideoEnabled(), rtpConnection.isCameraSwitchable());
                } else {
                    updateInCallButtonConfigurationVideo(true, false);
                }
            } else {
                if (muji) {
                    if (!updateMujiSpeakerButton()) {
                        this.binding.inCallActionRight.setVisibility(View.GONE);
                    }
                } else if (rtpConnection != null) {
                    final CallIntegration callIntegration = rtpConnection.getCallIntegration();
                    updateInCallButtonConfigurationSpeaker(
                            callIntegration.getSelectedAudioDevice(),
                            callIntegration.getAudioDevices().size());
                } else {
                    this.binding.inCallActionRight.setVisibility(View.GONE);
                }
                if (muji && isCameraFeatureAvailable()) {
                    // audio-only in a group call: offer turning our camera on, whatever the
                    // others (or the leg this screen is bound to) send
                    this.binding.inCallActionFarRight.setImageResource(
                            R.drawable.ic_videocam_off_24dp);
                    this.binding.inCallActionFarRight.setOnClickListener(this::enableVideo);
                    this.binding.inCallActionFarRight.setContentDescription(
                            getString(R.string.video_is_disabled_tap_to_enable));
                    setVisibleAndShow(this.binding.inCallActionFarRight);
                } else {
                    this.binding.inCallActionFarRight.setVisibility(View.GONE);
                }
            }
            if (muji) {
                // not the bound leg's: one that is still connecting has no audio track yet and
                // reports itself muted, so the button wouldn't react to taps
                updateInCallButtonConfigurationMicrophone(isMujiMicrophoneEnabled());
            } else if (media.contains(Media.AUDIO)) {
                updateInCallButtonConfigurationMicrophone(
                        rtpConnection == null || rtpConnection.isMicrophoneEnabled());
            } else {
                this.binding.inCallActionLeft.setVisibility(View.GONE);
            }
        } else if (!muji
                && STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state)
                && showButtons
                && Media.audioOnly(media)) {
            final CallIntegration callIntegration =
                    rtpConnection != null ? rtpConnection.getCallIntegration() : null;
            if (callIntegration != null) {
                updateInCallButtonConfigurationSpeaker(
                        callIntegration.getSelectedAudioDevice(),
                        callIntegration.getAudioDevices().size());
            } else {
                this.binding.inCallActionRight.setVisibility(View.GONE);
            }
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        } else {
            this.binding.inCallActionLeft.setVisibility(View.GONE);
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
    }

    /**
     * Muji: the group call on screen, if we're (still) in it — whether a leg is bound or not
     * (waiting alone, legs still connecting, or connected). Its audio routing (one shared audio
     * manager) and mute/camera state (flags applied to every leg) are conference-wide, so the
     * in-call buttons read and change them here, never on the bound leg: a leg that is still being
     * set up has no audio track yet and reports itself muted.
     */
    @Nullable
    private MujiConferenceManager activeMujiConference() {
        final Account account = currentMujiAccount();
        final String room = currentMujiRoom();
        if (account == null || room == null || xmppConnectionService == null) {
            return null;
        }
        final MujiConferenceManager muji =
                xmppConnectionService.getJingleConnectionManager().getMujiConferenceManager();
        return muji.isParticipating(account, room) ? muji : null;
    }

    /**
     * Whether the in-call buttons are the video controls (camera on/off, flip) rather than the
     * speaker toggle. In a group call that's whether WE send video — not whether the leg this
     * screen happens to be bound to carries (someone else's) video.
     */
    private boolean showsVideoControls(final Set<Media> media) {
        return isMujiCall() ? isSendingMujiVideo() : media.contains(Media.VIDEO);
    }

    private boolean isMujiVideoEnabled() {
        final Account account = currentMujiAccount();
        final String room = currentMujiRoom();
        return account != null
                && room != null
                && xmppConnectionService != null
                && xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConferenceManager()
                        .isVideoEnabled(account, room);
    }

    /** Group call: the conference's mute state (true when not in one). */
    private boolean isMujiMicrophoneEnabled() {
        final MujiConferenceManager muji = activeMujiConference();
        return muji == null || muji.isMicrophoneEnabled(currentMujiAccount(), currentMujiRoom());
    }

    /** Group call: the device the conference's shared audio manager routes to, else null. */
    @Nullable
    private CallIntegration.AudioDevice getMujiAudioDevice() {
        final MujiConferenceManager muji = activeMujiConference();
        return muji == null
                ? null
                : muji.getSelectedAudioDevice(currentMujiAccount(), currentMujiRoom());
    }

    /** Shows the speaker toggle of the group call on screen; false if not applicable. */
    private boolean updateMujiSpeakerButton() {
        final MujiConferenceManager muji = activeMujiConference();
        if (muji == null) {
            return false;
        }
        final Account account = currentMujiAccount();
        final String room = currentMujiRoom();
        final CallIntegration.AudioDevice selected = muji.getSelectedAudioDevice(account, room);
        if (selected == null || selected == CallIntegration.AudioDevice.NONE) {
            // audio manager not started yet; its first device event shows the button
            return false;
        }
        updateInCallButtonConfigurationSpeaker(
                selected, muji.getAudioDevices(account, room).size());
        return true;
    }

    /** Routes audio to {@code device}: the group call's shared route, else the 1:1 call's. */
    private void setAudioDevice(final CallIntegration.AudioDevice device) {
        final MujiConferenceManager muji = activeMujiConference();
        if (muji == null || !muji.setAudioDevice(currentMujiAccount(), currentMujiRoom(), device)) {
            requireCallIntegration().setAudioDevice(device);
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationSpeaker(
            final CallIntegration.AudioDevice selectedAudioDevice, final int numberOfChoices) {
        switch (selectedAudioDevice) {
            case EARPIECE -> {
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_volume_off_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_earpiece_tap_to_switch_to_speaker));
                    this.binding.inCallActionRight.setOnClickListener(this::switchToSpeaker);
                } else {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_earpiece));
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
            }
            case WIRED_HEADSET -> {
                this.binding.inCallActionRight.setContentDescription(
                        getString(R.string.call_is_using_wired_headset));
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_headset_mic_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
            }
            case SPEAKER_PHONE -> {
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_volume_up_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_speaker_tap_to_switch_to_earpiece));
                    this.binding.inCallActionRight.setOnClickListener(this::switchToEarpiece);
                } else {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_speaker));
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
            }
            case BLUETOOTH -> {
                this.binding.inCallActionRight.setContentDescription(
                        getString(R.string.call_is_using_bluetooth));
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_bluetooth_audio_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
            }
        }
        setVisibleAndShow(this.binding.inCallActionRight);
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationVideo(
            final boolean videoEnabled, final boolean isCameraSwitchable) {
        setVisibleAndShow(this.binding.inCallActionRight);
        if (isCameraSwitchable) {
            this.binding.inCallActionFarRight.setImageResource(
                    R.drawable.ic_flip_camera_android_24dp);
            setVisibleAndShow(this.binding.inCallActionFarRight);
            this.binding.inCallActionFarRight.setOnClickListener(this::switchCamera);
            this.binding.inCallActionFarRight.setContentDescription(
                    getString(R.string.flip_camera));
        } else {
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
        if (videoEnabled) {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::disableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_enabled_tap_to_disable));
        } else {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_off_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::enableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_disabled_tap_to_enable));
        }
    }

    private void switchCamera(final View view) {
        resetVisibilityToggleExecutor();
        Futures.addCallback(
                requireRtpConnection().switchCamera(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable Boolean isFrontCamera) {
                        binding.localVideo.setMirror(Boolean.TRUE.equals(isFrontCamera));
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.d(
                                Config.LOGTAG,
                                "could not switch camera",
                                Throwables.getRootCause(throwable));
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_switch_camera,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
        // TODO ^ replace with ContextCompat.getMainExecutor(getApplication())
    }

    private void enableVideo(final View view) {
        resetVisibilityToggleExecutor();
        if (isMujiCall() && !isSendingMujiVideo()) {
            // Joined audio-only (or only receiving others' video): turning the camera on is an
            // upgrade of our participation — every leg has to start carrying our video.
            requestPermissionAndSwitchToVideo();
            return;
        }
        try {
            // Muji: enable our camera on every leg so all peers see us.
            for (final JingleRtpConnection leg : callLegs()) {
                leg.setVideoEnabled(true);
            }
            if (isMujiCall()) {
                final JingleRtpConnection primary =
                        rtpConnectionReference == null ? null : rtpConnectionReference.get();
                final Account account = primary != null ? primary.getAccount() : mujiAccount;
                final String room = primary != null ? primary.getMujiRoom() : mujiRoom;
                if (account != null && room != null) {
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getMujiConferenceManager()
                            .setVideoEnabled(account, room, true);
                }
            }
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(true, isBoundCameraSwitchable());
    }

    private void disableVideo(final View view) {
        resetVisibilityToggleExecutor();
        // Group call: the camera is muted on all legs below; retracting a pending content-add
        // would only affect the one leg this screen happens to be bound to (and while waiting
        // alone there is no leg at all).
        if (!isMujiCall()) {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            final ContentAddition pending = rtpConnection.getPendingContentAddition();
            if (pending != null && pending.direction == ContentAddition.Direction.OUTGOING) {
                rtpConnection.retractContentAdd();
                return;
            }
        }
        try {
            // Muji: stop sending video on every leg.
            for (final JingleRtpConnection leg : callLegs()) {
                leg.setVideoEnabled(false);
            }
            if (isMujiCall()) {
                final JingleRtpConnection primary =
                        rtpConnectionReference == null ? null : rtpConnectionReference.get();
                final Account account = primary != null ? primary.getAccount() : mujiAccount;
                final String room = primary != null ? primary.getMujiRoom() : mujiRoom;
                if (account != null && room != null) {
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getMujiConferenceManager()
                            .setVideoEnabled(account, room, false);
                }
            }
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_disable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(false, isBoundCameraSwitchable());
    }

    /** Null-safe: a group call we're waiting in alone has no bound leg (and no camera to flip). */
    private boolean isBoundCameraSwitchable() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference == null ? null : this.rtpConnectionReference.get();
        return connection != null && connection.isCameraSwitchable();
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationMicrophone(final boolean microphoneEnabled) {
        if (microphoneEnabled) {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::disableMicrophone);
        } else {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_off_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::enableMicrophone);
        }
        setVisibleAndShow(this.binding.inCallActionLeft);
    }

    private void updateCallDuration() {
        if (isMujiCall()) {
            this.binding.duration.setVisibility(View.GONE); // covered by the participant grid
            final var actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setSubtitle(
                        !mujiTiles.isEmpty() && !mujiGridHasVideo && mujiConnectedSince > 0
                                ? TimeFrameUtils.formatElapsedTime(
                                        android.os.SystemClock.elapsedRealtime()
                                                - mujiConnectedSince,
                                        false)
                                : null);
            }
            return;
        }
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null || connection.getMedia().contains(Media.VIDEO)) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        if (connection.zeroDuration()) {
            this.binding.duration.setVisibility(View.GONE);
        } else {
            this.binding.duration.setText(
                    TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false));
            this.binding.duration.setVisibility(View.VISIBLE);
        }
    }

    private void resetVisibilityToggleExecutor() {
        mHandler.removeCallbacks(this.mVisibilityToggleExecutor);
        mHandler.postDelayed(this.mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
    }

    private void updateButtonInVideoCallVisibility() {
        if (isInConnectedVideoCall()) {
            if (isPictureInPicture()) {
                return;
            }
            Log.d(Config.LOGTAG, "hiding in-call buttons after timeout was reached");
            hideInCallButtons();
        }
    }

    private void hideInCallButtons() {
        binding.inCallActionLeft.hide();
        binding.endCall.hide();
        binding.inCallActionRight.hide();
        binding.inCallActionFarRight.hide();
    }

    private void showInCallButtons() {
        this.buttonsHiddenAfterTimeout = false;
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return;
        }
        updateButtonConfiguration(
                rtpConnection.getEndUserState(),
                rtpConnection.getMedia(),
                rtpConnection.getPendingContentAddition());
    }

    private void resetVisibilityExecutorShowButtons() {
        resetVisibilityToggleExecutor();
        showInCallButtons();
    }

    private void updateVideoViews(final RtpEndUserState state) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            binding.localVideo.setVisibility(View.GONE);
            binding.localVideo.release();
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.remoteVideo.release();
            releaseMujiGrid();
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            if (isPictureInPicture()) {
                binding.appBarLayout.setVisibility(View.GONE);
                binding.pipPlaceholder.setVisibility(View.VISIBLE);
                if (Arrays.asList(
                                RtpEndUserState.APPLICATION_ERROR,
                                RtpEndUserState.CONNECTIVITY_ERROR,
                                RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    binding.pipWarning.setVisibility(View.VISIBLE);
                    binding.pipWaiting.setVisibility(View.GONE);
                } else {
                    binding.pipWarning.setVisibility(View.GONE);
                    binding.pipWaiting.setVisibility(View.GONE);
                }
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                binding.pipPlaceholder.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.appBarLayout.setVisibility(View.GONE);
            binding.pipPlaceholder.setVisibility(View.VISIBLE);
            binding.pipWarning.setVisibility(View.GONE);
            binding.pipWaiting.setVisibility(View.VISIBLE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            return;
        }
        final Optional<VideoTrack> localVideoTrack = getLocalVideoTrack();
        if (localVideoTrack.isPresent() && !isPictureInPicture()) {
            ensureSurfaceViewRendererIsSetup(binding.localVideo);
            // paint local view over remote view
            binding.localVideo.setZOrderMediaOverlay(true);
            binding.localVideo.setMirror(requireRtpConnection().isFrontCamera());
            addSink(localVideoTrack.get(), binding.localVideo);
            binding.localVideo.setVisibility(View.VISIBLE);
            binding.localVideo.bringToFront();
        } else {
            binding.localVideo.setVisibility(View.GONE);
        }
        // XEP-0272 Muji group call: render every participant in the grid instead of a single
        // remote video. (The local self-preview above is shared across legs — one camera.)
        if (isMujiCall()) {
            updateMujiGrid(state);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            return;
        }
        final Optional<VideoTrack> remoteVideoTrack = getRemoteVideoTrack();
        if (remoteVideoTrack.isPresent()) {
            ensureSurfaceViewRendererIsSetup(binding.remoteVideo);
            addSink(remoteVideoTrack.get(), binding.remoteVideo);
            binding.remoteVideo.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            if (state == RtpEndUserState.CONNECTED) {
                binding.appBarLayout.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.VISIBLE);
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.GONE);
            }
            if (isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()) {
                binding.pipLocalMicOffIndicator.setVisibility(View.VISIBLE);
            } else {
                binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
        }
    }

    /**
     * Muji: after re-binding to a surviving leg (because the previously-bound one ended/errored),
     * fully refresh the call UI for the new leg. The dying leg's last state update may have left
     * the in-call buttons (hang up / mute / …) hidden — a transitional or END_CARD state is not in
     * {@link #STATES_CONSIDERED_CONNECTED} — so the buttons must be re-shown for the
     * still-CONNECTED conference, otherwise they vanish when a participant leaves and never come
     * back.
     */
    private void refreshBoundMujiLegUi() {
        resetVisibilityToggleExecutor();
        final Set<Media> media = getMedia();
        final ContentAddition contentAddition = getPendingContentAddition();
        updateStateDisplay(RtpEndUserState.CONNECTED, media, contentAddition);
        updateButtonConfiguration(RtpEndUserState.CONNECTED, media, contentAddition);
        updateVideoViews(RtpEndUserState.CONNECTED);
        updateMujiGrid(RtpEndUserState.CONNECTED);
        updateUsingAccount(null);
        // the ended leg may have released the screen: keep it on for the continuing call
        putScreenInCallMode(media);
    }

    /**
     * Muji: the bound leg `endedSid` ended — if the conference still has another live leg, re-bind
     * this activity to it so the call UI continues (only the leaver's tile drops). Returns false (→
     * caller finishes) when no other leg remains, e.g. the local user left the whole call.
     */
    private boolean rebindToAnotherMujiLeg(final String endedSid) {
        final JingleRtpConnection current =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (current == null || current.getMujiRoom() == null || xmppConnectionService == null) {
            return false;
        }
        final List<JingleRtpConnection> connections =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConnections(current.getId().account, current.getMujiRoom());
        Log.d(
                Config.LOGTAG,
                "muji: bound leg "
                        + endedSid
                        + " ended; room="
                        + current.getMujiRoom()
                        + " has "
                        + connections.size()
                        + " live leg(s)");
        for (final JingleRtpConnection connection : connections) {
            if (connection.getId().sessionId.equals(endedSid)) {
                continue;
            }
            if (END_CARD.contains(connection.getEndUserState())
                    || connection.getEndUserState() == RtpEndUserState.ENDED) {
                continue;
            }
            this.rtpConnectionReference = new WeakReference<>(connection);
            resetIntent(
                    connection.getId().account,
                    connection.getId().with,
                    connection.getId().sessionId);
            Log.d(
                    Config.LOGTAG,
                    "muji: rebinding to surviving leg " + connection.getId().sessionId);
            return true;
        }
        Log.d(Config.LOGTAG, "muji: no surviving leg — finishing call screen");
        return false;
    }

    /** Whether the call on screen is a XEP-0272 Muji group call (vs. a 1:1 call). */
    private boolean isMujiCall() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return (connection != null && connection.getMujiRoom() != null) || mujiRoom != null;
    }

    /**
     * Sync the participant grid for a Muji group call: one cell per conference leg that has a
     * remote video track. Idempotent — called from {@code updateVideoViews} and the periodic tick
     * (so a leg that connects without targeting this activity's session still appears). Renderers
     * are created/initialised once and released when their leg leaves or the call ends.
     */
    private void updateMujiGrid(final RtpEndUserState state) {
        JingleRtpConnection primary =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (primary == null
                && this.mujiRoom != null
                && this.mujiAccount != null
                && xmppConnectionService != null) {
            for (final JingleRtpConnection c :
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getMujiConnections(this.mujiAccount, this.mujiRoom)) {
                // Only adopt a still-live leg as the primary. A terminated/errored leg (e.g. one
                // whose verification failed) reports an empty media set, which would crash the
                // button configuration ("Media must not be empty").
                final RtpEndUserState legState = c.getEndUserState();
                if (legState == RtpEndUserState.ENDED || END_CARD.contains(legState)) {
                    continue;
                }
                primary = c;
                this.rtpConnectionReference = new WeakReference<>(c);
                runOnUiThread(() -> {
                    updateVideoViews(state);
                    final Set<Media> media = getMedia();
                    if (media.isEmpty()) {
                        return;
                    }
                    updateStateDisplay(state, media, getPendingContentAddition());
                    updateButtonConfiguration(state, media, getPendingContentAddition());
                });
                break;
            }
        }
        if (primary == null || primary.getMujiRoom() == null || xmppConnectionService == null) {
            return;
        }
        if (isPictureInPicture() || !STATES_CONSIDERED_CONNECTED.contains(state)) {
            // Only show the grid for an in-progress (non-PiP) call.
            binding.mujiVideoGrid.setVisibility(View.GONE);
            // Fail closed: the aggregate isn't recomputed here, so don't keep a stale one.
            mujiTrust = 0;
            mujiTrustLegacy = false;
            return;
        }
        final Account account = primary.getId().account;
        final List<JingleRtpConnection> connections =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConferenceManager()
                        .getConnections(account, primary.getMujiRoom());
        final Conversation muc = xmppConnectionService.find(account, Jid.of(primary.getMujiRoom()));
        boolean changed = false;
        boolean anyVideo = false;
        int conferenceTrust = Integer.MAX_VALUE;
        boolean conferenceTrustLegacy = false;
        final Set<String> live = new HashSet<>();
        for (final JingleRtpConnection connection : connections) {
            // A leg that is still connecting (or died) has no participant to show yet.
            if (!STATES_CONSIDERED_CONNECTED.contains(connection.getEndUserState())) {
                continue;
            }
            final int legTrust = connection.getCallTrustLevel();
            final boolean legLegacy = connection.isCallVerifiedLegacy();
            conferenceTrust = Math.min(conferenceTrust, legTrust);
            conferenceTrustLegacy |= legLegacy;
            final String sid = connection.getId().sessionId;
            live.add(sid);
            MujiTile tile = mujiTiles.get(sid);
            if (tile == null) {
                tile = createMujiTile(connection, muc);
                mujiTiles.put(sid, tile);
                binding.mujiVideoGrid.addView(tile.root);
                changed = true;
            }
            // The remote track appears (or is replaced) when the participant turns their camera
            // on mid-call — re-bind instead of keeping a renderer on a stale/absent track.
            final VideoTrack remote = connection.getRemoteVideoTrack().orNull();
            if (remote != tile.track) {
                bindMujiTileVideo(tile, remote, connection);
            }
            if (legTrust > 0) {
                setTrustIcon(tile.lock, legTrust, legLegacy);
                tile.lock.setVisibility(View.VISIBLE);
            } else {
                tile.lock.setVisibility(View.GONE);
            }
            anyVideo |= tile.track != null;
        }
        // No connected leg → nothing is authenticated yet.
        this.mujiTrust = conferenceTrust == Integer.MAX_VALUE ? 0 : conferenceTrust;
        this.mujiTrustLegacy = conferenceTrustLegacy;
        updateVerifiedShield(this.mujiTrust, this.mujiTrustLegacy);
        // Drop tiles of participants that left.
        for (final Iterator<Map.Entry<String, MujiTile>> it = mujiTiles.entrySet().iterator();
                it.hasNext(); ) {
            final Map.Entry<String, MujiTile> entry = it.next();
            if (!live.contains(entry.getKey())) {
                releaseMujiTile(entry.getValue());
                binding.mujiVideoGrid.removeView(entry.getValue().root);
                it.remove();
                changed = true;
            }
        }
        // The in-call buttons only auto-hide when the bound leg is a video leg; otherwise they
        // stay on screen, so keep the tiles (and their name labels) clear of them.
        final int bottomInset = isInConnectedVideoCall() ? 0 : binding.buttonRow.getHeight();
        if (binding.mujiVideoGrid.getPaddingBottom() != bottomInset) {
            binding.mujiVideoGrid.setPadding(0, 0, 0, bottomInset);
            changed = true;
        }
        // Only re-lay-out when the participant set changed or the grid was resized (orientation),
        // not every tick — otherwise the constant setLayoutParams triggers endless re-measures.
        final int gw = binding.mujiVideoGrid.getWidth();
        final int gh = binding.mujiVideoGrid.getHeight();
        if (changed || gw != mujiLaidOutWidth || gh != mujiLaidOutHeight) {
            if (layoutMujiGrid()) {
                mujiLaidOutWidth = gw;
                mujiLaidOutHeight = gh;
            }
        }
        binding.mujiVideoGrid.setVisibility(mujiTiles.isEmpty() ? View.GONE : View.VISIBLE);
        if (mujiGridHasVideo != anyVideo) {
            mujiGridHasVideo = anyVideo;
            // someone's video appeared (or the last one went): portrait lock on/off
            lockOrientation(getMedia());
        }
        if (mujiTiles.isEmpty()) {
            return;
        }
        if (mujiConnectedSince == 0) {
            mujiConnectedSince = android.os.SystemClock.elapsedRealtime();
        }
        if (anyVideo) {
            binding.appBarLayout.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            binding.appBarLayout.setVisibility(View.VISIBLE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (binding.localVideo.getVisibility() == View.VISIBLE) {
            binding.localVideo.bringToFront();
        }
    }

    private MujiTile createMujiTile(
            final JingleRtpConnection connection, @Nullable final Conversation muc) {
        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF202124);
        // Tapping a tile toggles the in-call controls, like the 1:1 remote view.
        root.setOnClickListener(this::onVideoScreenClick);
        final int avatarSize = getResources().getDimensionPixelSize(R.dimen.publish_avatar_size);
        final android.widget.ImageView avatar = new android.widget.ImageView(this);
        root.addView(
                avatar,
                new android.widget.FrameLayout.LayoutParams(
                        avatarSize, avatarSize, android.view.Gravity.CENTER));
        final android.widget.TextView name = new android.widget.TextView(this);
        final int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        name.setPadding(pad, pad / 2, pad, pad / 2);
        name.setTextColor(0xFFFFFFFF);
        name.setBackgroundColor(0x66000000);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(
                name,
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP | android.view.Gravity.START));
        final Jid with = connection.getId().with;
        final MucOptions.User user =
                muc == null ? null : muc.getMucOptions().findUserByRealJid(with);
        final Contact contact = connection.getId().account.getRoster().getContact(with);
        name.setText(
                user != null && user.getName() != null ? user.getName() : contact.getDisplayName());
        if (user != null) {
            AvatarWorkerTask.loadAvatar(user, avatar, R.dimen.publish_avatar_size);
        } else {
            AvatarWorkerTask.loadAvatar(contact, avatar, R.dimen.publish_avatar_size);
        }
        final android.widget.ImageView lock = new android.widget.ImageView(this);
        final int lockSize = Math.round(16 * getResources().getDisplayMetrics().density);
        final android.widget.FrameLayout.LayoutParams lockParams =
                new android.widget.FrameLayout.LayoutParams(
                        lockSize, lockSize, android.view.Gravity.TOP | android.view.Gravity.END);
        lockParams.setMargins(pad, pad, pad, pad);
        // Same tint as the toolbar shield (the drawables tint to colorControlNormal, which can be
        // dark — unreadable on the dark tile).
        androidx.core.widget.ImageViewCompat.setImageTintList(
                lock,
                android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                                this, R.color.light_green_600)));
        lock.setVisibility(View.GONE);
        root.addView(lock, lockParams);
        return new MujiTile(root, avatar, name, lock);
    }

    /** Show `track` in the tile (or the avatar when null), detaching the previous track first. */
    private void bindMujiTileVideo(
            final MujiTile tile,
            @Nullable final VideoTrack track,
            final JingleRtpConnection connection) {
        detachMujiTileTrack(tile);
        if (track == null) {
            if (tile.renderer != null) {
                tile.root.removeView(tile.renderer);
                tile.renderer.release();
                tile.renderer = null;
            }
            tile.avatar.setVisibility(View.VISIBLE);
            return;
        }
        if (tile.renderer == null) {
            final SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
            try {
                renderer.init(connection.getEglBaseContext(), null);
            } catch (final IllegalStateException e) {
                Log.w(Config.LOGTAG, "muji: renderer already initialised", e);
            }
            // Fit (not crop) so a portrait phone shows the whole frame — FILL would cut it off.
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            renderer.setOnClickListener(this::onVideoScreenClick);
            // below the name label
            tile.root.addView(
                    renderer,
                    0,
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            tile.renderer = renderer;
        }
        addSink(track, tile.renderer);
        tile.track = track;
        tile.avatar.setVisibility(View.GONE);
    }

    private static void detachMujiTileTrack(final MujiTile tile) {
        final VideoTrack track = tile.track;
        tile.track = null;
        if (track != null && tile.renderer != null) {
            try {
                track.removeSink(tile.renderer);
            } catch (final IllegalStateException e) {
                // track already disposed together with its leg
            }
        }
    }

    private static void releaseMujiTile(final MujiTile tile) {
        detachMujiTileTrack(tile);
        if (tile.renderer != null) {
            tile.renderer.release();
            tile.renderer = null;
        }
    }

    /**
     * Arrange the grid with explicit pixel cell sizes (GridLayout weight distribution is unreliable
     * and overflowed the screen in portrait → tiles cut off). The column count is the one that
     * makes the cells closest to square for this many tiles in this grid — e.g. a phone in portrait
     * stacks 2–4 participants vertically and uses two columns from 5 on, while landscape goes side
     * by side.
     *
     * @return false if the grid has no size yet (the tick retries)
     */
    private boolean layoutMujiGrid() {
        final int count = binding.mujiVideoGrid.getChildCount();
        if (count == 0) {
            return true;
        }
        final int gridW =
                binding.mujiVideoGrid.getWidth()
                        - binding.mujiVideoGrid.getPaddingLeft()
                        - binding.mujiVideoGrid.getPaddingRight();
        final int gridH =
                binding.mujiVideoGrid.getHeight()
                        - binding.mujiVideoGrid.getPaddingTop()
                        - binding.mujiVideoGrid.getPaddingBottom();
        if (gridW <= 0 || gridH <= 0) {
            return false;
        }
        final int columns = CallGrid.columns(count, gridW, gridH);
        final int rows = (int) Math.ceil((double) count / columns);
        final int cellW = gridW / columns;
        final int cellH = gridH / rows;
        // GridLayout validates child specs against the column/row count at BOTH setLayoutParams
        // and setColumn/RowCount — so changing the count while any tile references an
        // out-of-range cell crashes (growing crashes setLayoutParams, shrinking crashes
        // setColumnCount). Do it in three safe steps: (1) park every tile at cell (0,0) — always
        // valid — (2) set the new counts, (3) place each tile in its cell.
        for (int i = 0; i < count; i++) {
            final GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.rowSpec = GridLayout.spec(0);
            lp.columnSpec = GridLayout.spec(0);
            binding.mujiVideoGrid.getChildAt(i).setLayoutParams(lp);
        }
        binding.mujiVideoGrid.setColumnCount(columns);
        binding.mujiVideoGrid.setRowCount(rows);
        for (int i = 0; i < count; i++) {
            final View child = binding.mujiVideoGrid.getChildAt(i);
            final GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
            lp.rowSpec = GridLayout.spec(i / columns);
            lp.columnSpec = GridLayout.spec(i % columns);
            child.setLayoutParams(lp);
        }
        return true;
    }

    /** Release + remove all participant tiles (call end / leaving the screen). */
    private void releaseMujiGrid() {
        for (final MujiTile tile : mujiTiles.values()) {
            releaseMujiTile(tile);
        }
        mujiTiles.clear();
        binding.mujiVideoGrid.removeAllViews();
        binding.mujiVideoGrid.setVisibility(View.GONE);
        binding.mujiVideoGrid.setPadding(0, 0, 0, 0);
        mujiLaidOutWidth = -1;
        mujiLaidOutHeight = -1;
        mujiConnectedSince = 0;
        mujiGridHasVideo = false;
        mujiTrust = 0;
        mujiTrustLegacy = false;
        final var actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(null);
        }
    }

    private Optional<VideoTrack> getLocalVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getLocalVideoTrack();
    }

    private Optional<VideoTrack> getRemoteVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getRemoteVideoTrack();
    }

    private void disableMicrophone(final View view) {
        setMicrophoneEnabled(false);
    }

    private void enableMicrophone(final View view) {
        setMicrophoneEnabled(true);
    }

    private void setMicrophoneEnabled(final boolean enabled) {
        resetVisibilityExecutorShowButtons();
        try {
            // Muji: apply to EVERY leg, otherwise only one peer is (un)muted.
            boolean changed = false;
            for (final JingleRtpConnection leg : callLegs()) {
                changed |= leg.setMicrophoneEnabled(enabled);
            }
            if (isMujiCall()) {
                final JingleRtpConnection primary =
                        rtpConnectionReference == null ? null : rtpConnectionReference.get();
                final Account account = primary != null ? primary.getAccount() : mujiAccount;
                final String room = primary != null ? primary.getMujiRoom() : mujiRoom;
                if (account != null && room != null) {
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getMujiConferenceManager()
                            .setMicrophoneEnabled(account, room, enabled);
                }
                changed = true;
            }
            if (changed) {
                updateInCallButtonConfiguration();
            }
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Every per-pair leg the in-call controls (mute / video) should apply to: all conference legs
     * for a Muji group call, or just the single connection for a 1:1 call.
     */
    private List<JingleRtpConnection> callLegs() {
        final JingleRtpConnection primary =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (primary == null) {
            return Collections.emptyList();
        }
        if (primary.getMujiRoom() == null || xmppConnectionService == null) {
            return Collections.singletonList(primary);
        }
        final List<JingleRtpConnection> legs =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .getMujiConnections(primary.getId().account, primary.getMujiRoom());
        return legs.isEmpty() ? Collections.singletonList(primary) : legs;
    }

    private void switchToEarpiece(final View view) {
        try {
            setAudioDevice(CallIntegration.AudioDevice.EARPIECE);
            acquireProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToSpeaker(final View view) {
        try {
            setAudioDevice(CallIntegration.AudioDevice.SPEAKER_PHONE);
            releaseProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void retry(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.of(intent.getStringExtra(EXTRA_WITH));
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final String action = intent.getAction();
        final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
        this.rtpConnectionReference = null;
        Log.d(Config.LOGTAG, "attempting retry with " + with.toString());
        CallIntegrationConnectionService.placeCall(xmppConnectionService, account, with, media);
    }

    private void exit(final View view) {
        finish();
    }

    private void recordVoiceMail(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.of(intent.getStringExtra(EXTRA_WITH));
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(account, with, false, true);
        final Intent launchIntent = new Intent(this, ConversationsActivity.class);
        launchIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        launchIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        launchIntent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(
                ConversationsActivity.EXTRA_POST_INIT_ACTION,
                ConversationsActivity.POST_ACTION_RECORD_VOICE);
        startActivity(launchIntent);
        finish();
    }

    private Contact getWith() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final String room = connection != null ? connection.getMujiRoom() : this.mujiRoom;
        if (room != null) {
            final Account account =
                    connection != null ? connection.getId().account : this.mujiAccount;
            if (account != null && xmppConnectionService != null) {
                final Conversation muc = xmppConnectionService.find(account, Jid.of(room));
                return muc != null
                        ? muc.getContact()
                        : account.getRoster().getContact(Jid.of(room));
            }
        }
        if (connection == null) {
            return null;
        }
        final AbstractJingleConnection.Id id = connection.getId();
        final Account account = id.account;
        return account.getRoster().getContact(id.with);
    }

    private JingleRtpConnection requireRtpConnection() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            throw new IllegalStateException("No RTP connection found");
        }
        return connection;
    }

    private CallIntegration requireCallIntegration() {
        return requireOngoingRtpSession().getCallIntegration();
    }

    private OngoingRtpSession requireOngoingRtpSession() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            return connection;
        }
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        final var account = extractAccount(currentIntent);
        if (withExtra == null) {
            throw new IllegalStateException("Current intent has no EXTRA_WITH");
        }
        final var matching =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .matchingProposal(account, Jid.of(withExtra));
        if (matching.isPresent()) {
            return matching.get();
        }
        throw new IllegalStateException("No matching session proposal");
    }

    @Override
    public void onJingleRtpConnectionUpdate(
            Account account, Jid with, final String sessionId, RtpEndUserState state) {
        Log.d(Config.LOGTAG, "onJingleRtpConnectionUpdate(" + state + ")");
        // Only release the screen once this screen's own call shows its end card: a group call's
        // individual legs (or an unrelated call) reaching an end state must not let it turn off.
        if (with.isBareJid()) {
            // TODO check for ENDED
            updateRtpSessionProposalState(account, with, state);
            return;
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "not reinitializing session");
                if (mujiRoom == null) {
                    releaseScreenFromCallMode();
                }
                return;
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId);
            return;
        }
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        // Muji: the bound leg's trust says nothing about the other legs — use the aggregate.
        final boolean muji = isMujiCall();
        final int callTrust = muji ? mujiTrust : requireRtpConnection().getCallTrustLevel();
        final boolean callTrustLegacy =
                muji ? mujiTrustLegacy : requireRtpConnection().isCallVerifiedLegacy();
        final Set<Media> media = getMedia();
        lockOrientation(media);
        final ContentAddition contentAddition = getPendingContentAddition();
        final Contact contact = getWith();
        if (account == id.account && id.with.equals(with) && id.sessionId.equals(sessionId)) {
            if (state == RtpEndUserState.ENDED) {
                // Muji: one participant leaving ends only their leg — if other legs are still
                // live, keep the call going by re-binding to one of them instead of finishing.
                if (rebindToAnotherMujiLeg(sessionId)) {
                    runOnUiThread(this::refreshBoundMujiLegUi);
                    return;
                }
                finish();
                return;
            }
            resetVisibilityToggleExecutor();
            runOnUiThread(() -> {
                updateStateDisplay(state, media, contentAddition);
                updateVerifiedShield(
                        STATES_SHOWING_SWITCH_TO_CHAT.contains(state) ? callTrust : 0,
                        callTrustLegacy);
                updateButtonConfiguration(state, media, contentAddition);
                updateVideoViews(state);
                updateIncomingCallScreen(state, contact);
                updateSupportWarning(state, contact);
                invalidateOptionsMenu();
            });
            if (END_CARD.contains(state)) {
                // Muji: a single leg erroring/ending shouldn't tear down the whole conference UI
                // if other legs are alive — re-bind to one of them.
                if (rebindToAnotherMujiLeg(sessionId)) {
                    runOnUiThread(this::refreshBoundMujiLegUi);
                    return;
                }
                Log.d(Config.LOGTAG, "end card reached");
                // Muji: the conference outlives its last leg while peers are still in the room
                // (the re-mesh re-establishes it), so keep the screen on until we've left.
                if (!isStillInMujiCall()) {
                    releaseScreenFromCallMode();
                }
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                resetIntent(account, with, state, rtpConnection.getMedia());
                releaseVideoTracks(rtpConnection);
                this.rtpConnectionReference = null;
            }
        } else {
            Log.d(Config.LOGTAG, "received update for other rtp session");
        }
    }

    @Override
    public void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        Log.d(
                Config.LOGTAG,
                "onAudioDeviceChanged in activity: selected:"
                        + selectedAudioDevice
                        + ", available:"
                        + availableAudioDevices);
        if (activeMujiConference() != null) {
            // group call (legs connected or not): the conference's shared audio manager changed —
            // redraw from the conference, not from the bound leg (which may not report media yet)
            updateInCallButtonConfiguration();
            putProximityWakeLockInProperState(selectedAudioDevice);
            return;
        }
        try {
            final OngoingRtpSession ongoingRtpSession = requireOngoingRtpSession();
            final RtpEndUserState endUserState;
            if (ongoingRtpSession instanceof JingleRtpConnection jingleRtpConnection) {
                endUserState = jingleRtpConnection.getEndUserState();
            } else {
                // for session proposals all end user states are functionally the same
                endUserState = RtpEndUserState.RINGING;
            }
            final Set<Media> media = ongoingRtpSession.getMedia();
            if (END_CARD.contains(endUserState)) {
                Log.d(
                        Config.LOGTAG,
                        "onAudioDeviceChanged() nothing to do because end card has been reached");
            } else {
                if (!media.isEmpty()
                        && !showsVideoControls(media)
                        && STATES_SHOWING_SPEAKER_CONFIGURATION.contains(endUserState)) {
                    final CallIntegration callIntegration = requireCallIntegration();
                    updateInCallButtonConfigurationSpeaker(
                            callIntegration.getSelectedAudioDevice(),
                            callIntegration.getAudioDevices().size());
                }
                Log.d(
                        Config.LOGTAG,
                        "put proximity wake lock into proper state after device update");
                putProximityWakeLockInProperState(selectedAudioDevice);
            }
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "RTP connection was not available when audio device changed");
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull @NotNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("dialpad_visible", binding.dialpad.getVisibility() == View.VISIBLE);
    }

    private void updateRtpSessionProposalState(
            final Account account, final Jid with, final RtpEndUserState state) {
        if (isMujiCall()) {
            // JMI proposals are always 1:1 calls (Muji legs skip JMI): a participant's 1:1 call
            // attempt must not take over the group call screen
            return;
        }
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        if (withExtra == null) {
            return;
        }
        final Set<Media> media = actionToMedia(currentIntent.getStringExtra(EXTRA_LAST_ACTION));
        if (Jid.of(withExtra).asBareJid().equals(with)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "end card reached");
                releaseScreenFromCallMode();
            }
            runOnUiThread(() -> {
                updateVerifiedShield(0);
                updateStateDisplay(state);
                updateButtonConfiguration(state, media, null);
                updateIncomingCallScreen(state);
                updateSupportWarning(state, account.getRoster().getContact(with));
                invalidateOptionsMenu();
            });
            resetIntent(account, with, state, media);
        }
    }

    private void resetIntent(final Bundle extras) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtras(extras);
        setIntent(intent);
    }

    private void resetIntent(
            final Account account, Jid with, final RtpEndUserState state, final Set<Media> media) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toString());
        if (RtpCapability.jmiSupport(account.getRoster().getContact(with))) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toString());
        } else {
            intent.putExtra(EXTRA_WITH, with.toString());
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString());
        intent.putExtra(
                EXTRA_LAST_ACTION,
                media.contains(Media.VIDEO) ? ACTION_MAKE_VIDEO_CALL : ACTION_MAKE_VOICE_CALL);
        setIntent(intent);
    }

    private static boolean emptyReference(final WeakReference<?> weakReference) {
        return weakReference == null || weakReference.get() == null;
    }

    private enum Event {
        ON_BACKEND_CONNECTED,
        ON_NEW_INTENT
    }
}
