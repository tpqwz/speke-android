package eu.siacs.conversations.services;

import static eu.siacs.conversations.utils.Compatibility.s;
import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.security.KeyChain;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;
import androidx.annotation.BoolRes;
import androidx.annotation.IntegerRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;
import de.monocles.chat.EmojiSearch;
import de.monocles.chat.StickersMigration;
import de.monocles.chat.WebxdcUpdate;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.android.JabberIdContact;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.crypto.PgpDecryptionService;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.OnRenameListener;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.persistance.UnifiedPushDatabase;
import eu.siacs.conversations.receiver.SystemEventReceiver;
import eu.siacs.conversations.ui.ChooseAccountForProfilePictureActivity;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.RtpSessionActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.ConversationsFileObserver;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.MediaMetadataStripper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.utils.QuickLoader;
import eu.siacs.conversations.utils.ReplacingSerialSingleThreadExecutor;
import eu.siacs.conversations.utils.ReplacingTaskManager;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.ThemeHelper;
import eu.siacs.conversations.utils.TorServiceUtils;
import eu.siacs.conversations.utils.TranscoderStrategies;
import eu.siacs.conversations.utils.WakeLockHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnContactStatusChanged;
import eu.siacs.conversations.xmpp.OnGatewayResult;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnMessageAcknowledged;
import eu.siacs.conversations.xmpp.OnStatusChanged;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.jid.OtrJidHelper;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import im.conversations.android.xmpp.model.stanza.Iq;
import io.ipfs.cid.Cid;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import me.leolin.shortcutbadger.ShortcutBadger;
import net.java.otr4j.session.Session;
import net.java.otr4j.session.SessionID;
import net.java.otr4j.session.SessionImpl;
import net.java.otr4j.session.SessionStatus;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.conscrypt.Conscrypt;
import org.jxmpp.stringprep.libidn.LibIdnXmppStringprep;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

public class XmppConnectionService extends Service {

    public static final String ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations";
    public static final String ACTION_MARK_AS_READ = "mark_as_read";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_CLEAR_MESSAGE_NOTIFICATION = "clear_message_notification";
    public static final String ACTION_CLEAR_MISSED_CALL_NOTIFICATION =
            "clear_missed_call_notification";
    public static final String ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error";
    public static final String ACTION_TRY_AGAIN = "try_again";

    public static final String ACTION_TEMPORARILY_DISABLE = "temporarily_disable";
    public static final String ACTION_PING = "ping";
    public static final String ACTION_IDLE_PING = "idle_ping";
    public static final String ACTION_INTERNAL_PING = "internal_ping";
    public static final String ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh";
    public static final String ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received";
    public static final String ACTION_DISMISS_CALL = "dismiss_call";
    public static final String ACTION_END_CALL = "end_call";
    public static final String ACTION_STOP_LIVE_LOCATION = "stop_live_location";

    /** Sent by {@link AppSessionLock}: go offline cleanly, finish all writes, then terminate. */
    public static final String ACTION_LOCK = "lock_session";
    public static final String ACTION_STARTING_CALL = "starting_call";
    public static final String ACTION_CALL_INTEGRATION_SERVICE_STARTED =
            "call_integration_service_started";
    public static final String ACTION_POST_CONNECTIVITY_CHANGE =
            "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE";
    public static final String ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS =
            "eu.siacs.conversations.UNIFIED_PUSH_RENEW";
    public static final String ACTION_QUICK_LOG = "eu.siacs.conversations.QUICK_LOG";
    public static final String ACTION_EXPIRE_MESSAGES = "eu.siacs.conversations.EXPIRE_MESSAGES";

    private static final String SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp";

    public final CountDownLatch restoredFromDatabaseLatch = new CountDownLatch(1);
    private static final Executor FILE_OBSERVER_EXECUTOR = Executors.newSingleThreadExecutor();
    public static final Executor FILE_ATTACHMENT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Executor COPY_TO_DOWNLOAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService internalPingExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService userTuneUpdateExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingUserTuneUpdate;

    private final ScheduledExecutorService storyRetractionExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService storyCacheExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private static final SerialSingleThreadExecutor VIDEO_COMPRESSION_EXECUTOR =
            new SerialSingleThreadExecutor("VideoCompression");
    private final SerialSingleThreadExecutor mDatabaseWriterExecutor =
            new SerialSingleThreadExecutor("DatabaseWriter");
    private final SerialSingleThreadExecutor mDatabaseReaderExecutor =
            new SerialSingleThreadExecutor("DatabaseReader");
    private final SerialSingleThreadExecutor mNotificationExecutor =
            new SerialSingleThreadExecutor("NotificationExecutor");
    private final ReplacingTaskManager mRosterSyncTaskManager = new ReplacingTaskManager();
    private final IBinder mBinder = new XmppConnectionBinder();
    private final List<Conversation> conversations = new CopyOnWriteArrayList<>();
    private final IqGenerator mIqGenerator = new IqGenerator(this);
    private final Set<String> mInProgressAvatarFetches = new HashSet<>();
    private final Set<String> mOmittedPepAvatarFetches = new HashSet<>();
    private final HashSet<Jid> mLowPingTimeoutMode = new HashSet<>();
    private final Consumer<Iq> mDefaultIqHandler =
            packet -> {
                if (packet.getType() != Iq.Type.RESULT) {
                    final var error = packet.getError();
                    String text = error != null ? error.findChildContent("text") : null;
                    if (text != null) {
                        Log.d(Config.LOGTAG, "received iq error: " + text);
                    }
                }
            };
    public DatabaseBackend databaseBackend;
    private eu.siacs.conversations.EncryptionException mCriticalError = null;
    private boolean mNeedsPassword = false;
    private volatile boolean mDatabaseReady = false;
    private final List<Runnable> mDbReadyCallbacks = new ArrayList<>();

    public eu.siacs.conversations.EncryptionException getCriticalError() {
        return mCriticalError;
    }

    public boolean needsPassword() {
        return mNeedsPassword;
    }

    public boolean isDatabaseReady() {
        return mDatabaseReady;
    }

    /** True while the background DB-init thread is running (not yet ready, no error yet). */
    public boolean isInitializing() {
        return !mDatabaseReady && !mNeedsPassword && mCriticalError == null;
    }

    /**
     * Run {@code r} on the main thread once the database is ready (or in an error/needs-password
     * state). If already in a terminal state, {@code r} is posted immediately. Must be called from
     * the main thread.
     */
    public void runWhenDatabaseReady(final Runnable r) {
        if (!isInitializing()) {
            mLiveLocationHandler.post(r);
        } else {
            mDbReadyCallbacks.add(r);
        }
    }

    private void notifyDbReadyCallbacks() {
        for (final Runnable r : mDbReadyCallbacks) r.run();
        mDbReadyCallbacks.clear();
    }

    private Multimap<String, String> mutedMucUsers;
    private final ReplacingSerialSingleThreadExecutor mContactMergerExecutor =
            new ReplacingSerialSingleThreadExecutor("ContactMerger");
    private final ReplacingSerialSingleThreadExecutor mStickerScanExecutor =
            new ReplacingSerialSingleThreadExecutor("StickerScan");
    private long mLastActivity = 0;
    private long mLastMucPing = 0;
    private Map<String, Message> mScheduledMessages = new HashMap<>();
    private long mLastStickerRescan = 0;
    private android.location.LocationManager mLiveLocationAndroidManager = null;
    private final java.util.concurrent.ConcurrentHashMap<String, OutgoingLiveInfo>
            mOutgoingLiveSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final android.os.Handler mLiveLocationHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler mMainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final AppSettings appSettings = new AppSettings(this);
    private final FileBackend fileBackend = new FileBackend(this);
    private MemorizingTrustManager mMemorizingTrustManager;
    private final NotificationService mNotificationService = new NotificationService(this);
    private final UnifiedPushBroker unifiedPushBroker = new UnifiedPushBroker(this);
    private final ChannelDiscoveryService mChannelDiscoveryService =
            new ChannelDiscoveryService(this);
    private final ShortcutService mShortcutService = new ShortcutService(this);
    private final AtomicBoolean mInitialAddressbookSyncCompleted = new AtomicBoolean(false);
    private final AtomicBoolean mOngoingVideoTranscoding = new AtomicBoolean(false);
    private final AtomicBoolean mForceDuringOnCreate = new AtomicBoolean(false);
    private final AtomicReference<OngoingCall> ongoingCall = new AtomicReference<>();
    private final MessageGenerator mMessageGenerator = new MessageGenerator(this);
    public OnContactStatusChanged onContactStatusChanged =
            (contact, online) -> {
                final var conversation = find(contact);
                if (conversation == null) {
                    return;
                }
                if (online) {
                    conversation.endOtrIfNeeded();
                    if (contact.getPresences().size() == 1) {
                        sendUnsentMessages(conversation);
                    }
                    fetchStories(conversation.getAccount(), conversation.getContact());
                } else {
                    // check if the resource we are haveing a conversation with is still online
                    if (conversation.hasValidOtrSession()) {
                        String otrResource =
                                conversation.getOtrSession().getSessionID().getUserID();
                        if (!(Arrays.asList(contact.getPresences().toResourceArray())
                                .contains(otrResource))) {
                            conversation.endOtrIfNeeded();
                        }
                    }
                }
            };
    private final PresenceGenerator mPresenceGenerator = new PresenceGenerator(this);
    private List<Account> accounts = new ArrayList<>();
    private final JingleConnectionManager mJingleConnectionManager =
            new JingleConnectionManager(this);
    private final HttpConnectionManager mHttpConnectionManager = new HttpConnectionManager(this);
    private final AvatarService mAvatarService = new AvatarService(this);
    private final MessageArchiveService mMessageArchiveService = new MessageArchiveService(this);
    private final PushManagementService mPushManagementService = new PushManagementService(this);
    private final QuickConversationsService mQuickConversationsService =
            new QuickConversationsService(this);
    private final ConversationsFileObserver fileObserver =
            new ConversationsFileObserver(
                    Environment.getExternalStorageDirectory().getAbsolutePath()) {
                @Override
                public void onEvent(final int event, final File file) {
                    markFileDeleted(file);
                }
            };
    private final OnMessageAcknowledged mOnMessageAcknowledgedListener =
            new OnMessageAcknowledged() {

                @Override
                public boolean onMessageAcknowledged(
                        final Account account, final Jid to, final String id) {
                    if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                        final String sessionId =
                                id.substring(
                                        JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX
                                                .length());
                        mJingleConnectionManager.updateProposedSessionDiscovered(
                                account,
                                to,
                                sessionId,
                                JingleConnectionManager.DeviceDiscoveryState
                                        .SEARCHING_ACKNOWLEDGED);
                    }

                    final Jid bare = to.asBareJid();

                    for (final Conversation conversation : getConversations()) {
                        if (conversation.getAccount() == account
                                && conversation.getJid().asBareJid().equals(bare)) {
                            final Message message = conversation.findUnsentMessageWithUuid(id);
                            if (message != null) {
                                message.setStatus(Message.STATUS_SEND);
                                message.setErrorMessage(null);
                                databaseBackend.updateMessage(message, false);
                                return true;
                            }
                        }
                    }
                    return false;
                }
            };

    private final AtomicBoolean diallerIntegrationActive = new AtomicBoolean(false);

    public void setDiallerIntegrationActive(boolean active) {
        diallerIntegrationActive.set(active);
    }

    private volatile boolean destroyed = false;

    private int unreadCount = -1;

    // Ui callback listeners
    private final Set<OnConversationUpdate> mOnConversationUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnConversationUpdate, Boolean>());
    private final Set<OnShowErrorToast> mOnShowErrorToasts =
            Collections.newSetFromMap(new WeakHashMap<OnShowErrorToast, Boolean>());
    private final Set<OnAccountUpdate> mOnAccountUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnAccountUpdate, Boolean>());
    private final Set<OnCaptchaRequested> mOnCaptchaRequested =
            Collections.newSetFromMap(new WeakHashMap<OnCaptchaRequested, Boolean>());
    private final Set<OnRosterUpdate> mOnRosterUpdates =
            Collections.newSetFromMap(new WeakHashMap<OnRosterUpdate, Boolean>());
    private final Set<OnUpdateBlocklist> mOnUpdateBlocklist =
            Collections.newSetFromMap(new WeakHashMap<OnUpdateBlocklist, Boolean>());
    private final Set<OnMucRosterUpdate> mOnMucRosterUpdate =
            Collections.newSetFromMap(new WeakHashMap<OnMucRosterUpdate, Boolean>());
    private final Set<OnKeyStatusUpdated> mOnKeyStatusUpdated =
            Collections.newSetFromMap(new WeakHashMap<OnKeyStatusUpdated, Boolean>());
    private final Set<OnJingleRtpConnectionUpdate> onJingleRtpConnectionUpdate =
            Collections.newSetFromMap(new WeakHashMap<OnJingleRtpConnectionUpdate, Boolean>());

    private final Object LISTENER_LOCK = new Object();

    public final Set<String> FILENAMES_TO_IGNORE_DELETION = new HashSet<>();

    private final AtomicLong mLastExpiryRun = new AtomicLong(0);
    private final LruCache<Pair<String, String>, ServiceDiscoveryResult> discoCache =
            new LruCache<>(20);
    private final OnStatusChanged statusListener =
            new OnStatusChanged() {

                @Override
                public void onStatusChanged(final Account account) {
                    XmppConnection connection = account.getXmppConnection();
                    updateAccountUi();

                    if (account.getStatus() == Account.State.ONLINE
                            || account.getStatus().isError()) {
                        mQuickConversationsService.signalAccountStateChange();
                    }

                    if (account.getStatus() == Account.State.ONLINE) {
                        synchronized (mLowPingTimeoutMode) {
                            if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": leaving low ping timeout mode");
                            }
                        }
                        if (account.setShowErrorNotification(true)) {
                            databaseBackend.updateAccount(account);
                        }
                        mMessageArchiveService.executePendingQueries(account);
                        if (connection != null && connection.getFeatures().csi()) {
                            if (checkListeners()) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid() + " sending csi//inactive");
                                connection.sendInactive();
                            } else {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid() + " sending csi//active");
                                connection.sendActive();
                            }
                        }
                        List<Conversation> conversations = getConversations();
                        for (Conversation conversation : conversations) {
                            final boolean inProgressJoin;
                            synchronized (account.inProgressConferenceJoins) {
                                inProgressJoin =
                                        account.inProgressConferenceJoins.contains(conversation);
                            }
                            final boolean pendingJoin;
                            synchronized (account.pendingConferenceJoins) {
                                pendingJoin = account.pendingConferenceJoins.contains(conversation);
                            }
                            if (conversation.getAccount() == account
                                    && !pendingJoin
                                    && !inProgressJoin) {
                                if (!conversation.startOtrIfNeeded()) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": couldn't start OTR with "
                                                    + conversation.getContact().getJid()
                                                    + " when needed");
                                }
                                sendUnsentMessages(conversation);
                            }
                        }
                        final List<Conversation> pendingLeaves;
                        synchronized (account.pendingConferenceLeaves) {
                            pendingLeaves = new ArrayList<>(account.pendingConferenceLeaves);
                            account.pendingConferenceLeaves.clear();
                        }
                        for (Conversation conversation : pendingLeaves) {
                            leaveMuc(conversation);
                        }
                        final List<Conversation> pendingJoins;
                        synchronized (account.pendingConferenceJoins) {
                            pendingJoins = new ArrayList<>(account.pendingConferenceJoins);
                            account.pendingConferenceJoins.clear();
                        }
                        for (Conversation conversation : pendingJoins) {
                            joinMuc(conversation);
                        }
                        fetchOwnStories(account);
                        fixCommentsNodesAccessOnce(account);
                        sendLiveLocationStopForOrphanedSessions(account);
                        scheduleWakeUpCall(
                                Config.PING_MAX_INTERVAL * 1000L, account.getUuid().hashCode());
                    } else if (account.getStatus() == Account.State.OFFLINE
                            || account.getStatus() == Account.State.DISABLED
                            || account.getStatus() == Account.State.LOGGED_OUT) {
                        resetSendingToWaiting(account);
                        if (account.isConnectionEnabled() && isInLowPingTimeoutMode(account)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": went into offline state during low ping mode."
                                            + " reconnecting now");
                            reconnectAccount(account, true, false);
                        } else {
                            final int timeToReconnect = SECURE_RANDOM.nextInt(10) + 2;
                            scheduleWakeUpCall(timeToReconnect, account.getUuid().hashCode());
                        }
                    } else if (account.getStatus() == Account.State.REGISTRATION_SUCCESSFUL) {
                        databaseBackend.updateAccount(account);
                        reconnectAccount(account, true, false);
                    } else if (account.getStatus() != Account.State.CONNECTING
                            && account.getStatus() != Account.State.NO_INTERNET) {
                        resetSendingToWaiting(account);
                        if (connection != null && account.getStatus().isAttemptReconnect()) {
                            final boolean aggressive =
                                    account.getStatus() == Account.State.SEE_OTHER_HOST
                                            || hasJingleRtpConnection(account);
                            final int next = connection.getTimeToNextAttempt(aggressive);
                            final boolean lowPingTimeoutMode = isInLowPingTimeoutMode(account);
                            if (next <= 0) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": error connecting account. reconnecting now."
                                                + " lowPingTimeout="
                                                + lowPingTimeoutMode);
                                reconnectAccount(account, true, false);
                            } else {
                                final int attempt = connection.getAttempt() + 1;
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": error connecting account. try again in "
                                                + next
                                                + "s for the "
                                                + attempt
                                                + " time. lowPingTimeout="
                                                + lowPingTimeoutMode
                                                + ", aggressive="
                                                + aggressive);
                                scheduleWakeUpCall(next, account.getUuid().hashCode());
                                // connection threads can outlive onDestroy(), which shuts
                                // the executor down
                                if (aggressive && !destroyed) {
                                    try {
                                        internalPingExecutor.schedule(
                                                XmppConnectionService.this
                                                        ::manageAccountConnectionStatesInternal,
                                                (next * 1000L) + 50,
                                                TimeUnit.MILLISECONDS);
                                    } catch (final RejectedExecutionException e) {
                                        Log.d(
                                                Config.LOGTAG,
                                                "not scheduling reconnect; service destroyed");
                                    }
                                }
                            }
                        }
                    }
                    getNotificationService().updateErrorNotification();
                }
            };
    private OpenPgpServiceConnection pgpServiceConnection;
    private PgpEngine mPgpEngine = null;
    private WakeLock wakeLock;
    private LruCache<String, Drawable> mDrawableCache;
    private final BroadcastReceiver mInternalEventReceiver = new InternalEventReceiver();
    private final BroadcastReceiver mInternalRestrictedEventReceiver =
            new RestrictedEventReceiver(Arrays.asList(TorServiceUtils.ACTION_STATUS));
    private final BroadcastReceiver mInternalScreenEventReceiver = new InternalEventReceiver();
    private volatile EmojiSearch emojiSearch = null;

    private static String generateFetchKey(Account account, final Avatar avatar) {
        return account.getJid().asBareJid() + "_" + avatar.owner + "_" + avatar.sha1sum;
    }

    private boolean isInLowPingTimeoutMode(Account account) {
        synchronized (mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.getJid().asBareJid());
        }
    }

    public void startOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(true);
        toggleForegroundService();
    }

    public void stopOngoingVideoTranscodingForegroundNotification() {
        mOngoingVideoTranscoding.set(false);
        toggleForegroundService();
    }

    public boolean areMessagesInitialized() {
        return this.restoredFromDatabaseLatch.getCount() == 0;
    }

    public void copyAttachmentToDownloadsFolder(Message m, final UiCallback<Integer> callback) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                fileBackend.copyAttachmentToDownloadsFolder(m);
                if (callback != null) {
                    callback.success(-1);
                }
            } catch (FileBackend.FileCopyException e) {
                if (callback != null) {
                    callback.error(-1, e.getResId());
                }
            }
        });
    }

    public void copyAttachmentToDownloadsFolder(File file, final UiCallback<Integer> callback) {
        COPY_TO_DOWNLOAD_EXECUTOR.execute(() -> {
            try {
                fileBackend.copyAttachmentToDownloadsFolder(file);
                if (callback != null) {
                    callback.success(-1);
                }
            } catch (FileBackend.FileCopyException e) {
                if (callback != null) {
                    callback.error(-1, e.getResId());
                }
            }
        });
    }

    public PgpEngine getPgpEngine() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            if (this.mPgpEngine == null) {
                this.mPgpEngine =
                        new PgpEngine(
                                new OpenPgpApi(
                                        getApplicationContext(), pgpServiceConnection.getService()),
                                this);
            }
            return mPgpEngine;
        } else {
            return null;
        }
    }

    public OpenPgpApi getOpenPgpApi() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            return new OpenPgpApi(this, pgpServiceConnection.getService());
        } else {
            return null;
        }
    }

    public AppSettings getAppSettings() {
        return this.appSettings;
    }

    public FileBackend getFileBackend() {
        return this.fileBackend;
    }

    public DownloadableFile getFileForCid(Cid cid) {
        return this.databaseBackend.getFileForCid(cid);
    }

    public String getUrlForCid(Cid cid) {
        return this.databaseBackend.getUrlForCid(cid);
    }

    public void saveCid(Cid cid, File file) throws BlockedMediaException {
        saveCid(cid, file, null);
    }

    public void saveCid(Cid cid, File file, String url) throws BlockedMediaException {
        if (this.databaseBackend.isBlockedMedia(cid)) {
            throw new BlockedMediaException();
        }
        this.databaseBackend.saveCid(cid, file, url);
    }

    public boolean muteMucUser(MucOptions.User user) {
        boolean muted = databaseBackend.muteMucUser(user);
        if (!muted) return false;
        mutedMucUsers.put(user.getMuc().toString(), user.getOccupantId());
        return true;
    }

    public boolean unmuteMucUser(MucOptions.User user) {
        boolean unmuted = databaseBackend.unmuteMucUser(user);
        if (!unmuted) return false;
        mutedMucUsers.remove(user.getMuc().toString(), user.getOccupantId());
        return true;
    }

    public boolean isMucUserMuted(MucOptions.User user) {
        return mutedMucUsers.containsEntry("" + user.getMuc(), user.getOccupantId());
    }

    public void blockMedia(File f) {
        try {
            Cid[] cids = getFileBackend().calculateCids(getFileBackend().openInputStream(f));
            for (Cid cid : cids) {
                blockMedia(cid);
            }
        } catch (final IOException e) {
        }
    }

    public void blockMedia(Cid cid) {
        this.databaseBackend.blockMedia(cid);
    }

    public void clearBlockedMedia() {
        this.databaseBackend.clearBlockedMedia();
    }

    public Message getMessage(Conversation conversation, String uuid) {
        return this.databaseBackend.getMessage(conversation, uuid);
    }

    public Map<String, Message> getMessageFuzzyIds(
            Conversation conversation, Collection<String> ids) {
        return this.databaseBackend.getMessageFuzzyIds(conversation, ids);
    }

    public void insertWebxdcUpdate(final WebxdcUpdate update) {
        this.databaseBackend.insertWebxdcUpdate(update);
    }

    public WebxdcUpdate findLastWebxdcUpdate(Message message) {
        return this.databaseBackend.findLastWebxdcUpdate(message);
    }

    public List<WebxdcUpdate> findWebxdcUpdates(Message message, long serial) {
        return this.databaseBackend.findWebxdcUpdates(message, serial);
    }

    public AvatarService getAvatarService() {
        return this.mAvatarService;
    }

    public void attachLocationToConversation(
            final Conversation conversation,
            final Uri uri,
            final String subject,
            final UiCallback<Message> callback) {
        int encryption = conversation.getNextEncryption();
        if (encryption == Message.ENCRYPTION_PGP) {
            encryption = Message.ENCRYPTION_DECRYPTED;
        }
        Message message = new Message(conversation, uri.toString(), encryption);
        if (subject != null && subject.length() > 0) message.setSubject(subject);
        if (getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread());
        }
        Message.configurePrivateMessage(message);
        if (encryption == Message.ENCRYPTION_DECRYPTED) {
            getPgpEngine().encrypt(message, callback);
        } else {
            sendMessage(message);
            callback.success(message);
        }
    }

    private static class OutgoingLiveInfo {
        final String sessionId;
        final Conversation conversation;
        final long expiresAt;
        android.location.LocationListener locationListener;
        Runnable expiryRunnable;

        OutgoingLiveInfo(String sessionId, Conversation conversation, long expiresAt) {
            this.sessionId = sessionId;
            this.conversation = conversation;
            this.expiresAt = expiresAt;
        }
    }

    public void startLiveLocationSharing(
            final Conversation conversation,
            final long durationMs,
            final double initialLat,
            final double initialLon,
            final float initialAccuracy) {
        final String sessionId = java.util.UUID.randomUUID().toString();
        final long expiresAt = System.currentTimeMillis() + durationMs;
        // XEP-0082 in UTC on purpose: an offset pattern ("XXX") would put the sender's local
        // timezone
        // into every live-location stanza.
        final java.text.SimpleDateFormat expiresFormat =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        expiresFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        final String expiresAtISO = expiresFormat.format(new java.util.Date(expiresAt));

        int encryption = conversation.getNextEncryption();
        if (encryption == Message.ENCRYPTION_PGP) {
            encryption = Message.ENCRYPTION_DECRYPTED;
        }
        final String geoUriStr;
        if (initialAccuracy > 0) {
            geoUriStr =
                    String.format(
                            java.util.Locale.US,
                            "geo:%s,%s;u=%s",
                            initialLat,
                            initialLon,
                            (int) initialAccuracy);
        } else {
            geoUriStr = String.format(java.util.Locale.US, "geo:%s,%s", initialLat, initialLon);
        }
        final Message message = new Message(conversation, geoUriStr, encryption);
        message.setEphemeralTimer(
                0); // live location has its own lifetime; never expire via ephemeral timer
        if (getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread());
        }
        Message.configurePrivateMessage(message);

        final eu.siacs.conversations.xml.Element liveEl =
                new eu.siacs.conversations.xml.Element("live-location", Namespace.LIVE_LOCATION);
        liveEl.setAttribute("id", sessionId);
        liveEl.setAttribute("expires", expiresAtISO);
        message.addPayload(liveEl);

        sendMessage(message);

        eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                .registerOutgoingSession(
                        conversation.getUuid(),
                        sessionId,
                        message.getUuid(),
                        expiresAt,
                        initialLat,
                        initialLon);

        if (mLiveLocationAndroidManager == null) {
            mLiveLocationAndroidManager =
                    (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }

        final OutgoingLiveInfo info = new OutgoingLiveInfo(sessionId, conversation, expiresAt);

        final android.location.LocationListener locationListener =
                new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(
                            @androidx.annotation.NonNull android.location.Location location) {
                        sendLiveLocationUpdate(
                                conversation,
                                sessionId,
                                location.getLatitude(),
                                location.getLongitude(),
                                location.getAccuracy());
                    }

                    @Override
                    public void onStatusChanged(
                            String provider, int status, android.os.Bundle extras) {}

                    @Override
                    public void onProviderEnabled(@androidx.annotation.NonNull String provider) {}

                    @Override
                    public void onProviderDisabled(@androidx.annotation.NonNull String provider) {}
                };

        info.locationListener = locationListener;
        mOutgoingLiveSessions.put(conversation.getUuid(), info);

        mNotificationService.showLiveLocationNotification(conversation.getUuid());
        // Must call startForeground with FOREGROUND_SERVICE_TYPE_LOCATION before
        // requestLocationUpdates — Android 14+ revokes location access otherwise.
        toggleForegroundService();

        try {
            if (mLiveLocationAndroidManager != null) {
                final java.util.List<String> providers =
                        mLiveLocationAndroidManager.getAllProviders();
                if (providers.contains(android.location.LocationManager.GPS_PROVIDER)) {
                    mLiveLocationAndroidManager.requestLocationUpdates(
                            android.location.LocationManager.GPS_PROVIDER,
                            5_000L,
                            5f,
                            locationListener,
                            android.os.Looper.getMainLooper());
                }
                if (providers.contains(android.location.LocationManager.NETWORK_PROVIDER)) {
                    mLiveLocationAndroidManager.requestLocationUpdates(
                            android.location.LocationManager.NETWORK_PROVIDER,
                            5_000L,
                            5f,
                            locationListener,
                            android.os.Looper.getMainLooper());
                }
            }
        } catch (SecurityException ignored) {
        }

        final Runnable expiryRunnable = () -> stopLiveLocationSharing(conversation.getUuid());
        info.expiryRunnable = expiryRunnable;
        mLiveLocationHandler.postDelayed(expiryRunnable, durationMs);
        updateConversationUi();
    }

    private void sendLiveLocationUpdate(
            final Conversation conversation,
            final String sessionId,
            final double lat,
            final double lon,
            final float accuracy) {
        final Element updateEl = new Element("live-location-update", Namespace.LIVE_LOCATION);
        updateEl.setAttribute("id", sessionId);
        updateEl.setAttribute("lat", String.valueOf(lat));
        updateEl.setAttribute("lon", String.valueOf(lon));

        final im.conversations.android.xmpp.model.stanza.Message packet =
                new im.conversations.android.xmpp.model.stanza.Message();
        packet.setTo(
                conversation.getMode() == Conversation.MODE_SINGLE
                        ? conversation.getJid()
                        : conversation.getJid().asBareJid());
        packet.setType(
                conversation.getMode() == Conversation.MODE_SINGLE
                        ? im.conversations.android.xmpp.model.stanza.Message.Type.CHAT
                        : im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT);
        packet.addChild("no-store", Namespace.HINTS);

        if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            conversation
                    .getAccount()
                    .getAxolotlService()
                    .sendOmemo2Packet(
                            conversation, packet, java.util.Collections.singletonList(updateEl));
        } else {
            packet.addChild(updateEl);
            sendMessagePacket(conversation.getAccount(), packet);
        }
        eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                .notifyOutgoingPositionUpdate(sessionId, lat, lon);
        updateMessageGeoPayload(
                conversation.getUuid(), getLiveLocationMessageUuid(sessionId), lat, lon);
    }

    private String getLiveLocationMessageUuid(String sessionId) {
        for (eu.siacs.conversations.utils.LiveLocationManager.OutgoingSession os :
                eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                        .getAllOutgoingSessions()) {
            if (sessionId.equals(os.sessionId)) {
                return os.messageUuid;
            }
        }
        return null;
    }

    public void stopLiveLocationSharing(final String conversationUuid) {
        final OutgoingLiveInfo info = mOutgoingLiveSessions.remove(conversationUuid);
        if (info == null) return;
        if (info.locationListener != null && mLiveLocationAndroidManager != null) {
            try {
                mLiveLocationAndroidManager.removeUpdates(info.locationListener);
            } catch (SecurityException ignored) {
            }
        }
        if (info.expiryRunnable != null) {
            mLiveLocationHandler.removeCallbacks(info.expiryRunnable);
        }
        eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                .clearOutgoingSession(conversationUuid);
        // Notify receiver that sharing has stopped
        final Conversation conversation = findConversationByUuid(conversationUuid);
        if (conversation != null) {
            final Element stopEl = new Element("live-location-stop", Namespace.LIVE_LOCATION);
            stopEl.setAttribute("id", info.sessionId);

            final im.conversations.android.xmpp.model.stanza.Message packet =
                    new im.conversations.android.xmpp.model.stanza.Message();
            packet.setTo(
                    conversation.getMode() == Conversation.MODE_SINGLE
                            ? conversation.getJid()
                            : conversation.getJid().asBareJid());
            packet.setType(
                    conversation.getMode() == Conversation.MODE_SINGLE
                            ? im.conversations.android.xmpp.model.stanza.Message.Type.CHAT
                            : im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT);
            packet.addChild("no-store", Namespace.HINTS);

            if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                conversation
                        .getAccount()
                        .getAxolotlService()
                        .sendOmemo2Packet(
                                conversation, packet, java.util.Collections.singletonList(stopEl));
            } else {
                packet.addChild(stopEl);
                sendMessagePacket(conversation.getAccount(), packet);
            }
        }
        mNotificationService.cancelLiveLocationNotification();
        toggleForegroundService();
        updateConversationUi();
    }

    private void sendLiveLocationStopForOrphanedSessions(final Account account) {
        final java.util.List<String[]> rows =
                databaseBackend.getRecentOutgoingLiveLocationMessages(account.getUuid());
        for (final String[] row : rows) {
            final String conversationUuid = row[0];
            if (mOutgoingLiveSessions.containsKey(conversationUuid)) continue;
            final String payloadsXml = row[1];
            if (payloadsXml == null) continue;
            // Parse session id and expires out of the stored payload XML
            String sessionId = null;
            long expiresAt = 0;
            try {
                final eu.siacs.conversations.xml.XmlReader xmlReader =
                        new eu.siacs.conversations.xml.XmlReader();
                xmlReader.setInputStream(
                        new java.io.ByteArrayInputStream(
                                payloadsXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                eu.siacs.conversations.xml.Tag tag;
                while ((tag = xmlReader.readTag()) != null) {
                    if ("live-location".equals(tag.getName())) {
                        final eu.siacs.conversations.xml.Element el = xmlReader.readElement(tag);
                        sessionId = el.getAttribute("id");
                        final String expiresStr = el.getAttribute("expires");
                        if (expiresStr != null) {
                            try {
                                expiresAt =
                                        eu.siacs.conversations.parser.AbstractParser.parseTimestamp(
                                                expiresStr);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "Could not parse live-location payload: " + e.getMessage());
                continue;
            }
            if (sessionId == null || System.currentTimeMillis() >= expiresAt) continue;
            // Session was active when app died — send stop stanza now
            final Conversation conversation = findConversationByUuid(conversationUuid);
            if (conversation == null || conversation.getAccount() != account) continue;

            final Element stopEl = new Element("live-location-stop", Namespace.LIVE_LOCATION);
            stopEl.setAttribute("id", sessionId);

            final im.conversations.android.xmpp.model.stanza.Message packet =
                    new im.conversations.android.xmpp.model.stanza.Message();
            packet.setTo(
                    conversation.getMode() == Conversation.MODE_SINGLE
                            ? conversation.getJid()
                            : conversation.getJid().asBareJid());
            packet.setType(
                    conversation.getMode() == Conversation.MODE_SINGLE
                            ? im.conversations.android.xmpp.model.stanza.Message.Type.CHAT
                            : im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT);
            packet.addChild("no-store", Namespace.HINTS);

            if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                conversation
                        .getAccount()
                        .getAxolotlService()
                        .sendOmemo2Packet(
                                conversation, packet, java.util.Collections.singletonList(stopEl));
            } else {
                packet.addChild(stopEl);
                sendMessagePacket(account, packet);
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": sent live-location-stop for orphaned session "
                            + sessionId);
        }
    }

    /**
     * Collects the files that are being sent as one XEP-0447 message. The first file becomes the
     * message itself; every later file becomes an extra file of it. The group is handed to the
     * attach calls in send order, so the caller does not have to wait for the (asynchronous) first
     * attach to complete before starting the next one.
     */
    public static class AttachmentGroup {
        private final int size;
        private Message parent;

        public AttachmentGroup(final int size) {
            this.size = size;
        }

        private synchronized void add(final Message message) {
            if (this.parent == null) {
                this.parent = message;
                // Hold the stanza back until every file of this message exists and has been
                // uploaded — all of them travel in that single stanza.
                message.setExpectedAttachments(this.size - 1);
            } else {
                this.parent.addAttachment(message);
            }
        }

        /**
         * Drops a file that turned out to be handled by a different attach path, which builds a
         * message of its own for it (see the compression fallback in {@link
         * #attachImageToConversation}). Without this the group would wait forever for a message
         * nobody is going to send.
         */
        private synchronized void withdraw(final Message message) {
            if (this.parent != message) {
                if (this.parent != null) {
                    this.parent.removeAttachment(message);
                }
                return;
            }
            if (this.parent.hasAttachments()) {
                // Files are attached one after the other, so the message being withdrawn is
                // always the one that was added last and cannot have files of its own yet.
                Log.w(Config.LOGTAG, "refusing to withdraw a message that already carries files");
                return;
            }
            this.parent = null;
        }
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String subject,
            final UiCallback<Message> callback) {
        attachFileToConversation(conversation, uri, type, subject, callback, null);
    }

    public void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String subject,
            final UiCallback<Message> callback,
            final AttachmentGroup group) {
        attachFileToConversation(conversation, uri, type, subject, callback, group, null);
    }

    /**
     * @param captionOverride the caption for paths that hand a file over here after having built a
     *     message for it already: by then the composing screen has cleared the caption from the
     *     conversation, so it has to be carried along.
     */
    private void attachFileToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String subject,
            final UiCallback<Message> callback,
            final AttachmentGroup group,
            final String captionOverride) {
        final Message message;
        if (conversation.getReplyTo() == null) {
            message = new Message(conversation, "", conversation.getNextEncryption());
        } else {
            message = conversation.getReplyTo().reply();
            message.setEncryption(conversation.getNextEncryption());
        }
        if (group != null) {
            group.add(message);
        }
        if (!message.isAttachment()) {
            final String caption =
                    Strings.isNullOrEmpty(captionOverride)
                            ? conversation.getCaption()
                            : captionOverride;
            if (caption != null) {
                message.appendBody(caption + " ");
                message.setEncryption(conversation.getNextEncryption());
            }
        }
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
        }
        if (subject != null && subject.length() > 0) message.setSubject(subject);
        final boolean isWebxdc = "application/webxdc+zip".equals(type);
        if (isWebxdc || getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_FILE);
        }
        Log.d(Config.LOGTAG, "attachFile: type=" + message.getType());
        Log.d(Config.LOGTAG, "counterpart=" + message.getCounterpart());
        final AttachFileToConversationRunnable runnable =
                new AttachFileToConversationRunnable(this, uri, type, message, callback);
        if (runnable.isVideoMessage()) {
            VIDEO_COMPRESSION_EXECUTOR.execute(runnable);
        } else {
            FILE_ATTACHMENT_EXECUTOR.execute(runnable);
        }
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String subject,
            final UiCallback<Message> callback) {
        attachImageToConversation(conversation, uri, type, subject, callback, null);
    }

    public void attachImageToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String subject,
            final UiCallback<Message> callback,
            final AttachmentGroup group) {
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(this, uri, type);
        final String compressPictures = getCompressPicturesPreference();

        if ("never".equals(compressPictures)
                || ("auto".equals(compressPictures) && getFileBackend().useImageAsIs(uri))
                || (mimeType != null && mimeType.endsWith("/gif"))
                || getFileBackend().unusualBounds(uri)
                || "data".equals(uri.getScheme())) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": not compressing picture. sending as file");
            attachFileToConversation(conversation, uri, mimeType, subject, callback, group);
            return;
        }
        final Message message;

        if (conversation.getReplyTo() == null) {
            message = new Message(conversation, "", conversation.getNextEncryption());
        } else {
            message = conversation.getReplyTo().reply();
            message.setEncryption(conversation.getNextEncryption());
        }
        if (group != null) {
            group.add(message);
        }
        // Read once: the composing screen clears the caption as soon as this call returns, but
        // the file is only copied later and may still be handed over to attachFileToConversation.
        final String caption = message.isAttachment() ? null : conversation.getCaption();
        if (caption != null) {
            message.appendBody(caption + " ");
            message.setEncryption(conversation.getNextEncryption());
        }
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
        }
        if (subject != null && subject.length() > 0) message.setSubject(subject);
        if (getBooleanPreference("show_thread_feature", R.bool.show_thread_feature)) {
            message.setThread(conversation.getThread());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_IMAGE);
        }
        Log.d(Config.LOGTAG, "attachImage: type=" + message.getType());
        FILE_ATTACHMENT_EXECUTOR.execute(() -> {
            try {
                getFileBackend().copyImageToPrivateStorage(message, uri);
            } catch (FileBackend.ImageCompressionException e) {
                // Everything the picture picker returns arrives here, videos included. Hand the
                // file over with the message it was going to be, so that it stays part of the
                // message it was attached to instead of turning into one of its own.
                Log.d(Config.LOGTAG, "unable to compress image. fall back to file transfer", e);
                if (group != null) {
                    group.withdraw(message);
                }
                attachFileToConversation(
                        conversation, uri, mimeType, subject, callback, group, caption);
                return;
            } catch (final FileBackend.FileCopyException e) {
                callback.error(e.getResId(), message);
                return;
            }
            if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
                final PgpEngine pgpEngine = getPgpEngine();
                if (pgpEngine != null) {
                    pgpEngine.encrypt(message, callback);
                } else if (callback != null) {
                    callback.error(R.string.unable_to_connect_to_keychain, null);
                }
            } else {
                sendMessage(message, false, false, false, () -> callback.success(message), false);
            }
        });
    }

    /**
     * Share one or more attachments (or a piece of text) to several conversations at once.
     *
     * <p>For encrypted file shares the file is uploaded only once per representation (aesgcm for
     * OMEMO/OMEMO2, https for unencrypted) and the resulting URL is reused for the remaining
     * recipients of that representation. This does not weaken security: the aesgcm key travels
     * inside each recipient's own OMEMO envelope, exactly like a single file sent into a group
     * chat. A plaintext (https) upload is never reused for an end-to-end-encrypted recipient, and
     * PGP recipients are always encrypted/uploaded individually.
     */
    public void shareToConversations(
            final List<Conversation> targets,
            final List<Uri> uris,
            final String type,
            final String caption,
            final String text) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        // Text-only share: the shared/edited text is the message body.
        if (uris == null || uris.isEmpty()) {
            if (Strings.isNullOrEmpty(text)) {
                return;
            }
            for (final Conversation target : targets) {
                sendMessage(new Message(target, text, target.getNextEncryption()));
            }
            return;
        }
        // Several files go out as one multi-file message wherever the recipient's wire format
        // can carry it — the same rule the attachment menu follows (see
        // ConversationFragment#attachmentGroupFor). Everybody else gets one message per file.
        final List<Conversation> perFile = new ArrayList<>();
        final List<Conversation> groupedPlain = new ArrayList<>();
        final List<Conversation> groupedEncrypted = new ArrayList<>();
        for (final Conversation target : targets) {
            if (uris.size() < 2 || !canGroupShare(target, uris)) {
                perFile.add(target);
            } else if (target.getNextEncryption() == Message.ENCRYPTION_NONE) {
                groupedPlain.add(target);
            } else {
                groupedEncrypted.add(target);
            }
        }
        if (getBooleanPreference("share_separate_uploads", R.bool.share_separate_uploads)) {
            for (final Conversation target : groupedPlain) {
                attachUrisAsGroup(target, uris, type, caption, null);
            }
            for (final Conversation target : groupedEncrypted) {
                attachUrisAsGroup(target, uris, type, caption, null);
            }
        } else {
            sendGroupToBucketWithReuse(groupedPlain, uris, type, caption);
            sendGroupToBucketWithReuse(groupedEncrypted, uris, type, caption);
        }
        // File/image share: upload once per representation and reuse where it is safe. The
        // caption rides on the first file only, as it does for a multi-file message.
        if (!perFile.isEmpty()) {
            for (int i = 0; i < uris.size(); ++i) {
                shareUriToConversations(
                        new ArrayList<>(perFile), uris.get(i), type, i == 0 ? caption : null);
            }
        }
        if (!Strings.isNullOrEmpty(caption)) {
            // The caption is embedded directly in the file message (inside the encrypted
            // SCE envelope) for OMEMO2 and plaintext recipients — see canEmbedCaption().
            // Recipients whose wire format can't carry a file caption (legacy OMEMO v0.3,
            // which encrypts only the URL, and PGP) still receive it as a separate
            // encrypted text message so nobody silently loses the caption.
            for (final Conversation target : targets) {
                if (!canEmbedCaption(target.getNextEncryption())) {
                    sendMessage(new Message(target, caption, target.getNextEncryption()));
                }
            }
        }
    }

    /**
     * Whether a file message to a recipient using {@code encryption} can carry the caption inside
     * the same (encrypted) message. True for plaintext (body + OOB) and PQ OMEMO2 (SCE body + OOB).
     * False for legacy OMEMO v0.3 (single body = URL) and PGP.
     */
    private boolean canEmbedCaption(final int encryption) {
        return encryption == Message.ENCRYPTION_NONE
                || encryption == Message.ENCRYPTION_AXOLOTL_OMEMO2;
    }

    /**
     * Whether a share of several files reaches {@code target} as one multi-file message: the file
     * descriptions must be able to travel with it (plaintext or PQ OMEMO2, never legacy OMEMO or
     * PGP) and every file needs an HTTP upload URL to put into that one stanza.
     */
    private boolean canGroupShare(final Conversation target, final List<Uri> uris) {
        if (!canEmbedCaption(target.getNextEncryption())
                || !target.getAccount().httpUploadAvailable()) {
            return false;
        }
        for (final Uri uri : uris) {
            if ("geo".equals(uri.getScheme())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Uploads the files for the first recipient of {@code bucket} as one multi-file message, then
     * hands the same URLs to everybody else in the bucket instead of uploading again.
     */
    private void sendGroupToBucketWithReuse(
            final List<Conversation> bucket,
            final List<Uri> uris,
            final String type,
            final String caption) {
        if (bucket.isEmpty()) {
            return;
        }
        final Conversation first = bucket.get(0);
        final List<Conversation> remaining = new ArrayList<>(bucket.subList(1, bucket.size()));
        attachUrisAsGroup(
                first,
                uris,
                type,
                caption,
                remaining.isEmpty()
                        ? null
                        // Copies every file once per recipient, so keep it off the main thread.
                        : uploaded ->
                                FILE_ATTACHMENT_EXECUTOR.execute(() ->
                                        reuseUploadedGroup(
                                                uploaded, remaining, uris, type, caption)));
    }

    /**
     * Attaches {@code uris} to {@code conversation} as one multi-file message. The files are
     * attached one after the other, each once the previous one is through, just as the attachment
     * menu does — that keeps their rows in send order. {@code onDone} receives the message of every
     * file, in send order, with {@code null} for a file that failed.
     */
    private void attachUrisAsGroup(
            final Conversation conversation,
            final List<Uri> uris,
            final String type,
            final String caption,
            final Consumer<List<Message>> onDone) {
        final AttachmentGroup group = new AttachmentGroup(uris.size());
        final List<Message> results = new ArrayList<>();
        final Runnable next =
                new Runnable() {
                    private int index = 0;

                    @Override
                    public void run() {
                        if (index >= uris.size()) {
                            if (onDone != null) {
                                onDone.accept(results);
                            }
                            return;
                        }
                        final Uri uri = uris.get(index);
                        final Runnable self = this;
                        final UiCallback<Message> callback =
                                new UiCallback<>() {
                                    @Override
                                    public void success(final Message message) {
                                        results.add(message);
                                        mMainHandler.post(self);
                                    }

                                    @Override
                                    public void error(final int errorCode, final Message message) {
                                        results.add(null);
                                        mMainHandler.post(self);
                                    }

                                    @Override
                                    public void userInputRequired(
                                            final PendingIntent pi, final Message message) {}
                                };
                        attachUriToConversation(
                                conversation,
                                uri,
                                type,
                                index == 0 ? caption : null,
                                callback,
                                group);
                        index++;
                    }
                };
        next.run();
    }

    /**
     * Sends the multi-file message that was just uploaded for one recipient to every other
     * recipient as well, reusing the uploaded URLs. Any recipient for whom that is not possible —
     * an upload did not finish, or a local copy failed — gets a fresh upload of its own.
     */
    private void reuseUploadedGroup(
            final List<Message> uploaded,
            final List<Conversation> remaining,
            final List<Uri> uris,
            final String type,
            final String caption) {
        boolean complete = uploaded.size() == uris.size();
        for (final Message message : uploaded) {
            if (message == null
                    || message.getFileParams() == null
                    || Strings.isNullOrEmpty(message.getFileParams().url)) {
                complete = false;
                break;
            }
        }
        for (final Conversation target : remaining) {
            if (!complete) {
                attachUrisAsGroup(target, uris, type, caption, null);
                continue;
            }
            final List<Message> messages = new ArrayList<>();
            for (int i = 0; i < uploaded.size(); ++i) {
                final Message source = uploaded.get(i);
                final Message message =
                        reusedFileMessage(
                                target,
                                source,
                                source.getFileParams().url,
                                type,
                                i == 0 ? caption : null);
                if (message == null) {
                    break;
                }
                messages.add(message);
            }
            if (messages.size() != uploaded.size()) {
                attachUrisAsGroup(target, uris, type, caption, null);
                continue;
            }
            // Same order as a fresh group: the parent is held back until it has every file,
            // and the last file to be added releases it (see releaseParentIfReady).
            final Message parent = messages.get(0);
            parent.setExpectedAttachments(messages.size() - 1);
            sendMessage(parent);
            for (final Message attachment : messages.subList(1, messages.size())) {
                parent.addAttachment(attachment);
                sendMessage(attachment);
            }
        }
    }

    /**
     * Builds a message for {@code target} that carries a copy of {@code source}'s file under the
     * already-uploaded {@code url}, so it needs no upload of its own. Returns null if the local
     * copy failed.
     */
    private Message reusedFileMessage(
            final Conversation target,
            final Message source,
            final String url,
            final String type,
            final String caption) {
        final Message message;
        if (target.getReplyTo() == null) {
            message = new Message(target, "", target.getNextEncryption());
        } else {
            message = target.getReplyTo().reply();
            message.setEncryption(target.getNextEncryption());
        }
        // Embed the caption in the file message body for OMEMO2/plaintext recipients
        // (it then rides inside their own encrypted envelope); legacy/PGP recipients
        // get the separate caption message sent by shareToConversations instead.
        if (!Strings.isNullOrEmpty(caption) && canEmbedCaption(target.getNextEncryption())) {
            message.appendBody(caption + " ");
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(target.getNextCounterpart());
            message.setType(
                    source.getType() == Message.TYPE_IMAGE
                            ? Message.TYPE_IMAGE
                            : Message.TYPE_FILE);
        }
        try {
            getFileBackend()
                    .copyFileToPrivateStorage(
                            message, Uri.fromFile(getFileBackend().getFile(source)), type);
            // Pre-set the shared URL so needsUploading() is false: no second upload,
            // the message just transmits the already-uploaded (aesgcm or https) URL.
            getFileBackend().updateFileParams(message, url);
        } catch (final FileBackend.FileCopyException e) {
            Log.d(Config.LOGTAG, "reuse copy failed; falling back to a fresh upload", e);
            return null;
        }
        return message;
    }

    private void shareUriToConversations(
            final List<Conversation> targets,
            final Uri uri,
            final String type,
            final String caption) {
        // Privacy option: when enabled, every recipient gets its own fresh upload so the
        // file host cannot correlate that the same encrypted blob went to several
        // contacts. Off by default (single-upload reuse, which never weakens encryption).
        if (getBooleanPreference("share_separate_uploads", R.bool.share_separate_uploads)) {
            for (final Conversation target : targets) {
                attachUriToConversation(target, uri, type, caption, null);
            }
            return;
        }
        final List<Conversation> encrypted = new ArrayList<>();
        final List<Conversation> plain = new ArrayList<>();
        final List<Conversation> individual = new ArrayList<>();
        for (final Conversation target : targets) {
            switch (target.getNextEncryption()) {
                case Message.ENCRYPTION_AXOLOTL:
                case Message.ENCRYPTION_AXOLOTL_OMEMO2:
                    encrypted.add(target);
                    break;
                case Message.ENCRYPTION_NONE:
                    plain.add(target);
                    break;
                default:
                    // PGP/DECRYPTED (per-recipient public-key encryption) and anything
                    // else are never reused — upload/encrypt each one separately.
                    individual.add(target);
                    break;
            }
        }
        sendUriToBucketWithReuse(encrypted, uri, type, caption);
        sendUriToBucketWithReuse(plain, uri, type, caption);
        for (final Conversation target : individual) {
            attachUriToConversation(target, uri, type, caption, null);
        }
    }

    private void sendUriToBucketWithReuse(
            final List<Conversation> bucket,
            final Uri uri,
            final String type,
            final String caption) {
        if (bucket.isEmpty()) {
            return;
        }
        final Conversation first = bucket.get(0);
        final List<Conversation> remaining = new ArrayList<>(bucket.subList(1, bucket.size()));
        if (remaining.isEmpty()) {
            // Only one recipient in this bucket — nothing to reuse, normal upload.
            attachUriToConversation(first, uri, type, caption, null);
            return;
        }
        final UiCallback<Message> callback =
                new UiCallback<>() {
                    @Override
                    public void success(final Message firstMessage) {
                        // Runs after the first recipient's upload completes (the URL is set
                        // by then on success; null when offline/queued or on failure).
                        final Message.FileParams params = firstMessage.getFileParams();
                        final String url = params == null ? null : params.url;
                        if (Strings.isNullOrEmpty(url)) {
                            for (final Conversation target : remaining) {
                                attachUriToConversation(target, uri, type, caption, null);
                            }
                            return;
                        }
                        reuseUploadedFile(firstMessage, url, remaining, type, caption);
                    }

                    @Override
                    public void error(final int errorCode, final Message object) {
                        for (final Conversation target : remaining) {
                            attachUriToConversation(target, uri, type, caption, null);
                        }
                    }

                    @Override
                    public void userInputRequired(final PendingIntent pi, final Message object) {}
                };
        attachUriToConversation(first, uri, type, caption, callback);
    }

    private void attachUriToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String caption,
            final UiCallback<Message> callback) {
        attachUriToConversation(conversation, uri, type, caption, callback, null);
    }

    private void attachUriToConversation(
            final Conversation conversation,
            final Uri uri,
            final String type,
            final String caption,
            final UiCallback<Message> callback,
            final AttachmentGroup group) {
        // attachImage/FileToConversation read the caption from conversation.getCaption()
        // (synchronously, while building the message). Set it only for recipients whose
        // wire format can embed it; clear it otherwise so legacy/PGP recipients fall back
        // to the separate caption message instead of silently dropping it.
        if (!Strings.isNullOrEmpty(caption) && canEmbedCaption(conversation.getNextEncryption())) {
            conversation.setCaption(caption);
        } else {
            conversation.setCaption(null);
        }
        final UiCallback<Message> cb =
                callback != null
                        ? callback
                        : new UiCallback<>() {
                            @Override
                            public void success(final Message object) {}

                            @Override
                            public void error(final int errorCode, final Message object) {}

                            @Override
                            public void userInputRequired(
                                    final PendingIntent pi, final Message object) {}
                        };
        final String uriMime = MimeUtils.guessMimeTypeFromUri(this, uri);
        final String mime =
                uriMime == null || "application/octet-stream".equals(uriMime) ? type : uriMime;
        if (mime != null && mime.startsWith("image/")) {
            attachImageToConversation(conversation, uri, type, null, cb, group);
        } else {
            attachFileToConversation(conversation, uri, type, null, cb, group);
        }
    }

    private void reuseUploadedFile(
            final Message firstMessage,
            final String url,
            final List<Conversation> remaining,
            final String type,
            final String caption) {
        for (final Conversation target : remaining) {
            final Message message = reusedFileMessage(target, firstMessage, url, type, caption);
            if (message == null) {
                attachUriToConversation(
                        target,
                        Uri.fromFile(getFileBackend().getFile(firstMessage)),
                        type,
                        caption,
                        null);
                continue;
            }
            sendMessage(message);
        }
    }

    public File stickerDir() {
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
        return StickersMigration.getStickersDir(this);
    }

    /**
     * Set when stickers were added in the background, so the next rescan (which feeds emoji search;
     * the picker reads the folder itself) is not throttled.
     */
    private static volatile boolean sStickersChanged = false;

    /** Called by the sticker download once it stored new stickers. */
    public static void stickersChanged() {
        sStickersChanged = true;
    }

    public void rescanStickers() {
        long msToRescan = (mLastStickerRescan + 600000L) - SystemClock.elapsedRealtime();
        if (msToRescan > 0 && !sStickersChanged) return;
        sStickersChanged = false;
        Log.d(Config.LOGTAG, "rescanStickers");

        mLastStickerRescan = SystemClock.elapsedRealtime();
        mStickerScanExecutor.execute(() -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            StickersMigration.requireMigration(this);
            convertAnimatedPngStickers();
            try {
                for (File file : Files.fileTraverser().breadthFirst(stickerDir())) {
                    try {
                        if (file.isFile() && file.canRead()) {
                            DownloadableFile df = new DownloadableFile(file.getAbsolutePath());
                            Drawable icon =
                                    fileBackend.getThumbnail(
                                            df,
                                            getResources(),
                                            (int)
                                                    (getResources().getDisplayMetrics().density
                                                            * 288),
                                            false);
                            final String filename = Files.getNameWithoutExtension(df.getName());
                            // stickers are encrypted at rest: hash and size are of the plaintext
                            final com.google.common.io.CountingInputStream plain =
                                    new com.google.common.io.CountingInputStream(
                                            fileBackend.openInputStream(df));
                            Cid[] cids = fileBackend.calculateCids(plain);
                            for (Cid cid : cids) {
                                saveCid(cid, file);
                            }
                            // the hash read all of it: its count is the plaintext size
                            if (plain.getCount() < 129000) {
                                emojiSearch.addEmoji(
                                        new EmojiSearch.CustomEmoji(
                                                filename,
                                                cids[0].toString(),
                                                icon,
                                                file.getParentFile().getName()));
                            }
                        }
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, "rescanStickers: " + e);
                    }
                }
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "rescanStickers: " + e);
            }
        });
    }

    /** Set once stickers imported as animated PNG were converted to animated WebP. */
    private static final String STICKERS_APNG_CONVERTED = "stickers_apng_converted_v1";

    /**
     * Once: converts stickers stored as animated PNG (Signal packs imported before the import
     * converted them) to animated WebP, which Android can play. Runs on the sticker executor;
     * the rescan that follows records the new files' cids.
     */
    private void convertAnimatedPngStickers() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean(STICKERS_APNG_CONVERTED, false)) {
            return;
        }
        final File root = stickerDir();
        try {
            for (final File file : Files.fileTraverser().breadthFirst(root)) {
                if (!file.isFile() || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                    continue;
                }
                try {
                    if (eu.siacs.conversations.persistance.MediaCrypto.plainLength(this, file)
                            > 1024 * 1024) continue;
                    final byte[] data;
                    try (final InputStream in = fileBackend.openInputStream(file)) {
                        data = ByteStreams.toByteArray(ByteStreams.limit(in, 1024 * 1024));
                    }
                    final byte[] webp =
                            eu.siacs.conversations.utils.ApngConverter.toAnimatedWebp(data);
                    if (webp == null) continue;
                    final String name = file.getName();
                    final File target =
                            new File(
                                    file.getParentFile(),
                                    name.substring(0, name.length() - 4) + ".webp");
                    if (target.exists()) continue;
                    eu.siacs.conversations.persistance.MediaCrypto.store(
                            this, new java.io.ByteArrayInputStream(webp), target);
                    if (!file.delete()) {
                        Log.d(Config.LOGTAG, "could not remove converted sticker " + name);
                    }
                } catch (final Exception e) {
                    Log.d(Config.LOGTAG, "could not convert sticker " + file.getName() + ": " + e);
                }
            }
            preferences.edit().putBoolean(STICKERS_APNG_CONVERTED, true).apply();
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "convertAnimatedPngStickers: " + e);
        }
    }

    /** Set once the old cache media folders were moved to internal storage. */
    public static final String CACHE_MIGRATED_TO_INTERNAL = "cache_migrated_to_internal_v2";

    /** Set once the stored file paths were pointed at the moved files. */
    public static final String MEDIA_PATHS_MIGRATED_TO_INTERNAL =
            "media_paths_migrated_to_internal";

    private void migrateCacheToInternalStorage() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean(CACHE_MIGRATED_TO_INTERNAL, false)) {
            return;
        }
        final List<String> dirsToMigrate = Arrays.asList("media", "Camera", "avatars", "stories");
        for (String dirName : dirsToMigrate) {
            final File oldDir = new File(getCacheDir(), dirName);
            final File newDir = new File(getFilesDir(), dirName);
            if (oldDir.exists() && oldDir.isDirectory()) {
                if (!newDir.exists()) {
                    newDir.mkdirs();
                }
                final File[] files = oldDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        final File newFile = new File(newDir, file.getName());
                        if (!file.renameTo(newFile)) {
                            // Copy next to the target and rename only once complete: a failed copy
                            // must neither leave a partial file (the stored path would be moved
                            // onto it) nor damage a file already there under the same (content
                            // hash) name. On failure the intact original stays in the cache.
                            final File tmp = new File(newDir, file.getName() + ".part");
                            boolean copied = false;
                            try {
                                try (InputStream in = new FileInputStream(file);
                                        OutputStream out = new FileOutputStream(tmp)) {
                                    ByteStreams.copy(in, out);
                                }
                                copied = true; // only once both streams closed cleanly
                            } catch (IOException e) {
                                Log.w(
                                        Config.LOGTAG,
                                        "Failed to migrate "
                                                + dirName
                                                + " file: "
                                                + file.getAbsolutePath());
                            }
                            if (copied && tmp.renameTo(newFile)) {
                                file.delete();
                            } else {
                                tmp.delete();
                            }
                        }
                    }
                }
            }
        }
        preferences.edit().putBoolean(CACHE_MIGRATED_TO_INTERNAL, true).apply();
    }

    /**
     * Follow-up to {@link #migrateCacheToInternalStorage()}: that moved the files but left the
     * absolute paths in the database pointing at the cache directory, so every attachment kept
     * there looked deleted. Runs under its own flag so installs that already moved their files get
     * repaired too.
     */
    private void migrateMediaPathsToInternalStorage(final DatabaseBackend backend) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean(MEDIA_PATHS_MIGRATED_TO_INTERNAL, false)) {
            return;
        }
        final SharedPreferences.Editor editor = preferences.edit();
        // The storage setting was renamed along with the move; keep what the user had chosen.
        if (preferences.contains("default_store_media_in_cache")
                && !preferences.contains(AppSettings.USE_INTERNAL_SECURE_STORAGE)) {
            editor.putBoolean(
                    AppSettings.USE_INTERNAL_SECURE_STORAGE,
                    preferences.getBoolean("default_store_media_in_cache", true));
        }
        try {
            final Set<String> cacheDirs = new HashSet<>();
            cacheDirs.add(getCacheDir().getAbsolutePath());
            try {
                cacheDirs.add(getCacheDir().getCanonicalPath());
            } catch (final IOException e) {
                Log.w(Config.LOGTAG, "could not resolve canonical cache path", e);
            }
            int rewritten = 0;
            for (final String dirName : Arrays.asList("media", "Camera")) {
                final String newDir = new File(getFilesDir(), dirName).getAbsolutePath();
                for (final String cacheDir : cacheDirs) {
                    rewritten +=
                            backend.relocateFilePaths(
                                    new File(cacheDir, dirName).getAbsolutePath(), newDir);
                }
            }
            Log.d(
                    Config.LOGTAG,
                    "relocated " + rewritten + " file paths from cache to internal storage");
        } catch (final RuntimeException e) {
            // Retried on the next start; never worth failing the startup over.
            Log.w(Config.LOGTAG, "could not relocate file paths to internal storage", e);
            editor.apply();
            return;
        }
        editor.putBoolean(MEDIA_PATHS_MIGRATED_TO_INTERNAL, true).apply();
    }

    private void migrateRecordingsToInternalStorage() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean("recordings_migrated_to_internal", false)) {
            return;
        }
        final File oldDir =
                new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOCUMENTS),
                        "monocles chat" + File.separator + "recordings");
        boolean allMoved = true;
        if (oldDir.isDirectory()) {
            final File newDir = new File(getFilesDir(), "media");
            if (!newDir.exists()) {
                newDir.mkdirs();
            }
            final File[] files = oldDir.listFiles();
            if (files != null) {
                for (final File file : files) {
                    if (file.isDirectory()) {
                        continue;
                    }
                    final File newFile = new File(newDir, file.getName());
                    try {
                        // encrypted at rest and atomically; the public original is removed
                        eu.siacs.conversations.persistance.MediaCrypto.moveIntoStorage(
                                this, file, newFile);
                        if (file.exists()) {
                            allMoved = false;
                        }
                    } catch (IOException e) {
                        allMoved = false;
                        Log.w(
                                Config.LOGTAG,
                                "failed to migrate recording: " + file.getAbsolutePath());
                    }
                }
            }
            oldDir.delete();
        }
        if (allMoved) {
            preferences.edit().putBoolean("recordings_migrated_to_internal", true).apply();
        }
    }

    protected void cleanupTemporaryStorage() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        int deletionTimeDays;
        try {
            // Same default as preferences_attachments.xml ("never"): the XML default is only
            // stored once the settings screen was opened.
            deletionTimeDays = Integer.parseInt(preferences.getString("cache_deletion_time", "0"));
        } catch (NumberFormatException e) {
            deletionTimeDays = 0;
        }
        if (Build.VERSION.SDK_INT < 26) {
            return; // Doesn't support file.toPath
        }
        final long day = 1000L * 60 * 60 * 24;
        final Map<File, Long> directories = new java.util.LinkedHashMap<>();
        if (deletionTimeDays > 0) {
            directories.put(getCacheDir(), day * deletionTimeDays);
            directories.put(new File(getFilesDir(), "media"), day * deletionTimeDays);
        }
        // Camera only holds the originals of in-app captures, which are always copied into media
        // when sent; nothing points at them, so they are cleaned even when deletion is "never".
        directories.put(
                new File(getFilesDir(), "Camera"),
                day * (deletionTimeDays > 0 ? deletionTimeDays : 7));
        mStickerScanExecutor.execute(() -> {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            final var now = System.currentTimeMillis();
            for (final Map.Entry<File, Long> entry : directories.entrySet()) {
                final File directory = entry.getKey();
                final long maxAge = entry.getValue();
                if (!directory.exists()) {
                    continue;
                }
                try {
                    for (File file : Files.fileTraverser().breadthFirst(directory)) {
                        if (file.isFile() && file.canRead() && file.canWrite()) {
                            final var attrs =
                                    java.nio.file.Files.readAttributes(
                                            file.toPath(),
                                            java.nio.file.attribute.BasicFileAttributes.class);
                            if ((now - attrs.lastAccessTime().toMillis()) > maxAge) {
                                Log.d(
                                        Config.LOGTAG,
                                        "cleanupTemporaryStorage removing file not used recently: "
                                                + file);
                                file.delete();
                            }
                        }
                    }
                } catch (final Exception e) {
                    Log.w(Config.LOGTAG, "cleanupTemporaryStorage " + e);
                }
            }
        });
    }

    public EmojiSearch emojiSearch() {
        return emojiSearch;
    }

    public Conversation find(Bookmark bookmark) {
        return find(bookmark.getAccount(), bookmark.getJid());
    }

    public Conversation find(final Account account, final Jid jid) {
        return find(getConversations(), account, jid);
    }

    public Conversation find(final Account account, final Jid jid, final Jid counterpart) {
        return find(getConversations(), account, jid, counterpart);
    }

    public boolean isMuc(final Account account, final Jid jid) {
        final Conversation c = find(account, jid);
        return c != null && c.getMode() == Conversational.MODE_MULTI;
    }

    public void search(
            final List<String> term,
            final String uuid,
            final OnSearchResultsAvailable onSearchResultsAvailable) {
        MessageSearchTask.search(this, term, uuid, onSearchResultsAvailable);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        final var nomedia = getBooleanPreference("nomedia", R.bool.default_nomedia);
        fileBackend.setupNomedia(nomedia);
        final String action = Strings.nullToEmpty(intent == null ? null : intent.getAction());
        final boolean needsForegroundService =
                intent != null
                        && intent.getBooleanExtra(
                                SystemEventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, false);
        if (needsForegroundService) {
            Log.d(
                    Config.LOGTAG,
                    "toggle forced foreground service after receiving event (action="
                            + action
                            + ")");
            toggleForegroundService(true, action.equals(ACTION_STARTING_CALL));
        }
        final String uuid = intent == null ? null : intent.getStringExtra("uuid");
        switch (action) {
            case QuickConversationsService.SMS_RETRIEVED_ACTION:
                mQuickConversationsService.handleSmsReceived(intent);
                break;
            case ConnectivityManager.CONNECTIVITY_ACTION:
                if (hasInternetConnection()) {
                    if (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0) {
                        schedulePostConnectivityChange();
                    }
                    if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                        resetAllAttemptCounts(true, false);
                    }
                    Resolver.clearCache();
                }
                break;
            case Intent.ACTION_SHUTDOWN:
                logoutAndSave(true);
                return START_NOT_STICKY;
            case ACTION_LOCK:
                lockAndTerminate();
                return START_NOT_STICKY;
            case ACTION_CLEAR_MESSAGE_NOTIFICATION:
                final int id = intent.getIntExtra("id", -1);
                mNotificationExecutor.execute(() -> {
                    try {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            mNotificationService.clearMessages(c);
                            if (id == NotificationService.GROUP_CALL_INVITE_NOTIFICATION_ID) {
                                mNotificationService.cancelGroupCallInvite(c);
                                mNotificationService.stopSoundAndVibration();
                            }
                        } else {
                            mNotificationService.clearMessages();
                        }
                        restoredFromDatabaseLatch.await();

                    } catch (InterruptedException e) {
                        Log.d(Config.LOGTAG, "unable to process clear message notification");
                    }
                });
                break;
            case ACTION_CLEAR_MISSED_CALL_NOTIFICATION:
                mNotificationExecutor.execute(() -> {
                    try {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            mNotificationService.clearMissedCalls(c);
                        } else {
                            mNotificationService.clearMissedCalls();
                        }
                        restoredFromDatabaseLatch.await();

                    } catch (InterruptedException e) {
                        Log.d(Config.LOGTAG, "unable to process clear missed call notification");
                    }
                });
                break;
            case ACTION_DISMISS_CALL:
                {
                    if (intent == null) {
                        break;
                    }
                    final String sessionId =
                            intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(
                            Config.LOGTAG,
                            "received intent to dismiss call with session id " + sessionId);
                    mJingleConnectionManager.rejectRtpSession(sessionId);
                    break;
                }
            case TorServiceUtils.ACTION_STATUS:
                final String status =
                        intent == null ? null : intent.getStringExtra(TorServiceUtils.EXTRA_STATUS);
                // TODO port and host are in 'extras' - but this may not be a reliable source?
                if ("ON".equals(status)) {
                    handleOrbotStartedEvent();
                    return START_STICKY;
                }
                break;
            case ACTION_END_CALL:
                {
                    if (intent == null) {
                        break;
                    }
                    final String sessionId =
                            intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(
                            Config.LOGTAG,
                            "received intent to end call with session id " + sessionId);
                    mJingleConnectionManager.endRtpSession(sessionId);
                }
                break;
            case ACTION_STOP_LIVE_LOCATION:
                {
                    if (uuid != null) {
                        stopLiveLocationSharing(uuid);
                    }
                }
                break;
            case ACTION_DISMISS_ERROR_NOTIFICATIONS:
                dismissErrorNotifications();
                break;
            case ACTION_TRY_AGAIN:
                resetAllAttemptCounts(false, true);
                break;
            case ACTION_REPLY_TO_CONVERSATION:
                final Bundle remoteInput =
                        intent == null ? null : RemoteInput.getResultsFromIntent(intent);
                if (remoteInput == null) {
                    break;
                }
                final CharSequence body = remoteInput.getCharSequence("text_reply");
                final boolean dismissNotification =
                        intent.getBooleanExtra("dismiss_notification", false);
                final String lastMessageUuid = intent.getStringExtra("last_message_uuid");
                if (body == null || body.length() <= 0) {
                    break;
                }
                mNotificationExecutor.execute(() -> {
                    try {
                        restoredFromDatabaseLatch.await();
                        final Conversation c = findConversationByUuid(uuid);
                        if (c != null) {
                            directReply(c, body.toString(), lastMessageUuid, dismissNotification);
                        }
                    } catch (InterruptedException e) {
                        Log.d(Config.LOGTAG, "unable to process direct reply");
                    }
                });
                break;
            case ACTION_MARK_AS_READ:
                mNotificationExecutor.execute(() -> {
                    final Conversation c = findConversationByUuid(uuid);
                    if (c == null) {
                        Log.d(
                                Config.LOGTAG,
                                "received mark read intent for unknown conversation ("
                                        + uuid
                                        + ")");
                        return;
                    }
                    try {
                        restoredFromDatabaseLatch.await();
                        sendReadMarker(c, null);
                    } catch (InterruptedException e) {
                        Log.d(
                                Config.LOGTAG,
                                "unable to process notification read marker for"
                                        + " conversation "
                                        + c.getName());
                    }
                });
                break;
            case ACTION_SNOOZE:
                mNotificationExecutor.execute(() -> {
                    final Conversation c = findConversationByUuid(uuid);
                    if (c == null) {
                        Log.d(
                                Config.LOGTAG,
                                "received snooze intent for unknown conversation (" + uuid + ")");
                        return;
                    }
                    c.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000);
                    mNotificationService.clearMessages(c);
                    updateConversation(c);
                });
            case AudioManager.RINGER_MODE_CHANGED_ACTION:
            case NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                if (dndOnSilentMode()) {
                    refreshAllPresences();
                }
                break;
            case Intent.ACTION_SCREEN_ON:
                deactivateGracePeriod();
            case Intent.ACTION_USER_PRESENT:
            case Intent.ACTION_SCREEN_OFF:
                if (awayWhenScreenLocked()) {
                    refreshAllPresences();
                }
                break;
            case ACTION_FCM_TOKEN_REFRESH:
                refreshAllFcmTokens();
                break;
            case ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS:
                if (intent == null) {
                    break;
                }
                final String instance = intent.getStringExtra("instance");
                final String application = intent.getStringExtra("application");
                final Messenger messenger = intent.getParcelableExtra("messenger");
                final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger;
                if (messenger != null && application != null && instance != null) {
                    pushTargetMessenger =
                            new UnifiedPushBroker.PushTargetMessenger(
                                    new UnifiedPushDatabase.PushTarget(application, instance),
                                    messenger);
                    Log.d(Config.LOGTAG, "found push target messenger");
                } else {
                    pushTargetMessenger = null;
                }
                final Optional<UnifiedPushBroker.Transport> transport =
                        renewUnifiedPushEndpoints(pushTargetMessenger);
                if (instance != null && transport.isPresent()) {
                    unifiedPushBroker.rebroadcastEndpoint(messenger, instance, transport.get());
                }
                break;
            case ACTION_IDLE_PING:
                scheduleNextIdlePing();
                break;
            case ACTION_FCM_MESSAGE_RECEIVED:
                Log.d(Config.LOGTAG, "push message arrived in service. account");
                break;
            case ACTION_EXPIRE_MESSAGES:
                expireOldMessages();
                break;
            case ACTION_QUICK_LOG:
                final String message = intent == null ? null : intent.getStringExtra("message");
                if (message != null && Config.QUICK_LOG) {
                    quickLog(message);
                }
                break;
            case Intent.ACTION_SEND:
                final Uri uri = intent == null ? null : intent.getData();
                if (uri != null) {
                    Log.d(Config.LOGTAG, "received uri permission for " + uri);
                }
                return START_STICKY;
            case ACTION_TEMPORARILY_DISABLE:
                toggleSoftDisabled(true);
                if (checkListeners()) {
                    stopSelf();
                }
                return START_NOT_STICKY;
        }
        sendScheduledMessages();
        final var extras = intent == null ? null : intent.getExtras();
        try {
            internalPingExecutor.execute(() -> manageAccountConnectionStates(action, extras));
        } catch (final RejectedExecutionException e) {
            Log.e(Config.LOGTAG, "can not schedule connection states manager");
        }
        if (SystemClock.elapsedRealtime() - mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages();
        }
        return START_STICKY;
    }

    private void quickLog(final String message) {
        if (Strings.isNullOrEmpty(message)) {
            return;
        }
        final Account account = AccountUtils.getFirstEnabled(this);
        if (account == null) {
            return;
        }
        final Conversation conversation =
                findOrCreateConversation(account, Config.BUG_REPORTS, false, true);
        final Message report =
                new Message(
                        conversation, message, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        conversation.add(report);
        databaseBackend.createMessage(report);
        updateConversationUi();
    }

    private void manageAccountConnectionStatesInternal() {
        manageAccountConnectionStates(ACTION_INTERNAL_PING, null);
    }

    private synchronized void manageAccountConnectionStates(
            final String action, final Bundle extras) {
        final String pushedAccountHash = extras == null ? null : extras.getString("account");
        final boolean interactive = java.util.Objects.equals(ACTION_TRY_AGAIN, action);
        WakeLockHelper.acquire(wakeLock);
        boolean pingNow =
                ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                        || (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0
                                && ACTION_POST_CONNECTIVITY_CHANGE.equals(action));
        final HashSet<Account> pingCandidates = new HashSet<>();
        final String androidId = pushedAccountHash == null ? null : PhoneHelper.getAndroidId(this);
        for (final Account account : accounts) {
            final boolean pushWasMeantForThisAccount =
                    androidId != null
                            && CryptoHelper.getAccountFingerprint(account, androidId)
                                    .equals(pushedAccountHash);
            pingNow |=
                    processAccountState(
                            account,
                            interactive,
                            "ui".equals(action),
                            pushWasMeantForThisAccount,
                            pingCandidates);
        }
        if (pingNow) {
            for (final Account account : pingCandidates) {
                final var connection = account.getXmppConnection();
                final boolean lowTimeout = isInLowPingTimeoutMode(account);
                final var delta =
                        (SystemClock.elapsedRealtime() - connection.getLastPacketReceived())
                                / 1000L;
                connection.sendPing();
                Log.d(
                        Config.LOGTAG,
                        String.format(
                                "%s: send ping (action=%s,lowTimeout=%s,interval=%s)",
                                account.getJid().asBareJid(), action, lowTimeout, delta));
                scheduleWakeUpCall(
                        lowTimeout ? Config.LOW_PING_TIMEOUT : Config.PING_TIMEOUT,
                        account.getUuid().hashCode());
            }
        }
        long msToMucPing =
                (mLastMucPing + (Config.PING_MAX_INTERVAL * 2000L)) - SystemClock.elapsedRealtime();
        if (pingNow || ("ui".equals(action) && msToMucPing <= 0) || msToMucPing < -300000) {
            Log.d(Config.LOGTAG, "ping MUCs");
            mLastMucPing = SystemClock.elapsedRealtime();
            for (Conversation c : getConversations()) {
                if (c.getMode() == Conversation.MODE_MULTI
                        && (c.getMucOptions().online()
                                || c.getMucOptions().getError() == MucOptions.Error.SHUTDOWN)) {
                    mucSelfPingAndRejoin(c);
                }
            }
        }
        WakeLockHelper.release(wakeLock);
    }

    private void sendScheduledMessages() {
        Log.d(Config.LOGTAG, "looking for and sending scheduled messages");

        for (final var message : new ArrayList<>(mScheduledMessages.values())) {
            if (message.getTimeSent() > System.currentTimeMillis()) continue;

            final var conversation = message.getConversation();
            final var account = conversation.getAccount();
            final boolean inProgressJoin;
            synchronized (account.inProgressConferenceJoins) {
                inProgressJoin = account.inProgressConferenceJoins.contains(conversation);
            }
            final boolean pendingJoin;
            synchronized (account.pendingConferenceJoins) {
                pendingJoin = account.pendingConferenceJoins.contains(conversation);
            }
            if (conversation.getAccount() == account && !pendingJoin && !inProgressJoin) {
                resendMessage(message, false);
            }
        }
    }

    private void handleOrbotStartedEvent() {
        for (final Account account : accounts) {
            if (account.getStatus() == Account.State.TOR_NOT_AVAILABLE) {
                reconnectAccount(account, true, false);
            }
        }
    }

    private boolean processAccountState(
            final Account account,
            final boolean interactive,
            final boolean isUiAction,
            final boolean isAccountPushed,
            final HashSet<Account> pingCandidates) {
        if (!account.getStatus().isAttemptReconnect()) {
            return false;
        }
        final var requestCode = account.getUuid().hashCode();
        if (!hasInternetConnection()) {
            account.setStatus(Account.State.NO_INTERNET);
            statusListener.onStatusChanged(account);
        } else {
            if (account.getStatus() == Account.State.NO_INTERNET) {
                account.setStatus(Account.State.OFFLINE);
                statusListener.onStatusChanged(account);
            }
            if (account.getStatus() == Account.State.ONLINE) {
                synchronized (mLowPingTimeoutMode) {
                    long lastReceived = account.getXmppConnection().getLastPacketReceived();
                    long lastSent = account.getXmppConnection().getLastPingSent();
                    long pingInterval =
                            isUiAction
                                    ? Config.PING_MIN_INTERVAL * 1000
                                    : Config.PING_MAX_INTERVAL * 1000;
                    long msToNextPing =
                            (Math.max(lastReceived, lastSent) + pingInterval)
                                    - SystemClock.elapsedRealtime();
                    int pingTimeout =
                            mLowPingTimeoutMode.contains(account.getJid().asBareJid())
                                    ? Config.LOW_PING_TIMEOUT * 1000
                                    : Config.PING_TIMEOUT * 1000;
                    long pingTimeoutIn = (lastSent + pingTimeout) - SystemClock.elapsedRealtime();
                    if (lastSent > lastReceived) {
                        if (pingTimeoutIn < 0) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ping timeout");
                            this.reconnectAccount(account, true, interactive);
                        } else {
                            this.scheduleWakeUpCall(pingTimeoutIn, requestCode);
                        }
                    } else {
                        pingCandidates.add(account);
                        if (isAccountPushed) {
                            if (mLowPingTimeoutMode.add(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": entering low ping timeout mode");
                            }
                            return true;
                        } else if (msToNextPing <= 0) {
                            return true;
                        } else {
                            this.scheduleWakeUpCall(msToNextPing, requestCode);
                            if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": leaving low ping timeout mode");
                            }
                        }
                    }
                }
            } else if (account.getStatus() == Account.State.OFFLINE) {
                reconnectAccount(account, true, interactive);
            } else if (account.getStatus() == Account.State.CONNECTING) {
                final var connection = account.getXmppConnection();
                final var connectionDuration = connection.getConnectionDuration();
                final var discoDuration = connection.getDiscoDuration();
                final var connectionTimeout = Config.CONNECT_TIMEOUT * 1000L - connectionDuration;
                final var discoTimeout = Config.CONNECT_DISCO_TIMEOUT * 1000L - discoDuration;
                if (connectionTimeout < 0) {
                    connection.triggerConnectionTimeout();
                } else if (discoTimeout < 0) {
                    connection.sendDiscoTimeout();
                    scheduleWakeUpCall(discoTimeout, requestCode);
                } else {
                    scheduleWakeUpCall(Math.min(connectionTimeout, discoTimeout), requestCode);
                }
            } else {
                final boolean aggressive =
                        account.getStatus() == Account.State.SEE_OTHER_HOST
                                || hasJingleRtpConnection(account);
                if (account.getXmppConnection().getTimeToNextAttempt(aggressive) <= 0) {
                    reconnectAccount(account, true, interactive);
                }
            }
        }
        return false;
    }

    private void toggleSoftDisabled(final boolean softDisabled) {
        for (final Account account : this.accounts) {
            if (account.isEnabled()) {
                if (account.setOption(Account.OPTION_SOFT_DISABLED, softDisabled)) {
                    updateAccount(account);
                }
            }
        }
    }

    public boolean processUnifiedPushMessage(
            final Account account, final Jid transport, final Element push) {
        return unifiedPushBroker.processPushMessage(account, transport, push);
    }

    public void reinitializeMuclumbusService() {
        mChannelDiscoveryService.initializeMuclumbusService();
    }

    public void discoverChannels(
            String query,
            ChannelDiscoveryService.Method method,
            Map<Jid, Account> mucServices,
            ChannelDiscoveryService.OnChannelSearchResultsFound onChannelSearchResultsFound) {
        mChannelDiscoveryService.discover(
                Strings.nullToEmpty(query).trim(),
                method,
                mucServices,
                onChannelSearchResultsFound);
    }

    public boolean isDataSaverDisabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        final ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        return !Compatibility.isActiveNetworkMetered(connectivityManager)
                || Compatibility.getRestrictBackgroundStatus(connectivityManager)
                        == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
    }

    public Map<Integer, Integer> getMessagesCountGroupByDay(
            String conversationUuid, int year, int month) {
        return databaseBackend.getMessagesCountGroupByDay(conversationUuid, year, month);
    }

    private void directReply(
            final Conversation conversation,
            final String body,
            final String lastMessageUuid,
            final boolean dismissAfterReply) {
        final Message inReplyTo =
                lastMessageUuid == null ? null : conversation.findMessageWithUuid(lastMessageUuid);
        Message message = new Message(conversation, body, conversation.getNextEncryption());
        if (inReplyTo != null) {
            if (Emoticons.isEmoji(body.replaceAll("\\s", ""))) {
                final var aggregated = inReplyTo.getAggregatedReactions();
                final ImmutableSet.Builder<String> reactionBuilder = new ImmutableSet.Builder<>();
                reactionBuilder.addAll(aggregated.ourReactions);
                reactionBuilder.add(body.replaceAll("\\s", ""));
                sendReactions(inReplyTo, reactionBuilder.build());
                return;
            } else {
                message = inReplyTo.reply();
            }
            message.clearFallbacks("urn:xmpp:reply:0");
            message.setBody(body);
            message.setEncryption(conversation.getNextEncryption());
        }
        if (inReplyTo != null && inReplyTo.isPrivateMessage()) {
            Message.configurePrivateMessage(message, inReplyTo.getCounterpart());
        }
        message.markUnread();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            getPgpEngine()
                    .encrypt(
                            message,
                            new UiCallback<Message>() {
                                @Override
                                public void success(Message message) {
                                    if (dismissAfterReply) {
                                        markRead((Conversation) message.getConversation(), true);
                                    } else {
                                        mNotificationService.pushFromDirectReply(message);
                                    }
                                }

                                @Override
                                public void error(int errorCode, Message object) {}

                                @Override
                                public void userInputRequired(PendingIntent pi, Message object) {}
                            });
        } else {
            sendMessage(message);
            if (dismissAfterReply) {
                markRead(conversation, true);
            } else {
                mNotificationService.pushFromDirectReply(message);
            }
        }
    }

    private boolean dndOnSilentMode() {
        return getBooleanPreference(AppSettings.DND_ON_SILENT_MODE, R.bool.dnd_on_silent_mode);
    }

    private boolean manuallyChangePresence() {
        return getBooleanPreference(
                AppSettings.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    private boolean treatVibrateAsSilent() {
        return getBooleanPreference(
                AppSettings.TREAT_VIBRATE_AS_SILENT, R.bool.treat_vibrate_as_silent);
    }

    private boolean awayWhenScreenLocked() {
        return getBooleanPreference(
                AppSettings.AWAY_WHEN_SCREEN_IS_OFF, R.bool.away_when_screen_off);
    }

    private String getCompressPicturesPreference() {
        return getPreferences()
                .getString(
                        "picture_compression",
                        getResources().getString(R.string.picture_compression));
    }

    private Presence.Status getTargetPresence() {
        if (dndOnSilentMode() && isPhoneSilenced()) {
            return Presence.Status.DND;
        } else if (awayWhenScreenLocked() && isScreenLocked()) {
            return Presence.Status.AWAY;
        } else {
            return Presence.Status.ONLINE;
        }
    }

    public boolean isScreenLocked() {
        final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        final PowerManager powerManager = getSystemService(PowerManager.class);
        final boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        final boolean interactive;
        try {
            interactive = powerManager != null && powerManager.isInteractive();
        } catch (final Exception e) {
            return false;
        }
        return locked || !interactive;
    }

    private boolean isPhoneSilenced() {
        final NotificationManager notificationManager = getSystemService(NotificationManager.class);
        final int filter =
                notificationManager == null
                        ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                        : notificationManager.getCurrentInterruptionFilter();
        final boolean notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        final AudioManager audioManager = getSystemService(AudioManager.class);
        final int ringerMode =
                audioManager == null
                        ? AudioManager.RINGER_MODE_NORMAL
                        : audioManager.getRingerMode();
        try {
            if (treatVibrateAsSilent()) {
                return notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL;
            } else {
                return notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT;
            }
        } catch (final Throwable throwable) {
            Log.d(
                    Config.LOGTAG,
                    "platform bug in isPhoneSilenced (" + throwable.getMessage() + ")");
            return notificationDnd;
        }
    }

    private void resetAllAttemptCounts(boolean reallyAll, boolean retryImmediately) {
        Log.d(Config.LOGTAG, "resetting all attempt counts");
        for (Account account : accounts) {
            if (account.hasErrorStatus() || reallyAll) {
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    connection.resetAttemptCount(retryImmediately);
                }
            }
            if (account.setShowErrorNotification(true)) {
                mDatabaseWriterExecutor.execute(() -> {
                    if (databaseBackend != null) databaseBackend.updateAccount(account);
                });
            }
        }
        mNotificationService.updateErrorNotification();
    }

    private void dismissErrorNotifications() {
        for (final Account account : this.accounts) {
            if (account.hasErrorStatus()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": dismissing error notification");
                if (account.setShowErrorNotification(false)) {
                    mDatabaseWriterExecutor.execute(() -> {
                        if (databaseBackend != null) databaseBackend.updateAccount(account);
                    });
                }
            }
        }
    }

    private void expireOldMessages() {
        expireOldMessages(false);
    }

    public void expireOldMessages(final boolean resetHasMessagesLeftOnServer) {
        final DatabaseBackend backend = databaseBackend;
        if (backend == null) return;
        mLastExpiryRun.set(SystemClock.elapsedRealtime());
        mDatabaseWriterExecutor.execute(() -> {
            // first, so the extra files of a multi-file message are collected below with it
            final int synced = backend.syncAttachmentExpiry();
            if (synced > 0) {
                Log.d(Config.LOGTAG, "gave " + synced + " extra files their message's expiry");
            }
            long timestamp = getAutomaticMessageDeletionDate();
            // The files of disappearing messages go with them, whatever "delete unused files"
            // says (otherwise a disappeared picture stayed in storage); that setting still
            // decides for messages removed for their age (timestamp 0 leaves those out). Files
            // another message still uses are never part of the list.
            final List<String> exclusivePaths =
                    backend.getExclusiveFilePathsExpiring(
                            getAppSettings().isDeleteUnusedFiles() ? timestamp : 0);
            if (!exclusivePaths.isEmpty()) {
                FILE_ATTACHMENT_EXECUTOR.execute(
                        () -> deleteFilesAsync(exclusivePaths, "background expiry"));
            }
            noteMessagesWiped(backend.expireOldMessages(timestamp));
            optimizeSearchIndexIfDue(backend);
            synchronized (XmppConnectionService.this.conversations) {
                for (Conversation conversation : XmppConnectionService.this.conversations) {
                    conversation.expireOldMessages(timestamp);
                    if (resetHasMessagesLeftOnServer) {
                        conversation.messagesLoaded.set(true);
                        conversation.setHasMessagesLeftOnServer(true);
                    }
                }
            }
            updateConversationUi();
            scheduleNextExpiry();
        });
    }

    /**
     * At most one search-index rewrite per this interval (see {@link #optimizeSearchIndexIfDue}).
     */
    private static final long SEARCH_INDEX_OPTIMIZE_INTERVAL = 10 * 60 * 1000L;

    private static final String SEARCH_INDEX_OPTIMIZE_DUE = "search_index_optimize_due";
    private static final String SEARCH_INDEX_OPTIMIZED_AT = "search_index_optimized_at";

    /**
     * Disappearing messages were wiped: their words are still in the search index (see {@link
     * DatabaseBackend#optimizeSearchIndex}) until it is rewritten. Remembered across restarts.
     */
    private void noteMessagesWiped(final int wiped) {
        if (wiped > 0) {
            getPreferences().edit().putBoolean(SEARCH_INDEX_OPTIMIZE_DUE, true).apply();
        }
    }

    /**
     * When the search index still holds words of wiped messages, the time it may next be rewritten;
     * 0 if nothing is waiting. The rewrite covers the whole index, so with short timers in a busy
     * chat it is limited to one per {@link #SEARCH_INDEX_OPTIMIZE_INTERVAL}.
     */
    private long searchIndexOptimizeTime() {
        final SharedPreferences preferences = getPreferences();
        if (!preferences.getBoolean(SEARCH_INDEX_OPTIMIZE_DUE, false)) {
            return 0;
        }
        final long last = preferences.getLong(SEARCH_INDEX_OPTIMIZED_AT, 0);
        final long now = System.currentTimeMillis();
        // a stored time in the future (clock changed) must not postpone it indefinitely
        return last > now ? now : last + SEARCH_INDEX_OPTIMIZE_INTERVAL;
    }

    private static final String IMAGE_DISK_CACHE_PURGED = "glide_disk_cache_purged_v2";

    /**
     * Local pictures are no longer put into Glide's disk cache: a copy would outlive a
     * disappearing or deleted message, and for files encrypted at rest it was their plaintext.
     * Copies written before that are removed once.
     */
    private void purgeImageDiskCacheOnce() {
        if (getPreferences().getBoolean(IMAGE_DISK_CACHE_PURGED, false)) {
            return;
        }
        FILE_ATTACHMENT_EXECUTOR.execute(() -> {
            try {
                com.bumptech.glide.Glide.get(this).clearDiskCache();
                getPreferences().edit().putBoolean(IMAGE_DISK_CACHE_PURGED, true).apply();
                Log.d(Config.LOGTAG, "cleared the image disk cache");
            } catch (final RuntimeException e) {
                Log.w(Config.LOGTAG, "could not clear the image disk cache", e);
            }
        });
    }

    /** Runs on the database thread. */
    private void optimizeSearchIndexIfDue(final DatabaseBackend backend) {
        final long due = searchIndexOptimizeTime();
        final long now = System.currentTimeMillis();
        if (due == 0 || due > now) {
            return;
        }
        backend.optimizeSearchIndex();
        getPreferences()
                .edit()
                .putBoolean(SEARCH_INDEX_OPTIMIZE_DUE, false)
                .putLong(SEARCH_INDEX_OPTIMIZED_AT, now)
                .apply();
        Log.d(Config.LOGTAG, "rewrote the search index without the words of wiped messages");
    }

    public void scheduleNextExpiry() {
        final DatabaseBackend backend = databaseBackend;
        mDatabaseWriterExecutor.execute(() -> {
            if (backend == null) return;
            final long expiry = backend.getNextExpiration();
            // the same alarm also brings the search-index rewrite (it runs in expireOldMessages),
            // so it happens even when the process was gone in between
            final long optimize = searchIndexOptimizeTime();
            final long next =
                    expiry > 0 && optimize > 0
                            ? Math.min(expiry, optimize)
                            : Math.max(expiry, optimize);
            if (next > 0) {
                final AlarmManager alarmManager =
                        (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (alarmManager == null) {
                    return;
                }
                final Intent intent =
                        new Intent(this, eu.siacs.conversations.receiver.SystemEventReceiver.class);
                intent.setAction(ACTION_EXPIRE_MESSAGES);
                final PendingIntent pendingIntent =
                        PendingIntent.getBroadcast(
                                this,
                                0,
                                intent,
                                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent);
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, next, pendingIntent);
                }
            }
        });
    }

    public boolean hasInternetConnection() {
        final ConnectivityManager cm =
                ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return true; // if internet connection can not be checked it is probably best to just
            // try
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                final Network activeNetwork = cm.getActiveNetwork();
                final NetworkCapabilities capabilities =
                        activeNetwork == null ? null : cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                final NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null
                        && (networkInfo.isConnected()
                                || networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET);
            }
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to check for internet connection", e);
            return true; // if internet connection can not be checked it is probably best to just
            // try
        }
    }

    /**
     * Older versions put the backup password into WorkManager's input data, which WorkManager keeps
     * unencrypted on disk (no_backup/androidx.work.workdb) until it prunes finished work. Prune
     * once, so those rows don't wait for the next import or WorkManager's own cleanup. Only
     * finished work is removed.
     */
    private static void pruneLegacyImportWorkOnce(final Context context) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean("workdb_pruned_password_v1", false)) {
            return;
        }
        try {
            androidx.work.WorkManager.getInstance(context).pruneWork();
            preferences.edit().putBoolean("workdb_pruned_password_v1", true).apply();
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "unable to prune finished work", e);
        }
    }

    @SuppressLint("TrulyRandom")
    @Override
    public void onCreate() {
        eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                .setOnSessionExpiredUiCallback(this::updateConversationUi);
        de.monocles.chat.AndroidLoggingHandler.reset(new de.monocles.chat.AndroidLoggingHandler());
        java.util.logging.Logger.getLogger("").setLevel(java.util.logging.Level.FINEST);
        LibIdnXmppStringprep.setup();
        final Context appCtx = getApplicationContext();
        // plaintext a crash left behind (recordings, transcodes, decrypted and upload copies,
        // stale partial downloads). Needs no key, so it runs before the database is unlocked:
        // with the startup password the user may never unlock again.
        eu.siacs.conversations.persistance.MediaFiles.wipeTemporary(appCtx);
        new Thread(
                        () -> {
                            emojiSearch = new EmojiSearch(appCtx);
                        },
                        "emoji-init")
                .start();
        pruneLegacyImportWorkOnce(appCtx);
        setTheme(R.style.Theme_Conversations3);
        ThemeHelper.applyCustomColors(this);
        if (Compatibility.runsTwentySix()) {
            mNotificationService.initializeChannels();
        }
        mChannelDiscoveryService.initializeMuclumbusService();
        mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(this));
        toggleForegroundService();
        this.destroyed = false;
        OmemoSetting.load(this);
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable);
        }
        Resolver.init(this);
        updateMemorizingTrustManager();
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        this.mDrawableCache =
                new LruCache<String, Drawable>(cacheSize) {
                    @Override
                    protected int sizeOf(final String key, final Drawable drawable) {
                        if (drawable instanceof BitmapDrawable) {
                            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                            if (bitmap == null) return 1024;

                            return bitmap.getByteCount() / 1024;
                        } else if (drawable instanceof AvatarService.TextDrawable) {
                            return 50;
                        } else {
                            return drawable.getIntrinsicWidth()
                                    * drawable.getIntrinsicHeight()
                                    * 40
                                    / 1024;
                        }
                    }
                };
        if (mLastActivity == 0) {
            mLastActivity =
                    getPreferences().getLong(SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis());
        }

        Log.d(Config.LOGTAG, "starting database initialization in background...");
        new Thread(this::initializeDatabaseInBackground, "db-init").start();
    }

    /**
     * Opens the encrypted database on a background thread to avoid blocking the main thread with
     * the AndroidKeyStore key setup and SQLCipher Argon2id KDF. Posts back to the main thread when
     * done (success or failure).
     */
    private void initializeDatabaseInBackground() {
        final DatabaseBackend backend;
        try {
            backend = DatabaseBackend.getInstance(getApplicationContext());
        } catch (final eu.siacs.conversations.EncryptionException e) {
            mLiveLocationHandler.post(() -> {
                this.accounts = new java.util.ArrayList<>();
                if (e.reason
                        == eu.siacs.conversations.EncryptionException.Reason
                                .NEEDS_SESSION_PASSWORD) {
                    Log.i(Config.LOGTAG, "Database requires startup password — waiting for user");
                    this.mNeedsPassword = true;
                } else {
                    Log.e(Config.LOGTAG, "Critical keystore failure during service startup", e);
                    this.mCriticalError = e;
                }
                notifyDbReadyCallbacks();
            });
            return;
        }
        // Pre-warm UnifiedPushDatabase on this background thread so its Argon2id key derivation
        // never runs on the main thread. UnifiedPushDatabase.getInstance() is called from
        // BroadcastReceiver.onReceive() (main thread) and the XMPP connection thread; without
        // pre-warming, the first call from either thread triggers 2-4s of Argon2id computation
        // and can cause an ANR if it races with a main-thread caller holding the class lock.
        try {
            eu.siacs.conversations.persistance.UnifiedPushDatabase.getInstance(
                    getApplicationContext());
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "Failed to pre-initialize UnifiedPushDatabase (non-fatal)", e);
        }

        // Before anything loads messages: the stored paths must already point at the moved files.
        migrateCacheToInternalStorage();
        migrateMediaPathsToInternalStorage(backend);

        Log.d(Config.LOGTAG, "restoring accounts...");
        final List<Account> loadedAccounts;
        try {
            loadedAccounts = backend.getAccounts();
        } catch (final net.zetetic.database.sqlcipher.SQLiteNotADatabaseException e) {
            mLiveLocationHandler.post(() -> {
                Log.e(Config.LOGTAG, "Wrong database key on getAccounts", e);
                DatabaseBackend.closeInstance();
                eu.siacs.conversations.AppSettings.clearSessionPassword();
                this.mCriticalError =
                        new eu.siacs.conversations.EncryptionException(
                                "Wrong database key",
                                e,
                                eu.siacs.conversations.EncryptionException.Reason.DB_WRONG_KEY);
                this.accounts = new java.util.ArrayList<>();
                notifyDbReadyCallbacks();
            });
            return;
        }
        mLiveLocationHandler.post(() -> continueAfterDbInit(backend, loadedAccounts));
    }

    /**
     * Continues service setup on the main thread once the database is open and accounts loaded.
     * Mirrors what was previously the second half of onCreate().
     */
    private void continueAfterDbInit(
            final DatabaseBackend backend, final List<Account> loadedAccounts) {
        if (destroyed) {
            // onDestroy() ran while the DB was opening: the executors are shut down and nothing
            // must be registered on a dead service (scheduling threw RejectedExecutionException)
            Log.d(
                    Config.LOGTAG,
                    "service destroyed during database initialization; not continuing");
            return;
        }
        this.databaseBackend = backend;
        this.accounts = loadedAccounts;

        for (Account account : this.accounts) {
            final int color = getPreferences().getInt("account_color:" + account.getUuid(), 0);
            if (color != 0) account.setColor(color);
        }
        final SharedPreferences.Editor editor = getPreferences().edit();
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        editor.putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        editor.apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
        reconfigurePushDistributor();

        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountsAsync(this, this.accounts);
        }

        try {
            restoreFromDatabase();
        } catch (net.zetetic.database.sqlcipher.SQLiteNotADatabaseException e) {
            Log.e(Config.LOGTAG, "Wrong database key on restoreFromDatabase", e);
            DatabaseBackend.closeInstance();
            this.databaseBackend = null;
            eu.siacs.conversations.AppSettings.clearSessionPassword();
            this.mCriticalError =
                    new eu.siacs.conversations.EncryptionException(
                            "Wrong database key",
                            e,
                            eu.siacs.conversations.EncryptionException.Reason.DB_WRONG_KEY);
            notifyDbReadyCallbacks();
            return;
        }

        if (QuickConversationsService.isContactListIntegration(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED) {
            startContactObserver();
        }
        if (Compatibility.hasStoragePermission(this)) {
            Log.d(Config.LOGTAG, "starting file observer");
            FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::startWatching);
            FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
        }
        if (Config.supportOpenPgp()) {
            this.pgpServiceConnection =
                    new OpenPgpServiceConnection(
                            this,
                            "org.sufficientlysecure.keychain",
                            new OpenPgpServiceConnection.OnBound() {
                                @Override
                                public void onBound(final IOpenPgpService2 service) {
                                    for (Account account : accounts) {
                                        final PgpDecryptionService pgp =
                                                account.getPgpDecryptionService();
                                        if (pgp != null) {
                                            pgp.continueDecryption(true);
                                        }
                                    }
                                }

                                @Override
                                public void onError(final Exception exception) {
                                    Log.e(
                                            Config.LOGTAG,
                                            "could not bind to OpenKeyChain",
                                            exception);
                                }
                            });
            this.pgpServiceConnection.bindToService();
        }

        final PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            this.wakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service");
        }

        toggleForegroundService();
        updateUnreadCountBadge();
        toggleScreenEventReceiver();
        final IntentFilter systemBroadcastFilter = new IntentFilter();
        scheduleNextIdlePing();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            systemBroadcastFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        }
        systemBroadcastFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        ContextCompat.registerReceiver(
                this,
                this.mInternalEventReceiver,
                systemBroadcastFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        final IntentFilter exportedBroadcastFilter = new IntentFilter();
        exportedBroadcastFilter.addAction(TorServiceUtils.ACTION_STATUS);
        ContextCompat.registerReceiver(
                this,
                this.mInternalRestrictedEventReceiver,
                exportedBroadcastFilter,
                ContextCompat.RECEIVER_EXPORTED);
        mForceDuringOnCreate.set(false);
        toggleForegroundService();
        rescanStickers();
        mStickerScanExecutor.execute(this::migrateRecordingsToInternalStorage);
        cleanupTemporaryStorage();

        internalPingExecutor.scheduleWithFixedDelay(
                this::manageAccountConnectionStatesInternal, 10, 10, TimeUnit.SECONDS);
        storyRetractionExecutor.scheduleWithFixedDelay(
                this::retractOldStories, 1, 60, TimeUnit.MINUTES);
        storyCacheExecutor.scheduleWithFixedDelay(this::cleanupStoryCache, 1, 1, TimeUnit.HOURS);
        storyCacheExecutor.scheduleWithFixedDelay(
                () -> eu.siacs.conversations.persistance.MediaFiles.wipeTemporary(this),
                1,
                1,
                TimeUnit.HOURS);
        final SharedPreferences sharedPreferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(
                            SharedPreferences sharedPreferences, @Nullable String key) {
                        Log.d(Config.LOGTAG, "preference '" + key + "' has changed");
                        if (AppSettings.KEEP_FOREGROUND_SERVICE.equals(key)) {
                            toggleForegroundService();
                        }
                    }
                });

        mDatabaseReady = true;
        // encrypt attachments that are still plain (older installations); needs the database
        eu.siacs.conversations.worker.MediaEncryptionWorker.schedule(this);
        notifyDbReadyCallbacks();
    }

    private void checkForDeletedFiles() {
        if (destroyed) {
            Log.d(
                    Config.LOGTAG,
                    "Do not check for deleted files because service has been destroyed");
            return;
        }
        final long start = SystemClock.elapsedRealtime();
        final List<DatabaseBackend.FilePathInfo> relativeFilePaths =
                databaseBackend.getFilePathInfo();
        final List<DatabaseBackend.FilePathInfo> changed = new ArrayList<>();
        for (final DatabaseBackend.FilePathInfo filePath : relativeFilePaths) {
            if (destroyed) {
                Log.d(
                        Config.LOGTAG,
                        "Stop checking for deleted files because service has been destroyed");
                return;
            }
            final File file = fileBackend.getFileForPath(filePath.path);
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath);
            }
        }
        final long duration = SystemClock.elapsedRealtime() - start;
        Log.d(
                Config.LOGTAG,
                "found "
                        + changed.size()
                        + " changed files on start up. total="
                        + relativeFilePaths.size()
                        + ". ("
                        + duration
                        + "ms)");
        if (changed.size() > 0) {
            databaseBackend.markFilesAsChanged(changed);
            markChangedFiles(changed);
        }
    }

    public void startContactObserver() {
        getContentResolver()
                .registerContentObserver(
                        ContactsContract.Contacts.CONTENT_URI,
                        true,
                        new ContentObserver(null) {
                            @Override
                            public void onChange(boolean selfChange) {
                                super.onChange(selfChange);
                                if (restoredFromDatabaseLatch.getCount() == 0) {
                                    loadPhoneContacts();
                                }
                            }
                        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.d(Config.LOGTAG, "clear cache due to low memory");
            getDrawableCache().evictAll();
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(this.mInternalEventReceiver);
            unregisterReceiver(this.mInternalRestrictedEventReceiver);
            unregisterReceiver(this.mInternalScreenEventReceiver);
        } catch (final IllegalArgumentException e) {
            // ignored
        }
        destroyed = true;
        fileObserver.stopWatching();
        internalPingExecutor.shutdown();
        storyRetractionExecutor.shutdown();
        storyCacheExecutor.shutdown();
        super.onDestroy();
    }

    public void restartFileObserver() {
        Log.d(Config.LOGTAG, "restarting file observer");
        FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::restartWatching);
        FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
    }

    public void toggleScreenEventReceiver() {
        if (awayWhenScreenLocked() && !manuallyChangePresence()) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(this.mInternalScreenEventReceiver, filter);
        } else {
            try {
                unregisterReceiver(this.mInternalScreenEventReceiver);
            } catch (IllegalArgumentException e) {
                // ignored
            }
        }
    }

    public void toggleForegroundService() {
        toggleForegroundService(false, false);
    }

    public void setOngoingCall(
            AbstractJingleConnection.Id id, Set<Media> media, final boolean reconnecting) {
        setOngoingCall(id, media, reconnecting, null);
    }

    /** {@code mujiRoom} is the group chat of a Muji group call, null for a 1:1 call. */
    public void setOngoingCall(
            AbstractJingleConnection.Id id,
            Set<Media> media,
            final boolean reconnecting,
            final String mujiRoom) {
        ongoingCall.set(new OngoingCall(id, media, reconnecting, mujiRoom));
        toggleForegroundService(false, true);
    }

    /** Whether the ongoing call (notification) is the group call in {@code mujiRoom}. */
    public boolean isOngoingGroupCall(final Account account, final String mujiRoom) {
        final OngoingCall ongoing = ongoingCall.get();
        return ongoing != null
                && ongoing.id.account == account
                && mujiRoom.equals(ongoing.mujiRoom);
    }

    public void removeOngoingCall() {
        ongoingCall.set(null);
        toggleForegroundService(false, false);
    }

    private void toggleForegroundService(boolean force, boolean needMic) {
        final boolean status;
        final OngoingCall ongoing = ongoingCall.get();
        final boolean ongoingVideoTranscoding = mOngoingVideoTranscoding.get();
        final boolean ongoingLiveLocation = !mOutgoingLiveSessions.isEmpty();
        final int id;
        if (force
                || mForceDuringOnCreate.get()
                || ongoingVideoTranscoding
                || ongoingLiveLocation
                || ongoing != null
                || (Compatibility.keepForegroundService(this) && hasEnabledAccounts())) {
            if (Compatibility.runsTwentySix()) {
                mNotificationService.initializeChannels();
            }
            final Notification notification;
            if (ongoing != null && !diallerIntegrationActive.get()) {
                notification = this.mNotificationService.getOngoingCallNotification(ongoing);
                id = NotificationService.ONGOING_CALL_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, true);
            } else if (ongoingVideoTranscoding) {
                notification = this.mNotificationService.getIndeterminateVideoTranscoding();
                id = NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID;
                startForegroundOrCatch(id, notification, false);
            } else {
                notification = this.mNotificationService.createForegroundNotification();
                id = NotificationService.FOREGROUND_NOTIFICATION_ID;
                startForegroundOrCatch(
                        id,
                        notification,
                        needMic || ongoing != null || diallerIntegrationActive.get());
            }
            mNotificationService.notify(id, notification);
            status = true;
        } else {
            id = 0;
            stopForeground(true);
            status = false;
        }

        for (final int toBeRemoved :
                Collections2.filter(
                        Arrays.asList(
                                NotificationService.FOREGROUND_NOTIFICATION_ID,
                                NotificationService.ONGOING_CALL_NOTIFICATION_ID,
                                NotificationService.ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID),
                        i -> i != id)) {
            mNotificationService.cancel(toBeRemoved);
        }
        Log.d(
                Config.LOGTAG,
                "ForegroundService: " + (status ? "on" : "off") + ", notification: " + id);
    }

    private void startForegroundOrCatch(
            final int id, final Notification notification, final boolean requireMicrophone) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                final boolean hasLiveLocation =
                        !mOutgoingLiveSessions.isEmpty()
                                && ContextCompat.checkSelfPermission(
                                                this, Manifest.permission.ACCESS_FINE_LOCATION)
                                        == PackageManager.PERMISSION_GRANTED;
                int foregroundServiceType;
                if (requireMicrophone
                        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                    // Active call: claim MICROPHONE (and LOCATION if live location is also running)
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    if (hasLiveLocation) {
                        foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    }
                    Log.d(Config.LOGTAG, "foreground service type: microphone");
                } else if (hasLiveLocation) {
                    // Live location only, no active call — use LOCATION alone.
                    // Do NOT combine with MICROPHONE here: MICROPHONE requires an active recording
                    // session in API 34+, and claiming it without one can invalidate the entire
                    // service type, revoking GPS access.
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    Log.d(Config.LOGTAG, "foreground service type: location");
                } else if (getSystemService(PowerManager.class)
                        .isIgnoringBatteryOptimizations(getPackageName())) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                } else {
                    foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                    Log.w(Config.LOGTAG, "falling back to special use foreground service type");
                }
                startForeground(id, notification, foregroundServiceType);
            } else {
                startForeground(id, notification);
            }
        } catch (final IllegalStateException | SecurityException e) {
            Log.e(Config.LOGTAG, "Could not start foreground service", e);
        }
    }

    public boolean foregroundNotificationNeedsUpdatingWhenErrorStateChanges() {
        return !mOngoingVideoTranscoding.get()
                && ongoingCall.get() == null
                && Compatibility.keepForegroundService(this)
                && hasEnabledAccounts();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if ((Compatibility.keepForegroundService(this) && hasEnabledAccounts())
                || mOngoingVideoTranscoding.get()
                || ongoingCall.get() != null) {
            Log.d(Config.LOGTAG, "ignoring onTaskRemoved because foreground service is activated");
        } else {
            this.logoutAndSave(false);
        }
    }

    /**
     * Locks the session (see {@link AppSessionLock}): rosters are saved and every account goes
     * offline properly (contacts see it at once, no ghost session), then a last task on the
     * database writer runs after every write queued before it, and ends the process.
     */
    private void lockAndTerminate() {
        final List<Account> current = accounts == null ? List.of() : new ArrayList<>(accounts);
        new Thread(
                        () -> {
                            for (final Account account : current) {
                                try {
                                    if (account.isConnectionEnabled() && databaseBackend != null) {
                                        databaseBackend.writeRoster(account.getRoster());
                                    }
                                    if (account.getXmppConnection() != null) {
                                        disconnect(account, false);
                                    }
                                } catch (final RuntimeException e) {
                                    Log.w(Config.LOGTAG, "could not close " + account.getJid(), e);
                                }
                            }
                            mDatabaseWriterExecutor.execute(
                                    () -> AppSessionLock.terminate(getApplicationContext()));
                        },
                        "session-lock")
                .start();
    }

    private void logoutAndSave(boolean stop) {
        int activeAccounts = 0;
        if (accounts != null) {
            for (final Account account : accounts) {
                if (account.isConnectionEnabled()) {
                    if (databaseBackend != null) {
                        databaseBackend.writeRoster(account.getRoster());
                    }
                    activeAccounts++;
                }
                if (account.getXmppConnection() != null) {
                    new Thread(() -> disconnect(account, false)).start();
                }
            }
        }
        if (stop || activeAccounts == 0) {
            Log.d(Config.LOGTAG, "good bye");
            stopSelf();
        }
    }

    private void schedulePostConnectivityChange() {
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final long triggerAtMillis =
                SystemClock.elapsedRealtime()
                        + (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000);
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_POST_CONNECTIVITY_CHANGE);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this,
                            1,
                            intent,
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e);
        }
    }

    public void scheduleWakeUpCall(final int seconds, final int requestCode) {
        scheduleWakeUpCall((seconds < 0 ? 1 : seconds + 1) * 1000L, requestCode);
    }

    public void scheduleWakeUpCall(final long milliSeconds, final int requestCode) {
        final var timeToWake = SystemClock.elapsedRealtime() + milliSeconds;
        final var alarmManager = getSystemService(AlarmManager.class);
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_PING);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e);
        }
    }

    private void scheduleNextIdlePing() {
        long timeUntilWake = Config.IDLE_PING_INTERVAL * 1000;
        final var now = System.currentTimeMillis();
        for (final var message : mScheduledMessages.values()) {
            if (message.getTimeSent() <= now) continue; // Just in case
            if (message.getTimeSent() - now < timeUntilWake)
                timeUntilWake = message.getTimeSent() - now;
        }
        final var timeToWake = SystemClock.elapsedRealtime() + timeUntilWake;
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.d(Config.LOGTAG, "no alarm manager?");
            return;
        }
        final Intent intent = new Intent(this, SystemEventReceiver.class);
        intent.setAction(ACTION_IDLE_PING);
        try {
            final PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            this,
                            0,
                            intent,
                            s()
                                    ? PendingIntent.FLAG_IMMUTABLE
                                            | PendingIntent.FLAG_UPDATE_CURRENT
                                    : PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to schedule alarm for idle ping", e);
        }
    }

    public XmppConnection createConnection(final Account account) {
        final XmppConnection connection = new XmppConnection(account, this);
        connection.setOnStatusChangedListener(this.statusListener);
        connection.setOnJinglePacketReceivedListener((mJingleConnectionManager::deliverPacket));
        connection.setOnMessageAcknowledgeListener(this.mOnMessageAcknowledgedListener);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mMessageArchiveService);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mAvatarService);
        AxolotlService axolotlService = account.getAxolotlService();
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
        return connection;
    }

    /**
     * Sends a delivery receipt / chat-marker {@code <received/>} for message {@code id} from {@code
     * to}. In a PQ OMEMO2 chat it goes inside the encrypted envelope, like every other piece of
     * chat metadata: in the clear the server learns which messages were received (and the contact's
     * client drops a cleartext receipt in such a chat anyway).
     */
    public void sendDeliveryReceipt(
            final Account account,
            final Jid to,
            final String id,
            final List<String> namespaces,
            final im.conversations.android.xmpp.model.stanza.Message.Type type) {
        if (to == null || id == null || namespaces.isEmpty()) {
            return;
        }
        final Conversation conversation = find(account, to.asBareJid());
        if (conversation != null
                && conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            final boolean groupChat =
                    type == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
            final var basePacket = new im.conversations.android.xmpp.model.stanza.Message();
            basePacket.setType(type);
            basePacket.setTo(groupChat ? to.asBareJid() : to);
            basePacket.setFrom(account.getJid());
            basePacket.addChild("store", "urn:xmpp:hints");
            final List<Element> contents = new ArrayList<>();
            for (final String ns : namespaces) {
                contents.add(new Element("received", ns).setAttribute("id", id));
            }
            account.getAxolotlService().sendOmemo2Packet(conversation, basePacket, contents);
        } else {
            sendMessagePacket(
                    account,
                    mMessageGenerator.received(account, to, id, new ArrayList<>(namespaces), type));
        }
    }

    public void sendChatState(Conversation conversation) {
        if (!sendChatStates()) return;
        if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            // Wrap the chat state into an OMEMO2 SCE envelope so typing/active
            // metadata is not exposed to the server / passive observers.
            final var basePacket = new im.conversations.android.xmpp.model.stanza.Message();
            basePacket.setType(
                    conversation.getMode() == Conversation.MODE_MULTI
                            ? im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT
                            : im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
            basePacket.setTo(conversation.getJid().asBareJid());
            basePacket.addChild("no-store", "urn:xmpp:hints");
            final Element chatState = ChatState.toElement(conversation.getOutgoingChatState());
            conversation
                    .getAccount()
                    .getAxolotlService()
                    .sendOmemo2Packet(conversation, basePacket, java.util.List.of(chatState));
            return;
        }
        final var packet = mMessageGenerator.generateChatState(conversation);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    private void sendFileMessage(
            final Message message, final boolean delay, final Runnable cb, final boolean forceP2P) {
        final var account = message.getConversation().getAccount();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": send file message. forceP2P=" + forceP2P);
        if ((account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                        || message.getConversation().getMode() == Conversation.MODE_MULTI)
                && !forceP2P) {
            mHttpConnectionManager.createNewUploadConnection(message, delay, cb);
        } else {
            mJingleConnectionManager.startJingleFileTransfer(message);
            if (cb != null) cb.run();
        }
    }

    public void sendMessage(final Message message) {
        sendMessage(message, false, false, false, null, false);
    }

    public void sendMessage(final Message message, final Runnable cb) {
        sendMessage(message, false, false, false, cb, false);
    }

    public void sendEphemeralImplicitNegotiation(Conversation conversation, int timer) {
        sendEphemeralControl(
                conversation,
                new Element("ephemeral", Namespace.EPHEMERAL)
                        .setAttribute("timer", String.valueOf(timer)));
    }

    public void sendEphemeralIWantOut(Conversation conversation) {
        sendEphemeralControl(conversation, new Element("i-want-out", Namespace.EPHEMERAL));
    }

    /**
     * Announces a change of the disappearing-message timer. In a PQ OMEMO2 chat it goes inside the
     * encrypted envelope like every other piece of chat metadata: in the clear the server would
     * learn the timer and could change it on the contact's side.
     */
    private void sendEphemeralControl(final Conversation conversation, final Element control) {
        final var packet = new im.conversations.android.xmpp.model.stanza.Message();
        packet.setTo(conversation.getJid().asBareJid());
        packet.setType(
                conversation.getMode() == Conversation.MODE_SINGLE
                        ? im.conversations.android.xmpp.model.stanza.Message.Type.CHAT
                        : im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT);
        packet.addChild("store", Namespace.HINTS);
        if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            conversation
                    .getAccount()
                    .getAxolotlService()
                    .sendOmemo2Packet(
                            conversation, packet, java.util.Collections.singletonList(control));
        } else {
            packet.addChild(control);
            sendMessagePacket(conversation.getAccount(), packet);
        }
    }

    private void sendMessage(
            final Message message,
            final boolean resend,
            final boolean previewedLinks,
            final boolean delay,
            final Runnable cb,
            final boolean forceP2P) {
        final Account account = message.getConversation().getAccount();
        if (account.setShowErrorNotification(true)) {
            databaseBackend.updateAccount(account);
            mNotificationService.updateErrorNotification();
        }
        final Conversation conversation = (Conversation) message.getConversation();
        account.deactivateGracePeriod();

        // A disappearing message whose time ran out while it waited (offline) has been wiped:
        // no body, deleted, encryption reset to none. Sending it would put a body-less plaintext
        // stanza on the wire, even in a PQ OMEMO2 chat. It is not sent at all; marked failed, so
        // it is not picked up as waiting again, but without markMessage's "could not be
        // delivered" notification: it disappeared as intended. (Checked on the time, not on
        // isDeleted(): a retraction legitimately goes out as a deleted message.)
        if (message.getExpireAt() > 0 && message.getExpireAt() <= System.currentTimeMillis()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": not sending "
                            + message.getUuid()
                            + ", its disappearing-message time ran out before it could be sent");
            message.setStatus(Message.STATUS_SEND_FAILED);
            databaseBackend.updateMessage(message, false);
            updateConversationUi();
            // the alarm may not have come yet (doze): wipe it now
            expireOldMessages();
            if (cb != null) {
                cb.run();
            }
            return;
        }

        if (message.getEphemeralTimer() > 0 && message.getExpireAt() == 0) {
            message.setExpireAt(System.currentTimeMillis() + message.getEphemeralTimer() * 1000L);
        }

        if (QuickConversationsService.isQuicksy()
                && conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            if (!contact.showInRoster() && contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": adding "
                                + contact.getJid()
                                + " on sending message");
                createContact(contact, true);
            }
        }

        im.conversations.android.xmpp.model.stanza.Message packet = null;
        // An extra file of a multi-file message gets a row of its own (so it can be
        // downloaded, opened and deleted like any other file) but no bubble and no stanza:
        // it is described inside its parent's single stanza.
        final boolean isAttachment = message.isAttachment();
        final boolean addToConversation =
                !message.edited() && message.getRawBody() != null && !isAttachment;
        boolean saveInDb = addToConversation || isAttachment;
        message.setStatus(Message.STATUS_WAITING);

        if (message.getEncryption() != Message.ENCRYPTION_NONE
                && conversation.getMode() == Conversation.MODE_MULTI
                && conversation.isPrivateAndNonAnonymous()) {
            if (conversation.setAttribute(
                    Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, true)) {
                databaseBackend.updateConversation(conversation);
            }
        }

        if (!resend && message.getEncryption() != Message.ENCRYPTION_OTR) {
            conversation.endOtrIfNeeded();
            conversation.findUnsentMessagesWithEncryption(
                    Message.ENCRYPTION_OTR,
                    message1 -> markMessage(message1, Message.STATUS_SEND_FAILED));
        }

        final boolean inProgressJoin = isJoinInProgress(conversation);

        if (message.getCounterpart() == null && !message.isPrivateMessage()) {
            message.setCounterpart(message.getConversation().getJid().asBareJid());
        }

        boolean waitForPreview = false;
        // OMEMO2 is allowed into this block (legacy OMEMO is not). The OGP /
        // RDF Description metadata produced by the HTML branch below ends up
        // in message.getPayloads() which AxolotlService.encryptOmemo2 places
        // inside the authenticated SCE envelope. The image/audio/video/pdf
        // file-attachment branch is gated separately below so OMEMO2 doesn't
        // silently re-upload a linked file.
        if (getPreferences().getBoolean("send_link_previews", true)
                && !previewedLinks
                && !message.needsUploading()
                && message.getEncryption() != Message.ENCRYPTION_AXOLOTL) {
            message.clearLinkDescriptions();
            final List<URI> links = message.getLinks();
            if (!links.isEmpty()) {
                waitForPreview = true;
                if (account.isOnlineAndConnected()) {
                    FILE_ATTACHMENT_EXECUTOR.execute(() -> {
                        for (URI link : links) {
                            // https everywhere; plain http only for hidden services, whose
                            // transport is already end-to-end encrypted by Tor/I2P.
                            final boolean hiddenService =
                                    HttpConnectionManager.isOnionHost(link.getHost())
                                            || HttpConnectionManager.isI2PHost(link.getHost());
                            if ("https".equals(link.getScheme())
                                    || (hiddenService && "http".equals(link.getScheme()))) {
                                try {
                                    HttpUrl url = HttpUrl.parse(link.toString());
                                    // total deadline + only allowed redirect hops, see
                                    // linkPreviewClient
                                    final boolean proxied =
                                            HttpConnectionManager.route(
                                                            url.host(),
                                                            useTorToConnect() || account.isOnion(),
                                                            useI2PToConnect() || account.isI2P())
                                                    != HttpConnectionManager.Route.DIRECT;
                                    final OkHttpClient http =
                                            HttpConnectionManager.linkPreviewClient(
                                                    getHttpConnectionManager()
                                                            .buildHttpClient(
                                                                    url, account, 5, false),
                                                    proxied);
                                    final var request =
                                            new okhttp3.Request.Builder().url(url).head().build();
                                    okhttp3.Response response = null;
                                    if ("www.amazon.com".equals(link.getHost())
                                            || "www.amazon.ca".equals(link.getHost())) {
                                        // Amazon blocks HEAD
                                        response =
                                                new okhttp3.Response.Builder()
                                                        .request(request)
                                                        .protocol(okhttp3.Protocol.HTTP_1_1)
                                                        .code(200)
                                                        .message("OK")
                                                        .addHeader("Content-Type", "text/html")
                                                        .build();
                                    } else {
                                        response =
                                                HttpConnectionManager.executeLinkPreviewRequest(
                                                        http, request);
                                    }
                                    final String mimeType =
                                            response.header("Content-Type") == null
                                                    ? ""
                                                    : response.header("Content-Type");
                                    final boolean image = mimeType.startsWith("image/");
                                    final boolean audio = mimeType.startsWith("audio/");
                                    final boolean video = mimeType.startsWith("video/");
                                    final boolean pdf = mimeType.equals("application/pdf");
                                    final boolean html =
                                            mimeType.startsWith("text/html")
                                                    || mimeType.startsWith("application/xhtml+xml");
                                    if (response.isSuccessful()
                                            && (image || audio || video || pdf)
                                            && message.getEncryption()
                                                    != Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                                        // OMEMO2 messages do not auto-convert a linked
                                        // public file into a re-uploaded encrypted file
                                        // attachment. Doing so would silently change a
                                        // user's plain "share a link" intent into a full
                                        // download + per-message AES-GCM re-upload to
                                        // their own HTTP-upload server. The OGP/HTML
                                        // branch below still runs for OMEMO2 and yields
                                        // an encrypted link-description payload.
                                        Message.FileParams params = message.getFileParams();
                                        params.url = url.toString();
                                        if (response.header("Content-Length") != null)
                                            params.size =
                                                    Long.parseLong(
                                                            response.header("Content-Length"), 10);
                                        if (!Message.configurePrivateFileMessage(message)) {
                                            message.setType(
                                                    image ? Message.TYPE_IMAGE : Message.TYPE_FILE);
                                        }
                                        params.setName(
                                                HttpConnectionManager.extractFilenameFromResponse(
                                                        response));

                                        if (link.toString().equals(message.getRawBody())) {
                                            Element fallback =
                                                    new Element("fallback", "urn:xmpp:fallback:0")
                                                            .setAttribute("for", Namespace.OOB);
                                            fallback.addChild("body", "urn:xmpp:fallback:0");
                                            message.addPayload(fallback);
                                        } else if (message.getRawBody().indexOf(link.toString())
                                                >= 0) {
                                            // Part of the real body, not just a fallback
                                            Element fallback =
                                                    new Element("fallback", "urn:xmpp:fallback:0")
                                                            .setAttribute("for", Namespace.OOB);
                                            fallback.addChild("body", "urn:xmpp:fallback:0")
                                                    .setAttribute("start", "0")
                                                    .setAttribute("end", "0");
                                            message.addPayload(fallback);
                                        }

                                        final int encryption = message.getEncryption();
                                        getHttpConnectionManager()
                                                .createNewDownloadConnection(
                                                        message,
                                                        false,
                                                        file -> {
                                                            message.setEncryption(encryption);
                                                            synchronized (
                                                                    message.getConversation()) {
                                                                if (message.getStatus()
                                                                        == Message.STATUS_WAITING)
                                                                    sendMessage(
                                                                            message, true, true,
                                                                            false, cb, false);
                                                            }
                                                        });
                                        return;
                                    } else if (response.isSuccessful() && html) {
                                        response.close();
                                        // Fetch the page with the same OkHttp client as the HEAD so
                                        // it follows the Tor/I2P routing policy (incl. redirects).
                                        final Element rdf = fetchLinkDescription(http, url, link);
                                        if (rdf != null) message.addPayload(rdf);
                                    }
                                } catch (final IOException | RuntimeException e) {
                                    Log.d(Config.LOGTAG, "unable to build link preview", e);
                                }
                            }
                        }
                        synchronized (message.getConversation()) {
                            if (message.getStatus() == Message.STATUS_WAITING)
                                sendMessage(message, true, true, false, cb, false);
                        }
                    });
                }
            }
        }

        boolean passedCbOn = false;
        // A multi-file message is held back until every one of its files has been uploaded,
        // because they all travel in the same stanza. The extra files never produce a stanza
        // of their own; the last upload to land releases the parent (see releaseParentIfReady).
        final boolean holdForAttachments = !isAttachment && message.hasPendingAttachments();
        if (account.isOnlineAndConnected()
                && !inProgressJoin
                && !waitForPreview
                && message.getTimeSent() <= System.currentTimeMillis()) {
            if (isAttachment || holdForAttachments) {
                if (message.needsUploading()
                        && (account.httpUploadAvailable(
                                        fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart())) {
                    this.sendFileMessage(message, delay, cb, forceP2P);
                    passedCbOn = true;
                }
            } else {
                switch (message.getEncryption()) {
                    case Message.ENCRYPTION_NONE:
                        if (message.needsUploading()) {
                            if (account.httpUploadAvailable(
                                            fileBackend.getFile(message, false).getSize())
                                    || conversation.getMode() == Conversation.MODE_MULTI
                                    || message.fixCounterpart()) {
                                this.sendFileMessage(message, delay, cb, forceP2P);
                                passedCbOn = true;
                            } else {
                                break;
                            }
                        } else {
                            packet = mMessageGenerator.generateChat(message);
                        }
                        break;
                    case Message.ENCRYPTION_PGP:
                    case Message.ENCRYPTION_DECRYPTED:
                        if (message.needsUploading()) {
                            if (account.httpUploadAvailable(
                                            fileBackend.getFile(message, false).getSize())
                                    || conversation.getMode() == Conversation.MODE_MULTI
                                    || message.fixCounterpart()) {
                                this.sendFileMessage(message, delay, cb, forceP2P);
                                passedCbOn = true;
                            } else {
                                break;
                            }
                        } else {
                            packet = mMessageGenerator.generatePgpChat(message);
                        }
                        break;
                    case Message.ENCRYPTION_OTR:
                        if (!Config.supportOtr()) {
                            // OTR is switched off: never start a session, never send.
                            if (resend) {
                                markMessage(message, Message.STATUS_SEND_FAILED);
                            } else {
                                message.setStatus(Message.STATUS_SEND_FAILED);
                            }
                            break;
                        }
                        SessionImpl otrSession = conversation.getOtrSession();
                        if (otrSession != null
                                && otrSession.getSessionStatus() == SessionStatus.ENCRYPTED) {
                            try {
                                message.setCounterpart(
                                        OtrJidHelper.fromSessionID(otrSession.getSessionID()));
                            } catch (IllegalArgumentException e) {
                                break;
                            }
                            if (message.needsUploading()) {
                                mJingleConnectionManager.startJingleFileTransfer(message);
                            } else {
                                packet = mMessageGenerator.generateOtrChat(message);
                            }
                        } else if (otrSession == null) {
                            if (message.fixCounterpart()) {
                                conversation.startOtrSession(
                                        message.getCounterpart().getResource(), true);
                            } else {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": could not fix counterpart for OTR message to"
                                                + " contact "
                                                + message.getCounterpart());
                                break;
                            }
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + " OTR session with "
                                            + message.getContact()
                                            + " is in wrong state: "
                                            + otrSession.getSessionStatus().toString());
                        }
                        break;
                    case Message.ENCRYPTION_AXOLOTL:
                        // The legacy stack has its own identity key; getOwnFingerprint() is the
                        // OMEMO2 one, which has no trust row under our JID (lock instead of
                        // shield).
                        message.setFingerprint(
                                account.getAxolotlService().getOwnLegacyFingerprint());
                        if (message.needsUploading()) {
                            if (account.httpUploadAvailable(
                                            fileBackend.getFile(message, false).getSize())
                                    || conversation.getMode() == Conversation.MODE_MULTI
                                    || message.fixCounterpart()) {
                                this.sendFileMessage(message, delay, cb, forceP2P);
                                passedCbOn = true;
                            } else {
                                break;
                            }
                        } else {
                            XmppAxolotlMessage axolotlMessage =
                                    account.getAxolotlService()
                                            .fetchAxolotlMessageFromCache(message);
                            if (axolotlMessage == null) {
                                account.getAxolotlService().preparePayloadMessage(message, delay);
                            } else {
                                packet =
                                        mMessageGenerator.generateAxolotlChat(
                                                message, axolotlMessage);
                            }
                        }
                        break;
                    case Message.ENCRYPTION_AXOLOTL_OMEMO2:
                        message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                        if (message.needsUploading()) {
                            if (account.httpUploadAvailable(
                                            fileBackend.getFile(message, false).getSize())
                                    || conversation.getMode() == Conversation.MODE_MULTI
                                    || message.fixCounterpart()) {
                                this.sendFileMessage(message, delay, cb, forceP2P);
                                passedCbOn = true;
                            } else {
                                break;
                            }
                        } else {
                            eu.siacs.conversations.crypto.axolotl.XmppOmemo2Message omemo2Message =
                                    account.getAxolotlService()
                                            .fetchOmemo2MessageFromCache(message);
                            if (omemo2Message == null) {
                                account.getAxolotlService()
                                        .prepareOmemo2PayloadMessage(message, delay);
                            } else {
                                packet =
                                        mMessageGenerator.generateOmemo2Chat(
                                                message, omemo2Message);
                            }
                        }
                        break;
                }
            }
            if (packet != null) {
                if (account.getXmppConnection().getFeatures().sm()
                        || (conversation.getMode() == Conversation.MODE_MULTI
                                && message.getCounterpart().isBareJid())) {
                    message.setStatus(Message.STATUS_UNSEND);
                } else {
                    message.setStatus(Message.STATUS_SEND);
                }
            }
        } else {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_DECRYPTED:
                    if (!message.needsUploading()) {
                        String pgpBody = message.getEncryptedBody();
                        String decryptedBody = message.getBody();
                        message.setBody(pgpBody); // TODO might throw NPE
                        message.setEncryption(Message.ENCRYPTION_PGP);
                        if (message.edited()) {
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                            if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                                Log.e(Config.LOGTAG, "error updated message in DB after edit");
                            }
                            updateConversationUi();
                            if (!waitForPreview && cb != null) cb.run();
                            return;
                        } else {
                            databaseBackend.createMessage(message);
                            saveInDb = false;
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                        }
                    }
                    break;
                case Message.ENCRYPTION_OTR:
                    if (Config.supportOtr()
                            && !conversation.hasValidOtrSession()
                            && message.getCounterpart() != null) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": create otr session without starting for "
                                        + message.getContact().getJid());
                        conversation.startOtrSession(message.getCounterpart().getResource(), false);
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnLegacyFingerprint());
                    break;
                case Message.ENCRYPTION_AXOLOTL_OMEMO2:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    break;
            }
        }

        synchronized (mScheduledMessages) {
            if (message.getTimeSent() > System.currentTimeMillis()) {
                mScheduledMessages.put(message.getUuid(), message);
                scheduleNextIdlePing();
            } else {
                mScheduledMessages.remove(message.getUuid());
            }
        }

        boolean mucMessage =
                conversation.getMode() == Conversation.MODE_MULTI && !message.isPrivateMessage();
        if (mucMessage) {
            message.setCounterpart(conversation.getMucOptions().getSelf().getFullJid());
        }

        if (resend) {
            if (isAttachment) {
                // No stanza of its own, so persist the row here — this is where the URL
                // handed back by the upload lands.
                databaseBackend.updateMessage(message, false);
            }
            if (packet != null && addToConversation) {
                if (account.getXmppConnection().getFeatures().sm() || mucMessage) {
                    markMessage(message, Message.STATUS_UNSEND);
                } else {
                    markMessage(message, Message.STATUS_SEND);
                }
            }
        } else {
            if (addToConversation) {
                conversation.add(message);
            }
            if (saveInDb) {
                databaseBackend.createMessage(message);
                if (message.isEphemeral()) {
                    scheduleNextExpiry();
                }
            } else if (message.edited()) {
                if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit");
                }
                if (message.isEphemeral()) {
                    scheduleNextExpiry();
                }
            }
            updateConversationUi();
        }
        if (isAttachment && !message.needsUploading()) {
            releaseParentIfReady(conversation, message, delay);
        }
        if (packet != null) {
            if (delay) {
                mMessageGenerator.addDelay(packet, message.getTimeSent());
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                // Do not piggy-back the chat state on OMEMO2 messages: it would
                // sit on the outer stanza, outside the SCE envelope. The standalone
                // sendChatState() path encrypts it instead.
                if (this.sendChatStates()
                        && message.getEncryption() != Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
                }
            }
            sendMessagePacket(account, packet);
            if (message.getConversation().getMode() == Conversation.MODE_MULTI
                    && message.hasCustomEmoji()) {
                if (message.getConversation() instanceof Conversation)
                    presenceToMuc((Conversation) message.getConversation());
            }
        }
        if (!waitForPreview && !passedCbOn && cb != null) cb.run();
    }

    private boolean isJoinInProgress(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                final boolean inProgress = account.inProgressConferenceJoins.contains(conversation);
                final boolean pending = account.pendingConferenceJoins.contains(conversation);
                final boolean inProgressJoin = inProgress || pending;
                if (inProgressJoin) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": holding back message to group. inProgress="
                                    + inProgress
                                    + ", pending="
                                    + pending);
                }
                return inProgressJoin;
            } else {
                return false;
            }
        }
    }

    private void sendUnsentMessages(final Conversation conversation) {
        synchronized (conversation) {
            conversation.findWaitingMessages(message -> {
                // The extra files of a multi-file message are not in the message list,
                // so they would never be picked up here. Resume their uploads first:
                // the message itself cannot go out until all of them have a URL.
                for (final Message attachment : message.getAttachments()) {
                    if (attachment.needsUploading()) {
                        resendMessage(attachment, true);
                    }
                }
                resendMessage(message, true);
            });
        }
    }

    /**
     * Called when one of the extra files of a multi-file message has finished uploading. Once the
     * last one lands — and the message's own file is up as well — the single stanza describing all
     * of them is sent.
     */
    private void releaseParentIfReady(
            final Conversation conversation, final Message attachment, final boolean delay) {
        final Message parent = conversation.findMessageWithUuid(attachment.getParentUuid());
        if (parent == null) {
            Log.w(
                    Config.LOGTAG,
                    "uploaded a file for "
                            + attachment.getParentUuid()
                            + " but that message is gone");
            return;
        }
        if (parent.needsUploading() || parent.hasPendingAttachments()) {
            return;
        }
        resendMessage(parent, delay);
    }

    public void resendMessage(final Message message, final boolean delay) {
        sendMessage(message, true, false, delay, null, false);
    }

    public void resendMessage(final Message message, final boolean delay, final Runnable cb) {
        sendMessage(message, true, false, delay, cb, false);
    }

    public void resendMessage(
            final Message message, final boolean delay, final boolean previewedLinks) {
        sendMessage(message, true, previewedLinks, delay, null, false);
    }

    public Pair<Account, Account> onboardingIncomplete() {
        if (getAccounts().size() != 2) return null;
        Account onboarding = null;
        Account newAccount = null;
        for (final Account account : getAccounts()) {
            if (account.getJid().getDomain().equals(Config.ONBOARDING_DOMAIN)) {
                onboarding = account;
            } else {
                newAccount = account;
            }
        }

        if (onboarding != null && newAccount != null) {
            return new Pair<>(onboarding, newAccount);
        }

        return null;
    }

    public boolean isOnboarding() {
        final List<Account> accounts = getAccounts();
        return accounts != null
                && accounts.size() == 1
                && accounts.get(0).getJid().getDomain().equals(Config.ONBOARDING_DOMAIN);
    }

    public void requestEasyOnboardingInvite(
            final Account account, final EasyOnboardingInvite.OnInviteRequested callback) {
        final XmppConnection connection = account.getXmppConnection();
        final Jid jid =
                connection == null
                        ? null
                        : connection.getJidForCommand(Namespace.EASY_ONBOARDING_INVITE);
        if (jid == null) {
            callback.inviteRequestFailed(
                    getString(R.string.server_does_not_support_easy_onboarding_invites));
            return;
        }
        final Iq request = new Iq(Iq.Type.SET);
        request.setTo(jid);
        final Element command = request.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", Namespace.EASY_ONBOARDING_INVITE);
        command.setAttribute("action", "execute");
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element resultCommand =
                                response.findChild("command", Namespace.COMMANDS);
                        final Element x =
                                resultCommand == null
                                        ? null
                                        : resultCommand.findChild("x", Namespace.DATA);
                        if (x != null) {
                            final Data data = Data.parse(x);
                            final String uri = data.getValue("uri");
                            final String landingUrl = data.getValue("landing-url");
                            if (uri != null) {
                                final EasyOnboardingInvite invite =
                                        new EasyOnboardingInvite(
                                                jid.getDomain().toString(), uri, landingUrl);
                                callback.inviteRequested(invite);
                                return;
                            }
                        }
                        callback.inviteRequestFailed(getString(R.string.unable_to_parse_invite));
                        Log.d(Config.LOGTAG, response.toString());
                    } else if (response.getType() == Iq.Type.ERROR) {
                        callback.inviteRequestFailed(IqParser.errorMessage(response));
                    } else {
                        callback.inviteRequestFailed(getString(R.string.remote_server_timeout));
                    }
                });
    }

    public void fetchBookmarks(final Account account) {
        final Iq iqPacket = new Iq(Iq.Type.GET);
        final Element query = iqPacket.query("jabber:iq:private");
        query.addChild("storage", Namespace.BOOKMARKS);
        final Consumer<Iq> callback =
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element query1 = response.query();
                        final Element storage = query1.findChild("storage", "storage:bookmarks");
                        Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                        processBookmarksInitial(account, bookmarks, false);
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": could not fetch bookmarks");
                    }
                };
        sendIqPacket(account, iqPacket, callback);
    }

    public void fetchBookmarks2(final Account account) {
        final Iq retrieve = mIqGenerator.retrieveBookmarks();
        sendIqPacket(
                account,
                retrieve,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                        final Map<Jid, Bookmark> bookmarks =
                                Bookmark.parseFromPubSub(pubsub, account);
                        processBookmarksInitial(account, bookmarks, true);
                    }
                });
    }

    public void fetchMessageDisplayedSynchronization(final Account account) {
        Log.d(Config.LOGTAG, account.getJid() + ": retrieve mds");
        final var retrieve = mIqGenerator.retrieveMds();
        sendIqPacket(
                account,
                retrieve,
                response -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        return;
                    }
                    final var pubSub = response.findChild("pubsub", Namespace.PUBSUB);
                    final Element items = pubSub == null ? null : pubSub.findChild("items");
                    if (items == null
                            || !Namespace.MDS_DISPLAYED.equals(items.getAttribute("node"))) {
                        return;
                    }
                    for (final Element child : items.getChildren()) {
                        if ("item".equals(child.getName())) {
                            processMdsItem(account, child);
                        }
                    }
                });
    }

    public void processMdsItem(final Account account, final Element item) {
        final Jid jid =
                item == null ? null : Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("id"));
        if (jid == null) {
            return;
        }
        final Element displayed = item.findChild("displayed", Namespace.MDS_DISPLAYED);
        final Element stanzaId =
                displayed == null ? null : displayed.findChild("stanza-id", Namespace.STANZA_IDS);
        final String id = stanzaId == null ? null : stanzaId.getAttribute("id");
        final Conversation conversation = find(account, jid);
        if (id != null && conversation != null) {
            conversation.setDisplayState(id);
            markReadUpToStanzaId(conversation, id);
        }
    }

    public void markReadUpToStanzaId(final Conversation conversation, final String stanzaId) {
        final Message message = conversation.findMessageWithServerMsgId(stanzaId);
        if (message == null) { // do we want to check if isRead?
            return;
        }
        markReadUpTo(conversation, message);
    }

    public void markReadUpTo(final Conversation conversation, final Message message) {
        final boolean isDismissNotification = isDismissNotification(message);
        final var uuid = message.getUuid();
        Log.d(
                Config.LOGTAG,
                conversation.getAccount().getJid().asBareJid()
                        + ": mark "
                        + conversation.getJid().asBareJid()
                        + " as read up to "
                        + uuid);
        markRead(conversation, uuid, isDismissNotification);
    }

    private static boolean isDismissNotification(final Message message) {
        Message next = message.next();
        while (next != null) {
            if (message.getStatus() == Message.STATUS_RECEIVED) {
                return false;
            }
            next = next.next();
        }
        return true;
    }

    public void processBookmarksInitial(
            final Account account, final Map<Jid, Bookmark> bookmarks, final boolean pep) {
        final Set<Jid> previousBookmarks = account.getBookmarkedJids();
        for (final Bookmark bookmark : bookmarks.values()) {
            previousBookmarks.remove(bookmark.getJid().asBareJid());
            processModifiedBookmark(bookmark, pep);
        }
        if (pep) {
            processDeletedBookmarks(account, previousBookmarks);
        }
        account.setBookmarks(bookmarks);
    }

    public void processDeletedBookmarks(final Account account, final Collection<Jid> bookmarks) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": "
                        + bookmarks.size()
                        + " bookmarks have been removed");
        for (final Jid bookmark : bookmarks) {
            processDeletedBookmark(account, bookmark);
        }
    }

    public void processDeletedBookmark(final Account account, final Jid jid) {
        final Conversation conversation = find(account, jid);
        if (conversation != null
                && conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": archiving destroyed conference ("
                            + conversation.getJid()
                            + ") after receiving pep");
            archiveConversation(conversation, false);
        }
    }

    private void processModifiedBookmark(final Bookmark bookmark, final boolean pep) {
        final Account account = bookmark.getAccount();
        Conversation conversation = find(bookmark);
        if (conversation != null) {
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return;
            }
            bookmark.setConversation(conversation);
            if (pep && !bookmark.autojoin()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": archiving conference ("
                                + conversation.getJid()
                                + ") after receiving pep");
                archiveConversation(conversation, false);
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.getError() == MucOptions.Error.NICK_IN_USE) {
                    final String current = mucOptions.getActualNick();
                    final String proposed = mucOptions.getProposedNickPure();
                    if (current != null && !current.equals(proposed)) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": proposed nick changed after bookmark push "
                                        + current
                                        + "->"
                                        + proposed);
                        joinMuc(conversation);
                    }
                } else {
                    checkMucRequiresRename(conversation);
                }
            }
        } else if (bookmark.autojoin()) {
            conversation =
                    findOrCreateConversation(account, bookmark.getFullJid(), true, true, false);
            bookmark.setConversation(conversation);
        }
    }

    public void processModifiedBookmark(final Bookmark bookmark) {
        processModifiedBookmark(bookmark, true);
    }

    public void ensureBookmarkIsAutoJoin(final Conversation conversation) {
        final var account = conversation.getAccount();
        final var existingBookmark = conversation.getBookmark();
        if (existingBookmark == null) {
            final var bookmark = new Bookmark(account, conversation.getJid().asBareJid());
            bookmark.setAutojoin(true);
            createBookmark(account, bookmark);
        } else {
            if (existingBookmark.autojoin()) {
                return;
            }
            existingBookmark.setAutojoin(true);
            createBookmark(account, existingBookmark);
        }
    }

    public void createBookmark(final Account account, final Bookmark bookmark) {
        account.putBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": no connection. ignoring bookmark creation");
        } else if (connection.getFeatures().bookmarks2()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": pushing bookmark via Bookmarks 2");
            final Element item = mIqGenerator.publishBookmarkItem(bookmark);
            pushNodeAndEnforcePublishOptions(
                    account,
                    Namespace.BOOKMARKS2,
                    item,
                    bookmark.getJid().asBareJid().toString(),
                    PublishOptions.persistentWhitelistAccessMaxItems());
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    public void deleteBookmark(final Account account, final Bookmark bookmark) {
        if (bookmark.getJid().toString().equals("support@conference.monocles.eu")) {
            getPreferences().edit().putBoolean("monocles_support_bookmark_deleted", true).apply();
        }
        account.removeBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) return;

        if (connection.getFeatures().bookmarks2()) {
            final Iq request =
                    mIqGenerator.deleteItem(
                            Namespace.BOOKMARKS2, bookmark.getJid().asBareJid().toString());
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": removing bookmark via Bookmarks 2");
            sendIqPacket(
                    account,
                    request,
                    response -> {
                        if (response.getType() == Iq.Type.ERROR) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": unable to delete bookmark "
                                            + response.getErrorCondition());
                        }
                    });
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    private void pushBookmarksPrivateXml(Account account) {
        if (!account.areBookmarksLoaded()) return;

        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via private xml");
        final Iq iqPacket = new Iq(Iq.Type.SET);
        Element query = iqPacket.query("jabber:iq:private");
        Element storage = query.addChild("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        sendIqPacket(account, iqPacket, mDefaultIqHandler);
    }

    private void pushBookmarksPep(Account account) {
        if (!account.areBookmarksLoaded()) return;

        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via pep");
        final Element storage = new Element("storage", "storage:bookmarks");
        for (final Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        pushNodeAndEnforcePublishOptions(
                account,
                Namespace.BOOKMARKS,
                storage,
                "current",
                PublishOptions.persistentWhitelistAccess());
    }

    private void pushNodeAndEnforcePublishOptions(
            final Account account,
            final String node,
            final Element element,
            final String id,
            final Bundle options) {
        pushNodeAndEnforcePublishOptions(account, node, element, id, options, true);
    }

    private void pushNodeAndEnforcePublishOptions(
            final Account account,
            final String node,
            final Element element,
            final String id,
            final Bundle options,
            final boolean retry) {
        final Iq packet = mIqGenerator.publishElement(node, element, id, options);
        sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        return;
                    }
                    if (retry && PublishOptions.preconditionNotMet(response)) {
                        pushNodeConfiguration(
                                account,
                                node,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        pushNodeAndEnforcePublishOptions(
                                                account, node, element, id, options, false);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to push node configuration ("
                                                        + node
                                                        + ")");
                                    }
                                });
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": error publishing "
                                        + node
                                        + " (retry="
                                        + retry
                                        + ") "
                                        + response);
                    }
                });
    }

    private void restoreFromDatabase() {
        synchronized (this.conversations) {
            final Map<String, Account> accountLookupTable =
                    ImmutableMap.copyOf(Maps.uniqueIndex(this.accounts, Account::getUuid));
            Log.d(Config.LOGTAG, "restoring conversations...");
            final long startTimeConversationsRestore = SystemClock.elapsedRealtime();
            this.conversations.addAll(
                    databaseBackend.getConversations(Conversation.STATUS_AVAILABLE));
            for (Iterator<Conversation> iterator = conversations.listIterator();
                    iterator.hasNext(); ) {
                Conversation conversation = iterator.next();
                Account account = accountLookupTable.get(conversation.getAccountUuid());
                if (account != null) {
                    conversation.setAccount(account);
                } else {
                    Log.e(
                            Config.LOGTAG,
                            "unable to restore Conversations with " + conversation.getJid());
                    conversations.remove(conversation);
                }
            }
            long diffConversationsRestore =
                    SystemClock.elapsedRealtime() - startTimeConversationsRestore;
            Log.d(
                    Config.LOGTAG,
                    "finished restoring conversations in " + diffConversationsRestore + "ms");
            Runnable runnable =
                    () -> {
                        // Only after a schema change. Rebuilding whenever the index had a few
                        // segments
                        // re-tokenized the whole message history on almost every cold start; FTS4
                        // automerge (see DatabaseBackend.enableFtsAutomerge) keeps it compact
                        // instead.
                        if (DatabaseBackend.requiresMessageIndexRebuild()) {
                            databaseBackend.rebuildMessagesIndex();
                        }
                        mutedMucUsers = databaseBackend.loadMutedMucUsers();
                        final long deletionDate = getAutomaticMessageDeletionDate();
                        mLastExpiryRun.set(SystemClock.elapsedRealtime());
                        if (deletionDate > 0) {
                            Log.d(
                                    Config.LOGTAG,
                                    "deleting messages that are older than "
                                            + AbstractGenerator.getTimestamp(deletionDate));
                            noteMessagesWiped(databaseBackend.expireOldMessages(deletionDate));
                        }
                        Log.d(Config.LOGTAG, "restoring roster...");
                        for (final Account account : accounts) {
                            databaseBackend.readRoster(account.getRoster());
                            account.initAccountServices(
                                    XmppConnectionService
                                            .this); // roster needs to be loaded at this stage
                        }
                        getDrawableCache().evictAll();
                        loadPhoneContacts();
                        Log.d(Config.LOGTAG, "restoring messages...");
                        final long startMessageRestore = SystemClock.elapsedRealtime();
                        final Conversation quickLoad = QuickLoader.get(this.conversations);
                        if (quickLoad != null) {
                            restoreMessages(quickLoad);
                            updateConversationUi();
                            final long diffMessageRestore =
                                    SystemClock.elapsedRealtime() - startMessageRestore;
                            Log.d(
                                    Config.LOGTAG,
                                    "quickly restored "
                                            + quickLoad.getName()
                                            + " after "
                                            + diffMessageRestore
                                            + "ms");
                        }
                        for (Conversation conversation : this.conversations) {
                            if (quickLoad != conversation) {
                                restoreMessages(conversation);
                            }
                        }
                        mNotificationService.finishBacklog();
                        restoredFromDatabaseLatch.countDown();
                        final long diffMessageRestore =
                                SystemClock.elapsedRealtime() - startMessageRestore;
                        Log.d(
                                Config.LOGTAG,
                                "finished restoring messages in " + diffMessageRestore + "ms");
                        updateConversationUi();
                        // A reboot or an app update clears the expiry alarm: wipe what expired
                        // while the app was not running and set the alarm again (this also
                        // brings a pending search-index rewrite).
                        expireOldMessages();
                        purgeImageDiskCacheOnce();
                        Log.d(Config.LOGTAG, "restoring stories...");
                        final List<eu.siacs.conversations.entities.Story> persistedStories =
                                databaseBackend.getStoriesFromDatabase();
                        // Runs concurrently with onStoryReceived on the connection threads; the
                        // check-then-add
                        // must be atomic or the same story ends up in the list twice.
                        synchronized (stories) {
                            for (final eu.siacs.conversations.entities.Story story :
                                    persistedStories) {
                                if (story.getUuid() == null
                                        || story.getContact() == null
                                        || retractedStoryIds.containsKey(
                                                storyKey(story.getContact(), story.getUuid()))) {
                                    continue;
                                }
                                if (indexOfStory(story.getUuid()) < 0) {
                                    stories.add(story);
                                }
                            }
                            java.util.Collections.sort(
                                    stories,
                                    (a, b) -> Long.compare(b.getPublished(), a.getPublished()));
                        }
                        Log.d(
                                Config.LOGTAG,
                                "restored " + persistedStories.size() + " stories from database");
                        updateStoriesUi();
                    };
            mDatabaseReaderExecutor.execute(
                    runnable); // will contain one write command (expiry) but that's fine
        }
    }

    private void restoreMessages(Conversation conversation) {
        conversation.addAll(0, databaseBackend.getMessages(conversation, Config.PAGE_SIZE), false);
        conversation.findUnsentTextMessages(
                message -> markMessage(message, Message.STATUS_WAITING));
        conversation.findMessagesAndCallsToNotify(mNotificationService::pushFromBacklog);
    }

    public void loadPhoneContacts() {
        mContactMergerExecutor.execute(() -> {
            final Map<Jid, JabberIdContact> contacts = JabberIdContact.load(this);
            Log.d(Config.LOGTAG, "start merging phone contacts with roster");
            for (final Account account : accounts) {
                final List<Contact> withSystemAccounts =
                        account.getRoster().getWithSystemAccounts(JabberIdContact.class);
                for (final JabberIdContact jidContact : contacts.values()) {
                    final Contact contact = account.getRoster().getContact(jidContact.getJid());
                    boolean needsCacheClean = contact.setPhoneContact(jidContact);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                    withSystemAccounts.remove(contact);
                }
                for (final Contact contact : withSystemAccounts) {
                    boolean needsCacheClean = contact.unsetPhoneContact(JabberIdContact.class);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                }
            }
            Log.d(Config.LOGTAG, "finished merging phone contacts");
            mShortcutService.refresh(mInitialAddressbookSyncCompleted.compareAndSet(false, true));
            updateRosterUi(UpdateRosterReason.PUSH);
            mQuickConversationsService.considerSync();
        });
    }

    public void syncRoster(final Account account) {
        mRosterSyncTaskManager.execute(
                account,
                () -> {
                    unregisterPhoneAccounts(account);
                    databaseBackend.writeRoster(account.getRoster());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                    }
                });
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    private void markFileDeleted(final File file) {
        synchronized (FILENAMES_TO_IGNORE_DELETION) {
            if (FILENAMES_TO_IGNORE_DELETION.remove(file.getAbsolutePath())) {
                Log.d(Config.LOGTAG, "ignored deletion of " + file.getAbsolutePath());
                return;
            }
        }
        final boolean isInternalFile = fileBackend.isInternalFile(file);
        final List<String> uuids = databaseBackend.markFileAsDeleted(file, isInternalFile);
        Log.d(
                Config.LOGTAG,
                "deleted file "
                        + file.getAbsolutePath()
                        + " internal="
                        + isInternalFile
                        + ", database hits="
                        + uuids.size());
        markUuidsAsDeletedFiles(uuids);
    }

    private void markUuidsAsDeletedFiles(List<String> uuids) {
        boolean deleted = false;
        for (Conversation conversation : getConversations()) {
            deleted |= conversation.markAsDeleted(uuids);
        }
        for (final String uuid : uuids) {
            evictPreview(uuid);
        }
        if (deleted) {
            updateConversationUi();
        }
    }

    private void markChangedFiles(List<DatabaseBackend.FilePathInfo> infos) {
        boolean changed = false;
        for (Conversation conversation : getConversations()) {
            changed |= conversation.markAsChanged(infos);
        }
        if (changed) {
            updateConversationUi();
        }
    }

    public void populateWithOrderedConversations(final List<Conversation> list) {
        populateWithOrderedConversations(list, true, true);
    }

    public void populateWithOrderedConversations(
            final List<Conversation> list, final boolean includeNoFileUpload) {
        populateWithOrderedConversations(list, includeNoFileUpload, true);
    }

    public void populateWithOrderedConversations(
            final List<Conversation> list, final boolean includeNoFileUpload, final boolean sort) {
        final List<String> orderedUuids;
        if (sort) {
            orderedUuids = null;
        } else {
            orderedUuids = new ArrayList<>();
            for (Conversation conversation : list) {
                orderedUuids.add(conversation.getUuid());
            }
        }
        list.clear();
        if (includeNoFileUpload) {
            list.addAll(getConversations());
        } else {
            for (Conversation conversation : getConversations()) {
                if (conversation.getMode() == Conversation.MODE_SINGLE
                        || (conversation.getAccount().httpUploadAvailable()
                                && conversation.getMucOptions().participating())) {
                    list.add(conversation);
                }
            }
        }
        try {
            if (orderedUuids != null) {
                Collections.sort(
                        list,
                        (a, b) -> {
                            final int indexA = orderedUuids.indexOf(a.getUuid());
                            final int indexB = orderedUuids.indexOf(b.getUuid());
                            if (indexA == -1 || indexB == -1 || indexA == indexB) {
                                return a.compareTo(b);
                            }
                            return indexA - indexB;
                        });
            } else {
                Collections.sort(list);
            }
        } catch (IllegalArgumentException e) {
            // ignore
        }
    }

    public void jumpToMessage(
            final Conversation conversation, final String uuid, JumpToMessageListener listener) {
        final Runnable runnable =
                () -> {
                    List<Message> messages =
                            databaseBackend.getMessagesNearUuid(conversation, 30, uuid);
                    if (messages != null && !messages.isEmpty()) {
                        conversation.jumpToHistoryPart(messages);
                        listener.onSuccess();
                    } else {
                        listener.onNotFound();
                    }
                };

        mDatabaseReaderExecutor.execute(runnable);
    }

    public void loadMoreMessages(
            final Conversation conversation,
            final long timestamp,
            boolean isForward,
            final OnMoreMessagesLoaded callback) {
        if (XmppConnectionService.this
                .getMessageArchiveService()
                .queryInProgress(conversation, callback)) {
            return;
        } else if (timestamp == 0) {
            return;
        }
        Log.d(
                Config.LOGTAG,
                "load more messages for "
                        + conversation.getName()
                        + " prior to "
                        + MessageGenerator.getTimestamp(timestamp));

        if (isForward) {
            Log.d(
                    Config.LOGTAG,
                    "load more messages for "
                            + conversation.getName()
                            + " after "
                            + MessageGenerator.getTimestamp(timestamp));
        } else {
            Log.d(
                    Config.LOGTAG,
                    "load more messages for "
                            + conversation.getName()
                            + " prior to "
                            + MessageGenerator.getTimestamp(timestamp));
        }

        final Runnable runnable =
                () -> {
                    final Account account = conversation.getAccount();
                    List<Message> messages =
                            databaseBackend.getMessages(
                                    conversation, Config.PAGE_SIZE, timestamp, isForward);
                    if (messages.size() > 0) {
                        if (isForward) {
                            conversation.addAll(-1, messages, true);
                        } else {
                            conversation.addAll(0, messages, true);
                        }
                        callback.onMoreMessagesLoaded(messages.size(), conversation);
                    } else if (!isForward
                            && conversation.hasMessagesLeftOnServer()
                            && account.isOnlineAndConnected()
                            && conversation.getLastClearHistory().getTimestamp() == 0) {
                        final boolean mamAvailable;
                        if (conversation.getMode() == Conversation.MODE_SINGLE) {
                            mamAvailable =
                                    account.getXmppConnection().getFeatures().mam()
                                            && !conversation.getContact().isBlocked();
                        } else {
                            mamAvailable = conversation.getMucOptions().mamSupport();
                        }
                        if (mamAvailable) {
                            MessageArchiveService.Query query =
                                    getMessageArchiveService()
                                            .query(
                                                    conversation,
                                                    new MamReference(0),
                                                    timestamp,
                                                    false);
                            if (query != null) {
                                query.setCallback(callback);
                                callback.informUser(R.string.fetching_history_from_server);
                            } else {
                                callback.informUser(R.string.not_fetching_history_retention_period);
                            }
                        }
                    }
                };
        mDatabaseReaderExecutor.execute(runnable);
    }

    public List<Account> getAccounts() {
        if (this.accounts == null) {
            return new ArrayList<>();
        }
        return this.accounts;
    }

    /**
     * This will find all conferences with the contact as member and also the conference that is the
     * contact (that 'fake' contact is used to store the avatar)
     */
    public List<Conversation> findAllConferencesWith(Contact contact) {
        final ArrayList<Conversation> results = new ArrayList<>();
        for (final Conversation c : conversations) {
            if (c.getMode() != Conversation.MODE_MULTI) {
                continue;
            }
            final MucOptions mucOptions = c.getMucOptions();
            if (c.getJid().asBareJid().equals(contact.getJid().asBareJid())
                    || (mucOptions != null && mucOptions.isContactInRoom(contact))) {
                results.add(c);
            }
        }
        return results;
    }

    public Conversation find(final Contact contact) {
        for (final Conversation conversation : this.conversations) {
            if (conversation.getContact() == contact) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation find(
            final Iterable<Conversation> haystack, final Account account, final Jid jid) {
        if (jid == null || haystack == null) {
            return null;
        }
        for (final Conversation conversation : haystack) {
            if ((account == null || conversation.getAccount() == account)
                    && (conversation.getJid().asBareJid().equals(jid.asBareJid()))) {
                return conversation;
            }
        }
        return null;
    }

    private Conversation find(
            final Iterable<Conversation> haystack,
            final Account account,
            final Jid jid,
            final Jid counterpart) {
        if (jid == null) {
            return null;
        }

        if (counterpart != null) {
            for (final Conversation conversation : haystack) {
                if ((account == null || conversation.getAccount() == account)
                        && (conversation.getJid().asBareJid().equals(jid.asBareJid()))
                        && Objects.equal(conversation.getNextCounterpart(), counterpart)) {
                    return conversation;
                }
            }
        } else {
            for (final Conversation conversation : haystack) {
                if ((account == null || conversation.getAccount() == account)
                        && (conversation.getJid().asBareJid().equals(jid.asBareJid()))
                        && conversation.getNextCounterpart() == null) {
                    return conversation;
                }
            }
        }
        return null;
    }

    public boolean isConversationsListEmpty(final Conversation ignore) {
        synchronized (this.conversations) {
            final int size = this.conversations.size();
            return size == 0 || size == 1 && this.conversations.get(0) == ignore;
        }
    }

    public boolean isConversationStillOpen(final Conversation conversation) {
        synchronized (this.conversations) {
            for (Conversation current : this.conversations) {
                if (current == conversation) {
                    return true;
                }
            }
        }
        return false;
    }

    public void maybeRegisterWithMuc(Conversation c, String nickArg) {
        final var nick =
                nickArg == null ? c.getMucOptions().getSelf().getFullJid().getResource() : nickArg;
        final var register = new Iq(Iq.Type.GET);
        register.query(Namespace.REGISTER);
        register.setTo(c.getJid().asBareJid());
        sendIqPacket(
                c.getAccount(),
                register,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element query = response.query(Namespace.REGISTER);
                        String username = query.findChildContent("username", Namespace.REGISTER);
                        if (username == null)
                            username = query.findChildContent("nick", Namespace.REGISTER);
                        if (username != null && username.equals(nick)) {
                            // Already registered with this nick, done
                            Log.d(
                                    Config.LOGTAG,
                                    "Already registered with "
                                            + c.getJid().asBareJid()
                                            + " as "
                                            + username);
                            return;
                        }
                        Data form = Data.parse(query.findChild("x", Namespace.DATA));
                        if (form != null) {
                            final var field = form.getFieldByName("muc#register_roomnick");
                            if (field != null && nick.equals(field.getValue())) {
                                Log.d(
                                        Config.LOGTAG,
                                        "Already registered with "
                                                + c.getJid().asBareJid()
                                                + " as "
                                                + field.getValue());
                                return;
                            }
                        }
                        if (form == null
                                || !"form".equals(form.getFormType())
                                || !form.getFields().stream()
                                        .anyMatch(f ->
                                                f.isRequired()
                                                        && !"muc#register_roomnick"
                                                                .equals(f.getFieldName()))) {
                            // No form, result form, or no required fields other than nickname,
                            // let's just send nickname
                            if (form == null || !"form".equals(form.getFormType())) {
                                form = new Data();
                                form.put("FORM_TYPE", "http://jabber.org/protocol/muc#register");
                            }
                            form.put("muc#register_roomnick", nick);
                            form.submit();
                            final var finish = new Iq(Iq.Type.SET);
                            finish.query(Namespace.REGISTER).addChild(form);
                            finish.setTo(c.getJid().asBareJid());
                            sendIqPacket(
                                    c.getAccount(),
                                    finish,
                                    response2 -> {
                                        if (response.getType() == Iq.Type.RESULT) {
                                            Log.w(
                                                    Config.LOGTAG,
                                                    "Success registering with channel "
                                                            + c.getJid().asBareJid()
                                                            + "/"
                                                            + nick);
                                        } else {
                                            Log.w(
                                                    Config.LOGTAG,
                                                    "Error registering with channel: " + response2);
                                        }
                                    });
                        } else {
                            // TODO: offer registration form to user
                            Log.d(
                                    Config.LOGTAG,
                                    "Complex registration form for "
                                            + c.getJid().asBareJid()
                                            + ": "
                                            + response);
                        }
                    } else {
                        // We said maybe. Guess not
                        Log.d(
                                Config.LOGTAG,
                                "Could not register with "
                                        + c.getJid().asBareJid()
                                        + ": "
                                        + response);
                    }
                });
    }

    public void deregisterWithMuc(Conversation c) {
        final Iq register = new Iq(Iq.Type.GET);
        register.query(Namespace.REGISTER).addChild("remove");
        register.setTo(c.getJid().asBareJid());
        sendIqPacket(
                c.getAccount(),
                register,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        Log.d(Config.LOGTAG, "deregistered with " + c.getJid().asBareJid());
                    } else {
                        Log.w(
                                Config.LOGTAG,
                                "Could not deregister with "
                                        + c.getJid().asBareJid()
                                        + ": "
                                        + response);
                    }
                });
    }

    public Conversation findOrCreateConversation(
            Account account, Jid jid, boolean muc, final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, false, async);
    }

    public Conversation findOrCreateConversation(
            final Account account,
            final Jid jid,
            final boolean muc,
            final boolean joinAfterCreate,
            final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, joinAfterCreate, null, async, null);
    }

    public Conversation findOrCreateConversation(
            final Account account,
            final Jid jid,
            final boolean muc,
            final boolean joinAfterCreate,
            final MessageArchiveService.Query query,
            final boolean async) {
        return this.findOrCreateConversation(
                account, jid, muc, joinAfterCreate, query, async, null);
    }

    public Conversation findOrCreateConversation(
            final Account account,
            final Jid jid,
            final boolean muc,
            final boolean joinAfterCreate,
            final MessageArchiveService.Query query,
            final boolean async,
            final String password) {
        synchronized (this.conversations) {
            final var cached = find(account, jid);
            if (cached != null) {
                return cached;
            }
            final var existing = databaseBackend.findConversation(account, jid);
            final Conversation conversation;
            final boolean loadMessagesFromDb;
            if (existing != null) {
                conversation = existing;
                if (password != null) conversation.getMucOptions().setPassword(password);
                loadMessagesFromDb = restoreFromArchive(conversation, jid, muc);
            } else {
                String conversationName;
                final Contact contact = account.getRoster().getContact(jid);
                if (contact != null) {
                    conversationName = contact.getDisplayName();
                } else {
                    conversationName = jid.getLocal();
                }
                if (muc) {
                    conversation =
                            new Conversation(
                                    conversationName, account, jid, Conversation.MODE_MULTI);
                } else {
                    conversation =
                            new Conversation(
                                    conversationName,
                                    account,
                                    jid.asBareJid(),
                                    Conversation.MODE_SINGLE);
                }
                if (password != null) conversation.getMucOptions().setPassword(password);
                this.databaseBackend.createConversation(conversation);
                loadMessagesFromDb = false;
            }
            if (async) {
                mDatabaseReaderExecutor.execute(() ->
                        postProcessConversation(
                                conversation, loadMessagesFromDb, joinAfterCreate, query));
            } else {
                postProcessConversation(conversation, loadMessagesFromDb, joinAfterCreate, query);
            }
            this.conversations.add(conversation);
            updateConversationUi();
            return conversation;
        }
    }

    public Conversation findConversationByUuidReliable(final String uuid) {
        final var cached = findConversationByUuid(uuid);
        if (cached != null) {
            return cached;
        }
        final var existing = databaseBackend.findConversation(uuid);
        if (existing == null) {
            return null;
        }
        Log.d(Config.LOGTAG, "restoring conversation with " + existing.getJid() + " from DB");
        final Map<String, Account> accounts =
                ImmutableMap.copyOf(Maps.uniqueIndex(this.accounts, Account::getUuid));
        final var account = accounts.get(existing.getAccountUuid());
        if (account == null) {
            Log.d(Config.LOGTAG, "could not find account " + existing.getAccountUuid());
            return null;
        }
        existing.setAccount(account);
        final var loadMessagesFromDb = restoreFromArchive(existing);
        mDatabaseReaderExecutor.execute(() ->
                postProcessConversation(
                        existing,
                        loadMessagesFromDb,
                        existing.getMode() == Conversational.MODE_MULTI,
                        null));
        this.conversations.add(existing);
        if (existing.getMode() == Conversational.MODE_MULTI) {
            ensureBookmarkIsAutoJoin(existing);
        }
        updateConversationUi();
        return existing;
    }

    private boolean restoreFromArchive(
            final Conversation conversation, final Jid jid, final boolean muc) {
        // An archived 1:1 chat is not turned into a group chat on request: messages and invites
        // from the network ask for that too. It only changes mode once the address identifies
        // as a room (see fetchConferenceConfiguration).
        if (muc) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                // already a room: only the nick part of the address may have changed
                conversation.setContactJid(jid);
            }
        } else {
            conversation.setMode(Conversation.MODE_SINGLE);
            conversation.setContactJid(jid.asBareJid());
        }
        return restoreFromArchive(conversation);
    }

    private boolean restoreFromArchive(final Conversation conversation) {
        conversation.setStatus(Conversation.STATUS_AVAILABLE);
        databaseBackend.updateConversation(conversation);
        return conversation.messagesLoaded.compareAndSet(true, false);
    }

    private void postProcessConversation(
            final Conversation c,
            final boolean loadMessagesFromDb,
            final boolean joinAfterCreate,
            final MessageArchiveService.Query query) {
        final var singleMode = c.getMode() == Conversational.MODE_SINGLE;
        final var account = c.getAccount();
        if (loadMessagesFromDb) {
            c.addAll(0, databaseBackend.getMessages(c, Config.PAGE_SIZE), false);
            updateConversationUi();
            c.messagesLoaded.set(true);
        }
        if (account.getXmppConnection() != null
                && !c.getContact().isBlocked()
                && account.getXmppConnection().getFeatures().mam()
                && singleMode) {
            if (query == null) {
                mMessageArchiveService.query(c);
            } else {
                if (query.getConversation() == null) {
                    mMessageArchiveService.query(c, query.getStart(), query.isCatchup());
                }
            }
        }
        if (joinAfterCreate) {
            joinMuc(c);
        }
    }

    public void archiveConversation(Conversation conversation) {
        archiveConversation(conversation, true);
    }

    private void archiveConversation(
            Conversation conversation, final boolean maySynchronizeWithBookmarks) {
        if (isOnboarding()) return;

        getNotificationService().clear(conversation);
        conversation.setStatus(Conversation.STATUS_ARCHIVED);
        conversation.setNextMessage(null);
        synchronized (this.conversations) {
            getMessageArchiveService().kill(conversation);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (conversation.getAccount().getStatus() == Account.State.ONLINE) {
                    final Bookmark bookmark = conversation.getBookmark();
                    if (maySynchronizeWithBookmarks && bookmark != null) {
                        if (conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED) {
                            Account account = bookmark.getAccount();
                            bookmark.setConversation(null);
                            deleteBookmark(account, bookmark);
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false);
                            createBookmark(bookmark.getAccount(), bookmark);
                        }
                    }
                }
                deregisterWithMuc(conversation);
                leaveMuc(conversation);
            } else {
                if (conversation
                        .getContact()
                        .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    stopPresenceUpdatesTo(conversation.getContact());
                }
            }
            updateConversation(conversation);
            this.conversations.remove(conversation);
            updateConversationUi();
        }
    }

    public void stopPresenceUpdatesTo(Contact contact) {
        Log.d(Config.LOGTAG, "Canceling presence request from " + contact.getJid().toString());
        sendPresencePacket(contact.getAccount(), mPresenceGenerator.stopPresenceUpdatesTo(contact));
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    public void createAccount(final Account account) {
        account.initAccountServices(this);
        databaseBackend.createAccount(account);
        if (CallIntegration.hasSystemFeature(this)) {
            CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
        }
        this.accounts.add(account);
        // Activate show own account name when there is more than one account
        if (accounts.size() > 1) {
            final var editor = getPreferences().edit();
            editor.putBoolean("show_own_accounts", true).apply();
            editor.apply();
        }
        this.reconnectAccountInBackground(account);
        updateAccountUi();
        syncEnabledAccountSetting();
        toggleForegroundService();
    }

    private void syncEnabledAccountSetting() {
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        getPreferences()
                .edit()
                .putBoolean(SystemEventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts)
                .apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
    }

    private void toggleSetProfilePictureActivity(final boolean enabled) {
        try {
            final ComponentName name =
                    new ComponentName(this, ChooseAccountForProfilePictureActivity.class);
            final int targetState =
                    enabled
                            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager()
                    .setComponentEnabledSetting(name, targetState, PackageManager.DONT_KILL_APP);
        } catch (IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle profile picture activity");
        }
    }

    public boolean reconfigurePushDistributor() {
        return this.unifiedPushBroker.reconfigurePushDistributor();
    }

    private Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints(
            final UnifiedPushBroker.PushTargetMessenger pushTargetMessenger) {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(pushTargetMessenger);
    }

    public Optional<UnifiedPushBroker.Transport> renewUnifiedPushEndpoints() {
        return this.unifiedPushBroker.renewUnifiedPushEndpoints(null);
    }

    public UnifiedPushBroker getUnifiedPushBroker() {
        return this.unifiedPushBroker;
    }

    public void createAccountFromKey(final String alias, final OnAccountCreated callback) {
        new Thread(
                        () -> {
                            try {
                                final X509Certificate[] chain =
                                        KeyChain.getCertificateChain(this, alias);
                                final X509Certificate cert =
                                        chain != null && chain.length > 0 ? chain[0] : null;
                                if (cert == null) {
                                    callback.informUser(R.string.unable_to_parse_certificate);
                                    return;
                                }
                                Pair<Jid, String> info = CryptoHelper.extractJidAndName(cert);
                                if (info == null) {
                                    callback.informUser(R.string.certificate_does_not_contain_jid);
                                    return;
                                }
                                if (findAccountByJid(info.first) == null) {
                                    final Account account = new Account(info.first, "");
                                    account.setPrivateKeyAlias(alias);
                                    account.setOption(Account.OPTION_DISABLED, true);
                                    account.setOption(Account.OPTION_FIXED_USERNAME, true);
                                    account.setDisplayName(info.second);
                                    createAccount(account);
                                    callback.onAccountCreated(account);
                                    if (Config.X509_VERIFICATION) {
                                        try {
                                            getMemorizingTrustManager()
                                                    .getNonInteractive(
                                                            account.getServer(),
                                                            null,
                                                            0,
                                                            null,
                                                            false)
                                                    .checkClientTrusted(chain, "RSA");
                                        } catch (CertificateException e) {
                                            callback.informUser(
                                                    R.string.certificate_chain_is_not_trusted);
                                        }
                                    }
                                } else {
                                    callback.informUser(R.string.account_already_exists);
                                }
                            } catch (Exception e) {
                                callback.informUser(R.string.unable_to_parse_certificate);
                            }
                        })
                .start();
    }

    public void updateKeyInAccount(final Account account, final String alias) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": update key in account " + alias);
        try {
            X509Certificate[] chain =
                    KeyChain.getCertificateChain(XmppConnectionService.this, alias);
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " loaded certificate chain");
            Pair<Jid, String> info = CryptoHelper.extractJidAndName(chain[0]);
            if (info == null) {
                showErrorToastInUi(R.string.certificate_does_not_contain_jid);
                return;
            }
            if (account.getJid().asBareJid().equals(info.first)) {
                account.setPrivateKeyAlias(alias);
                account.setDisplayName(info.second);
                databaseBackend.updateAccount(account);
                if (Config.X509_VERIFICATION) {
                    try {
                        getMemorizingTrustManager()
                                .getNonInteractive()
                                .checkClientTrusted(chain, "RSA");
                    } catch (CertificateException e) {
                        showErrorToastInUi(R.string.certificate_chain_is_not_trusted);
                    }
                    account.getAxolotlService().regenerateKeys(true);
                }
            } else {
                showErrorToastInUi(R.string.jid_does_not_match_certificate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean updateAccount(final Account account) {
        if (databaseBackend.updateAccount(account)) {
            Integer color = account.getColorToSave();
            if (color == null) {
                getPreferences().edit().remove("account_color:" + account.getUuid()).commit();
            } else {
                getPreferences()
                        .edit()
                        .putInt("account_color:" + account.getUuid(), color.intValue())
                        .commit();
            }
            account.setShowErrorNotification(true);
            this.statusListener.onStatusChanged(account);
            databaseBackend.updateAccount(account);
            reconnectAccountInBackground(account);
            updateAccountUi();
            getNotificationService().updateErrorNotification();
            toggleForegroundService();
            syncEnabledAccountSetting();
            mChannelDiscoveryService.cleanCache();
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.togglePhoneAccountAsync(this, account);
            }
            return true;
        } else {
            return false;
        }
    }

    public void updateAccountPasswordOnServer(
            final Account account,
            final String newPassword,
            final OnAccountPasswordChanged callback) {
        final Iq iq = getIqGenerator().generateSetPassword(account, newPassword);
        sendIqPacket(
                account,
                iq,
                packet -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        account.setPassword(newPassword);
                        account.setOption(Account.OPTION_MAGIC_CREATE, false);
                        databaseBackend.updateAccount(account);
                        callback.onPasswordChangeSucceeded();
                    } else {
                        callback.onPasswordChangeFailed();
                    }
                });
    }

    public void unregisterAccount(final Account account, final Consumer<Boolean> callback) {
        final Iq iqPacket = new Iq(Iq.Type.SET);
        final Element query = iqPacket.addChild("query", Namespace.REGISTER);
        query.addChild("remove");
        sendIqPacket(
                account,
                iqPacket,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        deleteAccount(account);
                        callback.accept(true);
                    } else {
                        callback.accept(false);
                    }
                });
    }

    public void deleteAccount(final Account account) {
        getPreferences().edit().remove("onboarding_continued").commit();
        final boolean connected = account.getStatus() == Account.State.ONLINE;
        synchronized (this.conversations) {
            if (connected) {
                account.getAxolotlService().deleteOmemoIdentity();
            }
            for (final Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        if (connected) {
                            leaveMuc(conversation);
                        }
                    }
                    conversations.remove(conversation);
                    mNotificationService.clear(conversation);
                }
            }
            new Thread(
                            () -> {
                                for (final Contact contact : account.getRoster().getContacts()) {
                                    contact.unregisterAsPhoneAccount(this);
                                }
                            })
                    .start();
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, !connected)).start();
            }
            final Runnable runnable =
                    () -> {
                        if (!databaseBackend.deleteAccount(account)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid() + ": unable to delete account");
                        }
                    };
            mDatabaseWriterExecutor.execute(runnable);
            this.accounts.remove(account);
            if (CallIntegration.hasSystemFeature(this)) {
                CallIntegrationConnectionService.unregisterPhoneAccount(this, account);
            }
            this.mRosterSyncTaskManager.clear(account);
            updateAccountUi();
            mNotificationService.updateErrorNotification();
            syncEnabledAccountSetting();
            toggleForegroundService();
        }
    }

    public void setOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnConversationUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as ConversationListChangedListener");
            }
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnConversationUpdates.remove(listener);
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnShowErrorToastListener(OnShowErrorToast listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnShowErrorToasts.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnShowErrorToastListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnShowErrorToastListener(OnShowErrorToast onShowErrorToast) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnShowErrorToasts.remove(onShowErrorToast);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnAccountUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnAccountListChangedtListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnAccountUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnCaptchaRequested.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnCaptchaRequestListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnCaptchaRequested.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnRosterUpdates.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnRosterUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnRosterUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnUpdateBlocklist.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnUpdateBlocklistListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnUpdateBlocklist.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnKeyStatusUpdatedListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnKeyStatusUpdated.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnKeyStatusUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnNewKeysAvailableListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnKeyStatusUpdated.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.onJingleRtpConnectionUpdate.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnJingleRtpConnectionUpdate");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.onJingleRtpConnectionUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnMucRosterUpdateListener(OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnMucRosterUpdate.add(listener)) {
                Log.w(
                        Config.LOGTAG,
                        listener.getClass().getName()
                                + " is already registered as OnMucRosterListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnMucRosterUpdateListener(final OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnMucRosterUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public boolean checkListeners() {
        return (this.mOnAccountUpdates.isEmpty()
                && this.mOnConversationUpdates.isEmpty()
                && this.mOnRosterUpdates.isEmpty()
                && this.mOnCaptchaRequested.isEmpty()
                && this.mOnMucRosterUpdate.isEmpty()
                && this.mOnUpdateBlocklist.isEmpty()
                && this.mOnShowErrorToasts.isEmpty()
                && this.onJingleRtpConnectionUpdate.isEmpty()
                && this.mOnKeyStatusUpdated.isEmpty()
                && this.mOnStoriesUpdates.isEmpty());
    }

    private void switchToForeground() {
        toggleSoftDisabled(false);
        final boolean broadcastLastActivity = broadcastLastActivity();
        for (Conversation conversation : getConversations()) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                conversation.getMucOptions().resetChatState();
            } else {
                conversation.setIncomingChatState(Config.DEFAULT_CHAT_STATE);
            }
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                account.deactivateGracePeriod();
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (connection.getFeatures().csi()) {
                        connection.sendActive();
                    }
                    if (broadcastLastActivity) {
                        sendPresence(
                                account,
                                false); // send new presence but don't include idle because we are
                        // not
                    }
                }
            }
        }
        Log.d(Config.LOGTAG, "app switched into foreground");
    }

    private void switchToBackground() {
        final boolean broadcastLastActivity = broadcastLastActivity();
        if (broadcastLastActivity) {
            mLastActivity = System.currentTimeMillis();
            final SharedPreferences.Editor editor = getPreferences().edit();
            editor.putLong(SETTING_LAST_ACTIVITY_TS, mLastActivity);
            editor.apply();
        }
        final List<Account> accounts = getAccounts();
        for (Account account : accounts) {
            if (account.getStatus() == Account.State.ONLINE) {
                XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(account, true);
                    }
                    if (connection.getFeatures().csi()) {
                        connection.sendInactive();
                    }
                }
            }
        }
        this.mNotificationService.setIsInForeground(false);
        Log.d(Config.LOGTAG, "app switched into background");
    }

    public void connectMultiModeConversations(Account account) {
        List<Conversation> conversations = getConversations();
        for (Conversation conversation : conversations) {
            if (conversation.getMode() == Conversation.MODE_MULTI
                    && conversation.getAccount() == account) {
                joinMuc(conversation);
            }
        }
    }

    public void mucSelfPingAndRejoin(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (account.inProgressConferenceJoins.contains(conversation)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": canceling muc self ping because join is already under way");
                return;
            }
        }
        synchronized (account.inProgressConferencePings) {
            if (!account.inProgressConferencePings.add(conversation)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": canceling muc self ping because ping is already under way");
                return;
            }
        }
        final Jid self = conversation.getMucOptions().getSelf().getFullJid();
        final Iq ping = new Iq(Iq.Type.GET);
        ping.setTo(self);
        ping.addChild("ping", Namespace.PING);
        sendIqPacket(
                conversation.getAccount(),
                ping,
                response -> {
                    if (response.getType() == Iq.Type.ERROR) {
                        final var error = response.getError();
                        if (error == null
                                || error.hasChild("service-unavailable")
                                || error.hasChild("feature-not-implemented")
                                || error.hasChild("item-not-found")) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ping to "
                                            + self
                                            + " came back as ignorable error");
                        } else if (error.hasChild("remote-server-not-found")
                                || error.hasChild("remote-server-timeout")) {
                            // XEP-0410 v1.1.0: the remote server is unreachable, which says
                            // nothing about whether we are still joined. Treat it like a timeout
                            // and leave it to the next self-ping instead of rejoining.
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ping to "
                                            + self
                                            + " came back undecided (remote server unreachable)");
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ping to "
                                            + self
                                            + " failed. attempting rejoin");
                            joinMuc(conversation);
                        }
                    } else if (response.getType() == Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": ping to "
                                        + self
                                        + " came back fine");
                    }
                    synchronized (account.inProgressConferencePings) {
                        account.inProgressConferencePings.remove(conversation);
                    }
                });
    }

    public void joinMuc(Conversation conversation) {
        joinMuc(conversation, null, false);
    }

    public void joinMuc(Conversation conversation, boolean followedInvite) {
        joinMuc(conversation, null, followedInvite);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined) {
        joinMuc(conversation, onConferenceJoined, false);
    }

    private void joinMuc(
            final Conversation conversation,
            final OnConferenceJoined onConferenceJoined,
            final boolean followedInvite) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE) {
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.add(conversation);
            }
            if (Config.MUC_LEAVE_BEFORE_JOIN) {
                sendPresencePacket(account, mPresenceGenerator.leave(conversation.getMucOptions()));
            }
            // XEP-0272 Muji: the join replays every occupant's presence; forget the old view.
            mJingleConnectionManager.getMujiConferenceManager().onRoomJoining(conversation);
            conversation.resetMucOptions();
            if (onConferenceJoined != null) {
                conversation.getMucOptions().flagNoAutoPushConfiguration();
            }
            conversation.setHasMessagesLeftOnServer(false);
            fetchConferenceConfiguration(
                    conversation,
                    new OnConferenceConfigurationFetched() {

                        private void join(Conversation conversation) {
                            Account account = conversation.getAccount();
                            final MucOptions mucOptions = conversation.getMucOptions();

                            if (mucOptions.nonanonymous()
                                    && !mucOptions.membersOnly()
                                    && !conversation.getBooleanAttribute(
                                            "accept_non_anonymous", false)) {
                                synchronized (account.inProgressConferenceJoins) {
                                    account.inProgressConferenceJoins.remove(conversation);
                                }
                                mucOptions.setError(MucOptions.Error.NON_ANONYMOUS);
                                updateConversationUi();
                                if (onConferenceJoined != null) {
                                    onConferenceJoined.onConferenceJoined(conversation);
                                }
                                return;
                            }

                            final Jid joinJid = mucOptions.getSelf().getFullJid();
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": joining conversation "
                                            + joinJid.toString());
                            final var packet =
                                    mPresenceGenerator.selfPresence(
                                            account,
                                            Presence.Status.ONLINE,
                                            mucOptions.nonanonymous() || onConferenceJoined != null,
                                            mucOptions.getSelf().getNick());
                            packet.setTo(joinJid);
                            addMujiIfInCall(conversation, packet);
                            Element x = packet.addChild("x", "http://jabber.org/protocol/muc");
                            if (conversation.getMucOptions().getPassword() != null) {
                                x.addChild("password").setContent(mucOptions.getPassword());
                            }

                            if (mucOptions.mamSupport()) {
                                // Use MAM instead of the limited muc history to get history
                                x.addChild("history").setAttribute("maxchars", "0");
                            } else {
                                // Fallback to muc history
                                x.addChild("history")
                                        .setAttribute(
                                                "since",
                                                PresenceGenerator.getTimestamp(
                                                        conversation
                                                                .getLastMessageTransmitted()
                                                                .getTimestamp()));
                            }
                            sendPresencePacket(account, packet);
                            if (onConferenceJoined != null) {
                                onConferenceJoined.onConferenceJoined(conversation);
                            }
                            if (!joinJid.equals(conversation.getJid())) {
                                conversation.setContactJid(joinJid);
                                databaseBackend.updateConversation(conversation);
                            }

                            maybeRegisterWithMuc(conversation, null);

                            if (mucOptions.mamSupport()) {
                                getMessageArchiveService().catchupMUC(conversation);
                            }
                            fetchConferenceMembers(conversation);
                            if (mucOptions.isPrivateAndNonAnonymous()) {
                                if (followedInvite) {
                                    final Bookmark bookmark = conversation.getBookmark();
                                    if (bookmark != null) {
                                        if (!bookmark.autojoin()) {
                                            bookmark.setAutojoin(true);
                                            createBookmark(account, bookmark);
                                        }
                                    } else {
                                        saveConversationAsBookmark(conversation, null);
                                    }
                                }
                            }
                            synchronized (account.inProgressConferenceJoins) {
                                account.inProgressConferenceJoins.remove(conversation);
                                sendUnsentMessages(conversation);
                            }
                        }

                        @Override
                        public void onConferenceConfigurationFetched(Conversation conversation) {
                            if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": conversation ("
                                                + conversation.getJid()
                                                + ") got archived before IQ result");
                                return;
                            }
                            join(conversation);
                        }

                        @Override
                        public void onFetchFailed(
                                final Conversation conversation, final String errorCondition) {
                            if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": conversation ("
                                                + conversation.getJid()
                                                + ") got archived before IQ result");
                                return;
                            }
                            if ("remote-server-not-found".equals(errorCondition)) {
                                synchronized (account.inProgressConferenceJoins) {
                                    account.inProgressConferenceJoins.remove(conversation);
                                }
                                conversation
                                        .getMucOptions()
                                        .setError(MucOptions.Error.SERVER_NOT_FOUND);
                                updateConversationUi();
                            } else {
                                join(conversation);
                                fetchConferenceConfiguration(conversation);
                            }
                        }
                    });
            updateConversationUi();
        } else {
            synchronized (account.pendingConferenceJoins) {
                account.pendingConferenceJoins.add(conversation);
            }
            conversation.resetMucOptions();
            conversation.setHasMessagesLeftOnServer(false);
            updateConversationUi();
        }
    }

    private void fetchConferenceMembers(final Conversation conversation) {
        final Account account = conversation.getAccount();
        final AxolotlService axolotlService = account.getAxolotlService();
        final var affiliations = new ArrayList<String>();
        affiliations.add("outcast");
        if (conversation.getMucOptions().isPrivateAndNonAnonymous())
            affiliations.addAll(List.of("member", "admin", "owner"));
        final Consumer<Iq> callback =
                new Consumer<Iq>() {

                    private int i = 0;
                    private boolean success = true;

                    @Override
                    public void accept(Iq response) {
                        final boolean omemoEnabled =
                                conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL
                                        || conversation.getNextEncryption()
                                                == Message.ENCRYPTION_AXOLOTL_OMEMO2;
                        Element query = response.query("http://jabber.org/protocol/muc#admin");
                        if (response.getType() == Iq.Type.RESULT && query != null) {
                            for (Element child : query.getChildren()) {
                                if ("item".equals(child.getName())) {
                                    MucOptions.User user =
                                            AbstractParser.parseItem(conversation, child);
                                    user.setOnline(false);
                                    if (!user.realJidMatchesAccount()) {
                                        boolean isNew =
                                                conversation.getMucOptions().updateUser(user);
                                        Contact contact = user.getContact();
                                        if (omemoEnabled
                                                && isNew
                                                && user.getRealJid() != null
                                                && (contact == null
                                                        || !contact.mutualPresenceSubscription())
                                                && axolotlService.hasEmptyDeviceList(
                                                        user.getRealJid())) {
                                            axolotlService.fetchDeviceIds(user.getRealJid());
                                        }
                                    }
                                }
                            }
                        } else {
                            success = false;
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": could not request affiliation "
                                            + affiliations.get(i)
                                            + " in "
                                            + conversation.getJid().asBareJid());
                        }
                        ++i;
                        if (i >= affiliations.size()) {
                            final var mucOptions = conversation.getMucOptions();
                            List<Jid> members = mucOptions.getMembers(true);
                            if (success) {
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                boolean changed = false;
                                for (ListIterator<Jid> iterator = cryptoTargets.listIterator();
                                        iterator.hasNext(); ) {
                                    Jid jid = iterator.next();
                                    if (!members.contains(jid)
                                            && !members.contains(jid.getDomain())) {
                                        iterator.remove();
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": removed "
                                                        + jid
                                                        + " from crypto targets of "
                                                        + conversation.getName());
                                        changed = true;
                                    }
                                }
                                if (changed) {
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    updateConversation(conversation);
                                }
                            }
                            getAvatarService().clear(mucOptions);
                            updateMucRosterUi();
                            updateConversationUi();
                        }
                    }
                };
        for (String affiliation : affiliations) {
            sendIqPacket(
                    account, mIqGenerator.queryAffiliation(conversation, affiliation), callback);
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": fetching members for " + conversation.getName());
    }

    public void providePasswordForMuc(final Conversation conversation, final String password) {
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            conversation.getMucOptions().setPassword(password);
            if (conversation.getBookmark() != null) {
                final Bookmark bookmark = conversation.getBookmark();
                bookmark.setAutojoin(true);
                createBookmark(conversation.getAccount(), bookmark);
            }
            updateConversation(conversation);
            joinMuc(conversation);
        }
    }

    public void deleteAvatar(final Account account) {
        final AtomicBoolean executed = new AtomicBoolean(false);
        final Runnable onDeleted =
                () -> {
                    if (executed.compareAndSet(false, true)) {
                        account.setAvatar(null);
                        databaseBackend.updateAccount(account);
                        getAvatarService().clear(account);
                        updateAccountUi();
                    }
                };
        deleteVcardAvatar(account, onDeleted);
        deletePepNode(account, Namespace.AVATAR_DATA);
        deletePepNode(account, Namespace.AVATAR_METADATA, onDeleted);
    }

    public void deletePepNode(final Account account, final String node) {
        deletePepNode(account, node, null);
    }

    private void deletePepNode(final Account account, final String node, final Runnable runnable) {
        final Iq request = mIqGenerator.deleteNode(node);
        sendIqPacket(
                account,
                request,
                packet -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": successfully deleted pep node "
                                        + node);
                        if (runnable != null) {
                            runnable.run();
                        }
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": failed to delete " + packet);
                    }
                });
    }

    private void deleteVcardAvatar(final Account account, @NonNull final Runnable runnable) {
        final Iq retrieveVcard = mIqGenerator.retrieveVcardAvatar(account.getJid().asBareJid());
        sendIqPacket(
                account,
                retrieveVcard,
                response -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": no vCard set. nothing to do");
                        return;
                    }
                    final Element vcard = response.findChild("vCard", "vcard-temp");
                    if (vcard == null) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": no vCard set. nothing to do");
                        return;
                    }
                    Element photo = vcard.findChild("PHOTO");
                    if (photo == null) {
                        photo = vcard.addChild("PHOTO");
                    }
                    photo.clearChildren();
                    final Iq publication = new Iq(Iq.Type.SET);
                    publication.setTo(account.getJid().asBareJid());
                    publication.addChild(vcard);
                    sendIqPacket(
                            account,
                            publication,
                            publicationResponse -> {
                                if (publicationResponse.getType() == Iq.Type.RESULT) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": successfully deleted vcard avatar");
                                    runnable.run();
                                } else {
                                    Log.d(
                                            Config.LOGTAG,
                                            "failed to publish vcard "
                                                    + publicationResponse.getErrorCondition());
                                }
                            });
                });
    }

    private boolean hasEnabledAccounts() {
        if (this.accounts == null) {
            return false;
        }
        for (final Account account : this.accounts) {
            if (account.isConnectionEnabled()) {
                return true;
            }
        }
        return false;
    }

    public void getAttachments(
            final Conversation conversation, int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(conversation, null, limit, onMediaLoaded);
    }

    /**
     * @see DatabaseBackend#getRelativeFilePaths(String, Jid, String, int, boolean)
     */
    public void getAttachments(
            final Conversation conversation,
            final int limit,
            final boolean chronological,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(
                conversation.getAccount().getUuid(),
                conversation.getJid().asBareJid(),
                null,
                limit,
                chronological,
                onMediaLoaded);
    }

    public void getAttachments(
            final Conversation conversation,
            String query,
            int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(
                conversation.getAccount(),
                conversation.getJid().asBareJid(),
                query,
                limit,
                onMediaLoaded);
    }

    public void getAttachments(
            final Account account,
            final Jid jid,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account, jid, null, limit, onMediaLoaded);
    }

    public void getAttachments(
            final Account account,
            final Jid jid,
            final String query,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account.getUuid(), jid.asBareJid(), query, limit, onMediaLoaded);
    }

    public void getAttachments(
            final String account,
            final Jid jid,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account, jid, null, limit, onMediaLoaded);
    }

    public void getAttachments(
            final String account,
            final Jid jid,
            final String query,
            final int limit,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account, jid, query, limit, false, onMediaLoaded);
    }

    /**
     * @see DatabaseBackend#getRelativeFilePaths(String, Jid, String, int, boolean)
     */
    public void getAttachments(
            final String account,
            final Jid jid,
            final int limit,
            final boolean chronological,
            final OnMediaLoaded onMediaLoaded) {
        getAttachments(account, jid, null, limit, chronological, onMediaLoaded);
    }

    /**
     * @see DatabaseBackend#getRelativeFilePaths(String, Jid, String, int, boolean)
     */
    public void getAttachments(
            final String account,
            final Jid jid,
            final String query,
            final int limit,
            final boolean chronological,
            final OnMediaLoaded onMediaLoaded) {
        new Thread(() ->
                onMediaLoaded.onMediaLoaded(
                        fileBackend.convertToAttachments(
                                databaseBackend.getRelativeFilePaths(
                                        account, jid, query, limit, chronological))))
                .start();
    }

    public void getAttachments(
            final String query, final int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments((String) null, (Jid) null, query, limit, onMediaLoaded);
    }

    /**
     * Removes the files of the given attachments (gallery / media viewer) for their messages. Does
     * disk and database work; call it off the main thread.
     *
     * @return the uuids of the messages whose file was removed
     * @see #deleteMessageFiles(java.util.Map)
     */
    public List<String> deleteMedia(final List<Attachment> attachments) {
        final java.util.Map<String, File> files = new java.util.LinkedHashMap<>();
        for (final Attachment attachment : attachments) {
            final var path = getFileBackend().getOriginalPath(attachment.getUri());
            files.put(attachment.getUuid().toString(), path == null ? null : new File(path));
        }
        return deleteMessageFiles(files);
    }

    /**
     * Removes the file of one message (chat "delete file"). Does disk and database work; call it
     * off the main thread.
     *
     * @return whether the message's file was removed
     * @see #deleteMessageFiles(java.util.Map)
     */
    public boolean deleteFileOfMessage(final Message message) {
        final File file = fileBackend.getFile(message);
        if (deleteMessageFiles(java.util.Collections.singletonMap(message.getUuid(), file))
                .isEmpty()) {
            return false;
        }
        // The caller's object may be in no loaded list (e.g. a search result); update it too, as
        // a later updateMessage on it would otherwise write the old state back.
        message.setDeleted(true);
        if (file.exists()) {
            // still there: other messages use it and this one was only detached from it
            message.setRelativeFilePath(null);
        }
        return true;
    }

    /**
     * Removes the files of the given messages (uuid → file) for those messages only. Storage is
     * content addressed, so the same file can back messages in other chats: such a file stays on
     * disk and the messages here are just detached from it (deleted, path dropped), so the other
     * chats keep showing it. A file nothing else uses is deleted. Messages deleted together don't
     * count as "other" for each other. A file that could not be deleted leaves its message as it
     * is. The database and the loaded conversations are updated, so neither the media gallery nor
     * an open chat keeps listing what was removed.
     *
     * @return the uuids of the messages whose file was removed
     */
    private List<String> deleteMessageFiles(final java.util.Map<String, File> files) {
        final java.util.Set<String> batch = files.keySet();
        final List<String> deleted = new ArrayList<>();
        final List<String> detached = new ArrayList<>();
        for (final java.util.Map.Entry<String, File> entry : files.entrySet()) {
            final File file = entry.getValue();
            if (file != null
                    && file.exists()
                    && databaseBackend.isFileUsedByOtherMessage(file, batch, null)) {
                Log.d(
                        Config.LOGTAG,
                        "keeping " + file.getAbsolutePath() + ", still used elsewhere");
                detached.add(entry.getKey());
                continue;
            }
            if (file != null) {
                if (!file.delete() && file.exists()) {
                    Log.d(Config.LOGTAG, "unable to delete " + file.getAbsolutePath());
                    continue;
                }
                evictPreview(file);
            }
            deleted.add(entry.getKey());
        }
        if (!deleted.isEmpty()) {
            databaseBackend.markFileAsDeleted(deleted);
        }
        if (!detached.isEmpty()) {
            databaseBackend.detachFileFromMessages(detached);
            // or a later updateMessage would write the shared path back
            for (final Conversation conversation : getConversations()) {
                conversation.detachFile(detached);
            }
        }
        final List<String> removed = new ArrayList<>(deleted);
        removed.addAll(detached);
        if (!removed.isEmpty()) {
            markUuidsAsDeletedFiles(removed);
        }
        return removed;
    }

    public void persistSelfNick(final MucOptions.User self, final boolean modified) {
        final Conversation conversation = self.getConversation();
        final Account account = conversation.getAccount();
        final Jid full = self.getFullJid();
        if (!full.equals(conversation.getJid())) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": persisting full jid " + full);
            conversation.setContactJid(full);
            databaseBackend.updateConversation(conversation);
        }

        final String nick = self.getNick();
        final Bookmark bookmark = conversation.getBookmark();
        if (bookmark == null || !modified) {
            return;
        }
        final String defaultNick = MucOptions.defaultNick(account);
        if (nick.equals(defaultNick) || nick.equals(bookmark.getNick())) {
            return;
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": persist nick '"
                        + full.getResource()
                        + "' into bookmark for "
                        + conversation.getJid().asBareJid());
        bookmark.setNick(nick);
        createBookmark(bookmark.getAccount(), bookmark);
    }

    public void presenceToMuc(final Conversation conversation) {
        final MucOptions options = conversation.getMucOptions();
        if (options.online()) {
            Account account = conversation.getAccount();
            final Jid joinJid = options.getSelf().getFullJid();
            final var packet =
                    mPresenceGenerator.selfPresence(
                            account,
                            Presence.Status.ONLINE,
                            options.nonanonymous(),
                            options.getSelf().getNick());
            packet.setTo(joinJid);
            addMujiIfInCall(conversation, packet);
            sendPresencePacket(account, packet);
        }
    }

    public boolean renameInMuc(
            final Conversation conversation,
            final String nick,
            final UiCallback<Conversation> callback) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = conversation.getBookmark();
        final MucOptions options = conversation.getMucOptions();
        final Jid joinJid = options.createJoinJid(nick);
        if (joinJid == null) {
            return false;
        }
        if (options.online()) {
            maybeRegisterWithMuc(conversation, nick);
            options.setOnRenameListener(
                    new OnRenameListener() {

                        @Override
                        public void onSuccess() {
                            final var packet =
                                    mPresenceGenerator.selfPresence(
                                            account,
                                            Presence.Status.ONLINE,
                                            options.nonanonymous(),
                                            nick);
                            packet.setTo(joinJid);
                            addMujiIfInCall(conversation, packet);
                            sendPresencePacket(account, packet);
                            callback.success(conversation);
                        }

                        @Override
                        public void onFailure() {
                            callback.error(R.string.nick_in_use, conversation);
                        }
                    });

            final var packet =
                    mPresenceGenerator.selfPresence(
                            account, Presence.Status.ONLINE, options.nonanonymous(), nick);
            packet.setTo(joinJid);
            addMujiIfInCall(conversation, packet);
            sendPresencePacket(account, packet);
            if (nick.equals(MucOptions.defaultNick(account))
                    && bookmark != null
                    && bookmark.getNick() != null) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": removing nick from bookmark for "
                                + bookmark.getJid());
                bookmark.setNick(null);
                createBookmark(account, bookmark);
            }
        } else {
            conversation.setContactJid(joinJid);
            databaseBackend.updateConversation(conversation);
            if (account.getStatus() == Account.State.ONLINE) {
                if (bookmark != null) {
                    bookmark.setNick(nick);
                    createBookmark(account, bookmark);
                }
                joinMuc(conversation);
            }
        }
        return true;
    }

    public void checkMucRequiresRename() {
        synchronized (this.conversations) {
            for (final Conversation conversation : this.conversations) {
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    checkMucRequiresRename(conversation);
                }
            }
        }
    }

    private void checkMucRequiresRename(final Conversation conversation) {
        final var options = conversation.getMucOptions();
        if (!options.online()) {
            return;
        }
        final var account = conversation.getAccount();
        final String current = options.getActualNick();
        final String proposed = options.getProposedNickPure();
        if (current == null || current.equals(proposed)) {
            return;
        }
        final Jid joinJid = options.createJoinJid(proposed);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "%s: muc rename required %s (was: %s)",
                        account.getJid().asBareJid(), joinJid, current));
        final var packet =
                mPresenceGenerator.selfPresence(
                        account, Presence.Status.ONLINE, options.nonanonymous(), proposed);
        packet.setTo(joinJid);
        addMujiIfInCall(conversation, packet);
        sendPresencePacket(account, packet);
    }

    /**
     * XEP-0272 Muji: while we're in this room's group call, every presence we send to it must keep
     * carrying {@code <muji>} — a presence without it tells the other participants that we left the
     * call, and they hang up their legs to us.
     */
    private void addMujiIfInCall(
            final Conversation conversation,
            final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final Element muji =
                mJingleConnectionManager
                        .getMujiConferenceManager()
                        .currentPayload(
                                conversation.getAccount(),
                                conversation.getJid().asBareJid().toString());
        if (muji != null) {
            packet.addChild(muji);
        }
    }

    public void leaveMuc(Conversation conversation) {
        leaveMuc(conversation, false);
    }

    private void leaveMuc(Conversation conversation, boolean now) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE || now) {
            // XEP-0272 Muji: leaving the room ends our part in its group call.
            mJingleConnectionManager.getMujiConferenceManager().onRoomLeft(conversation);
            sendPresencePacket(
                    conversation.getAccount(),
                    mPresenceGenerator.leave(conversation.getMucOptions()));
            conversation.getMucOptions().setOffline();
            Bookmark bookmark = conversation.getBookmark();
            if (bookmark != null) {
                bookmark.setConversation(null);
            }
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": leaving muc "
                            + conversation.getJid());
        } else {
            synchronized (account.pendingConferenceLeaves) {
                account.pendingConferenceLeaves.add(conversation);
            }
        }
    }

    public String findConferenceServer(final Account account) {
        String server;
        if (account.getXmppConnection() != null) {
            server = account.getXmppConnection().getMucServer();
            if (server != null) {
                return server;
            }
        }
        for (Account other : getAccounts()) {
            if (other != account && other.getXmppConnection() != null) {
                server = other.getXmppConnection().getMucServer();
                if (server != null) {
                    return server;
                }
            }
        }
        return null;
    }

    public void createPublicChannel(
            final Account account,
            final String name,
            final Jid address,
            final UiCallback<Conversation> callback) {
        joinMuc(
                findOrCreateConversation(account, address, true, false, true),
                conversation -> {
                    final Bundle configuration = IqGenerator.defaultChannelConfiguration();
                    if (!TextUtils.isEmpty(name)) {
                        configuration.putString("muc#roomconfig_roomname", name);
                    }
                    pushConferenceConfiguration(
                            conversation,
                            configuration,
                            new OnConfigurationPushed() {
                                @Override
                                public void onPushSucceeded() {
                                    saveConversationAsBookmark(conversation, name);
                                    callback.success(conversation);
                                }

                                @Override
                                public void onPushFailed() {
                                    if (conversation
                                            .getMucOptions()
                                            .getSelf()
                                            .getAffiliation()
                                            .ranks(MucOptions.Affiliation.OWNER)) {
                                        callback.error(
                                                R.string.unable_to_set_channel_configuration,
                                                conversation);
                                    } else {
                                        callback.error(
                                                R.string.joined_an_existing_channel, conversation);
                                    }
                                }
                            });
                });
    }

    public boolean createAdhocConference(
            final Account account,
            final String name,
            final Iterable<Jid> jids,
            final UiCallback<Conversation> callback) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString()
                        + ": creating adhoc conference with "
                        + jids.toString());
        if (account.getStatus() == Account.State.ONLINE) {
            try {
                String server = findConferenceServer(account);
                if (server == null) {
                    if (callback != null) {
                        callback.error(R.string.no_conference_server_found, null);
                    }
                    return false;
                }
                final Jid jid = Jid.of(CryptoHelper.pronounceable(), server, null);
                final Conversation conversation =
                        findOrCreateConversation(account, jid, true, false, true);
                joinMuc(
                        conversation,
                        new OnConferenceJoined() {
                            @Override
                            public void onConferenceJoined(final Conversation conversation) {
                                final Bundle configuration =
                                        IqGenerator.defaultGroupChatConfiguration();
                                if (!TextUtils.isEmpty(name)) {
                                    configuration.putString("muc#roomconfig_roomname", name);
                                }
                                pushConferenceConfiguration(
                                        conversation,
                                        configuration,
                                        new OnConfigurationPushed() {
                                            @Override
                                            public void onPushSucceeded() {
                                                for (Jid invite : jids) {
                                                    invite(conversation, invite);
                                                }
                                                for (String resource :
                                                        account.getSelfContact()
                                                                .getPresences()
                                                                .toResourceArray()) {
                                                    Jid other =
                                                            account.getJid().withResource(resource);
                                                    Log.d(
                                                            Config.LOGTAG,
                                                            account.getJid().asBareJid()
                                                                    + ": sending direct invite to "
                                                                    + other);
                                                    directInvite(conversation, other);
                                                }
                                                saveConversationAsBookmark(conversation, name);
                                                if (callback != null) {
                                                    callback.success(conversation);
                                                }
                                            }

                                            @Override
                                            public void onPushFailed() {
                                                archiveConversation(conversation);
                                                if (callback != null) {
                                                    callback.error(
                                                            R.string.conference_creation_failed,
                                                            conversation);
                                                }
                                            }
                                        });
                            }
                        });
                return true;
            } catch (IllegalArgumentException e) {
                if (callback != null) {
                    callback.error(R.string.conference_creation_failed, null);
                }
                return false;
            }
        } else {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null);
            }
            return false;
        }
    }

    public void checkIfMuc(final Account account, final Jid jid, Consumer<Boolean> cb) {
        if (jid.isDomainJid()) {
            // Spec basically says MUC needs to have a node
            // And also specifies that MUC and MUC service should have the same identity...
            cb.accept(false);
            return;
        }

        final var request = mIqGenerator.queryDiscoInfo(jid.asBareJid());
        sendIqPacket(
                account,
                request,
                reply -> {
                    final var result = new ServiceDiscoveryResult(reply);
                    cb.accept(
                            result.getFeatures().contains("http://jabber.org/protocol/muc")
                                    && result.hasIdentity("conference", null));
                });
    }

    public void fetchConferenceConfiguration(final Conversation conversation) {
        fetchConferenceConfiguration(conversation, null);
    }

    public void fetchConferenceConfiguration(
            final Conversation conversation, final OnConferenceConfigurationFetched callback) {
        final Iq request = mIqGenerator.queryDiscoInfo(conversation.getJid().asBareJid());
        final var account = conversation.getAccount();
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final ServiceDiscoveryResult discoResult =
                                new ServiceDiscoveryResult(response);
                        if (conversation.getMode() == Conversation.MODE_SINGLE
                                && discoResult.hasIdentity("conference", null)
                                && discoResult.getFeatures().contains(Namespace.MUC)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": "
                                            + conversation.getJid().asBareJid()
                                            + " is a group chat. changing mode");
                            conversation.setMode(Conversation.MODE_MULTI);
                            databaseBackend.updateConversation(conversation);
                        }
                        final MucOptions mucOptions = conversation.getMucOptions();
                        final Bookmark bookmark = conversation.getBookmark();
                        final boolean sameBefore =
                                StringUtils.equals(
                                        bookmark == null ? null : bookmark.getBookmarkName(),
                                        mucOptions.getName());

                        final var hadOccupantId = mucOptions.occupantId();
                        if (mucOptions.updateConfiguration(discoResult)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": muc configuration changed for "
                                            + conversation.getJid().asBareJid());
                            updateConversation(conversation);
                        }

                        final var hasOccupantId = mucOptions.occupantId();

                        if (!hadOccupantId && hasOccupantId && mucOptions.online()) {
                            final var me = mucOptions.getSelf().getFullJid();
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": gained support for occupant-id in "
                                            + me
                                            + ". resending presence");
                            final var packet =
                                    mPresenceGenerator.selfPresence(
                                            account,
                                            Presence.Status.ONLINE,
                                            mucOptions.nonanonymous(),
                                            mucOptions.getSelf().getNick());
                            packet.setTo(me);
                            addMujiIfInCall(conversation, packet);
                            sendPresencePacket(account, packet);
                        }

                        if (bookmark != null
                                && (sameBefore || bookmark.getBookmarkName() == null)) {
                            if (bookmark.setBookmarkName(
                                    StringUtils.nullOnEmpty(mucOptions.getName()))) {
                                createBookmark(account, bookmark);
                            }
                        }

                        if (callback != null) {
                            callback.onConferenceConfigurationFetched(conversation);
                        }

                        updateConversationUi();
                    } else if (response.getType() == Iq.Type.TIMEOUT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": received timeout waiting for conference configuration"
                                        + " fetch");
                    } else {
                        if (callback != null) {
                            callback.onFetchFailed(conversation, response.getErrorCondition());
                        }
                    }
                });
    }

    public void pushNodeConfiguration(
            Account account,
            final String node,
            final Bundle options,
            final OnConfigurationPushed callback) {
        pushNodeConfiguration(account, account.getJid().asBareJid(), node, options, callback);
    }

    public void pushNodeConfiguration(
            Account account,
            final Jid jid,
            final String node,
            final Bundle options,
            final OnConfigurationPushed callback) {
        Log.d(Config.LOGTAG, "pushing node configuration");
        sendIqPacket(
                account,
                mIqGenerator.requestPubsubConfiguration(jid, node),
                responseToRequest -> {
                    if (responseToRequest.getType() == Iq.Type.RESULT) {
                        Element pubsub =
                                responseToRequest.findChild(
                                        "pubsub", "http://jabber.org/protocol/pubsub#owner");
                        Element configuration =
                                pubsub == null ? null : pubsub.findChild("configure");
                        Element x =
                                configuration == null
                                        ? null
                                        : configuration.findChild("x", Namespace.DATA);
                        if (x != null) {
                            final Data data = Data.parse(x);
                            data.submit(options);
                            sendIqPacket(
                                    account,
                                    mIqGenerator.publishPubsubConfiguration(jid, node, data),
                                    responseToPublish -> {
                                        if (responseToPublish.getType() == Iq.Type.RESULT
                                                && callback != null) {
                                            Log.d(
                                                    Config.LOGTAG,
                                                    account.getJid().asBareJid()
                                                            + ": successfully changed node"
                                                            + " configuration for node "
                                                            + node);
                                            callback.onPushSucceeded();
                                        } else if (responseToPublish.getType() == Iq.Type.ERROR
                                                && callback != null) {
                                            callback.onPushFailed();
                                        }
                                    });
                        } else if (callback != null) {
                            callback.onPushFailed();
                        }
                    } else if (responseToRequest.getType() == Iq.Type.ERROR && callback != null) {
                        callback.onPushFailed();
                    }
                });
    }

    public void pushConferenceConfiguration(
            final Conversation conversation,
            final Bundle options,
            final OnConfigurationPushed callback) {
        if (options.getString("muc#roomconfig_whois", "moderators").equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true);
            updateConversation(conversation);
        }
        if (options.containsKey("muc#roomconfig_moderatedroom")) {
            final boolean moderated = "1".equals(options.getString("muc#roomconfig_moderatedroom"));
            options.putString("members_by_default", moderated ? "0" : "1");
        }
        if (options.containsKey("muc#roomconfig_allowpm")) {
            // ejabberd :-/
            final boolean allow = "anyone".equals(options.getString("muc#roomconfig_allowpm"));
            options.putString("allow_private_messages", allow ? "1" : "0");
            options.putString("allow_private_messages_from_visitors", allow ? "anyone" : "nobody");
        }
        final var account = conversation.getAccount();
        final Iq request = new Iq(Iq.Type.GET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner");
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Data data =
                                Data.parse(response.query().findChild("x", Namespace.DATA));
                        data.submit(options);
                        final Iq set = new Iq(Iq.Type.SET);
                        set.setTo(conversation.getJid().asBareJid());
                        set.query("http://jabber.org/protocol/muc#owner").addChild(data);
                        sendIqPacket(
                                account,
                                set,
                                packet -> {
                                    if (callback != null) {
                                        if (packet.getType() == Iq.Type.RESULT) {
                                            callback.onPushSucceeded();
                                        } else {
                                            Log.d(Config.LOGTAG, "failed: " + packet.toString());
                                            callback.onPushFailed();
                                        }
                                    }
                                });
                    } else {
                        if (callback != null) {
                            callback.onPushFailed();
                        }
                    }
                });
    }

    public void pushSubjectToConference(final Conversation conference, final String subject) {
        final var packet =
                this.getMessageGenerator()
                        .conferenceSubject(conference, StringUtils.nullOnEmpty(subject));
        this.sendMessagePacket(conference.getAccount(), packet);
    }

    public void requestVoice(final Account account, final Jid jid) {
        final var packet = this.getMessageGenerator().requestVoice(jid);
        this.sendMessagePacket(account, packet);
    }

    public void changeAffiliationInConference(
            final Conversation conference,
            Jid user,
            final MucOptions.Affiliation affiliation,
            final OnAffiliationChanged callback) {
        final Jid jid = user.asBareJid();
        final Iq request =
                this.mIqGenerator.changeAffiliation(conference, jid, affiliation.toString());
        sendIqPacket(
                conference.getAccount(),
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final var mucOptions = conference.getMucOptions();
                        mucOptions.changeAffiliation(jid, affiliation);
                        getAvatarService().clear(mucOptions);
                        if (callback != null) {
                            callback.onAffiliationChangedSuccessful(jid);
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    "changed affiliation of " + user + " to " + affiliation);
                        }
                    } else if (callback != null) {
                        callback.onAffiliationChangeFailed(
                                jid, R.string.could_not_change_affiliation);
                    } else {
                        Log.d(Config.LOGTAG, "unable to change affiliation");
                    }
                });
    }

    public void changeRoleInConference(
            final Conversation conference, final String nick, MucOptions.Role role) {
        final var account = conference.getAccount();
        final Iq request = this.mIqGenerator.changeRole(conference, nick, role.toString());
        sendIqPacket(
                account,
                request,
                packet -> {
                    if (packet.getType() != Iq.Type.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + " unable to change role of " + nick);
                    }
                });
    }

    public void moderateMessage(final Account account, final Message m, final String reason) {
        final var request = this.mIqGenerator.moderateMessage(account, m, reason);
        sendIqPacket(
                account,
                request,
                packet -> {
                    if (packet.getType() != Iq.Type.RESULT) {
                        showErrorToastInUi(R.string.unable_to_moderate);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + " unable to moderate: " + packet);
                    }
                });
        // Earlier versions of an edited message are separate archive entries; moderate them
        // too, or the original text stays in the room archive (Conversations 13063fbf4).
        for (final String previous : m.getEditedServerMessageIds()) {
            if (previous.equals(m.getServerMsgId())) {
                continue;
            }
            sendIqPacket(
                    account,
                    this.mIqGenerator.moderateMessage(account, m, previous, reason),
                    packet -> {
                        if (packet.getType() != Iq.Type.RESULT) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + " unable to moderate previous version "
                                            + previous
                                            + ": "
                                            + packet);
                        }
                    });
        }
    }

    public void destroyRoom(final Conversation conversation, final OnRoomDestroy callback) {
        final Iq request = new Iq(Iq.Type.SET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy");
        sendIqPacket(
                conversation.getAccount(),
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        if (callback != null) {
                            callback.onRoomDestroySucceeded();
                        }
                    } else if (response.getType() == Iq.Type.ERROR) {
                        if (callback != null) {
                            callback.onRoomDestroyFailed();
                        }
                    }
                });
    }

    private void disconnect(final Account account, boolean force) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        if (!force) {
            final List<Conversation> conversations = getConversations();
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        leaveMuc(conversation, true);
                    }
                }
            }
            sendOfflinePresence(account);
        }
        connection.disconnect(force);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void deleteMessage(Message message) {
        mScheduledMessages.remove(message.getUuid());
        deleteFileIfUnused(message);
        // The extra files of a multi-file message go with it: their rows are dropped by
        // deleteMessage(uuid), so their files would be left behind on disk otherwise.
        for (final Message attachment : message.getAttachments()) {
            deleteFileIfUnused(attachment);
        }
        databaseBackend.deleteMessage(message.getUuid());
        ((Conversation) message.getConversation()).remove(message);
        updateConversationUi();
    }

    public void deleteFileIfUnused(Message message) {
        if (getAppSettings().isDeleteUnusedFiles()) {
            final String exclusivePath = databaseBackend.getExclusiveFilePath(message);
            if (exclusivePath != null) {
                final String jid = message.getConversation().getJid().asBareJid().toString();
                FILE_ATTACHMENT_EXECUTOR.execute(
                        () -> deleteFilesAsync(Collections.singletonList(exclusivePath), jid));
            }
        }
    }

    public void updateMessageGeoPayload(
            String conversationUuid, String messageUuid, double lat, double lon) {
        if (messageUuid == null) {
            return;
        }
        final Conversation conversation = findConversationByUuid(conversationUuid);
        if (conversation != null) {
            final Message message = conversation.findMessageWithUuid(messageUuid);
            if (message != null) {
                for (eu.siacs.conversations.xml.Element el : message.getPayloads()) {
                    if ("live-location".equals(el.getName())
                            && Namespace.LIVE_LOCATION.equals(el.getAttribute("xmlns"))) {
                        el.setAttribute("last_lat", String.valueOf(lat));
                        el.setAttribute("last_lon", String.valueOf(lon));
                        databaseBackend.updateMessage(message, false);
                        break;
                    }
                }
            }
        }
    }

    public void updateMessage(Message message) {
        updateMessage(message, true);
    }

    public void updateMessage(Message message, boolean includeBody) {
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
    }

    public void createMessageAsync(final Message message) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.createMessage(message));
    }

    public void updateMessage(Message message, String uuid) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit");
        }
        updateConversationUi();
    }

    public void syncDirtyContacts(Account account) {
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact);
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact);
            }
        }
    }

    protected void unregisterPhoneAccounts(final Account account) {
        for (final Contact contact : account.getRoster().getContacts()) {
            if (!contact.showInRoster()) {
                contact.unregisterAsPhoneAccount(this);
            }
        }
    }

    public void createContact(final Contact contact, final boolean autoGrant) {
        createContact(contact, autoGrant, null);
    }

    public void createContact(
            final Contact contact, final boolean autoGrant, final String preAuth) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
            contact.setOption(Contact.Options.ASKING);
        }
        pushContactToServer(contact, preAuth);
    }

    public void onOtrSessionEstablished(Conversation conversation) {
        final Account account = conversation.getAccount();
        final Session otrSession = conversation.getOtrSession();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + " otr session established with "
                        + conversation.getJid()
                        + "/"
                        + otrSession.getSessionID().getUserID());
        conversation.findUnsentMessagesWithEncryption(
                Message.ENCRYPTION_OTR,
                new Conversation.OnMessageFound() {

                    @Override
                    public void onMessageFound(Message message) {
                        SessionID id = otrSession.getSessionID();
                        try {
                            message.setCounterpart(
                                    Jid.of(id.getAccountID() + "/" + id.getUserID()));
                        } catch (IllegalArgumentException e) {
                            return;
                        }
                        if (message.needsUploading()) {
                            mJingleConnectionManager.startJingleFileTransfer(message);
                        } else {
                            im.conversations.android.xmpp.model.stanza.Message outPacket =
                                    mMessageGenerator.generateOtrChat(message);
                            if (outPacket != null) {
                                mMessageGenerator.addDelay(outPacket, message.getTimeSent());
                                message.setStatus(Message.STATUS_SEND);
                                databaseBackend.updateMessage(message, false);
                                sendMessagePacket(account, outPacket);
                            }
                        }
                        updateConversationUi();
                    }
                });
    }

    public void pushContactToServer(final Contact contact) {
        pushContactToServer(contact, null);
    }

    private void pushContactToServer(final Contact contact, final String preAuth) {
        contact.resetOption(Contact.Options.DIRTY_DELETE);
        contact.setOption(Contact.Options.DIRTY_PUSH);
        final Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final boolean ask = contact.getOption(Contact.Options.ASKING);
            final boolean sendUpdates =
                    contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
                            && contact.getOption(Contact.Options.PREEMPTIVE_GRANT);
            final Iq iq = new Iq(Iq.Type.SET);
            iq.query(Namespace.ROSTER).addChild(contact.asElement());
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
            if (sendUpdates) {
                sendPresencePacket(account, mPresenceGenerator.sendPresenceUpdatesTo(contact));
            }
            if (ask) {
                sendPresencePacket(
                        account, mPresenceGenerator.requestPresenceUpdatesFrom(contact, preAuth));
            }
        } else {
            syncRoster(contact.getAccount());
        }
    }

    public void publishMucAvatar(
            final Conversation conversation, final Uri image, final OnAvatarPublication callback) {
        new Thread(
                        () -> {
                            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
                            final int size = Config.AVATAR_SIZE;
                            final Avatar avatar =
                                    getFileBackend().getPepAvatar(image, size, format);
                            if (avatar != null) {
                                if (!getFileBackend().save(avatar)) {
                                    callback.onAvatarPublicationFailed(
                                            R.string.error_saving_avatar);
                                    return;
                                }
                                avatar.owner = conversation.getJid().asBareJid();
                                publishMucAvatar(conversation, avatar, callback);
                            } else {
                                callback.onAvatarPublicationFailed(
                                        R.string.error_publish_avatar_converting);
                            }
                        })
                .start();
    }

    public void publishAvatarAsync(
            final Account account,
            final Uri image,
            final boolean open,
            final OnAvatarPublication callback) {
        new Thread(() -> publishAvatar(account, image, open, callback)).start();
    }

    private void publishAvatar(
            final Account account,
            final Uri image,
            final boolean open,
            final OnAvatarPublication callback) {
        final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
        final int size = Config.AVATAR_SIZE;
        final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
        if (avatar != null) {
            if (!getFileBackend().save(avatar)) {
                Log.d(Config.LOGTAG, "unable to save vcard");
                callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                return;
            }
            publishAvatar(account, avatar, open, callback);
        } else {
            callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
        }
    }

    private void publishMucAvatar(
            Conversation conversation, Avatar avatar, OnAvatarPublication callback) {
        final var account = conversation.getAccount();
        final Iq retrieve = mIqGenerator.retrieveVcardAvatar(avatar);
        sendIqPacket(
                account,
                retrieve,
                response -> {
                    boolean itemNotFound =
                            response.getType() == Iq.Type.ERROR
                                    && response.hasChild("error")
                                    && response.findChild("error").hasChild("item-not-found");
                    if (response.getType() == Iq.Type.RESULT || itemNotFound) {
                        Element vcard = response.findChild("vCard", "vcard-temp");
                        if (vcard == null) {
                            vcard = new Element("vCard", "vcard-temp");
                        }
                        Element photo = vcard.findChild("PHOTO");
                        if (photo == null) {
                            photo = vcard.addChild("PHOTO");
                        }
                        photo.clearChildren();
                        photo.addChild("TYPE").setContent(avatar.type);
                        photo.addChild("BINVAL").setContent(avatar.image);
                        final Iq publication = new Iq(Iq.Type.SET);
                        publication.setTo(conversation.getJid().asBareJid());
                        publication.addChild(vcard);
                        sendIqPacket(
                                account,
                                publication,
                                publicationResponse -> {
                                    if (publicationResponse.getType() == Iq.Type.RESULT) {
                                        callback.onAvatarPublicationSucceeded();
                                    } else {
                                        Log.d(
                                                Config.LOGTAG,
                                                "failed to publish vcard "
                                                        + publicationResponse.getErrorCondition());
                                        callback.onAvatarPublicationFailed(
                                                R.string.error_publish_avatar_server_reject);
                                    }
                                });
                    } else {
                        Log.d(Config.LOGTAG, "failed to request vcard " + response);
                        callback.onAvatarPublicationFailed(
                                R.string.error_publish_avatar_no_server_support);
                    }
                });
    }

    public void publishAvatar(
            final Account account,
            final Avatar avatar,
            final boolean open,
            final OnAvatarPublication callback) {
        final Bundle options;
        if (account.getXmppConnection().getFeatures().pepPublishOptions()) {
            options = open ? PublishOptions.openAccess() : PublishOptions.presenceAccess();
        } else {
            options = null;
        }
        publishAvatar(account, avatar, options, true, callback);
    }

    public void publishAvatar(
            Account account,
            final Avatar avatar,
            final Bundle options,
            final boolean retry,
            final OnAvatarPublication callback) {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": publishing avatar. options=" + options);
        final Iq packet = this.mIqGenerator.publishAvatar(avatar, options);
        this.sendIqPacket(
                account,
                packet,
                result -> {
                    if (result.getType() == Iq.Type.RESULT) {
                        publishAvatarMetadata(account, avatar, options, true, callback);
                    } else if (retry && PublishOptions.preconditionNotMet(result)) {
                        pushNodeConfiguration(
                                account,
                                Namespace.AVATAR_DATA,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": changed node configuration for avatar"
                                                        + " node");
                                        publishAvatar(account, avatar, options, false, callback);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to change node configuration"
                                                        + " for avatar node");
                                        publishAvatar(account, avatar, null, false, callback);
                                    }
                                });
                    } else {
                        Element error = result.findChild("error");
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": server rejected avatar "
                                        + (avatar.size / 1024)
                                        + "KiB "
                                        + (error != null ? error.toString() : ""));
                        if (callback != null) {
                            callback.onAvatarPublicationFailed(
                                    R.string.error_publish_avatar_server_reject);
                        }
                    }
                });
    }

    public void publishAvatarMetadata(
            Account account,
            final Avatar avatar,
            final Bundle options,
            final boolean retry,
            final OnAvatarPublication callback) {
        final Iq packet =
                XmppConnectionService.this.mIqGenerator.publishAvatarMetadata(avatar, options);
        sendIqPacket(
                account,
                packet,
                result -> {
                    if (result.getType() == Iq.Type.RESULT) {
                        if (account.setAvatar(avatar.getFilename())) {
                            getAvatarService().clear(account);
                            databaseBackend.updateAccount(account);
                            notifyAccountAvatarHasChanged(account);
                        }
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": published avatar "
                                        + (avatar.size / 1024)
                                        + "KiB");
                        if (callback != null) {
                            callback.onAvatarPublicationSucceeded();
                        }
                    } else if (retry && PublishOptions.preconditionNotMet(result)) {
                        pushNodeConfiguration(
                                account,
                                Namespace.AVATAR_METADATA,
                                options,
                                new OnConfigurationPushed() {
                                    @Override
                                    public void onPushSucceeded() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": changed node configuration for avatar"
                                                        + " meta data node");
                                        publishAvatarMetadata(
                                                account, avatar, options, false, callback);
                                    }

                                    @Override
                                    public void onPushFailed() {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": unable to change node configuration"
                                                        + " for avatar meta data node");
                                        publishAvatarMetadata(
                                                account, avatar, null, false, callback);
                                    }
                                });
                    } else {
                        if (callback != null) {
                            callback.onAvatarPublicationFailed(
                                    R.string.error_publish_avatar_server_reject);
                        }
                    }
                });
    }

    public void republishAvatarIfNeeded(final Account account) {
        if (account.getAxolotlService().isPepBroken()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": skipping republication of avatar because pep is broken");
            return;
        }
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(
                account,
                packet,
                new Consumer<Iq>() {

                    private Avatar parseAvatar(Iq packet) {
                        Element pubsub =
                                packet.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                        if (pubsub != null) {
                            Element items = pubsub.findChild("items");
                            if (items != null) {
                                return Avatar.parseMetadata(items);
                            }
                        }
                        return null;
                    }

                    private boolean errorIsItemNotFound(Iq packet) {
                        Element error = packet.findChild("error");
                        return packet.getType() == Iq.Type.ERROR
                                && error != null
                                && error.hasChild("item-not-found");
                    }

                    @Override
                    public void accept(final Iq packet) {
                        if (packet.getType() == Iq.Type.RESULT || errorIsItemNotFound(packet)) {
                            final Avatar serverAvatar = parseAvatar(packet);
                            if (serverAvatar == null && account.getAvatar() != null) {
                                final Avatar avatar =
                                        fileBackend.getStoredPepAvatar(account.getAvatar());
                                if (avatar != null) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": avatar on server was null. republishing");
                                    // publishing as 'open' - old server (that requires
                                    // republication) likely doesn't support access models anyway
                                    publishAvatar(
                                            account,
                                            fileBackend.getStoredPepAvatar(account.getAvatar()),
                                            true,
                                            null);
                                } else {
                                    Log.e(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": error rereading avatar");
                                }
                            }
                        }
                    }
                });
    }

    public void cancelAvatarFetches(final Account account) {
        synchronized (mInProgressAvatarFetches) {
            for (final Iterator<String> iterator = mInProgressAvatarFetches.iterator();
                    iterator.hasNext(); ) {
                final String KEY = iterator.next();
                if (KEY.startsWith(account.getJid().asBareJid() + "_")) {
                    iterator.remove();
                }
            }
        }
    }

    public void fetchAvatar(Account account, Avatar avatar) {
        fetchAvatar(account, avatar, null);
    }

    public void fetchAvatar(
            Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        if (databaseBackend.isBlockedMedia(avatar.cid())) {
            if (callback != null) callback.error(0, null);
            return;
        }

        final String KEY = generateFetchKey(account, avatar);
        synchronized (this.mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                switch (avatar.origin) {
                    case PEP:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarPep(account, avatar, callback);
                        break;
                    case VCARD:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarVcard(account, avatar, callback);
                        break;
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY);
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": already fetching "
                                + avatar.origin
                                + " avatar for "
                                + avatar.owner);
            }
        }
    }

    private void fetchAvatarPep(
            final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrievePepAvatar(avatar);
        sendIqPacket(
                account,
                packet,
                result -> {
                    synchronized (mInProgressAvatarFetches) {
                        mInProgressAvatarFetches.remove(generateFetchKey(account, avatar));
                    }
                    final String ERROR =
                            account.getJid().asBareJid()
                                    + ": fetching avatar for "
                                    + avatar.owner
                                    + " failed ";
                    if (result.getType() == Iq.Type.RESULT) {
                        avatar.image = IqParser.avatarData(result);
                        if (avatar.image != null) {
                            if (getFileBackend().save(avatar)) {
                                if (account.getJid().asBareJid().equals(avatar.owner)) {
                                    if (account.setAvatar(avatar.getFilename())) {
                                        databaseBackend.updateAccount(account);
                                    }
                                    getAvatarService().clear(account);
                                    updateConversationUi();
                                    updateAccountUi();
                                } else {
                                    final Contact contact =
                                            account.getRoster().getContact(avatar.owner);
                                    contact.setAvatar(avatar);
                                    syncRoster(account);
                                    getAvatarService().clear(contact);
                                    updateConversationUi();
                                    updateRosterUi(UpdateRosterReason.AVATAR);
                                }
                                if (callback != null) {
                                    callback.success(avatar);
                                }
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": successfully fetched pep avatar for "
                                                + avatar.owner);
                                return;
                            }
                        } else {

                            Log.d(Config.LOGTAG, ERROR + "(parsing error)");
                        }
                    } else {
                        Element error = result.findChild("error");
                        if (error == null) {
                            Log.d(Config.LOGTAG, ERROR + "(server error)");
                        } else {
                            Log.d(Config.LOGTAG, ERROR + error.toString());
                        }
                    }
                    if (callback != null) {
                        callback.error(0, null);
                    }
                });
    }

    private void fetchAvatarVcard(
            final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrieveVcardAvatar(avatar);
        this.sendIqPacket(
                account,
                packet,
                response -> {
                    final boolean previouslyOmittedPepFetch;
                    synchronized (mInProgressAvatarFetches) {
                        final String KEY = generateFetchKey(account, avatar);
                        mInProgressAvatarFetches.remove(KEY);
                        previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY);
                    }
                    if (response.getType() == Iq.Type.RESULT) {
                        Element vCard = response.findChild("vCard", "vcard-temp");
                        Element photo = vCard != null ? vCard.findChild("PHOTO") : null;
                        String image = photo != null ? photo.findChildContent("BINVAL") : null;
                        if (image != null) {
                            avatar.image = image;
                            if (getFileBackend().save(avatar)) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": successfully fetched vCard avatar for "
                                                + avatar.owner
                                                + " omittedPep="
                                                + previouslyOmittedPepFetch);
                                if (avatar.owner.isBareJid()) {
                                    if (account.getJid().asBareJid().equals(avatar.owner)
                                            && account.getAvatar() == null) {
                                        Log.d(
                                                Config.LOGTAG,
                                                account.getJid().asBareJid()
                                                        + ": had no avatar. replacing with vcard");
                                        account.setAvatar(avatar.getFilename());
                                        databaseBackend.updateAccount(account);
                                        getAvatarService().clear(account);
                                        updateAccountUi();
                                    } else {
                                        final Contact contact =
                                                account.getRoster().getContact(avatar.owner);
                                        contact.setAvatar(avatar, previouslyOmittedPepFetch);
                                        syncRoster(account);
                                        getAvatarService().clear(contact);
                                        updateRosterUi(UpdateRosterReason.AVATAR);
                                    }
                                    updateConversationUi();
                                } else {
                                    Conversation conversation =
                                            find(account, avatar.owner.asBareJid());
                                    if (conversation != null
                                            && conversation.getMode() == Conversation.MODE_MULTI) {
                                        MucOptions.User user =
                                                conversation
                                                        .getMucOptions()
                                                        .findUserByFullJid(avatar.owner);
                                        if (user != null) {
                                            if (user.setAvatar(avatar)) {
                                                getAvatarService().clear(user);
                                                updateConversationUi();
                                                updateMucRosterUi();
                                            }
                                            // not copied onto the contact of the room-asserted
                                            // real jid; see PresenceParser
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
    }

    public void checkForAvatar(final Account account, final UiCallback<Avatar> callback) {
        final Iq packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        Element pubsub =
                                response.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                        if (pubsub != null) {
                            Element items = pubsub.findChild("items");
                            if (items != null) {
                                Avatar avatar = Avatar.parseMetadata(items);
                                if (avatar != null) {
                                    avatar.owner = account.getJid().asBareJid();
                                    if (fileBackend.isAvatarCached(avatar)) {
                                        if (account.setAvatar(avatar.getFilename())) {
                                            databaseBackend.updateAccount(account);
                                        }
                                        getAvatarService().clear(account);
                                        callback.success(avatar);
                                    } else {
                                        fetchAvatarPep(account, avatar, callback);
                                    }
                                    return;
                                }
                            }
                        }
                    }
                    callback.error(0, null);
                });
    }

    public void notifyAccountAvatarHasChanged(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null && connection.getFeatures().bookmarksConversion()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": avatar changed. resending presence to online group chats");
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversational.MODE_MULTI) {
                    presenceToMuc(conversation);
                }
            }
        }
    }

    public void fetchVcard4(
            Account account, final Contact contact, final Consumer<Element> callback) {
        final var packet = this.mIqGenerator.retrieveVcard4(contact.getJid());
        sendIqPacket(
                account,
                packet,
                result -> {
                    if (result.getType() == Iq.Type.RESULT) {
                        final Element item = IqParser.getItem(result);
                        if (item != null) {
                            final Element vcard4 = item.findChild("vcard", Namespace.VCARD4);
                            if (vcard4 != null) {
                                if (callback != null) {
                                    callback.accept(vcard4);
                                }
                                return;
                            }
                        }
                    } else {
                        Element error = result.findChild("error");
                        if (error == null) {
                            Log.d(Config.LOGTAG, "fetchVcard4 (server error)");
                        } else {
                            Log.d(Config.LOGTAG, "fetchVcard4 " + error.toString());
                        }
                    }
                    if (callback != null) {
                        callback.accept(null);
                    }
                });
    }

    public void deleteContactOnServer(Contact contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
        contact.resetOption(Contact.Options.DIRTY_PUSH);
        contact.setOption(Contact.Options.DIRTY_DELETE);
        Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final Iq iq = new Iq(Iq.Type.SET);
            Element item = iq.query(Namespace.ROSTER).addChild("item");
            item.setAttribute("jid", contact.getJid());
            item.setAttribute("subscription", "remove");
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
        }
    }

    public void updateConversation(final Conversation conversation) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.updateConversation(conversation));
    }

    private void reconnectAccount(
            final Account account, final boolean force, final boolean interactive) {
        synchronized (account) {
            final XmppConnection existingConnection = account.getXmppConnection();
            final XmppConnection connection;
            if (existingConnection != null) {
                connection = existingConnection;
            } else if (account.isConnectionEnabled()) {
                connection = createConnection(account);
                account.setXmppConnection(connection);
            } else {
                return;
            }
            final boolean hasInternet = hasInternetConnection();
            if (account.isConnectionEnabled() && hasInternet) {
                if (!force) {
                    disconnect(account, false);
                }
                Thread thread = new Thread(connection);
                connection.setInteractive(interactive);
                connection.prepareNewConnection();
                connection.interrupt();
                thread.start();
                scheduleWakeUpCall(Config.CONNECT_DISCO_TIMEOUT, account.getUuid().hashCode());
            } else {
                disconnect(account, force || account.getTrueStatus().isError() || !hasInternet);
                account.getRoster().clearPresences();
                connection.resetEverything();
                final AxolotlService axolotlService = account.getAxolotlService();
                if (axolotlService != null) {
                    axolotlService.resetBrokenness();
                }
                if (!hasInternet) {
                    account.setStatus(Account.State.NO_INTERNET);
                }
            }
        }
    }

    public void reconnectAccountInBackground(final Account account) {
        new Thread(() -> reconnectAccount(account, false, true)).start();
    }

    public void invite(final Conversation conversation, final Jid contact) {
        Log.d(
                Config.LOGTAG,
                conversation.getAccount().getJid().asBareJid()
                        + ": inviting "
                        + contact
                        + " to "
                        + conversation.getJid().asBareJid());
        final MucOptions.User user =
                conversation.getMucOptions().findUserByRealJid(contact.asBareJid());
        if (user == null || user.getAffiliation() == MucOptions.Affiliation.OUTCAST) {
            changeAffiliationInConference(conversation, contact, MucOptions.Affiliation.NONE, null);
        }
        final var packet = mMessageGenerator.invite(conversation, contact);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void directInvite(Conversation conversation, Jid jid) {
        final var packet = mMessageGenerator.directInvite(conversation, jid);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void resetSendingToWaiting(Account account) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount() == account) {
                conversation.findUnsentTextMessages(
                        message -> markMessage(message, Message.STATUS_WAITING));
            }
        }
    }

    public Message markMessage(
            final Account account, final Jid recipient, final String uuid, final int status) {
        return markMessage(account, recipient, uuid, status, null);
    }

    public Message markMessage(
            final Account account,
            final Jid recipient,
            final String uuid,
            final int status,
            String errorMessage) {
        if (uuid == null) {
            return null;
        }
        for (Conversation conversation : getConversations()) {
            if (conversation.getJid().asBareJid().equals(recipient)
                    && conversation.getAccount() == account) {
                final Message message = conversation.findSentMessageWithUuidOrRemoteId(uuid);
                if (message != null) {
                    markMessage(message, status, errorMessage);
                }
                return message;
            }
        }
        return null;
    }

    public boolean markMessage(
            final Conversation conversation,
            final String uuid,
            final int status,
            final String serverMessageId) {
        return markMessage(
                conversation, uuid, status, serverMessageId, null, null, null, null, null);
    }

    public boolean markMessage(
            final Conversation conversation,
            final String uuid,
            final int status,
            final String serverMessageId,
            final LocalizedContent body,
            final Element html,
            final String subject,
            final Element thread,
            final Set<Message.FileParams> attachments) {
        if (uuid == null) {
            return false;
        } else {
            final Message message = conversation.findSentMessageWithUuid(uuid);
            if (message != null) {
                if (message.getServerMsgId() == null) {
                    message.setServerMsgId(serverMessageId);
                }
                if (message.getEncryption() == Message.ENCRYPTION_NONE
                        && (body != null
                                || html != null
                                || subject != null
                                || thread != null
                                || attachments != null)) {
                    message.setBody(body.content);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                    message.setHtml(html);
                    message.setSubject(subject);
                    message.setThread(thread);
                    if (attachments != null && attachments.isEmpty()) {
                        message.setRelativeFilePath(null);
                        message.resetFileParams();
                    }
                    markMessage(message, status, null, true);
                } else {
                    markMessage(message, status);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    public void markMessage(Message message, int status) {
        markMessage(message, status, null);
    }

    public void markMessage(final Message message, final int status, final String errorMessage) {
        markMessage(message, status, errorMessage, false);
    }

    public void markMessage(
            final Message message,
            final int status,
            final String errorMessage,
            final boolean includeBody) {
        final int oldStatus = message.getStatus();
        if (status == Message.STATUS_SEND_FAILED
                && (oldStatus == Message.STATUS_SEND_RECEIVED
                        || oldStatus == Message.STATUS_SEND_DISPLAYED)) {
            return;
        }
        if (status == Message.STATUS_SEND_RECEIVED && oldStatus == Message.STATUS_SEND_DISPLAYED) {
            return;
        }
        message.setErrorMessage(errorMessage);
        message.setStatus(status);
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
        if (oldStatus != status && status == Message.STATUS_SEND_FAILED) {
            mNotificationService.pushFailedDelivery(message);
            if (message.isAttachment()
                    && message.getConversation() instanceof Conversation conversation) {
                // The stanza carries every file of the message at once, so a file that cannot
                // be uploaded fails the whole message instead of leaving it waiting forever.
                final Message parent = conversation.findMessageWithUuid(message.getParentUuid());
                if (parent != null) {
                    markMessage(parent, Message.STATUS_SEND_FAILED, errorMessage);
                }
            }
        }
    }

    public SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    public long getAutomaticMessageDeletionDate() {
        final long timeout =
                getLongPreference(
                        AppSettings.AUTOMATIC_MESSAGE_DELETION,
                        R.integer.automatic_message_deletion);
        return timeout == 0 ? timeout : (System.currentTimeMillis() - (timeout * 1000));
    }

    public long getOmemoAutoExpiry() {
        return getLongPreference(AppSettings.OMEMO_AUTO_EXPIRY, R.integer.omemo_auto_expiry)
                * 1000L;
    }

    public long getLongPreference(String name, @IntegerRes int res) {
        long defaultValue = getResources().getInteger(res);
        try {
            return Long.parseLong(getPreferences().getString(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public String getStringPreference(String name, @StringRes int res) {
        return getPreferences().getString(name, getResources().getString(res));
    }

    public boolean confirmMessages() {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages);
    }

    public boolean allowMessageCorrection() {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction);
    }

    public boolean showTextFormatting() {
        return getBooleanPreference("showtextformatting", R.bool.showtextformatting);
    }

    public boolean sendChatStates() {
        return getBooleanPreference("chat_states", R.bool.chat_states);
    }

    private static final int LINK_PREVIEW_MAX_BYTES = 90_000;

    /**
     * Downloads (at most {@link #LINK_PREVIEW_MAX_BYTES} of) an HTML page with the given, already
     * proxy-configured client and turns its OpenGraph tags into an rdf:Description payload ({@link
     * eu.siacs.conversations.utils.LinkPreviewParser}).
     */
    @Nullable
    private static Element fetchLinkDescription(
            final OkHttpClient http, final HttpUrl url, final URI link) throws IOException {
        // As a link-preview crawler first (see LINK_PREVIEW_USER_AGENT). A site that refuses
        // that agent gets one more try with the app's own; a page that was served but has no
        // OpenGraph tags is not fetched twice.
        final var crawler =
                new okhttp3.Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", HttpConnectionManager.LINK_PREVIEW_USER_AGENT)
                        .build();
        try (final okhttp3.Response response =
                HttpConnectionManager.executeLinkPreviewRequest(http, crawler)) {
            if (response.isSuccessful()) return describeLinkResponse(response, link);
        }
        final var request = new okhttp3.Request.Builder().url(url).get().build();
        try (final okhttp3.Response response =
                HttpConnectionManager.executeLinkPreviewRequest(http, request)) {
            return describeLinkResponse(response, link);
        }
    }

    @Nullable
    private static Element describeLinkResponse(final okhttp3.Response response, final URI link)
            throws IOException {
        final okhttp3.ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) return null;
        final okhttp3.MediaType mediaType = body.contentType();
        final String subtype =
                mediaType == null ? "" : mediaType.type() + "/" + mediaType.subtype();
        if (!subtype.equals("text/html") && !subtype.equals("application/xhtml+xml")) return null;
        final okio.BufferedSource source = body.source();
        source.request(LINK_PREVIEW_MAX_BYTES);
        final long available = Math.min(source.getBuffer().size(), LINK_PREVIEW_MAX_BYTES);
        final java.nio.charset.Charset charset =
                mediaType.charset(java.nio.charset.StandardCharsets.UTF_8);
        final String html =
                source.getBuffer()
                        .readString(
                                available,
                                charset == null
                                        ? java.nio.charset.StandardCharsets.UTF_8
                                        : charset);
        return eu.siacs.conversations.utils.LinkPreviewParser.describe(
                html, response.request().url().toString(), link.toString());
    }

    public boolean useTorToConnect() {
        return getBooleanPreference("use_tor", R.bool.use_tor);
    }

    public boolean useI2PToConnect() {
        return getBooleanPreference("use_i2p", R.bool.use_i2p);
    }

    public boolean broadcastLastActivity() {
        return getBooleanPreference(AppSettings.BROADCAST_LAST_ACTIVITY, R.bool.last_activity);
    }

    public int unreadCount() {
        int count = 0;
        for (Conversation conversation : getConversations()) {
            count += conversation.unreadCount(this);
        }
        return count;
    }

    private <T> List<T> threadSafeList(Set<T> set) {
        synchronized (LISTENER_LOCK) {
            return set.isEmpty() ? Collections.emptyList() : new ArrayList<>(set);
        }
    }

    /**
     * Show a long toast with a formatted message, from any thread. Unlike {@link
     * #showErrorToastInUi} this does not depend on which activities registered a listener, so it
     * reaches the user on every screen — used where the outcome of a background check is the only
     * signal the user gets (e.g. post-quantum identity verification).
     */
    public void showToastInUi(final int resId, final Object... formatArgs) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() ->
                        android.widget.Toast.makeText(
                                        this,
                                        getString(resId, formatArgs),
                                        android.widget.Toast.LENGTH_LONG)
                                .show());
    }

    public void showErrorToastInUi(int resId) {
        for (OnShowErrorToast listener : threadSafeList(this.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId);
        }
    }

    public void updateConversationUi() {
        updateConversationUi(false);
    }

    public void updateConversationUi(boolean newCaps) {
        for (OnConversationUpdate listener : threadSafeList(this.mOnConversationUpdates)) {
            listener.onConversationUpdate(newCaps);
        }
    }

    private final Set<OnCallLogUpdated> mOnCallLogUpdated =
            Collections.newSetFromMap(new WeakHashMap<OnCallLogUpdated, Boolean>());

    public void setOnCallLogUpdatedListener(OnCallLogUpdated listener) {
        synchronized (LISTENER_LOCK) {
            this.mOnCallLogUpdated.add(listener);
        }
    }

    public void removeOnCallLogUpdatedListener(OnCallLogUpdated listener) {
        synchronized (LISTENER_LOCK) {
            this.mOnCallLogUpdated.remove(listener);
        }
    }

    public void updateCallLogUi() {
        for (OnCallLogUpdated listener : threadSafeList(this.mOnCallLogUpdated)) {
            listener.onCallLogUpdated();
        }
    }

    public interface OnCallLogUpdated {
        void onCallLogUpdated();
    }

    public void notifyJingleRtpConnectionUpdate(
            final Account account,
            final Jid with,
            final String sessionId,
            final RtpEndUserState state) {
        for (OnJingleRtpConnectionUpdate listener :
                threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onJingleRtpConnectionUpdate(account, with, sessionId, state);
        }
    }

    public void notifyJingleRtpConnectionUpdate(
            CallIntegration.AudioDevice selectedAudioDevice,
            Set<CallIntegration.AudioDevice> availableAudioDevices) {
        for (OnJingleRtpConnectionUpdate listener :
                threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    }

    public void updateAccountUi() {
        for (final OnAccountUpdate listener : threadSafeList(this.mOnAccountUpdates)) {
            listener.onAccountUpdate();
        }
    }

    public void updateRosterUi(final UpdateRosterReason reason) {
        if (reason == UpdateRosterReason.PRESENCE)
            throw new IllegalArgumentException("PRESENCE must also come with a contact");
        updateRosterUi(reason, null);
    }

    public void updateRosterUi(final UpdateRosterReason reason, final Contact contact) {
        for (OnRosterUpdate listener : threadSafeList(this.mOnRosterUpdates)) {
            listener.onRosterUpdate(reason, contact);
        }
    }

    public boolean displayCaptchaRequest(Account account, String id, Data data, Bitmap captcha) {
        if (mOnCaptchaRequested.size() > 0) {
            DisplayMetrics metrics = getApplicationContext().getResources().getDisplayMetrics();
            Bitmap scaled =
                    Bitmap.createScaledBitmap(
                            captcha,
                            (int) (captcha.getWidth() * metrics.scaledDensity),
                            (int) (captcha.getHeight() * metrics.scaledDensity),
                            false);
            for (OnCaptchaRequested listener : threadSafeList(this.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled);
            }
            return true;
        }
        return false;
    }

    public void updateBlocklistUi(final OnUpdateBlocklist.Status status) {
        for (OnUpdateBlocklist listener : threadSafeList(this.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist(status);
        }
    }

    public void updateMucRosterUi() {
        for (OnMucRosterUpdate listener : threadSafeList(this.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate();
        }
    }

    public void keyStatusUpdated(AxolotlService.FetchStatus report) {
        for (OnKeyStatusUpdated listener : threadSafeList(this.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report);
        }
    }

    public Account findAccountByJid(final Jid jid) {
        for (final Account account : this.accounts) {
            if (account.getJid().asBareJid().equals(jid.asBareJid())) {
                return account;
            }
        }
        return null;
    }

    public Account findAccountByUuid(final String uuid) {
        for (Account account : this.accounts) {
            if (account.getUuid().equals(uuid)) {
                return account;
            }
        }
        return null;
    }

    public Conversation findConversationByUuid(String uuid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getUuid().equals(uuid)) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation findUniqueConversationByJid(XmppUri xmppUri) {
        List<Conversation> findings = new ArrayList<>();
        for (Conversation c : getConversations()) {
            if (c.getAccount().isEnabled()
                    && c.getJid().asBareJid().equals(xmppUri.getJid().asBareJid())
                    && ((c.getMode() == Conversational.MODE_MULTI)
                            == xmppUri.isAction(XmppUri.ACTION_JOIN))) {
                findings.add(c);
            }
        }
        return findings.size() == 1 ? findings.get(0) : null;
    }

    public boolean markRead(final Conversation conversation, boolean dismiss) {
        return markRead(conversation, null, dismiss).size() > 0;
    }

    public void markRead(final Conversation conversation) {
        markRead(conversation, null, true);
    }

    public List<Message> markRead(
            final Conversation conversation, String upToUuid, boolean dismiss) {
        if (dismiss) {
            mNotificationService.clear(conversation);
        }
        final List<Message> readMessages = conversation.markRead(upToUuid);
        if (readMessages.size() > 0) {
            Runnable runnable =
                    () -> {
                        for (Message message : readMessages) {
                            if (message.getEphemeralTimer() > 0 && message.getExpireAt() == 0) {
                                message.setExpireAt(
                                        System.currentTimeMillis()
                                                + message.getEphemeralTimer() * 1000L);
                            }
                            databaseBackend.updateMessage(message, false);
                        }
                        scheduleNextExpiry();
                    };
            mDatabaseWriterExecutor.execute(runnable);
            updateConversationUi();
            updateUnreadCountBadge();
            return readMessages;
        } else {
            return readMessages;
        }
    }

    public void markNotificationDismissed(final List<Message> messages) {
        Runnable runnable =
                () -> {
                    for (final var message : messages) {
                        message.markNotificationDismissed();
                        databaseBackend.updateMessage(message, false);
                    }
                };
        mDatabaseWriterExecutor.execute(runnable);
    }

    public synchronized void updateUnreadCountBadge() {
        int count = unreadCount();
        if (unreadCount != count) {
            Log.d(Config.LOGTAG, "update unread count to " + count);
            if (count > 0) {
                ShortcutBadger.applyCount(getApplicationContext(), count);
            } else {
                ShortcutBadger.removeCount(getApplicationContext());
            }
            unreadCount = count;
        }
    }

    public void sendReadMarker(final Conversation conversation, final String upToUuid) {
        final boolean isPrivateAndNonAnonymousMuc =
                conversation.getMode() == Conversation.MODE_MULTI
                        && conversation.isPrivateAndNonAnonymous();
        final List<Message> readMessages = this.markRead(conversation, upToUuid, true);
        if (readMessages.isEmpty()) {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        updateConversationUi();
        final var last =
                Iterables.getLast(
                        Collections2.filter(
                                readMessages,
                                m ->
                                        !m.isPrivateMessage()
                                                && m.getStatus() == Message.STATUS_RECEIVED),
                        null);
        if (last == null) {
            return;
        }

        final boolean sendDisplayedMarker =
                confirmMessages()
                        && (last.trusted() || isPrivateAndNonAnonymousMuc)
                        && last.getRemoteMsgId() != null
                        && (last.markable || isPrivateAndNonAnonymousMuc);
        final boolean serverAssist =
                connection != null && connection.getFeatures().mdsServerAssist();

        final String stanzaId = last.getServerMsgId();

        final boolean useOmemo2 =
                conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
        if (sendDisplayedMarker && serverAssist && !useOmemo2) {
            // Server-assist path: <displayed> must be readable by our server so it
            // can both forward it to the peer and sync the MDS marker to our other
            // devices. Skip it for OMEMO2, where we encrypt the marker instead.
            final var mdsDisplayed = mIqGenerator.mdsDisplayed(stanzaId, conversation);
            final var packet = mMessageGenerator.confirm(last);
            packet.addChild(mdsDisplayed);
            if (!last.isPrivateMessage()) {
                packet.setTo(packet.getTo().asBareJid());
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server assisted " + packet);
            this.sendMessagePacket(account, packet);
        } else {
            publishMds(last);
            // read markers will be sent after MDS to flush the CSI stanza queue
            if (sendDisplayedMarker) {
                Log.d(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": sending displayed marker to "
                                + last.getCounterpart().toString());
                sendDisplayedMarker(conversation, last);
            }
        }
    }

    /**
     * Send an {@code <displayed/>} chat marker for {@code message}. For OMEMO2 conversations the
     * marker is wrapped in an SCE envelope so the peer learns about the read but the server does
     * not.
     */
    private void sendDisplayedMarker(final Conversation conversation, final Message message) {
        final var account = conversation.getAccount();
        if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
            final boolean groupChat = conversation.getMode() == Conversation.MODE_MULTI;
            final var basePacket = new im.conversations.android.xmpp.model.stanza.Message();
            basePacket.setType(
                    groupChat
                            ? im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT
                            : im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
            basePacket.setTo(
                    groupChat ? message.getCounterpart().asBareJid() : message.getCounterpart());
            basePacket.addChild("store", "urn:xmpp:hints");
            final Element displayed = new Element("displayed", "urn:xmpp:chat-markers:0");
            if (groupChat) {
                final String stanzaId = message.getServerMsgId();
                if (stanzaId != null) {
                    displayed.setAttribute("id", stanzaId);
                } else {
                    displayed.setAttribute("sender", message.getCounterpart().toString());
                    displayed.setAttribute("id", message.getRemoteMsgId());
                }
            } else {
                displayed.setAttribute("id", message.getRemoteMsgId());
            }
            account.getAxolotlService()
                    .sendOmemo2Packet(conversation, basePacket, java.util.List.of(displayed));
            return;
        }
        final var packet = mMessageGenerator.confirm(message);
        sendMessagePacket(account, packet);
    }

    private void publishMds(@Nullable final Message message) {
        final String stanzaId = message == null ? null : message.getServerMsgId();
        if (Strings.isNullOrEmpty(stanzaId)) {
            return;
        }
        final Conversation conversation;
        final var conversational = message.getConversation();
        if (conversational instanceof Conversation c) {
            conversation = c;
        } else {
            return;
        }
        final var account = conversation.getAccount();
        final var connection = account.getXmppConnection();
        if (connection == null || !connection.getFeatures().mds()) {
            return;
        }
        final Jid itemId;
        if (message.isPrivateMessage()) {
            itemId = message.getCounterpart();
        } else {
            itemId = conversation.getJid().asBareJid();
        }
        Log.d(Config.LOGTAG, "publishing mds for " + itemId + "/" + stanzaId);
        publishMds(account, itemId, stanzaId, conversation);
    }

    private void publishMds(
            final Account account,
            final Jid itemId,
            final String stanzaId,
            final Conversation conversation) {
        final var item = mIqGenerator.mdsDisplayed(stanzaId, conversation);
        pushNodeAndEnforcePublishOptions(
                account,
                Namespace.MDS_DISPLAYED,
                item,
                itemId.toString(),
                PublishOptions.persistentWhitelistAccessMaxItems());
    }

    public boolean publishUserTuneAsync(MediaMetadata metadata) {
        CharSequence artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        CharSequence title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM);

        // Media without artist/title/album are likely not music, abort updating this track.
        if (artist == null || title == null || album == null) {
            return false;
        }

        if (pendingUserTuneUpdate != null && !pendingUserTuneUpdate.isDone()) {
            pendingUserTuneUpdate.cancel(false);
        }

        // XEP-0118 Implementation Notes
        // To prevent a large number of updates when a user is skipping through tracks, an
        // implementation SHOULD wait several seconds before publishing new tune information.
        pendingUserTuneUpdate =
                userTuneUpdateExecutor.schedule(
                        () -> {
                            for (Account account : accounts) {
                                final Bundle options = null;

                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": publishing user tune. options="
                                                + options);

                                final Iq packet =
                                        this.mIqGenerator.publishUserTune(metadata, options);
                                this.sendIqPacket(
                                        account,
                                        packet,
                                        result -> {
                                            if (result.getType() != Iq.Type.RESULT) {
                                                Element error = result.findChild("error");
                                                Log.d(
                                                        Config.LOGTAG,
                                                        account.getJid().asBareJid()
                                                                + ": server rejected user tune "
                                                                + (error != null
                                                                        ? error.toString()
                                                                        : ""));
                                            }
                                        });
                            }
                        },
                        3,
                        TimeUnit.SECONDS);

        Log.d(Config.LOGTAG, "Update user tune: " + artist + " - " + title);

        return true;
    }

    public void stopPublishingUserTuneAsync() {
        if (pendingUserTuneUpdate != null && !pendingUserTuneUpdate.isDone()) {
            pendingUserTuneUpdate.cancel(false);
        }
        pendingUserTuneUpdate =
                userTuneUpdateExecutor.schedule(
                        () -> {
                            for (Account account : accounts) {
                                if (!account.isOnlineAndConnected()) {
                                    continue;
                                }
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid() + ": revoking user tune");
                                sendIqPacket(account, mIqGenerator.publishUserTune(), null);
                            }
                        },
                        3,
                        TimeUnit.SECONDS);
    }

    public boolean sendReactions(final Message message, final Collection<String> reactions) {
        if (message.isPrivateMessage())
            throw new IllegalArgumentException("Reactions to PM not implemented");
        if (message.getConversation() instanceof Conversation conversation) {
            {
                final var isPrivateMessage = message.isPrivateMessage();
                final Jid reactTo;
                final boolean typeGroupChat;
                final String reactToId;
                final Collection<Reaction> combinedReactions;
                final var newReactions = new HashSet<>(reactions);
                newReactions.removeAll(message.getAggregatedReactions().ourReactions);
                if (conversation.getMode() == Conversational.MODE_MULTI && !isPrivateMessage) {
                    final var mucOptions = conversation.getMucOptions();
                    if (!mucOptions.participating()) {
                        Log.e(Config.LOGTAG, "not participating in MUC");
                        return false;
                    }
                    final var self = mucOptions.getSelf();
                    final String occupantId = self.getOccupantId();
                    if (Strings.isNullOrEmpty(occupantId)) {
                        Log.e(Config.LOGTAG, "occupant id not found for reaction in MUC");
                        return false;
                    }
                    final var existingRaw =
                            ImmutableSet.copyOf(
                                    Collections2.transform(
                                            message.getReactions(), r -> r.reaction));
                    final var reactionsAsExistingVariants =
                            ImmutableSet.copyOf(
                                    Collections2.transform(
                                            reactions,
                                            r -> Emoticons.existingVariant(r, existingRaw)));
                    if (!reactions.equals(reactionsAsExistingVariants)) {
                        Log.d(Config.LOGTAG, "modified reactions to existing variants");
                    }
                    reactToId = message.getServerMsgId();
                    reactTo = conversation.getJid().asBareJid();
                    typeGroupChat = true;
                    combinedReactions =
                            Reaction.withMine(
                                    message.getReactions(),
                                    reactionsAsExistingVariants,
                                    false,
                                    self.getFullJid(),
                                    conversation.getAccount().getJid(),
                                    occupantId,
                                    null);
                } else {
                    if (message.isCarbon() || message.getStatus() == Message.STATUS_RECEIVED) {
                        reactToId = message.getRemoteMsgId();
                    } else {
                        reactToId = message.getUuid();
                    }
                    typeGroupChat = false;
                    if (isPrivateMessage) {
                        reactTo = message.getCounterpart();
                    } else {
                        reactTo = conversation.getJid().asBareJid();
                    }
                    combinedReactions =
                            Reaction.withFrom(
                                    message.getReactions(),
                                    reactions,
                                    false,
                                    conversation.getAccount().getJid(),
                                    null);
                }
                if (reactTo == null || Strings.isNullOrEmpty(reactToId)) {
                    Log.e(Config.LOGTAG, "could not find id to react to");
                    return false;
                }

                final var packet =
                        mMessageGenerator.reaction(
                                reactTo, typeGroupChat, message, reactToId, reactions);

                final var quote =
                        QuoteHelper.quote(MessageUtils.prepareQuote(message, 1, 2)) + "\n\n";
                final var body = quote + String.join(" ", newReactions);
                if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2) {
                    final var omemo2Packet =
                            new im.conversations.android.xmpp.model.stanza.Message();
                    omemo2Packet.setType(
                            typeGroupChat
                                    ? im.conversations.android.xmpp.model.stanza.Message.Type
                                            .GROUPCHAT
                                    : im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
                    omemo2Packet.setTo(reactTo);
                    omemo2Packet.addChild("store", "urn:xmpp:hints");
                    final Element reactionsEl = new Element("reactions", Namespace.REACTIONS);
                    reactionsEl.setAttribute("id", reactToId);
                    for (final String r : reactions) {
                        reactionsEl.addChild("reaction").setContent(r);
                    }
                    final List<Element> sceContent = new ArrayList<>();
                    sceContent.add(reactionsEl);
                    if (newReactions.size() > 0) {
                        final Element bodyEl = new Element("body", "jabber:client");
                        bodyEl.setContent(body);
                        sceContent.add(bodyEl);
                        final Jid replyTo = typeGroupChat ? reactTo : message.getCounterpart();
                        final Element reply = new Element("reply", "urn:xmpp:reply:0");
                        reply.setAttribute(
                                "to", replyTo != null ? replyTo.toString() : reactTo.toString());
                        reply.setAttribute("id", reactToId);
                        sceContent.add(reply);
                        final Element replyFallback =
                                new Element("fallback", "urn:xmpp:fallback:0");
                        replyFallback.setAttribute("for", "urn:xmpp:reply:0");
                        replyFallback
                                .addChild("body", "urn:xmpp:fallback:0")
                                .setAttribute("start", "0")
                                .setAttribute(
                                        "end",
                                        String.valueOf(quote.codePointCount(0, quote.length())));
                        sceContent.add(replyFallback);
                        final Element reactionsFallback =
                                new Element("fallback", "urn:xmpp:fallback:0");
                        reactionsFallback.setAttribute("for", "urn:xmpp:reactions:0");
                        reactionsFallback.addChild("body", "urn:xmpp:fallback:0");
                        sceContent.add(reactionsFallback);
                    }
                    final Element thread = message.getThread();
                    if (thread != null) sceContent.add(thread);
                    message.setReactions(combinedReactions);
                    updateMessage(message, false);
                    conversation
                            .getAccount()
                            .getAxolotlService()
                            .sendOmemo2Packet(conversation, omemo2Packet, sceContent);
                } else if (conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL
                        && newReactions.size() > 0) {
                    FILE_ATTACHMENT_EXECUTOR.execute(() -> {
                        XmppAxolotlMessage axolotlMessage =
                                conversation
                                        .getAccount()
                                        .getAxolotlService()
                                        .encrypt(body, conversation);
                        if (axolotlMessage == null) {
                            return;
                        }
                        packet.setAxolotlMessage(axolotlMessage.toElement());
                        packet.addChild("encryption", "urn:xmpp:eme:0")
                                .setAttribute("name", "OMEMO")
                                .setAttribute("namespace", AxolotlService.PEP_PREFIX);
                        sendMessagePacket(conversation.getAccount(), packet);
                        message.setReactions(combinedReactions);
                        updateMessage(message, false);
                    });
                } else if (conversation.getNextEncryption() == Message.ENCRYPTION_NONE
                        || newReactions.size() < 1) {
                    if (newReactions.size() > 0) {
                        packet.setBody(body);
                        packet.addChild("reply", "urn:xmpp:reply:0")
                                .setAttribute("to", message.getCounterpart())
                                .setAttribute("id", reactToId);
                        final var replyFallback =
                                packet.addChild("fallback", "urn:xmpp:fallback:0")
                                        .setAttribute("for", "urn:xmpp:reply:0");
                        replyFallback
                                .addChild("body", "urn:xmpp:fallback:0")
                                .setAttribute("start", "0")
                                .setAttribute("end", "" + quote.codePointCount(0, quote.length()));
                        final var fallback =
                                packet.addChild("fallback", "urn:xmpp:fallback:0")
                                        .setAttribute("for", "urn:xmpp:reactions:0");
                        fallback.addChild("body", "urn:xmpp:fallback:0");
                    }

                    sendMessagePacket(conversation.getAccount(), packet);
                    message.setReactions(combinedReactions);
                    updateMessage(message, false);
                }

                return true;
            }
        } else {
            return false;
        }
    }

    public MemorizingTrustManager getMemorizingTrustManager() {
        return this.mMemorizingTrustManager;
    }

    public void setMemorizingTrustManager(MemorizingTrustManager trustManager) {
        this.mMemorizingTrustManager = trustManager;
    }

    public void updateMemorizingTrustManager() {
        final MemorizingTrustManager trustManager;
        if (appSettings.isTrustSystemCAStore()) {
            trustManager = new MemorizingTrustManager(getApplicationContext());
        } else {
            trustManager = new MemorizingTrustManager(getApplicationContext(), null);
        }
        setMemorizingTrustManager(trustManager);
    }

    public void syncRosterToDisk(final Account account) {
        Runnable runnable = () -> databaseBackend.writeRoster(account.getRoster());
        mDatabaseWriterExecutor.execute(runnable);
    }

    public LruCache<String, Drawable> getDrawableCache() {
        return this.mDrawableCache;
    }

    public Collection<String> getKnownHosts() {
        final Set<String> hosts = new HashSet<>();
        for (final Account account : getAccounts()) {
            hosts.add(account.getServer());
            for (final Contact contact : account.getRoster().getContacts()) {
                if (contact.showInRoster()) {
                    final String server = contact.getServer();
                    if (server != null) {
                        hosts.add(server);
                    }
                }
            }
        }
        if (Config.QUICKSY_DOMAIN != null) {
            hosts.remove(
                    Config.QUICKSY_DOMAIN
                            .toString()); // we only want to show this when we type a e164
            // number
        }
        // Not needed currently
        // if (Config.MAGIC_CREATE_DOMAIN != null) {
        //    hosts.add(Config.MAGIC_CREATE_DOMAIN);
        // }
        hosts.add("monocles.eu");
        hosts.add("monocles.de");
        return hosts;
    }

    public Collection<String> getKnownConferenceHosts() {
        final Set<String> mucServers = new HashSet<>();
        for (final Account account : accounts) {
            if (account.getXmppConnection() != null) {
                mucServers.addAll(account.getXmppConnection().getMucServers());
                for (final Bookmark bookmark : account.getBookmarks()) {
                    final Jid jid = bookmark.getJid();
                    final String s = jid == null ? null : jid.getDomain().toString();
                    if (s != null) {
                        mucServers.add(s);
                    }
                }
            }
        }
        return mucServers;
    }

    public void sendMessagePacket(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendMessagePacket(packet);
        }
    }

    public void sendPresencePacket(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendPresencePacket(packet);
        }
    }

    public void sendCreateAccountWithCaptchaPacket(Account account, String id, Data data) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection == null) {
            return;
        }
        connection.sendCreateAccountWithCaptchaPacket(id, data);
    }

    public void sendIqPacket(final Account account, final Iq packet, final Consumer<Iq> callback) {
        sendIqPacket(account, packet, callback, null);
    }

    public void sendIqPacket(
            final Account account, final Iq packet, final Consumer<Iq> callback, Long timeout) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendIqPacket(packet, callback, timeout);
        } else if (callback != null) {
            callback.accept(Iq.TIMEOUT);
        }
    }

    public void sendPresence(final Account account) {
        sendPresence(account, checkListeners() && broadcastLastActivity());
    }

    private void sendPresence(final Account account, final boolean includeIdleTimestamp) {
        final Presence.Status status;
        if (manuallyChangePresence()) {
            status = account.getPresenceStatus();
        } else {
            status = getTargetPresence();
        }
        final var packet = mPresenceGenerator.selfPresence(account, status);
        if (mLastActivity > 0 && includeIdleTimestamp) {
            long since =
                    Math.min(mLastActivity, System.currentTimeMillis()); // don't send future dates
            packet.addChild("idle", Namespace.IDLE)
                    .setAttribute("since", AbstractGenerator.getTimestamp(since));
        }
        sendPresencePacket(account, packet);
    }

    private void deactivateGracePeriod() {
        for (Account account : getAccounts()) {
            account.deactivateGracePeriod();
        }
    }

    public void refreshAllPresences() {
        boolean includeIdleTimestamp = checkListeners() && broadcastLastActivity();
        for (Account account : getAccounts()) {
            if (account.isConnectionEnabled()) {
                sendPresence(account, includeIdleTimestamp);
            }
        }
    }

    private void refreshAllFcmTokens() {
        for (Account account : getAccounts()) {
            if (account.isOnlineAndConnected() && mPushManagementService.available(account)) {
                mPushManagementService.registerPushTokenOnServer(account);
            }
        }
    }

    private void sendOfflinePresence(final Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": sending offline presence");
        sendPresencePacket(account, mPresenceGenerator.sendOfflinePresence(account));
    }

    public MessageGenerator getMessageGenerator() {
        return this.mMessageGenerator;
    }

    public PresenceGenerator getPresenceGenerator() {
        return this.mPresenceGenerator;
    }

    public IqGenerator getIqGenerator() {
        return this.mIqGenerator;
    }

    public JingleConnectionManager getJingleConnectionManager() {
        return this.mJingleConnectionManager;
    }

    private boolean hasJingleRtpConnection(final Account account) {
        return this.mJingleConnectionManager.hasJingleRtpConnection(account);
    }

    public MessageArchiveService getMessageArchiveService() {
        return this.mMessageArchiveService;
    }

    public QuickConversationsService getQuickConversationsService() {
        return this.mQuickConversationsService;
    }

    public List<Contact> findContacts(Jid jid, String accountJid) {
        ArrayList<Contact> contacts = new ArrayList<>();
        for (Account account : getAccounts()) {
            if ((account.isEnabled() || accountJid != null)
                    && (accountJid == null
                            || accountJid.equals(account.getJid().asBareJid().toString()))) {
                Contact contact = account.getRoster().getContactFromContactList(jid);
                if (contact != null) {
                    contacts.add(contact);
                }
            }
        }
        return contacts;
    }

    public Conversation findFirstMuc(Jid jid) {
        return findFirstMuc(jid, null);
    }

    public Conversation findFirstMuc(Jid jid, String accountJid) {
        for (Conversation conversation : getConversations()) {
            if ((conversation.getAccount().isEnabled() || accountJid != null)
                    && (accountJid == null
                            || accountJid.equals(
                                    conversation.getAccount().getJid().asBareJid().toString()))
                    && conversation.getJid().asBareJid().equals(jid.asBareJid())
                    && conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation;
            }
        }
        return null;
    }

    public NotificationService getNotificationService() {
        return this.mNotificationService;
    }

    public HttpConnectionManager getHttpConnectionManager() {
        return this.mHttpConnectionManager;
    }

    public void resendFailedMessages(final Message message, final boolean forceP2P) {
        message.setTime(System.currentTimeMillis());
        markMessage(message, Message.STATUS_WAITING);
        sendMessage(message, true, false, false, null, forceP2P);
        if (message.getConversation() instanceof Conversation c) {
            c.sort();
        }
        updateConversationUi();
    }

    public void clearConversationHistory(final Conversation conversation) {
        final long clearDate;
        final String reference;
        if (conversation.countMessages() > 0) {
            Message latestMessage = conversation.getLatestMessage();
            clearDate = latestMessage.getTimeSent() + 1000;
            reference = latestMessage.getServerMsgId();
        } else {
            clearDate = System.currentTimeMillis();
            reference = null;
        }
        conversation.clearMessages();
        conversation.setHasMessagesLeftOnServer(false); // avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference);
        final boolean deleteFiles = getAppSettings().isDeleteUnusedFiles();
        Runnable runnable =
                () -> {
                    final List<String> exclusiveFilePaths;
                    if (deleteFiles) {
                        exclusiveFilePaths = databaseBackend.getExclusiveFilePaths(conversation);
                    } else {
                        exclusiveFilePaths = null;
                    }
                    databaseBackend.deleteMessagesInConversation(conversation);
                    databaseBackend.updateConversation(conversation);
                    if (exclusiveFilePaths != null && !exclusiveFilePaths.isEmpty()) {
                        final String jid = conversation.getJid().asBareJid().toString();
                        FILE_ATTACHMENT_EXECUTOR.execute(
                                () -> deleteFilesAsync(exclusiveFilePaths, jid));
                    }
                };
        mDatabaseWriterExecutor.execute(runnable);
    }

    private void deleteFilesAsync(final List<String> exclusiveFilePaths, final String jid) {
        int deletedCount = 0;
        for (final String relativePath : exclusiveFilePaths) {
            try {
                final File file = getFileBackend().getFileForPath(relativePath);
                // Storage is content addressed: a message whose file matches one of our stickers
                // points at the sticker itself. No message owns a sticker, so it is never deleted
                // with one - otherwise anyone could remove it by sending the same picture as a
                // disappearing message (same rule as for retractions, see MessageParser).
                if (FileBackend.isStickerFile(this, file)) {
                    Log.d(Config.LOGTAG, "keeping sticker file " + file.getName());
                    continue;
                }
                if (file.exists() && file.delete()) {
                    deletedCount++;
                    // the decoded preview of a disappeared picture must not stay in memory either
                    evictPreview(file);
                    getFileBackend().updateMediaScanner(file);
                    Log.d(Config.LOGTAG, "deleted file: " + file.getAbsolutePath());
                }
            } catch (final Exception e) {
                Log.w(Config.LOGTAG, "error deleting file for path " + relativePath, e);
            }
        }
        Log.d(
                Config.LOGTAG,
                "deleted "
                        + deletedCount
                        + "/"
                        + exclusiveFilePaths.size()
                        + " exclusive files for "
                        + jid);
    }

    public boolean sendBlockRequest(
            final Blockable blockable, final boolean reportSpam, final String serverMsgId) {
        if (blockable != null && blockable.getBlockedJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(
                    account,
                    getIqGenerator().generateSetBlockRequest(jid, reportSpam, serverMsgId),
                    response -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            account.getBlocklist().add(jid);
                            updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED);
                        }
                    });
            if (blockable.getBlockedJid().isFullJid()) {
                return false;
            } else if (removeBlockedConversations(blockable.getAccount(), jid)) {
                updateConversationUi();
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean removeBlockedConversations(final Account account, final Jid blockedJid) {
        boolean removed = false;
        synchronized (this.conversations) {
            boolean domainJid = blockedJid.getLocal() == null;
            for (Conversation conversation : this.conversations) {
                boolean jidMatches =
                        (domainJid
                                        && blockedJid
                                                .getDomain()
                                                .equals(conversation.getJid().getDomain()))
                                || blockedJid.equals(conversation.getJid().asBareJid());
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversation.MODE_SINGLE
                        && jidMatches) {
                    this.conversations.remove(conversation);
                    markRead(conversation);
                    conversation.setStatus(Conversation.STATUS_ARCHIVED);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": archiving conversation "
                                    + conversation.getJid().asBareJid()
                                    + " because jid was blocked");
                    updateConversation(conversation);
                    removed = true;
                }
            }
        }
        return removed;
    }

    public void sendUnblockRequest(final Blockable blockable) {
        if (blockable != null && blockable.getJid() != null) {
            final var account = blockable.getAccount();
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(
                    account,
                    getIqGenerator().generateSetUnblockRequest(jid),
                    response -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            account.getBlocklist().remove(jid);
                            updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED);
                        }
                    });
        }
    }

    public void publishDisplayName(final Account account) {
        String displayName = account.getDisplayName();
        final Iq request;
        if (TextUtils.isEmpty(displayName)) {
            request = mIqGenerator.deleteNode(Namespace.NICK);
        } else {
            request = mIqGenerator.publishNick(displayName);
        }
        mAvatarService.clear(account);
        sendIqPacket(
                account,
                request,
                packet -> {
                    if (packet.getType() == Iq.Type.ERROR) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": unable to modify nick name "
                                        + packet);
                    }
                });
    }

    public ServiceDiscoveryResult getCachedServiceDiscoveryResult(Pair<String, String> key) {
        ServiceDiscoveryResult result = discoCache.get(key);
        if (result != null) {
            return result;
        } else {
            if (key.first == null || key.second == null) return null;
            final DatabaseBackend backend = databaseBackend;
            if (backend == null) return null;
            result = backend.findDiscoveryResult(key.first, key.second);
            if (result != null) {
                discoCache.put(key, result);
            }
            return result;
        }
    }

    public void fetchFromGateway(
            Account account, final Jid jid, final String input, final OnGatewayResult callback) {
        final var request = new Iq(input == null ? Iq.Type.GET : Iq.Type.SET);
        request.setTo(jid);
        Element query = request.query("jabber:iq:gateway");
        if (input != null) {
            Element prompt = query.addChild("prompt");
            prompt.setContent(input);
        }
        sendIqPacket(
                account,
                request,
                packet -> {
                    if (packet.getType() == Iq.Type.RESULT) {
                        callback.onGatewayResult(
                                packet.query().findChildContent(input == null ? "prompt" : "jid"),
                                null);
                    } else {
                        Element error = packet.findChild("error");
                        callback.onGatewayResult(
                                null, error == null ? null : error.findChildContent("text"));
                    }
                });
    }

    public void fetchCaps(Account account, final Jid jid, final Presence presence) {
        fetchCaps(account, jid, presence, null);
    }

    public void fetchCaps(Account account, final Jid jid, final Presence presence, Runnable cb) {
        final Pair<String, String> key =
                presence == null ? null : new Pair<>(presence.getHash(), presence.getVer());
        final ServiceDiscoveryResult disco =
                key == null ? null : getCachedServiceDiscoveryResult(key);

        if (disco != null) {
            presence.setServiceDiscoveryResult(disco);
            final Contact contact = account.getRoster().getContact(jid);
            if (contact.refreshRtpCapability()) {
                syncRoster(account);
            }
            contact.refreshCaps();
            if (disco.hasIdentity("gateway", "pstn")) {
                contact.registerAsPhoneAccount(this);
                mQuickConversationsService.considerSyncBackground(false);
            }
            updateConversationUi(true);
        } else {
            final Iq request = new Iq(Iq.Type.GET);
            request.setTo(jid);
            final String node = presence == null ? null : presence.getNode();
            final String ver = presence == null ? null : presence.getVer();
            final Element query = request.query(Namespace.DISCO_INFO);
            if (node != null && ver != null) {
                query.setAttribute("node", node + "#" + ver);
            }

            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": making disco request for "
                            + (key == null ? null : key.second)
                            + " to "
                            + jid);
            sendIqPacket(
                    account,
                    request,
                    response -> {
                        if (response.getType() == Iq.Type.RESULT) {
                            final ServiceDiscoveryResult discoveryResult =
                                    new ServiceDiscoveryResult(response);
                            // A null ver means the disco#info could not be hashed unambiguously,
                            // so there is no safe key to cache it under.
                            if (discoveryResult.getVer() != null
                                    && (presence == null
                                            || presence.getVer() == null
                                            || presence.getVer()
                                                    .equals(discoveryResult.getVer()))) {
                                databaseBackend.insertDiscoveryResult(discoveryResult);
                                injectServiceDiscoveryResult(
                                        account.getRoster(),
                                        presence == null ? null : presence.getHash(),
                                        presence == null ? null : presence.getVer(),
                                        jid.getResource(),
                                        discoveryResult);
                                if (discoveryResult.hasIdentity("gateway", "pstn")) {
                                    final Contact contact = account.getRoster().getContact(jid);
                                    contact.registerAsPhoneAccount(this);
                                    mQuickConversationsService.considerSyncBackground(false);
                                }
                                updateConversationUi(true);
                                if (cb != null) cb.run();
                            } else {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": mismatch in caps for contact "
                                                + jid
                                                + " "
                                                + presence.getVer()
                                                + " vs "
                                                + discoveryResult.getVer());
                            }
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": unable to fetch caps from "
                                            + jid);
                        }
                    });
        }
    }

    public void fetchCommands(Account account, final Jid jid, Consumer<Iq> callback) {
        final var request =
                mIqGenerator.queryDiscoItems(jid, "http://jabber.org/protocol/commands");
        sendIqPacket(account, request, callback);
    }

    private void injectServiceDiscoveryResult(
            Roster roster, String hash, String ver, String resource, ServiceDiscoveryResult disco) {
        boolean rosterNeedsSync = false;
        for (final Contact contact : roster.getContacts()) {
            boolean serviceDiscoverySet = false;
            Presence onePresence = contact.getPresences().get(resource == null ? "" : resource);
            if (onePresence != null) {
                onePresence.setServiceDiscoveryResult(disco);
                serviceDiscoverySet = true;
            } else if (resource == null && hash == null && ver == null) {
                Presence p = new Presence(Presence.Status.OFFLINE, null, null, null, "");
                p.setServiceDiscoveryResult(disco);
                contact.updatePresence("", p);
                serviceDiscoverySet = true;
            }
            if (hash != null && ver != null) {
                for (final Presence presence : contact.getPresences().getPresences()) {
                    if (hash.equals(presence.getHash()) && ver.equals(presence.getVer())) {
                        presence.setServiceDiscoveryResult(disco);
                        serviceDiscoverySet = true;
                    }
                }
            }
            if (serviceDiscoverySet) {
                rosterNeedsSync |= contact.refreshRtpCapability();
                contact.refreshCaps();
            }
        }
        if (rosterNeedsSync) {
            syncRoster(roster.getAccount());
        }
    }

    public void fetchMamPreferences(final Account account, final OnMamPreferencesFetched callback) {
        final MessageArchiveService.Version version = MessageArchiveService.Version.get(account);
        final Iq request = new Iq(Iq.Type.GET);
        request.addChild("prefs", version.namespace);
        sendIqPacket(
                account,
                request,
                packet -> {
                    final Element prefs = packet.findChild("prefs", version.namespace);
                    if (packet.getType() == Iq.Type.RESULT && prefs != null) {
                        callback.onPreferencesFetched(prefs);
                    } else {
                        callback.onPreferencesFetchFailed();
                    }
                });
    }

    public PushManagementService getPushManagementService() {
        return mPushManagementService;
    }

    public void changeStatus(Account account, PresenceTemplate template, String signature) {
        if (!template.getStatusMessage().isEmpty()) {
            databaseBackend.insertPresenceTemplate(template);
        }
        account.setPgpSignature(signature);
        account.setPresenceStatus(template.getStatus());
        account.setPresenceStatusMessage(template.getStatusMessage());
        databaseBackend.updateAccount(account);
        sendPresence(account);
    }

    public List<PresenceTemplate> getPresenceTemplates(Account account) {
        List<PresenceTemplate> templates = databaseBackend.getPresenceTemplates();
        for (PresenceTemplate template : account.getSelfContact().getPresences().asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template);
            }
        }
        return templates;
    }

    public void saveConversationAsBookmark(final Conversation conversation, final String name) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = new Bookmark(account, conversation.getJid().asBareJid());
        String nick = conversation.getMucOptions().getActualNick();
        if (nick == null) nick = conversation.getJid().getResource();
        if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
            bookmark.setNick(nick);
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.setBookmarkName(name);
        }
        bookmark.setAutojoin(true);
        createBookmark(account, bookmark);
        bookmark.setConversation(conversation);
    }

    public boolean verifyFingerprints(Contact contact, List<XmppUri.Fingerprint> fingerprints) {
        boolean needsRosterWrite = false;
        boolean performedVerification = false;
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OTR) {
                performedVerification |= contact.addOtrFingerprint(fp.fingerprint);
                needsRosterWrite |= performedVerification;
            } else if (fp.type == XmppUri.FingerprintType.OMEMO
                    || fp.type == XmppUri.FingerprintType.OMEMO_PQ) {
                // Trust is keyed by the fingerprint value, not by stack, so both
                // the legacy and the PQ OMEMO2 key of a scanned code are verified
                // here; a key belonging to a stack the contact does not use simply
                // stays a pre-verification that never matches anything.
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                // Scoped to THIS contact. Identity public keys are published in PEP for
                // anyone to copy, so the same fingerprint can legitimately appear under two
                // JIDs; looking it up account-wide meant a code claiming to be contact B,
                // but carrying contact A's key, updated A's row (silently verifying someone
                // the user never verified) and recorded nothing at all for B.
                //
                // getFingerprintStatusOrNull, NOT getFingerprintTrust: the latter substitutes
                // UNDECIDED for a missing row, which made this branch always take the UPDATE
                // path. For a key we have never seen that UPDATE matches nothing, so scanning
                // a QR before the contact's keys were ever fetched silently discarded the
                // verification while still reporting success.
                final String owner = contact.getJid().asBareJid().toString();
                // With a hybrid fingerprint the scan also covers the post-quantum identity.
                // Unless the pinned one already matches, the classical key must not be marked
                // verified now: the check completes asynchronously against the device's bundle,
                // only then records the verification, and reports its outcome to the user
                // itself. NOT counted as performed here — reporting success before the check
                // has run would claim a verification that may never happen.
                if (fp.hybrid != null
                        && !axolotlService.verifyHybridFingerprint(
                                owner, fingerprint, fp.deviceId, fp.hybrid)) {
                    continue;
                }
                FingerprintStatus fingerprintStatus =
                        axolotlService.getFingerprintStatusOrNull(owner, fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        performedVerification |=
                                axolotlService.setFingerprintTrust(
                                        owner, fingerprint, fingerprintStatus.toVerified());
                    }
                } else {
                    axolotlService.preVerifyFingerprint(contact, fingerprint);
                    performedVerification = true;
                }
            }
        }

        if (needsRosterWrite) {
            syncRosterToDisk(contact.getAccount());
        }

        return performedVerification;
    }

    public boolean verifyFingerprints(Account account, List<XmppUri.Fingerprint> fingerprints) {
        final AxolotlService axolotlService = account.getAxolotlService();
        boolean verifiedSomething = false;
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO
                    || fp.type == XmppUri.FingerprintType.OMEMO_PQ) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                Log.d(Config.LOGTAG, "trying to verify own fp=" + fingerprint);
                // See the note in the Contact overload above: this must be scoped to the
                // owning JID (here: our own account), and must distinguish "no row" from
                // "UNDECIDED", which getFingerprintTrust cannot.
                final String owner = account.getJid().asBareJid().toString();
                // See the Contact overload: a hybrid fingerprint is checked against the
                // post-quantum identity before anything is marked verified. Our own device's
                // key is skipped: there is nothing to verify, and checking it would fetch our
                // own bundle and store a peer-style pin for our own identity.
                if (fingerprint.equals(axolotlService.getOwnFingerprint())) {
                    continue;
                }
                if (fp.hybrid != null
                        && !axolotlService.verifyHybridFingerprint(
                                owner, fingerprint, fp.deviceId, fp.hybrid)) {
                    continue;
                }
                FingerprintStatus fingerprintStatus =
                        axolotlService.getFingerprintStatusOrNull(owner, fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        verifiedSomething |=
                                axolotlService.setFingerprintTrust(
                                        owner, fingerprint, fingerprintStatus.toVerified());
                    }
                } else {
                    axolotlService.preVerifyFingerprint(account, fingerprint);
                    verifiedSomething = true;
                }
            }
        }
        return verifiedSomething;
    }

    public boolean blindTrustBeforeVerification() {
        return getBooleanPreference(AppSettings.BLIND_TRUST_BEFORE_VERIFICATION, R.bool.btbv);
    }

    public ShortcutService getShortcutService() {
        return mShortcutService;
    }

    public void pushMamPreferences(Account account, Element prefs) {
        final Iq set = new Iq(Iq.Type.SET);
        set.addChild(prefs);
        account.setMamPrefs(prefs);
        sendIqPacket(account, set, null);
    }

    public void evictPreview(File f) {
        if (f == null) return;

        if (mDrawableCache.remove(f.getAbsolutePath()) != null) {
            Log.d(Config.LOGTAG, "deleted cached preview");
        }
    }

    public void evictPreview(String uuid) {
        if (mDrawableCache.remove(uuid) != null) {
            Log.d(Config.LOGTAG, "deleted cached preview");
        }
    }

    public void updateAccountOrder() {
        synchronized (this.accounts) {
            for (int i = 0; i < this.accounts.size(); i++) {
                Account account = this.accounts.get(i);
                // Update the order field on the object
                account.setOrdering(i);
                // Persist to database
                databaseBackend.updateAccount(account);
            }
        }
        // Notify UI
        if (this.mOnAccountUpdates != null) {
            for (OnAccountUpdate listener : this.mOnAccountUpdates) {
                listener.onAccountUpdate();
            }
        }
    }

    public interface OnMamPreferencesFetched {
        void onPreferencesFetched(Element prefs);

        void onPreferencesFetchFailed();
    }

    public interface OnAccountCreated {
        void onAccountCreated(Account account);

        void informUser(int r);
    }

    public interface JumpToMessageListener {
        void onSuccess();

        void onNotFound();
    }

    public interface OnMoreMessagesLoaded {
        void onMoreMessagesLoaded(int count, Conversation conversation);

        void informUser(int r);
    }

    public interface OnAccountPasswordChanged {
        void onPasswordChangeSucceeded();

        void onPasswordChangeFailed();
    }

    public interface OnRoomDestroy {
        void onRoomDestroySucceeded();

        void onRoomDestroyFailed();
    }

    public interface OnAffiliationChanged {
        void onAffiliationChangedSuccessful(Jid jid);

        void onAffiliationChangeFailed(Jid jid, int resId);
    }

    public interface OnConversationUpdate {
        default void onConversationUpdate() {
            onConversationUpdate(false);
        }

        default void onConversationUpdate(boolean newCaps) {
            onConversationUpdate();
        }
    }

    public interface OnJingleRtpConnectionUpdate {
        void onJingleRtpConnectionUpdate(
                final Account account,
                final Jid with,
                final String sessionId,
                final RtpEndUserState state);

        void onAudioDeviceChanged(
                CallIntegration.AudioDevice selectedAudioDevice,
                Set<CallIntegration.AudioDevice> availableAudioDevices);
    }

    public interface OnAccountUpdate {
        void onAccountUpdate();
    }

    public interface OnCaptchaRequested {
        void onCaptchaRequested(Account account, String id, Data data, Bitmap captcha);
    }

    public interface OnRosterUpdate {
        void onRosterUpdate(final UpdateRosterReason reason, final Contact contact);
    }

    public interface OnMucRosterUpdate {
        void onMucRosterUpdate();
    }

    public interface OnConferenceConfigurationFetched {
        void onConferenceConfigurationFetched(Conversation conversation);

        void onFetchFailed(Conversation conversation, String errorCondition);
    }

    public interface OnConferenceJoined {
        void onConferenceJoined(Conversation conversation);
    }

    public interface OnConfigurationPushed {
        void onPushSucceeded();

        void onPushFailed();
    }

    public interface OnShowErrorToast {
        void onShowErrorToast(int resId);
    }

    public class XmppConnectionBinder extends Binder {
        public XmppConnectionService getService() {
            return XmppConnectionService.this;
        }
    }

    private class InternalEventReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            onStartCommand(intent, 0, 0);
        }
    }

    private class RestrictedEventReceiver extends BroadcastReceiver {

        private final Collection<String> allowedActions;

        private RestrictedEventReceiver(final Collection<String> allowedActions) {
            this.allowedActions = allowedActions;
        }

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent == null ? null : intent.getAction();
            if (allowedActions.contains(action)) {
                onStartCommand(intent, 0, 0);
            } else {
                Log.e(Config.LOGTAG, "restricting broadcast of event " + action);
            }
        }
    }

    public static class OngoingCall {
        public final AbstractJingleConnection.Id id;
        public final Set<Media> media;
        public final boolean reconnecting;
        @Nullable public final String mujiRoom;

        public OngoingCall(
                AbstractJingleConnection.Id id,
                Set<Media> media,
                final boolean reconnecting,
                @Nullable final String mujiRoom) {
            this.id = id;
            this.media = media;
            this.reconnecting = reconnecting;
            this.mujiRoom = mujiRoom;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OngoingCall that = (OngoingCall) o;
            return reconnecting == that.reconnecting
                    && Objects.equal(id, that.id)
                    && Objects.equal(media, that.media)
                    && Objects.equal(mujiRoom, that.mujiRoom);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id, media, reconnecting, mujiRoom);
        }
    }

    public static void toggleForegroundService(final XmppConnectionService service) {
        if (service == null) {
            return;
        }
        service.toggleForegroundService();
    }

    public static void toggleForegroundService(final ConversationsActivity activity) {
        if (activity == null) {
            return;
        }
        toggleForegroundService(activity.xmppConnectionService);
    }

    public static class BlockedMediaException extends Exception {}

    public static enum UpdateRosterReason {
        INIT,
        AVATAR,
        PUSH,
        PRESENCE
    }

    public boolean colored_muc_names() {
        return getBooleanPreference("colored_muc_names", R.bool.use_colored_muc_names);
    }

    public void publishVCard4(Account account, Element vcardElement) {
        if (account.getStatus() != Account.State.ONLINE) {
            return;
        }

        // 1. Create IQ-SET
        final Iq packet = new Iq(Iq.Type.SET);

        // 2. IMPORTANT: PEP requests must be addressed to the User's OWN Bare JID
        // (e.g. user@example.com), NOT left blank (which targets the server).
        packet.setTo(account.getJid().asBareJid());

        // 3. Create PubSub structure
        final Element pubsub = packet.addChild("pubsub", "http://jabber.org/protocol/pubsub");

        // 4. Publish to the specific node 'urn:xmpp:vcard4' defined in XEP-0292
        final Element publish = pubsub.addChild("publish");
        publish.setAttribute("node", "urn:xmpp:vcard4");

        // 5. Add Item with ID (XEP suggests 'current', though random UUIDs are allowed)
        final Element item = publish.addChild("item");
        item.setAttribute("id", "current");

        // 6. Ensure the vcard element has the correct namespace
        // The inner content MUST be <vcard xmlns='urn:ietf:params:xml:ns:vcard-4.0'>
        if (!"vcard".equals(vcardElement.getName())) {
            // Wrap if it's not already a vcard element
            Element wrapper = new Element("vcard");
            wrapper.setAttribute("xmlns", "urn:ietf:params:xml:ns:vcard-4.0");
            wrapper.addChild(vcardElement);
            item.addChild(wrapper);
        } else {
            // Ensure namespace is set if missing
            if (vcardElement.getAttribute("xmlns") == null) {
                vcardElement.setAttribute("xmlns", "urn:ietf:params:xml:ns:vcard-4.0");
            }
            item.addChild(vcardElement);
        }

        // 7. Send packet
        sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        Log.d(Config.LOGTAG, account.getJid() + ": Successfully published VCard4");
                        // Optional: Reload to reflect changes
                        fetchVcard4(account, account.getSelfContact(), null);
                    } else {
                        // Log.d(Config.LOGTAG, account.getJid() + ": Failed to publish VCard4: " +
                        // response.toString());
                    }
                });
    }

    public void publishStory(
            final Account account,
            final String url,
            final String type,
            final String title,
            final UiCallback<Void> callback) {
        // One id for every attempt below, so a retry can never leave two items on the node.
        publishStory(account, UUID.randomUUID().toString(), url, type, title, true, true, callback);
    }

    /**
     * Publishes with publish-options so the node keeps more than one item. A node that was
     * auto-created with the server's PEP defaults (max_items=1) is reconfigured on
     * precondition-not-met; if that is refused the story still goes out without options.
     */
    private void publishStory(
            final Account account,
            final String storyId,
            final String url,
            final String type,
            final String title,
            final boolean enforceOptions,
            final boolean retry,
            final UiCallback<Void> callback) {
        if (account.getStatus() != Account.State.ONLINE) {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null);
            }
            return;
        }
        final Iq packet =
                getIqGenerator()
                        .publishStory(
                                account,
                                storyId,
                                url,
                                type,
                                title,
                                enforceOptions ? PublishOptions.storiesAccess() : null);
        sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        // Don't depend on the PEP echo (+notify) to show our own story; duplicates
                        // are
                        // collapsed by onStoryReceived.
                        fetchOwnStories(account);
                        if (callback != null) {
                            callback.success(null);
                        }
                    } else if (enforceOptions && PublishOptions.preconditionNotMet(response)) {
                        if (retry) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": stories node config does not match. reconfiguring"
                                            + " it");
                            pushStoriesNodeConfiguration(
                                    account,
                                    true,
                                    new OnConfigurationPushed() {
                                        @Override
                                        public void onPushSucceeded() {
                                            publishStory(
                                                    account, storyId, url, type, title, true, false,
                                                    callback);
                                        }

                                        @Override
                                        public void onPushFailed() {
                                            Log.w(
                                                    Config.LOGTAG,
                                                    account.getJid().asBareJid()
                                                            + ": unable to reconfigure stories"
                                                            + " node; publishing without options"
                                                            + " (node may keep only one story)");
                                            publishStory(
                                                    account, storyId, url, type, title, false,
                                                    false, callback);
                                        }
                                    });
                        } else {
                            Log.w(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": stories node still rejects publish-options;"
                                            + " publishing without options (node may keep only one"
                                            + " story)");
                            publishStory(
                                    account, storyId, url, type, title, false, false, callback);
                        }
                    } else {
                        Log.e(Config.LOGTAG, "Failed to publish story: " + response);
                        if (callback != null) {
                            callback.error(R.string.error_publish_avatar_server_reject, null);
                        }
                    }
                });
    }

    /**
     * Pushes the full stories node config; if the server refuses any of it (e.g. a field it won't
     * change), retries with just the publish-option fields, so the node at least stops keeping a
     * single item instead of falling back to a plain publish on the old config.
     */
    private void pushStoriesNodeConfiguration(
            final Account account, final boolean full, final OnConfigurationPushed callback) {
        pushNodeConfiguration(
                account,
                Namespace.PUBSUB_STORIES,
                full ? IqGenerator.defaultStoriesConfiguration() : PublishOptions.storiesAccess(),
                new OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        callback.onPushSucceeded();
                    }

                    @Override
                    public void onPushFailed() {
                        if (full) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": full stories node config refused. retrying with"
                                            + " access model and max items only");
                            pushStoriesNodeConfiguration(account, false, callback);
                        } else {
                            callback.onPushFailed();
                        }
                    }
                });
    }

    /**
     * Result of {@link #uploadFileForUrl}: the public URL and the type of what was actually
     * uploaded.
     */
    public interface OnMediaUploaded {
        void onMediaUploaded(String url, String mimeType);

        void onMediaUploadFailed(int errorResId);
    }

    private MediaMetadataStripper.Animation inspectAnimation(final android.net.Uri uri) {
        try {
            return MediaMetadataStripper.inspectAnimation(getFileBackend().openInputStream(uri));
        } catch (final IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Prepares and uploads media for a public broadcast (Stories, Posts). Everything published here
     * is readable by every contact, so no identifying metadata may leave the device: images are
     * re-compressed as JPEG (dropping EXIF, including GPS), videos are transcoded to MP4 with the
     * source location left out. GIFs, animated WebPs and videos that can't be transcoded are
     * published as they are but stripped by {@link MediaMetadataStripper}; if that fails too,
     * nothing is published. The callback receives the type of the uploaded file, which usually
     * differs from the type of the picked file.
     */
    public void uploadFileForUrl(
            final Account account,
            final android.net.Uri uri,
            final String mimeType,
            final OnMediaUploaded callback) {
        if (account == null) {
            callback.onMediaUploadFailed(R.string.no_active_account);
            return;
        }
        FILE_ATTACHMENT_EXECUTOR.execute(() -> {
            final String sourceMime =
                    Strings.isNullOrEmpty(mimeType) || "application/octet-stream".equals(mimeType)
                            ? MimeUtils.guessMimeTypeFromUri(this, uri)
                            : mimeType;
            DownloadableFile prepared = null;
            String preparedMime = null;
            try {
                final boolean video = sourceMime != null && sourceMime.startsWith("video/");
                final boolean image = sourceMime != null && sourceMime.startsWith("image/");
                // Animated WebPs must not be re-encoded (that keeps only the first frame); the
                // type comes from the file signature, not from the picker.
                final MediaMetadataStripper.Animation animation =
                        "image/webp".equals(sourceMime) ? inspectAnimation(uri) : null;
                final String stripMime = animation != null ? animation.mime : sourceMime;
                if (video) {
                    final DownloadableFile transcoded =
                            getFileBackend().getTemporaryFile("video/mp4");
                    if (transcodeStoryVideo(uri, transcoded)) {
                        prepared = transcoded;
                        preparedMime = "video/mp4";
                    } else {
                        getFileBackend().deleteFile(transcoded);
                        Log.w(
                                Config.LOGTAG,
                                "transcoding failed, publishing the original without its metadata");
                    }
                } else if (image && !"image/gif".equals(sourceMime) && animation == null) {
                    // Never fall back to the raw file: it would publish the EXIF data (GPS
                    // position, device) to all contacts.
                    final DownloadableFile jpeg = getFileBackend().getTemporaryFile("image/jpeg");
                    copyOrCleanup(
                            jpeg,
                            () -> {
                                try {
                                    getFileBackend().copyImageToPrivateStorage(jpeg, uri);
                                } catch (final FileBackend.ImageCompressionException e) {
                                    Log.d(Config.LOGTAG, "unable to decode image for upload", e);
                                    throw new FileBackend.FileCopyException(
                                            R.string.error_not_an_image_file);
                                }
                            });
                    prepared = jpeg;
                    preparedMime = "image/jpeg";
                }
                if (prepared == null) {
                    if (MediaMetadataStripper.canStrip(stripMime)) {
                        // GIFs and animated WebPs (re-encoding would lose the animation) and
                        // videos that couldn't be transcoded go out as they are, minus their
                        // metadata.
                        final File strippedFile =
                                new File(getCacheDir(), UUID.randomUUID() + ".strip");
                        try (final java.io.InputStream is = getFileBackend().openInputStream(uri)) {
                            preparedMime =
                                    MediaMetadataStripper.strip(
                                            this, uri, is, stripMime, strippedFile);
                        } catch (final IOException | SecurityException e) {
                            Log.w(Config.LOGTAG, "unable to strip metadata, not publishing", e);
                            throw new FileBackend.FileCopyException(
                                    R.string.error_stripping_media_metadata);
                        }
                        prepared = getFileBackend().getTemporaryFile(preparedMime);
                        if (!strippedFile.renameTo(prepared)) {
                            strippedFile.delete();
                            throw new FileBackend.FileCopyException(
                                    R.string.error_unable_to_create_temporary_file);
                        }
                    } else if (video || image) {
                        // A media format we can't clean; it may carry location or device data.
                        throw new FileBackend.FileCopyException(
                                R.string.error_stripping_media_metadata);
                    } else {
                        final DownloadableFile raw = getFileBackend().getTemporaryFile(sourceMime);
                        copyOrCleanup(
                                raw, () -> getFileBackend().copyFileToPrivateStorage(raw, uri));
                        prepared = raw;
                        preparedMime = sourceMime;
                    }
                }
            } catch (final FileBackend.FileCopyException e) {
                callback.onMediaUploadFailed(e.getResId());
                return;
            }
            final DownloadableFile file = prepared;
            final String uploadMime = preparedMime;

            final XmppConnection connection = account.getXmppConnection();
            final long maxSize =
                    connection == null ? -1 : connection.getFeatures().getMaxHttpUploadSize();
            if (maxSize > 0 && file.getSize() > maxSize) {
                Log.d(
                        Config.LOGTAG,
                        "media of " + file.getSize() + " bytes exceeds upload limit of " + maxSize);
                getFileBackend().deleteFile(file);
                callback.onMediaUploadFailed(R.string.file_too_large);
                return;
            }

            mHttpConnectionManager.createNewUploadConnection(
                    file,
                    account,
                    false,
                    new UiCallback<String>() {
                        @Override
                        public void success(String url) {
                            try {
                                callback.onMediaUploaded(url, uploadMime);
                            } finally {
                                getFileBackend().deleteFile(file);
                            }
                        }

                        @Override
                        public void error(int errorCode, String object) {
                            try {
                                callback.onMediaUploadFailed(errorCode);
                            } finally {
                                getFileBackend().deleteFile(file);
                            }
                        }

                        @Override
                        public void userInputRequired(android.app.PendingIntent pi, String object) {
                            getFileBackend().deleteFile(file);
                        }
                    });
        });
    }

    private interface FileCopy {
        void run() throws FileBackend.FileCopyException;
    }

    private void copyOrCleanup(final File file, final FileCopy copy)
            throws FileBackend.FileCopyException {
        try {
            copy.run();
        } catch (final FileBackend.FileCopyException | RuntimeException e) {
            if (file.exists() && !file.delete()) {
                Log.w(Config.LOGTAG, "could not delete temporary upload file");
            }
            throw e;
        }
    }

    /**
     * @return true if {@code target} now holds a non-empty MP4 transcode of {@code uri}.
     */
    private boolean transcodeStoryVideo(final android.net.Uri uri, final File target) {
        startOngoingVideoTranscodingForegroundNotification();
        try {
            final AtomicBoolean failed = new AtomicBoolean(false);
            final TranscoderListener listener =
                    new TranscoderListener() {
                        @Override
                        public void onTranscodeProgress(double progress) {}

                        @Override
                        public void onTranscodeCompleted(int successCode) {
                            Log.d(Config.LOGTAG, "transcoding successful for upload");
                        }

                        @Override
                        public void onTranscodeCanceled() {
                            failed.set(true);
                        }

                        @Override
                        public void onTranscodeFailed(@NonNull Throwable exception) {
                            Log.w(Config.LOGTAG, "video transcoding failed for upload", exception);
                            failed.set(true);
                        }
                    };
            final com.otaliastudios.transcoder.source.DataSource source =
                    AttachFileToConversationRunnable.locationStrippedSource(this, uri);
            final boolean highQuality =
                    "720".equals(AttachFileToConversationRunnable.getVideoCompression(this));
            Transcoder.into(target.getAbsolutePath())
                    .addDataSource(source)
                    .setVideoTrackStrategy(
                            highQuality
                                    ? TranscoderStrategies.VIDEO_720P
                                    : TranscoderStrategies.VIDEO_360P)
                    .setAudioTrackStrategy(
                            highQuality
                                    ? TranscoderStrategies.AUDIO_HQ
                                    : TranscoderStrategies.AUDIO_MQ)
                    .setListener(listener)
                    .transcode()
                    .get();
            return !failed.get() && target.length() > 0;
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "video transcoding setup failed for upload", e);
            return false;
        } finally {
            stopOngoingVideoTranscodingForegroundNotification();
        }
    }

    private final List<eu.siacs.conversations.entities.Story> stories =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    // Tombstones for retracted story ids (uuid -> retraction time). A fetch that was already in
    // flight when a retract event arrived, or a replayed publish notification, would otherwise
    // re-add the item via onStoryReceived. Entries are pruned after 24h in retractOldStories,
    // since stories expire by then anyway.
    private final Map<String, Long> retractedStoryIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<OnStoriesUpdate> mOnStoriesUpdates =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // retractedStoryIds key: story ids are only unique per publisher.
    private static String storyKey(final Jid owner, final String storyId) {
        return owner.asBareJid() + "|" + storyId;
    }

    public List<eu.siacs.conversations.entities.Story> getStories() {
        return this.stories;
    }

    public void onStoryReceived(eu.siacs.conversations.entities.Story story) {
        if (story == null) {
            return;
        }
        if (story.getPublished() < System.currentTimeMillis() - 86400000L) {
            Log.d(Config.LOGTAG, "Ignoring expired story with id: " + story.getUuid());
            return;
        }
        if (story.getUuid() == null || story.getContact() == null) {
            return;
        }
        // Every account's connection thread and the startup restore add stories; without the
        // lock two of them can both miss the uuid and add the same story twice, which showed up
        // as an extra progress segment in the story viewer.
        synchronized (stories) {
            if (retractedStoryIds.containsKey(storyKey(story.getContact(), story.getUuid()))) {
                Log.d(Config.LOGTAG, "Ignoring retracted story with id: " + story.getUuid());
                return;
            }
            final int i = indexOfStory(story.getUuid());
            if (i >= 0) {
                final var existing = stories.get(i);
                // Story ids are chosen by the publisher; one owner must not be able to replace
                // (or, via the uuid-keyed table, overwrite) another owner's story.
                if (!existing.getContact().asBareJid().equals(story.getContact().asBareJid())) {
                    Log.w(
                            Config.LOGTAG,
                            "Ignoring story "
                                    + story.getUuid()
                                    + " from "
                                    + story.getContact()
                                    + ": id already used by "
                                    + existing.getContact());
                    return;
                }
                mDatabaseWriterExecutor.execute(() -> databaseBackend.upsertStory(story));
                stories.set(i, story);
            } else {
                mDatabaseWriterExecutor.execute(() -> databaseBackend.upsertStory(story));
                this.stories.add(story);
            }
            java.util.Collections.sort(
                    stories, (a, b) -> Long.compare(b.getPublished(), a.getPublished()));
        }
        updateStoriesUi();
    }

    /** Caller must hold the {@code stories} lock. */
    private int indexOfStory(final String uuid) {
        for (int i = 0; i < stories.size(); ++i) {
            if (stories.get(i).getUuid().equals(uuid)) {
                return i;
            }
        }
        return -1;
    }

    public void setOnStoriesUpdateListener(OnStoriesUpdate listener) {
        synchronized (LISTENER_LOCK) {
            this.mOnStoriesUpdates.add(listener);
        }
    }

    public void removeOnStoriesUpdateListener(OnStoriesUpdate listener) {
        synchronized (LISTENER_LOCK) {
            this.mOnStoriesUpdates.remove(listener);
        }
    }

    public void updateStoriesUi() {
        for (OnStoriesUpdate listener : threadSafeList(this.mOnStoriesUpdates)) {
            listener.onStoriesUpdate();
        }
    }

    public interface OnStoriesUpdate {
        void onStoriesUpdate();
    }

    public void fetchStories(Account account, Contact contact) {
        fetchStoriesForJid(account, contact.getJid());
    }

    public void fetchOwnStories(Account account) {
        fetchStoriesForJid(account, account.getJid().asBareJid());
    }

    private void fetchStoriesForJid(Account account, Jid jid) {
        if (account.getStatus() != Account.State.ONLINE) {
            return;
        }
        Log.d(Config.LOGTAG, "fetching stories for " + jid);
        final Iq request = getIqGenerator().retrieveStories(jid);
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                        if (pubsub != null) {
                            reconcileStories(jid, pubsub);
                            final List<eu.siacs.conversations.entities.Story> fetched =
                                    eu.siacs.conversations.entities.Story.parseFromPubSub(
                                            pubsub, jid);
                            for (final eu.siacs.conversations.entities.Story story : fetched) {
                                onStoryReceived(story);
                            }
                        }
                    }
                });
    }

    /**
     * Drops local stories of {@code publisher} that are no longer on its node. A retraction we
     * missed (offline, or a node without notify_retract) otherwise kept a deleted story around as
     * an extra, usually unloadable, segment until it expired. Only a complete listing is trusted: a
     * paged (RSM) result may simply not contain the item.
     */
    private void reconcileStories(final Jid publisher, final Element pubsub) {
        final Element items = pubsub.findChild("items");
        if (items == null
                || !Namespace.PUBSUB_STORIES.equals(items.getAttribute("node"))
                || pubsub.findChild("set", "http://jabber.org/protocol/rsm") != null) {
            return;
        }
        final Set<String> present = new HashSet<>();
        for (final Element item : items.getChildren()) {
            if ("item".equals(item.getName()) && item.getAttribute("id") != null) {
                present.add(item.getAttribute("id"));
            }
        }
        final Jid owner = publisher.asBareJid();
        final List<String> removed = new ArrayList<>();
        synchronized (stories) {
            for (final eu.siacs.conversations.entities.Story s : this.stories) {
                if (s.getContact().asBareJid().equals(owner) && !present.contains(s.getUuid())) {
                    this.stories.remove(s);
                    removed.add(s.getUuid());
                }
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        Log.d(
                Config.LOGTAG,
                "dropping " + removed.size() + " stories of " + owner + " no longer on the node");
        mDatabaseWriterExecutor.execute(() -> {
            for (final String uuid : removed) {
                databaseBackend.deleteStory(uuid, owner);
            }
        });
        updateStoriesUi();
    }

    public void retractStory(Account account, String storyId, final UiCallback<Void> callback) {
        final Iq iq = getIqGenerator().deleteItem(Namespace.PUBSUB_STORIES, storyId);
        iq.setTo(account.getJid().asBareJid());
        this.sendIqPacket(
                account,
                iq,
                response -> {
                    // Treat item-not-found as already gone (e.g. it expired, or was stored under a
                    // stale id) so we still drop the local copy and the UI can clear it.
                    final Element error = response.findChild("error");
                    final boolean alreadyGone = error != null && error.hasChild("item-not-found");
                    if (response.getType() == Iq.Type.RESULT || alreadyGone) {
                        final Jid owner = account.getJid().asBareJid();
                        synchronized (stories) {
                            retractedStoryIds.put(
                                    storyKey(owner, storyId), System.currentTimeMillis());
                            // this.stories is a CopyOnWriteArrayList; its iterator is a snapshot
                            // and does not
                            // support remove() (throws UnsupportedOperationException).
                            // We avoid removeIf() because on some platforms it might fall back to
                            // the default
                            // implementation which uses iterator.remove().
                            for (eu.siacs.conversations.entities.Story s : this.stories) {
                                if (s.getUuid().equals(storyId)
                                        && s.getContact().asBareJid().equals(owner)) {
                                    this.stories.remove(s);
                                }
                            }
                        }
                        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteStory(storyId));
                        updateStoriesUi();
                        if (callback != null) {
                            callback.success(null);
                        }
                    } else {
                        if (callback != null) {
                            callback.error(R.string.error_deleting_story, null);
                        }
                    }
                });
    }

    /**
     * A story retraction from {@code publisher}'s node. Only that publisher's own story with this
     * id is removed - ids are publisher-chosen, so anyone else could otherwise delete it.
     */
    public void onStoryRetracted(final Jid publisher, final String storyId) {
        if (publisher == null || storyId == null) {
            return;
        }
        final Jid owner = publisher.asBareJid();
        boolean removed = false;
        synchronized (stories) {
            retractedStoryIds.put(storyKey(owner, storyId), System.currentTimeMillis());
            for (eu.siacs.conversations.entities.Story s : this.stories) {
                if (s.getUuid().equals(storyId) && s.getContact().asBareJid().equals(owner)) {
                    if (this.stories.remove(s)) {
                        removed = true;
                        break;
                    }
                }
            }
        }
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteStory(storyId, owner));
        if (removed) {
            updateStoriesUi();
            Log.d(Config.LOGTAG, "Retracted story with id: " + storyId);
        }
    }

    public void retractOldStories() {
        final long twentyFourHoursAgo = System.currentTimeMillis() - 86400000;
        retractedStoryIds.values().removeIf(retractedAt -> retractedAt < twentyFourHoursAgo);
        mDatabaseWriterExecutor.execute(() -> databaseBackend.deleteExpiredStories());
        final Map<Jid, Account> onlineAccounts = new HashMap<>();
        for (final Account account : getAccounts()) {
            if (account.isOnlineAndConnected()) {
                onlineAccounts.put(account.getJid().asBareJid(), account);
            }
        }
        for (final eu.siacs.conversations.entities.Story story : this.stories) {
            if (story.getPublished() < twentyFourHoursAgo) {
                final Account owner = onlineAccounts.get(story.getContact().asBareJid());
                if (owner != null) {
                    Log.d(
                            Config.LOGTAG,
                            "Retracting old story from account: " + owner.getJid().asBareJid());
                    retractStory(owner, story.getUuid(), null);
                } else {
                    // Not own story — just remove from memory; DB already cleaned up above
                    synchronized (stories) {
                        this.stories.remove(story);
                    }
                }
            }
        }
    }

    public void cleanupStoryCache() {
        Log.d(Config.LOGTAG, "Cleaning up story cache");
        final File storyCacheDir = getFileBackend().getStoryCacheDirectory();
        if (storyCacheDir.exists()) {
            final long twentyFourHoursAgo = System.currentTimeMillis() - 86400000;
            final File[] files = storyCacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.lastModified() < twentyFourHoursAgo) {
                        if (file.delete()) {
                            Log.d(Config.LOGTAG, "Deleted old story cache file: " + file.getName());
                        }
                    }
                }
            }
        }
    }

    public void updateContact(final Contact contact) {
        // First, persist the change in the database
        if (databaseBackend.updateContact(contact)) {
            // Then, find the live account and contact objects
            final Account account = findAccountByUuid(contact.getAccount().getUuid());
            if (account != null) {
                final Contact rosterContact = account.getRoster().getContact(contact.getJid());
                // Update the live contact object with the new setting
                if (rosterContact != null) {
                    rosterContact.setCallsDisabled(contact.areCallsDisabled());
                    // Now, notify the UI about the change.
                    // This will cause activities like ConversationActivity to redraw their menus
                    // and hide/show the call button as appropriate.
                    updateConversationUi();
                    updateConversationUi(true);
                }
            }
        }
    }

    /**
     * Fetches {@code node} from {@code server} with {@code account}. The account matters: feeds are
     * usually presence-restricted, so it must be one that the owner shares presence with. Without
     * an account, the one owning {@code server} or else the first connected one is used.
     */
    public void fetchPubsubItems(
            final Account account,
            final Jid server,
            final String node,
            final OnPubsubItemsFetched callback) {
        fetchPubsubItems(account, server, node, null, callback);
    }

    /**
     * As {@link #fetchPubsubItems(Account, Jid, String, OnPubsubItemsFetched)}, limited to {@code
     * itemId}.
     */
    public void fetchPubsubItems(
            Account account,
            final Jid server,
            final String node,
            final String itemId,
            final OnPubsubItemsFetched callback) {
        if (account == null && server != null) {
            account = findAccountByJid(server);
        }
        if (account == null) {
            for (Account acc : getAccounts()) {
                if (acc.isOnlineAndConnected()) {
                    account = acc;
                    break;
                }
            }
        }
        if (account == null || !account.isOnlineAndConnected()) {
            if (callback != null) {
                callback.onPubsubItemsFetchFailed();
            }
            return;
        }
        final Iq request = getIqGenerator().retrievePubsubItems(server, node);
        if (itemId != null) {
            final Element items = request.findChild("pubsub", Namespace.PUBSUB).findChild("items");
            items.addChild("item").setAttribute("id", itemId);
        }
        sendIqPacket(
                account,
                request,
                response -> {
                    final Element pubsub =
                            response.getType() == Iq.Type.RESULT
                                    ? response.findChild("pubsub", Namespace.PUBSUB)
                                    : null;
                    if (callback == null) {
                        return;
                    }
                    if (pubsub != null) {
                        callback.onPubsubItemsFetched(pubsub);
                    } else {
                        callback.onPubsubItemsFetchFailed();
                    }
                });
    }

    public interface OnPubsubItemsFetched {
        void onPubsubItemsFetched(Element pubsub);

        void onPubsubItemsFetchFailed();
    }

    public void publishPost(
            final Account account,
            final String title,
            final String content,
            final String attachmentUrl,
            final String attachmentType,
            final String postId,
            final String linkUrl,
            final OnPostPublished callback) {
        publishPost(
                account,
                title,
                content,
                attachmentUrl,
                attachmentType,
                postId,
                linkUrl,
                0,
                callback);
    }

    /**
     * @param publishedMillis original publication time of the edited post, 0 for a new post
     */
    public void publishPost(
            final Account account,
            final String title,
            final String content,
            final String attachmentUrl,
            final String attachmentType,
            final String postId,
            final String linkUrl,
            final long publishedMillis,
            final OnPostPublished callback) {
        if (account == null) {
            if (callback != null) {
                callback.onPostPublishFailed();
            }
            return;
        }

        final boolean isEdit = postId != null;
        final String idToPublish = isEdit ? postId : UUID.randomUUID().toString();

        final Runnable publicationRunnable =
                () -> {
                    final Iq request =
                            getIqGenerator()
                                    .publishPost(
                                            account,
                                            title,
                                            content,
                                            attachmentUrl,
                                            attachmentType,
                                            idToPublish,
                                            linkUrl,
                                            isEdit ? publishedMillis : 0);
                    sendIqPacket(
                            account,
                            request,
                            response2 -> {
                                if (response2.getType() == Iq.Type.RESULT) {
                                    if (!isEdit) {
                                        // The comments node gets the post's audience; an open
                                        // comments node would
                                        // let anyone read the discussion of a contacts-only post.
                                        createOwnCommentsNode(account, idToPublish, null);
                                    }
                                    if (callback != null) {
                                        callback.onPostPublished();
                                    }
                                } else {
                                    Log.e(
                                            Config.LOGTAG,
                                            "Could not publish post. Server responded with: "
                                                    + response2);
                                    if (callback != null) {
                                        callback.onPostPublishFailed();
                                    }
                                }
                            });
                };

        if (!isEdit) {
            // Whether or not this worked, publish: an existing node is the normal case, and
            // otherwise the server auto-creates one with its defaults.
            createNodeWithFallback(
                    account,
                    getIqGenerator()::createSocialFeedNode,
                    Namespace.MICROBLOG,
                    created -> publicationRunnable.run());
        } else {
            publicationRunnable.run();
        }
    }

    public void publishComment(
            final Account account,
            final String nodeUri,
            final String title,
            final OnPostPublished callback) {
        if (account == null) {
            if (callback != null) {
                callback.onPostPublishFailed();
            }
            return;
        }
        final Jid to;
        final String targetNode;
        try {
            final eu.siacs.conversations.utils.XmppUri uri =
                    new eu.siacs.conversations.utils.XmppUri(nodeUri);
            to = uri.getJid();
            targetNode = uri.getParameter("node");
        } catch (Exception e) {
            Log.e(Config.LOGTAG, "Invalid URI in publishComment: " + nodeUri, e);
            if (callback != null) {
                callback.onPostPublishFailed();
            }
            return;
        }

        if (to == null || targetNode == null) {
            Log.e(Config.LOGTAG, "Could not determine target for publishing comment");
            if (callback != null) {
                callback.onPostPublishFailed();
            }
            return;
        }

        final Consumer<Boolean> publishRunnable =
                ignored -> {
                    final Iq publishRequest =
                            getIqGenerator().publishComment(account, targetNode, title);
                    publishRequest.setTo(to);
                    sendIqPacket(
                            account,
                            publishRequest,
                            publishResponse -> {
                                if (publishResponse.getType() == Iq.Type.RESULT) {
                                    if (callback != null) {
                                        callback.onPostPublished();
                                    }
                                } else {
                                    Log.e(
                                            Config.LOGTAG,
                                            "Could not publish comment. Server responded with: "
                                                    + publishResponse);
                                    if (callback != null) {
                                        callback.onPostPublishFailed();
                                    }
                                }
                            });
                };
        // Only a comments node of our own post is ours to (re)create, e.g. if that failed when
        // the post was published. Someone else's node is theirs; we just publish to it.
        final String postId =
                targetNode.startsWith(COMMENTS_NODE_PREFIX)
                        ? targetNode.substring(COMMENTS_NODE_PREFIX.length())
                        : null;
        if (postId != null
                && !postId.isEmpty()
                && to.asBareJid().equals(account.getJid().asBareJid())) {
            createOwnCommentsNode(account, postId, publishRunnable);
        } else {
            publishRunnable.accept(true);
        }
    }

    /** The comments node of our post {@code postId}, readable by the post's audience. */
    private void createOwnCommentsNode(
            final Account account, final String postId, final Consumer<Boolean> done) {
        fetchFeedAccess(
                account,
                access ->
                        createNodeWithFallback(
                                account,
                                maxItems ->
                                        getIqGenerator()
                                                .createCommentsNode(
                                                        postId,
                                                        access.model,
                                                        access.rosterGroups,
                                                        maxItems),
                                COMMENTS_NODE_PREFIX + postId,
                                created -> {
                                    if (done != null) {
                                        done.accept(created);
                                    }
                                }));
    }

    /**
     * Sends the node creation from {@code request} with max_items "max" (XEP-0060/XEP-0472). A
     * server that predates that keyword rejects the whole configuration form, and the node would
     * then be auto-created on publish with the server's default, possibly tiny, item limit; so any
     * error other than conflict (node exists) is retried once with a number. {@code done} gets
     * whether the node exists afterwards.
     */
    private void createNodeWithFallback(
            final Account account,
            final java.util.function.Function<String, Iq> request,
            final String node,
            final Consumer<Boolean> done) {
        sendIqPacket(
                account,
                request.apply(IqGenerator.MAX_ITEMS),
                response -> {
                    if (nodeExistsAfter(response)) {
                        done.accept(true);
                        return;
                    }
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": creating "
                                    + node
                                    + " with max_items=max failed, retrying with "
                                    + IqGenerator.FALLBACK_MAX_ITEMS
                                    + ": "
                                    + response);
                    sendIqPacket(
                            account,
                            request.apply(IqGenerator.FALLBACK_MAX_ITEMS),
                            retry -> {
                                final boolean exists = nodeExistsAfter(retry);
                                if (!exists) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": could not create "
                                                    + node
                                                    + ": "
                                                    + retry);
                                }
                                done.accept(exists);
                            });
                });
    }

    private static boolean nodeExistsAfter(final Iq response) {
        if (response.getType() == Iq.Type.RESULT) {
            return true;
        }
        final Element error = response.findChild("error");
        return error != null && error.hasChild("conflict");
    }

    public void retractPost(
            final Account account,
            final String node,
            final String id,
            final OnPostRetracted callback) {
        if (account == null) {
            if (callback != null) {
                callback.onPostRetractionFailed();
            }
            return;
        }
        final Iq request = getIqGenerator().retractPost(node, id);
        sendIqPacket(
                account,
                request,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        if (callback != null) {
                            callback.onPostRetracted(id);
                        }
                    } else {
                        if (callback != null) {
                            callback.onPostRetractionFailed();
                        }
                    }
                });
    }

    public void retractPost(
            final Account account,
            final Jid to,
            final String node,
            final String id,
            final OnPostRetracted callback) {
        final Iq packet = getIqGenerator().retractPost(node, id);
        packet.setTo(to);
        this.sendIqPacket(
                account,
                packet,
                response -> {
                    if (response.getType() == Iq.Type.RESULT) {
                        if (callback != null) {
                            callback.onPostRetracted(id);
                        }
                    } else {
                        if (callback != null) {
                            callback.onPostRetractionFailed();
                        }
                    }
                });
    }

    public interface OnPostPublished {
        void onPostPublished();

        void onPostPublishFailed();
    }

    public interface OnPostReceived {
        void onPostReceived(Post post);
    }

    public interface OnPostRetracted {
        void onPostRetracted(String postId);

        void onPostRetractionFailed();

        /** A post of {@code author} was retracted (ids are only unique per author). */
        default void onPostRetracted(Jid author, String postId) {
            onPostRetracted(postId);
        }
    }

    private final Set<OnPostReceived> mOnPostReceivedListeners =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<OnPostRetracted> mOnPostRetractedListeners =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public void addOnPostReceivedListener(OnPostReceived listener) {
        synchronized (LISTENER_LOCK) {
            mOnPostReceivedListeners.add(listener);
        }
    }

    public void removeOnPostReceivedListener(OnPostReceived listener) {
        synchronized (LISTENER_LOCK) {
            mOnPostReceivedListeners.remove(listener);
        }
    }

    public void addOnPostRetractedListener(OnPostRetracted listener) {
        synchronized (LISTENER_LOCK) {
            mOnPostRetractedListeners.add(listener);
        }
    }

    public void removeOnPostRetractedListener(OnPostRetracted listener) {
        synchronized (LISTENER_LOCK) {
            mOnPostRetractedListeners.remove(listener);
        }
    }

    public void onPostReceived(Post post, Account account) {
        if (post == null || account == null) {
            return;
        }
        if (!databaseBackend.createPost(post, account)) {
            return;
        }
        for (OnPostReceived listener : threadSafeList(mOnPostReceivedListeners)) {
            listener.onPostReceived(post);
        }
    }

    /**
     * A retraction on {@code publisher}'s microblog {@code node}. A post is only removed if it is
     * that publisher's own (ids are publisher-chosen). A retraction on a comments node is passed on
     * to the listeners (the comments view removes the comment) - unless the id is one of our stored
     * posts, so a comment retraction can't be used to hide someone else's post.
     */
    public void onPostRetracted(final Jid publisher, final String node, final String postId) {
        if (publisher == null || postId == null) {
            return;
        }
        final boolean commentsNode =
                node != null && node.startsWith("urn:xmpp:microblog:0:comments");
        if (commentsNode) {
            // Goes only to the comment listeners, which check the node against the post.
            notifyOnCommentRetracted(publisher, node, postId);
            return;
        } else if (!databaseBackend.deletePost(postId, publisher.asBareJid())) {
            return;
        }
        notifyPostRetracted(publisher.asBareJid(), postId);
    }

    private void notifyPostRetracted(final Jid author, final String postId) {
        for (OnPostRetracted listener : threadSafeList(mOnPostRetractedListeners)) {
            listener.onPostRetracted(author, postId);
        }
    }

    /** Who may read the own microblog, as mirrored onto comments nodes. */
    private static final class FeedAccess {
        final String model;
        final List<String> rosterGroups;

        FeedAccess(final String model, final List<String> rosterGroups) {
            this.model = model;
            this.rosterGroups = rosterGroups;
        }
    }

    private static final String COMMENTS_NODE_PREFIX = Namespace.MICROBLOG + ":comments/";

    /**
     * The access model of the own microblog node, mapped to one that comments nodes can use. Falls
     * back to "presence" (contacts only) if it can't be read, so comments are never more public
     * than intended.
     */
    private void fetchFeedAccess(final Account account, final Consumer<FeedAccess> callback) {
        final Jid owner = account.getJid().asBareJid();
        sendIqPacket(
                account,
                mIqGenerator.requestPubsubConfiguration(owner, Namespace.MICROBLOG),
                response -> {
                    String model = null;
                    List<String> groups = java.util.Collections.emptyList();
                    if (response.getType() == Iq.Type.RESULT) {
                        final Element pubsub =
                                response.findChild(
                                        "pubsub", "http://jabber.org/protocol/pubsub#owner");
                        final Element configure =
                                pubsub == null ? null : pubsub.findChild("configure");
                        final Data data =
                                configure == null
                                        ? null
                                        : Data.parse(configure.findChild("x", Namespace.DATA));
                        if (data != null) {
                            model = data.getValue("pubsub#access_model");
                            final eu.siacs.conversations.xmpp.forms.Field field =
                                    data.getFieldByName("pubsub#roster_groups_allowed");
                            if (field != null) {
                                groups = field.getValues();
                            }
                        }
                    }
                    final String mapped;
                    if (model == null) {
                        mapped = "presence";
                    } else {
                        switch (model) {
                            case "open":
                            case "presence":
                            case "whitelist":
                            case "authorize":
                                mapped = model;
                                break;
                            case "roster":
                                mapped = groups.isEmpty() ? "presence" : "roster";
                                break;
                            default:
                                mapped = "presence";
                        }
                    }
                    callback.accept(new FeedAccess(mapped, groups));
                });
    }

    /**
     * Comments nodes used to be created open regardless of the post's audience. Once per account,
     * give the existing ones on our PEP service the access model of the microblog.
     */
    private void fixCommentsNodesAccessOnce(final Account account) {
        final String key = "comments_nodes_access_fixed_" + account.getUuid();
        if (getPreferences().getBoolean(key, false)) {
            return;
        }
        final Jid owner = account.getJid().asBareJid();
        fetchFeedAccess(
                account,
                access ->
                        sendIqPacket(
                                account,
                                mIqGenerator.queryDiscoItems(owner),
                                response -> {
                                    if (response.getType() != Iq.Type.RESULT) {
                                        return;
                                    }
                                    final Element query =
                                            response.findChild("query", Namespace.DISCO_ITEMS);
                                    if (query == null) {
                                        return;
                                    }
                                    int count = 0;
                                    for (final Element item : query.getChildren()) {
                                        final String node = item.getAttribute("node");
                                        if (!"item".equals(item.getName())
                                                || node == null
                                                || !node.startsWith(COMMENTS_NODE_PREFIX)) {
                                            continue;
                                        }
                                        // Only nodes on our own PEP service are ours to
                                        // reconfigure.
                                        final String jidAttribute = item.getAttribute("jid");
                                        final Jid jid =
                                                jidAttribute == null
                                                        ? null
                                                        : Jid.Invalid.getNullForInvalid(
                                                                Jid.ofOrInvalid(jidAttribute));
                                        if (jid == null || !jid.asBareJid().equals(owner)) {
                                            continue;
                                        }
                                        count++;
                                        setCommentsNodeAccess(account, owner, node, access);
                                    }
                                    Log.d(
                                            Config.LOGTAG,
                                            owner
                                                    + ": aligned access model of "
                                                    + count
                                                    + " comments nodes with the microblog ("
                                                    + access.model
                                                    + ")");
                                    getPreferences().edit().putBoolean(key, true).apply();
                                }));
    }

    private void setCommentsNodeAccess(
            final Account account, final Jid owner, final String node, final FeedAccess access) {
        sendIqPacket(
                account,
                mIqGenerator.requestPubsubConfiguration(owner, node),
                response -> {
                    if (response.getType() != Iq.Type.RESULT) {
                        return;
                    }
                    final Element pubsub =
                            response.findChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
                    final Element configure = pubsub == null ? null : pubsub.findChild("configure");
                    final Data data =
                            configure == null
                                    ? null
                                    : Data.parse(configure.findChild("x", Namespace.DATA));
                    if (data == null || access.model.equals(data.getValue("pubsub#access_model"))) {
                        return;
                    }
                    data.put("pubsub#access_model", access.model);
                    if ("roster".equals(access.model)) {
                        data.put("pubsub#roster_groups_allowed", access.rosterGroups);
                    }
                    data.submit();
                    sendIqPacket(
                            account,
                            mIqGenerator.publishPubsubConfiguration(owner, node, data),
                            result -> {
                                if (result.getType() != Iq.Type.RESULT) {
                                    Log.d(
                                            Config.LOGTAG,
                                            owner
                                                    + ": could not change access model of "
                                                    + node
                                                    + ": "
                                                    + result);
                                }
                            });
                });
    }

    private static final int MAX_POST_CHECKS_PER_FETCH = 50;

    /**
     * Drops stored posts of {@code author} that were deleted while we didn't get the retraction
     * (offline, or a node without notify_retract). {@code listedIds} are the item ids a fetch of
     * the feed returned. A listing may be paged or truncated by the server, so a stored post
     * missing from it is asked for individually and only removed once the server confirms it is
     * gone (the item, or the whole node, not found).
     */
    public void removeVanishedPosts(
            final Account account, final Jid author, final Set<String> listedIds) {
        final Jid owner = author.asBareJid();
        final Set<String> missing = databaseBackend.getPostIds(owner);
        missing.removeAll(listedIds);
        int checks = 0;
        for (final String postId : missing) {
            if (++checks > MAX_POST_CHECKS_PER_FETCH) {
                break;
            }
            final Iq request = getIqGenerator().retrievePubsubItems(owner, Namespace.MICROBLOG);
            request.findChild("pubsub", Namespace.PUBSUB)
                    .findChild("items")
                    .addChild("item")
                    .setAttribute("id", postId);
            sendIqPacket(
                    account,
                    request,
                    response -> {
                        final boolean gone;
                        if (response.getType() == Iq.Type.RESULT) {
                            final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                            final Element items = pubsub == null ? null : pubsub.findChild("items");
                            if (items == null) {
                                gone = false;
                            } else {
                                boolean found = false;
                                for (final Element item : items.getChildren()) {
                                    if ("item".equals(item.getName())
                                            && postId.equals(item.getAttribute("id"))) {
                                        found = true;
                                        break;
                                    }
                                }
                                gone = !found;
                            }
                        } else {
                            final Element error = response.findChild("error");
                            gone =
                                    response.getType() == Iq.Type.ERROR
                                            && error != null
                                            && error.hasChild("item-not-found");
                        }
                        if (gone && databaseBackend.deletePost(postId, owner)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": post "
                                            + postId
                                            + " of "
                                            + owner
                                            + " is gone");
                            notifyPostRetracted(owner, postId);
                        }
                    });
        }
    }

    public void subscribeTo(
            final Account account, final Jid to, final String node, final Consumer<Iq> callback) {
        final Iq iq =
                getIqGenerator()
                        .generateSubscriptionIq(to.asBareJid(), node, account.getJid().asBareJid());
        sendIqPacket(account, iq, callback);
    }

    public void unsubscribeFrom(
            final Account account, final Jid to, final String node, final Consumer<Iq> callback) {
        final Iq iq =
                getIqGenerator()
                        .generateUnsubscriptionIq(
                                to.asBareJid(), node, account.getJid().asBareJid());
        sendIqPacket(account, iq, callback);
    }

    /**
     * Events of a comments node. {@code service} and {@code node} identify the node; listeners must
     * compare both with the post's comments link, since the node name (and the item ids) are chosen
     * by whoever creates them.
     */
    public interface OnCommentReceived {
        void onCommentReceived(Jid service, String node, Comment comment);

        default void onCommentRetracted(Jid service, String node, String commentId) {}
    }

    private final List<OnCommentReceived> mOnCommentReceivedListeners = new ArrayList<>();

    public void addOnCommentReceivedListener(OnCommentReceived listener) {
        synchronized (mOnCommentReceivedListeners) {
            if (!mOnCommentReceivedListeners.contains(listener)) {
                mOnCommentReceivedListeners.add(listener);
            }
        }
    }

    public void removeOnCommentReceivedListener(OnCommentReceived listener) {
        synchronized (mOnCommentReceivedListeners) {
            mOnCommentReceivedListeners.remove(listener);
        }
    }

    private void notifyOnCommentRetracted(
            final Jid service, final String node, final String commentId) {
        synchronized (mOnCommentReceivedListeners) {
            for (OnCommentReceived listener : mOnCommentReceivedListeners) {
                try {
                    listener.onCommentRetracted(service, node, commentId);
                } catch (Exception e) {
                    Log.d(Config.LOGTAG, "safe to ignore, listener has been removed");
                }
            }
        }
    }

    public void notifyOnCommentReceived(
            final Jid service, final String node, final Comment comment) {
        synchronized (mOnCommentReceivedListeners) {
            for (OnCommentReceived listener : mOnCommentReceivedListeners) {
                try {
                    listener.onCommentReceived(service, node, comment);
                } catch (Exception e) {
                    Log.d(Config.LOGTAG, "safe to ignore, listener has been removed");
                }
            }
        }
    }
}
