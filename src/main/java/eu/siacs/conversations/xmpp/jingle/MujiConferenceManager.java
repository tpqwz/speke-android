package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.stanzas.Reason;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * XEP-0272 Muji - coordinates group calls on top of the existing 1:1 {@link JingleRtpConnection}
 * machinery. A conference is a MUC: we announce our {@code <muji>} presence, watch other occupants'
 * {@code <muji>} presence, run a deterministic glare tie-break, and establish one per-pair Jingle
 * RTP session (full mesh) with each ready participant.
 *
 * <p>Wire-compatible with the monocles desktop client. The per-pair sessions carry {@code <muji
 * room>} and skip JMI (see {@link JingleConnectionManager#initializeMujiSession} / {@link
 * JingleConnectionManager#createMujiResponder}). The monocles hardening on top of XEP-0272 is
 * documented in {@code docs/xep-0272-muji-monocles.xml}.
 */
public class MujiConferenceManager {

    /** A leg connecting longer than this without reaching CONNECTED is retired + retried. */
    private static final long STUCK_LEG_MILLIS = 12_000L;

    private final JingleConnectionManager jingleManager;
    private final XmppConnectionService service;

    /** Active conferences, keyed by {@link #key(Account, String)}. */
    private final Map<String, Conference> conferences = new ConcurrentHashMap<>();

    /**
     * Latest observed Muji presence per occupant, keyed by conference key -> occupant JID. Tracked
     * independently of {@link #conferences} so a call joined after peers announced still sees who
     * is already ready.
     */
    private final Map<String, Map<String, PeerState>> mujiSeen = new ConcurrentHashMap<>();

    /** Conference keys for which we've shown a "join group call" invite (we're not in the call). */
    private final Set<String> invited = ConcurrentHashMap.newKeySet();

    /** A peer's observed Muji presence: readiness + their real full JID for direct addressing. */
    private static final class PeerState {
        private final Muji.State state;
        private final String
                realJid; // user@host/resource, as vouched for by the (non-anonymous) MUC
        private final Integer deviceId;

        private PeerState(final Muji.State state, final String realJid, final Integer deviceId) {
            this.state = state;
            this.realJid = realJid;
            this.deviceId = deviceId;
        }
    }

    MujiConferenceManager(final JingleConnectionManager jingleManager) {
        this.jingleManager = jingleManager;
        this.service = jingleManager.getXmppConnectionService();
    }

    private static String key(final Account account, final String room) {
        return account.getJid().asBareJid().toString() + " " + room;
    }

    public boolean isParticipating(final Account account, final String room) {
        return conferences.containsKey(key(account, room));
    }

    /** Whether we are in any group call — also while still waiting alone (no legs yet). */
    public boolean isInAnyGroupCall() {
        return !conferences.isEmpty();
    }

    private Set<Media> mediaFor(final boolean video) {
        return video ? ImmutableSet.of(Media.AUDIO, Media.VIDEO) : ImmutableSet.of(Media.AUDIO);
    }

    // --- entry points -----------------------------------------------------------------------

    /**
     * Start / join a Muji group call in the given (already-joined) MUC conversation.
     *
     * @return false if the call can't be placed here (not joined, not a private non-anonymous room,
     *     or the account is not on the clear net — ICE would leak the IP address otherwise)
     */
    public synchronized boolean placeGroupCall(final Conversation muc, final boolean video) {
        final Account account = muc.getAccount();
        final String room = muc.getJid().asBareJid().toString();
        final MucOptions mucOptions = muc.getMucOptions();
        final Jid ourOccupant = mucOptions.getSelf().getFullJid();
        if (ourOccupant == null || !mucOptions.online()) {
            Log.w(Config.LOGTAG, "muji: cannot place group call - not joined to " + room);
            return false;
        }
        if (!mucOptions.isPrivateAndNonAnonymous()) {
            // Real JIDs are required to address + authenticate the per-pair sessions.
            Log.w(Config.LOGTAG, "muji: refusing group call in anonymous/public room " + room);
            return false;
        }
        if (!jingleManager.isUsingClearNet(account)) {
            Log.w(Config.LOGTAG, "muji: refusing group call over Tor/I2P (ICE would leak the IP)");
            return false;
        }
        final String k = key(account, room);
        if (conferences.containsKey(k)) {
            // Already in this call (e.g. a second tap on "join"); replacing the conference would
            // orphan its legs and its re-mesh task.
            return true;
        }
        // The group call is PQ OMEMO2-verified only when the user placed it with the conversation's
        // encryption lock set to OMEMO2; an unencrypted lock yields an unverified call.
        final boolean verified = muc.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
        // for the call log: did we start this call, or join one that was already running?
        final Map<String, PeerState> seenAtJoin = mujiSeen.get(k);
        boolean joinedRunningCall = false;
        if (seenAtJoin != null) {
            for (final PeerState peer : seenAtJoin.values()) {
                if (peer.state == Muji.State.READY) {
                    joinedRunningCall = true;
                    break;
                }
            }
        }
        final Conference conference =
                new Conference(
                        account,
                        room,
                        ourOccupant.toString(),
                        video,
                        verified,
                        mediaFor(video),
                        new AppRTCAudioManager(service),
                        joinedRunningCall);
        conferences.put(k, conference);
        startAudio(conference);
        // the group chat now shows the "ongoing call" indicator
        service.updateConversationUi();
        // We're joining now -> clear any pending "join" invite notification for this room.
        if (invited.remove(k)) {
            service.getNotificationService().cancelGroupCallInvite(muc);
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": muji placeGroupCall room="
                        + room
                        + " verified="
                        + verified);
        // XEP-0272: announce <preparing/> then the ready content. Our codec set is fixed, so we
        // advertise readiness immediately. Include our OMEMO device ID for fingerprint encryption.
        final Integer deviceId = account.getAxolotlService().getOwnDeviceId();
        announce(conference, Muji.payload(false, video, deviceId));
        announce(conference, Muji.payload(true, video, deviceId));
        // XEP-0272: wait until the room has rebroadcast our <muji> before meshing — our leg
        // requests travel directly while our presence goes through the room, so a request could
        // overtake it and be refused ("not-a-participant"). onSelfPresence starts the mesh when
        // the reflection arrives; this is only the safety net if it never does.
        JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(
                () -> markAnnounced(account, room, true), 5, TimeUnit.SECONDS);
        // Periodically heal the mesh: drop legs that never connected (stuck / one-sided) or died
        // and re-initiate with ready peers we have no live leg to.
        conference.remeshFuture =
                JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.scheduleWithFixedDelay(
                        () -> {
                            try {
                                remesh(account, room);
                                ensureVideoOnLegs(account, room);
                            } catch (final Exception e) {
                                // Never let a throw cancel future ticks (scheduleWithFixedDelay).
                                Log.w(Config.LOGTAG, "muji remesh tick failed", e);
                            }
                        },
                        8,
                        8,
                        TimeUnit.SECONDS);
        return true;
    }

    /**
     * The {@code <muji>} element our MUC presence must carry while we're in this room's call, or
     * null. Every self-presence we send to the room (avatar/status refresh, nick change, rejoin)
     * must include it: per XEP-0272 a presence <em>without</em> {@code <muji>} means we left the
     * call, and peers tear their legs down. Deliberately lock-free (read-only on the concurrent
     * map) so presence senders can call it from any thread without lock-ordering concerns.
     */
    public Element currentPayload(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        if (conference == null) {
            return null;
        }
        return Muji.payload(true, conference.video, account.getAxolotlService().getOwnDeviceId());
    }

    /** Whether we send video in this room's group call. Lock-free (called from leg threads). */
    public boolean isSendingVideo(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        return conference != null && conference.video;
    }

    /**
     * The audio device the conference's shared audio manager currently routes to, or null when
     * we're not in that call. Lets the call screen show the speaker toggle before any leg exists.
     */
    public CallIntegration.AudioDevice getSelectedAudioDevice(
            final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        if (conference == null) {
            return null;
        }
        final var device = conference.audio.getSelectedAudioDevice();
        return device == null ? CallIntegration.AudioDevice.NONE : device;
    }

    /** The audio devices available to this room's group call (empty when we're not in it). */
    public Set<CallIntegration.AudioDevice> getAudioDevices(
            final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        return conference == null ? ImmutableSet.of() : conference.audio.getAudioDevices();
    }

    /**
     * Route the whole group call's audio to {@code device} (user choice from the call screen).
     *
     * @return false if we're not in that call
     */
    public boolean setAudioDevice(
            final Account account, final String room, final CallIntegration.AudioDevice device) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return false;
        }
        final AppRTCAudioManager audio = conference.audio;
        audio.executeOnMain(() -> {
            // the call may have been left (and its audio stopped) since: don't switch the
            // speakerphone on after the call ended
            if (conferences.get(k) == conference) {
                audio.setDefaultAudioDevice(device);
            }
        });
        return true;
    }

    /**
     * Upgrade our participation in an audio group call to video: advertise the new 'webcam' content
     * (XEP-0272 "Adding a Content Type": {@code <preparing/>} first, then the content), and bring
     * our camera onto every existing leg — a content-add where the leg has no video yet, a senders
     * content-modify where we so far only received the peer's video. Legs established later are set
     * up with video from the start.
     *
     * @return false if we're not in that call
     */
    public boolean upgradeToVideo(final Account account, final String room) {
        final List<JingleRtpConnection> legs;
        synchronized (this) {
            final Conference conference = conferences.get(key(account, room));
            if (conference == null) {
                return false;
            }
            if (!conference.video) {
                conference.video = true;
                conference.media = mediaFor(true);
                conference.videoEnabled = true;
                final Integer deviceId = account.getAxolotlService().getOwnDeviceId();
                announce(conference, Muji.payload(false, true, deviceId));
                announce(conference, Muji.payload(true, true, deviceId));
                // Like a 1:1 video call: switch to the loudspeaker unless a headset is in use.
                routeAudio(conference, CallIntegration.AudioDevice.SPEAKER_PHONE);
            }
            legs = new ArrayList<>(conference.members.values());
        }
        // Outside our lock: legs lock themselves, and a leg calls back into us when it ends.
        for (final JingleRtpConnection leg : legs) {
            leg.setVideoEnabled(true);
            leg.ensureMujiVideoSending();
        }
        // the ongoing-call notification now shows a video call
        refreshOngoingCall(account, room);
        return true;
    }

    /** Self-healing part of the periodic re-mesh: every connected leg carries our camera. */
    private void ensureVideoOnLegs(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        if (conference == null || !conference.video) {
            return;
        }
        for (final JingleRtpConnection leg : new ArrayList<>(conference.members.values())) {
            leg.ensureMujiVideoSending();
        }
    }

    /**
     * We hung up while others stay in the call: treat that like dismissing its invite, so their
     * next presence (status, avatar, …) doesn't ring us for the call we just left. Cleared by
     * {@link #updateInvite} once nobody advertises {@code <muji>} any more, so a new call in the
     * room rings again; rejoining via the call button stays possible.
     */
    private void suppressInviteAfterLeaving(final String k) {
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null && !seen.isEmpty()) {
            invited.add(k);
        }
    }

    /**
     * Leave a Muji group call: drop our {@code <muji>} presence, then end every per-pair session.
     */
    public synchronized void leaveGroupCall(final Account account, final String room) {
        final String k = key(account, room);
        final Conference conference = conferences.remove(k);
        if (conference == null) {
            return;
        }
        teardown(conference, true, null);
        suppressInviteAfterLeaving(k);
        // Do NOT clear mujiSeen here. We're leaving the *call*, not the MUC — the other
        // participants are still in the call and still advertising <muji>, but won't re-send that
        // presence just because we left. mujiSeen is kept current by MUC presence (a peer is
        // removed when it actually drops <muji>), so preserving it is what lets placeGroupCall
        // re-mesh with the still-running call when the user rejoins via the call button. Clearing
        // it made rejoin initiate to nobody (only the legs peers started came up).
    }

    /**
     * We are (re)joining the MUC: the room is about to replay every occupant's presence, so forget
     * what we saw before (participants who left while we were disconnected would otherwise linger
     * forever, keeping invites and the re-mesh alive).
     */
    public synchronized void onRoomJoining(final Conversation muc) {
        final String k = key(muc.getAccount(), muc.getJid().asBareJid().toString());
        mujiSeen.remove(k);
        if (invited.remove(k)) {
            service.getNotificationService().cancelGroupCallInvite(muc);
        }
    }

    /** We left (or are leaving) the MUC itself: leave its call and forget its participants. */
    public synchronized void onRoomLeft(final Conversation muc) {
        final Account account = muc.getAccount();
        final String room = muc.getJid().asBareJid().toString();
        final String k = key(account, room);
        final Conference conference = conferences.remove(k);
        if (conference != null) {
            // No presence: we are no longer an occupant, and a directed presence to the room
            // would re-join it.
            teardown(conference, false, null);
        }
        mujiSeen.remove(k);
        if (invited.remove(k)) {
            service.getNotificationService().cancelGroupCallInvite(muc);
        }
    }

    public synchronized void setMicrophoneEnabled(
            final Account account, final String room, final boolean enabled) {
        final Conference conference = conferences.get(key(account, room));
        if (conference != null) {
            conference.microphoneEnabled = enabled;
        }
    }

    /** Whether our microphone is on in this room's group call (true when we're not in it). */
    public boolean isMicrophoneEnabled(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        return conference == null || conference.microphoneEnabled;
    }

    /** Whether our camera is on in this room's group call (lock-free, for the call screen). */
    public boolean isVideoEnabled(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        return conference != null && conference.videoEnabled;
    }

    public synchronized void setVideoEnabled(
            final Account account, final String room, final boolean enabled) {
        final Conference conference = conferences.get(key(account, room));
        if (conference != null) {
            conference.videoEnabled = enabled;
        }
    }

    // --- presence coordination (called from PresenceParser) ----------------------------------

    /**
     * Observe an occupant's MUC presence for a {@code <muji>} payload. Tracks who is ready and,
     * when we're in the conference, meshes (via the tie-break) or drops the participant.
     *
     * @param unavailable the presence is of type {@code unavailable}
     * @param nickChange the unavailable presence carries status 303 (the occupant is only renaming)
     */
    public synchronized void onPresence(
            final Account account,
            final Jid occupantFull,
            final String realJid,
            final Muji.Advertisement advertisement,
            final boolean isSelf,
            final boolean unavailable,
            final boolean nickChange) {
        if (occupantFull == null || occupantFull.getResource() == null) {
            return; // occupant JIDs only
        }
        final String room = occupantFull.asBareJid().toString();
        final String occupant = occupantFull.toString();
        final String k = key(account, room);
        if (isSelf) {
            onSelfPresence(account, k, occupantFull, advertisement, unavailable, nickChange);
            return;
        }
        final Map<String, PeerState> seen =
                mujiSeen.computeIfAbsent(k, x -> new ConcurrentHashMap<>());
        final PeerState previous = seen.remove(occupant);
        final PeerState current =
                advertisement == null ? null : acceptPeer(account, realJid, advertisement);
        if (current != null) {
            seen.put(occupant, current);
        }
        final Conference conference = conferences.get(k);
        if (conference == null) {
            updateInvite(account, occupantFull, k, !seen.isEmpty());
            return;
        }
        // A real JID that no longer belongs to this occupant (left, renamed away, lost <muji>,
        // got blocked) loses its leg. A pure nick change keeps it: the leg is keyed by real JID
        // and the new-nick presence re-advertises the same peer.
        if (previous != null
                && !(unavailable && nickChange)
                && (current == null || !previous.realJid.equals(current.realJid))) {
            removeMember(conference, previous.realJid);
        }
        // The last participant left the call. If their leg ended before this presence arrived,
        // onSessionEnded kept the conference (they still looked present) — nothing else would
        // end it, and we'd stay in an empty call (audio mode, "busy" for 1:1 calls). Only once
        // someone was connected: a freshly started call keeps waiting for people.
        if (previous != null
                && current == null
                && !(unavailable && nickChange)
                && conference.members.isEmpty()
                && seen.isEmpty()
                && conference.connectedSince.get() > 0) {
            conferences.remove(k);
            teardown(conference, true, null);
            mujiSeen.remove(k);
            return;
        }
        if (current != null && current.state == Muji.State.READY) {
            maybeInitiate(conference, occupant, current);
        }
    }

    private void onSelfPresence(
            final Account account,
            final String k,
            final Jid occupantFull,
            final Muji.Advertisement advertisement,
            final boolean unavailable,
            final boolean nickChange) {
        final Conference conference = conferences.get(k);
        if (unavailable) {
            if (nickChange) {
                return; // the presence under our new nick follows
            }
            // We left the room, were kicked/banned, or the room went away: our call is over.
            final Conversation conversation = service.find(account, occupantFull.asBareJid());
            if (conversation != null) {
                onRoomLeft(conversation);
            } else if (conference != null) {
                conferences.remove(k);
                teardown(conference, false, null);
                mujiSeen.remove(k);
            }
            return;
        }
        if (conference == null) {
            return;
        }
        // Track our (possibly renamed) occupant JID — it is the tie-break input and the target
        // of our <muji> presence updates.
        conference.ourOccupant = occupantFull.toString();
        if (advertisement == null) {
            // The room reflected a presence of ours without <muji> (e.g. one sent by a code path
            // that didn't decorate it) — peers now consider us gone. Re-assert participation.
            Log.d(Config.LOGTAG, "muji: reflected self-presence lost <muji>, re-announcing");
            announce(conference, currentPayload(account, conference.room));
        } else if (advertisement.state == Muji.State.READY && !conference.announced) {
            markAnnounced(account, conference.room, false);
        }
    }

    /**
     * The room has rebroadcast our READY {@code <muji>} (or, with {@code timedOut}, it didn't
     * within a few seconds): other participants now know us, so start the legs we initiate.
     */
    private synchronized void markAnnounced(
            final Account account, final String room, final boolean timedOut) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null || conference.announced) {
            return;
        }
        conference.announced = true;
        if (timedOut) {
            Log.w(
                    Config.LOGTAG,
                    "muji: the room did not reflect our <muji> presence in time - meshing anyway");
        } else {
            Log.d(Config.LOGTAG, "muji: room confirmed our participation in " + room);
        }
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null) {
            for (final Map.Entry<String, PeerState> e : seen.entrySet()) {
                if (e.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, e.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * Validate a participant's advertisement. Only participants whose real full JID the
     * (non-anonymous) room vouched for are meshed with — XEP-0272 sessions are addressed to real
     * JIDs, and the real JID is what the OMEMO fingerprint encryption + the incoming-initiate
     * membership check are bound to. Blocked JIDs are ignored entirely.
     */
    private PeerState acceptPeer(
            final Account account, final String realJid, final Muji.Advertisement advertisement) {
        if (realJid == null || realJid.isEmpty()) {
            return null;
        }
        final Jid jid;
        try {
            jid = Jid.of(realJid);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (jid.isBareJid() || isBlocked(account, jid)) {
            return null;
        }
        if (jid.equals(account.getJid())) {
            return null; // ourselves under another occupant JID
        }
        return new PeerState(advertisement.state, jid.toString(), advertisement.deviceId);
    }

    private static boolean isBlocked(final Account account, final Jid jid) {
        return account.isBlocked(jid) || account.getBlocklist().contains(jid.getDomain());
    }

    private void updateInvite(
            final Account account,
            final Jid occupantFull,
            final String k,
            final boolean anyParticipant) {
        // We're not in this room's call. Surface / clear a one-tap "join" invite.
        if (anyParticipant && invited.add(k)) {
            final Conversation conversation = service.find(account, occupantFull.asBareJid());
            // A muted group must not ring: any occupant can toggle <muji> at will.
            if (conversation != null && !conversation.isMuted()) {
                service.getNotificationService()
                        .pushGroupCallInvite(conversation, occupantFull.getResource());
            }
        } else if (!anyParticipant && invited.remove(k)) {
            final Conversation conversation = service.find(account, occupantFull.asBareJid());
            if (conversation != null) {
                service.getNotificationService().cancelGroupCallInvite(conversation);
            }
        }
    }

    // --- incoming session-initiate (called from JingleConnectionManager.deliverPacket) -------

    /**
     * Handle an incoming Muji session-initiate (tagged {@code <muji room>}): if we're in that
     * conference, spin up the responder side and auto-accept (the user already joined the call);
     * otherwise terminate it (no incoming group-call ring in this version).
     */
    public synchronized void handleIncomingSessionInitiate(
            final Account account,
            final Iq packet,
            final AbstractJingleConnection.Id id,
            final Jid from,
            final String roomAttribute) {
        // The <muji room> attribute is the peer's spelling of the room JID; our conferences are
        // keyed by the normalized bare JID (bare JIDs are case-insensitive). A raw-string lookup
        // declined every leg from a client that spells the room differently (it then retried
        // forever — the call kept connecting and disconnecting).
        final String room;
        try {
            room = Jid.of(roomAttribute).asBareJid().toString();
        } catch (final IllegalArgumentException e) {
            jingleManager.sendSessionTerminateMuji(
                    account, packet, id, Reason.GENERAL_ERROR, "invalid-room");
            return;
        }
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": terminating muji session-initiate for a conference we're not in ("
                            + room
                            + ")");
            jingleManager.sendSessionTerminateMuji(
                    account, packet, id, Reason.DECLINE, "not-in-conference");
            return;
        }
        // Same privacy gate as 1:1 calls: never run ICE (which reveals our IP) over Tor/I2P.
        if (!jingleManager.isUsingClearNet(account) || isBlocked(account, from)) {
            jingleManager.sendSessionTerminateMuji(account, packet, id, Reason.DECLINE, "policy");
            return;
        }
        // SECURITY: only accept a Muji leg from someone who is actually a participant of this room
        // — an occupant who announced <muji> presence whose real JID the (non-anonymous) MUC
        // vouched for. Without this, any JID that learns we're in the call could send a
        // <muji room=…> session-initiate and inject unsolicited media (it auto-accepts, no ring).
        // Per XEP-0272 the initiate arrives from the peer's real full JID, so match `from` against
        // the real JID we recorded from its <muji> presence (the seen map is keyed by occupant).
        final Map<String, PeerState> seen = mujiSeen.get(k);
        PeerState peerState = null;
        if (seen != null) {
            for (final PeerState candidate : seen.values()) {
                if (from.toString().equals(candidate.realJid)) {
                    peerState = candidate;
                    break;
                }
            }
            if (peerState == null) {
                // The MUC may report ANOTHER session of the participant's account than the one now
                // calling us: a client restarted while its old session still lingers in the room,
                // or a multi-session nick (the room shows the first session's JID). Accept the
                // leg if exactly one participant has the caller's bare JID — the account the MUC
                // vouched for (and on a verified call the fingerprint's OMEMO device must still
                // match the advertised one). Re-address that participant to the calling session.
                Map.Entry<String, PeerState> sameAccount = null;
                int matches = 0;
                for (final Map.Entry<String, PeerState> candidate : seen.entrySet()) {
                    final Jid candidateJid = Jid.ofOrInvalid(candidate.getValue().realJid);
                    if (from.asBareJid().equals(candidateJid.asBareJid())) {
                        sameAccount = candidate;
                        matches++;
                    }
                }
                if (matches == 1) {
                    final PeerState stale = sameAccount.getValue();
                    peerState = new PeerState(stale.state, from.toString(), stale.deviceId);
                    seen.put(sameAccount.getKey(), peerState);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": muji participant "
                                    + sameAccount.getKey()
                                    + " now calls from "
                                    + from
                                    + " (room reported "
                                    + stale.realJid
                                    + ")");
                }
            }
        }
        // Any advertised <muji> state counts: a peer sends <preparing/>, then READY, then initiates
        // at once — and its initiate (direct) can overtake its READY presence (via the MUC).
        if (peerState == null) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": rejecting muji session-initiate from non-participant "
                            + from);
            jingleManager.sendSessionTerminateMuji(
                    account, packet, id, Reason.SECURITY_ERROR, "not-a-participant");
            return;
        }
        final String memberKey = from.toString();
        final JingleRtpConnection existing = conference.members.get(memberKey);
        if (existing != null) {
            final RtpEndUserState st = existing.getEndUserState();
            final boolean existingUp =
                    st == RtpEndUserState.CONNECTED || st == RtpEndUserState.RECONNECTING;
            // Crossed initiates (both sides started a leg — e.g. they disagree on the occupant-JID
            // tie-break because the server rewrote a nick): keep the leg with the smaller session
            // id. Both sides compare the same two ids, so they always keep the SAME leg; the
            // occupant-JID tie-break could make each side kill the other's leg, forever.
            final boolean keepExisting =
                    existingUp || existing.getId().sessionId.compareTo(id.sessionId) < 0;
            if (keepExisting) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": rejecting duplicate muji session-initiate from "
                                + from);
                jingleManager.sendSessionTerminateMuji(
                        account, packet, id, Reason.DECLINE, "duplicate");
                return;
            }
            // The peer restarted its (stuck) leg: retire ours before installing the new one.
            conference.members.remove(memberKey);
            conference.legStartedAt.remove(memberKey);
            endSession(existing);
        } else if (conference.members.size() >= Config.MUJI_MAX_PEERS) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": rejecting muji session-initiate from "
                            + from
                            + " - participant limit reached");
            jingleManager.sendSessionTerminateMuji(account, packet, id, Reason.BUSY, "limit");
            return;
        }
        final JingleRtpConnection rtpConnection =
                jingleManager.createMujiResponder(
                        id,
                        from,
                        room,
                        conference.verified,
                        peerState.realJid,
                        peerState.deviceId,
                        conference.audio);
        rtpConnection.setProposedMedia(conference.media);
        rtpConnection.setMicrophoneEnabled(conference.microphoneEnabled);
        rtpConnection.setVideoEnabled(conference.videoEnabled);
        conference.members.put(memberKey, rtpConnection);
        conference.legStartedAt.put(memberKey, System.currentTimeMillis());
        // receiveSessionInitiate auto-accepts (no ring) because the connection's mujiRoom is set.
        rtpConnection.deliverPacket(packet);
    }

    /**
     * A per-pair session ended (peer left, hang-up, or error). Drop the member; once the last
     * member is gone, retract our {@code <muji>} presence and forget the conference (we've left).
     * Called from {@link JingleRtpConnection#finish()}.
     */
    public synchronized void onSessionEnded(
            final Account account,
            final String room,
            final String peerAddr,
            final JingleRtpConnection self) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return;
        }
        // `peerAddr` is the leg's id.with — the peer's real full JID — i.e. the members-map key.
        // Only drop the entry if it is still THIS leg: a replaced (stale) leg finishing late must
        // not evict the live leg that superseded it.
        final JingleRtpConnection current = conference.members.get(peerAddr);
        if (current == self) {
            conference.members.remove(peerAddr, self);
            conference.legStartedAt.remove(peerAddr);
        } else if (current != null) {
            return;
        }
        // Only LEAVE if nobody is left in the room at all. If any peer is still present (its <muji>
        // hasn't gone), keep the conference: the re-mesh re-establishes a leg whose ICE failed or
        // never came up. (The user explicitly leaving goes through leaveGroupCall, not here.)
        // Like onPresence: only once someone was connected — a call nobody has joined yet (its
        // first joiner failed and left) keeps waiting.
        final Map<String, PeerState> seen = mujiSeen.get(k);
        final boolean anyPeerPresent = seen != null && !seen.isEmpty();
        if (conference.members.isEmpty()
                && !anyPeerPresent
                && conference.connectedSince.get() > 0) {
            conferences.remove(k);
            teardown(conference, true, self);
            mujiSeen.remove(k);
        }
    }

    /**
     * Periodic mesh reconciliation (every few seconds while in the call). Drops legs that died or
     * never reached {@code CONNECTED} (stuck / one-sided), then (re)initiates with every ready peer
     * we have no live leg to. This heals an incomplete mesh and re-establishes a leg after a
     * mid-call ICE failure — so one participant leaving (or a flaky leg) no longer drops the call
     * for the others.
     */
    public synchronized void remesh(final Account account, final String room) {
        final String k = key(account, room);
        final Conference conference = conferences.get(k);
        if (conference == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        // 1. Retire legs that ended or have been connecting too long (never answered / no ICE).
        final List<String> drop = new ArrayList<>();
        for (final Map.Entry<String, JingleRtpConnection> e : conference.members.entrySet()) {
            final RtpEndUserState st = e.getValue().getEndUserState();
            final boolean up =
                    st == RtpEndUserState.CONNECTED || st == RtpEndUserState.RECONNECTING;
            final Long since = conference.legStartedAt.get(e.getKey());
            final boolean stuck = !up && since != null && (now - since) > STUCK_LEG_MILLIS;
            if (st == RtpEndUserState.ENDED || st == RtpEndUserState.CONNECTIVITY_ERROR || stuck) {
                drop.add(e.getKey());
            }
        }
        for (final String memberKey : drop) {
            final JingleRtpConnection connection = conference.members.remove(memberKey);
            conference.legStartedAt.remove(memberKey);
            if (connection != null) {
                endSession(connection);
            }
            Log.d(
                    Config.LOGTAG,
                    "muji remesh: dropped stuck/ended leg " + memberKey + ", will retry");
        }
        // 2. (Re)initiate with every ready peer we now have no member for (maybeInitiate dedups +
        //    applies the JID tie-break, so it only initiates where we should).
        final Map<String, PeerState> seen = mujiSeen.get(k);
        if (seen != null) {
            for (final Map.Entry<String, PeerState> e : seen.entrySet()) {
                if (e.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, e.getKey(), e.getValue());
                }
            }
        }
    }

    private void cancelRemesh(final Conference conference) {
        if (conference.remeshFuture != null) {
            conference.remeshFuture.cancel(false);
            conference.remeshFuture = null;
        }
    }

    /**
     * All per-pair connections of the conference in `room` (for the participant-grid UI + to keep
     * the call alive when a leg ends). Reads the live connection registry rather than our `members`
     * bookkeeping (which a terminating leg mutates), so it's race-free.
     */
    public java.util.List<JingleRtpConnection> getConnections(
            final Account account, final String room) {
        return jingleManager.getMujiConnections(account, room);
    }

    // --- internals --------------------------------------------------------------------------

    /**
     * Tie-break, then either initiate a per-pair session with a ready peer or wait for theirs. Per
     * XEP-0272 the Jingle session is addressed to the peer's <em>real full JID</em> — IQ routing to
     * a MUC occupant JID is not guaranteed — so that is also the members-map key (the value
     * `id.with` takes on both ends). The occupant JID (`peerOccupant`) is used only for the
     * initiate/respond tie-break.
     */
    private void maybeInitiate(
            final Conference conference, final String peerOccupant, final PeerState peer) {
        if (!conference.announced) {
            return; // the room hasn't confirmed our participation yet (see markAnnounced)
        }
        final String peerAddr = peer.realJid;
        if (conference.members.containsKey(peerAddr)) {
            return; // already have a session with this peer
        }
        if (!Muji.shouldInitiate(conference.ourOccupant, peerOccupant)) {
            return; // the peer initiates; we wait for their session-initiate
        }
        if (conference.members.size() >= Config.MUJI_MAX_PEERS) {
            Log.w(
                    Config.LOGTAG,
                    "muji: not meshing with " + peerOccupant + " - participant limit reached");
            return;
        }
        try {
            final JingleRtpConnection rtpConnection =
                    jingleManager.initializeMujiSession(
                            conference.account,
                            Jid.of(peerAddr),
                            conference.room,
                            conference.verified,
                            conference.media,
                            peerAddr,
                            peer.deviceId,
                            conference.audio);
            rtpConnection.setMicrophoneEnabled(conference.microphoneEnabled);
            rtpConnection.setVideoEnabled(conference.videoEnabled);
            conference.members.put(peerAddr, rtpConnection);
            conference.legStartedAt.put(peerAddr, System.currentTimeMillis());
            Log.d(
                    Config.LOGTAG,
                    conference.account.getJid().asBareJid()
                            + ": muji initiated session with "
                            + peerOccupant
                            + " (addr="
                            + peerAddr
                            + ")");
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "muji: failed to initiate session with " + peerOccupant, e);
        }
    }

    /**
     * Drop a participant who left the conference: end their per-pair session if any. `memberKey` is
     * the peer's real full JID string (the members map key).
     */
    private void removeMember(final Conference conference, final String memberKey) {
        final JingleRtpConnection connection = conference.members.remove(memberKey);
        conference.legStartedAt.remove(memberKey);
        if (connection != null) {
            endSession(connection);
        }
    }

    /**
     * End a conference that has already been removed from {@link #conferences}: stop the re-mesh,
     * optionally retract our {@code <muji>} presence first (XEP-0272 ordering — reduces join/leave
     * races), then end every leg except `except` (which is finishing on its own).
     */
    private void teardown(
            final Conference conference,
            final boolean retractPresence,
            final JingleRtpConnection except) {
        cancelRemesh(conference);
        if (retractPresence) {
            announce(conference, null);
        }
        for (final JingleRtpConnection connection : conference.members.values()) {
            if (connection != except) {
                endSession(connection);
            }
        }
        conference.members.clear();
        conference.legStartedAt.clear();
        final AppRTCAudioManager audio = conference.audio;
        audio.executeOnMain(audio::stop);
        refreshOngoingCall(conference.account, conference.room);
        writeCallLog(conference);
        // the group chat's "ongoing call" indicator goes away
        service.updateConversationUi();
    }

    /** A leg of this room's call reached CONNECTED. Lock-free: called from the leg's callback. */
    void onLegConnected(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        if (conference != null) {
            conference.connectedSince.compareAndSet(0, System.currentTimeMillis());
        }
    }

    /**
     * One call-log entry per group call (the legs don't log: that was one entry per participant and
     * per re-mesh retry, retried responder legs showing as missed calls). A call nobody ever
     * connected to is logged as an unsuccessful outgoing call, never as a missed one.
     */
    private void writeCallLog(final Conference conference) {
        final Conversation conversation = service.find(conference.account, Jid.of(conference.room));
        if (conversation == null) {
            return;
        }
        final long connectedSince = conference.connectedSince.get();
        final boolean connected = connectedSince > 0;
        final Message message =
                new Message(
                        conversation,
                        connected && conference.joinedRunningCall
                                ? Message.STATUS_RECEIVED
                                : Message.STATUS_SEND,
                        Message.TYPE_RTP_SESSION,
                        java.util.UUID.randomUUID().toString());
        message.setTime(conference.startedAt); // like 1:1 calls: listed at the call's start
        message.setBody(
                new RtpSessionStatus(
                                connected,
                                connected ? System.currentTimeMillis() - connectedSince : 0)
                        .toString());
        conversation.add(message);
        service.createMessageAsync(message);
        service.updateCallLogUi();
    }

    /**
     * Show one ongoing-call notification (and call foreground service) for the whole group call,
     * named after the group: as long as any leg is live it stays, whichever legs come and go.
     * Lock-free: called from inside a leg's synchronized state transition.
     */
    void refreshOngoingCall(final Account account, final String room) {
        final Conference conference = conferences.get(key(account, room));
        JingleRtpConnection live = null;
        if (conference != null) {
            for (final JingleRtpConnection leg : conference.members.values()) {
                final AbstractJingleConnection.State state = leg.getState();
                if (state == AbstractJingleConnection.State.SESSION_ACCEPTED) {
                    live = leg;
                    break;
                }
                if (live == null
                        && JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(state)) {
                    live = leg;
                }
            }
        }
        if (live != null) {
            service.setOngoingCall(live.getId(), conference.media, false, room);
        } else if (service.isOngoingGroupCall(account, room)) {
            service.removeOngoingCall();
        }
    }

    /**
     * Start the conference's shared audio manager (audio mode, focus, headset/bluetooth detection)
     * once for the whole call, pick the initial route (earpiece for audio, speaker for video —
     * unless a headset/bluetooth device is connected) and fan its device changes out to every leg,
     * so whichever leg the call screen is bound to shows the real state.
     */
    private void startAudio(final Conference conference) {
        final AppRTCAudioManager audio = conference.audio;
        audio.setAudioManagerEvents((selected, available) -> {
            if (conference.members.isEmpty()) {
                // no leg to forward through yet: tell the call screen directly
                service.notifyJingleRtpConnectionUpdate(selected, available);
                return;
            }
            for (final JingleRtpConnection leg : conference.members.values()) {
                leg.getCallIntegration().onSharedAudioDeviceChanged(selected, available);
            }
        });
        audio.executeOnMain(() -> {
            audio.start();
            final var initial = CallIntegration.initialAudioDevice(conference.media);
            final var available = audio.getAudioDevices();
            if (available.contains(initial)
                    && !available.contains(CallIntegration.AudioDevice.BLUETOOTH)
                    && !available.contains(CallIntegration.AudioDevice.WIRED_HEADSET)) {
                audio.setDefaultAudioDevice(initial);
            }
        });
    }

    private void routeAudio(final Conference conference, final CallIntegration.AudioDevice device) {
        final AppRTCAudioManager audio = conference.audio;
        audio.executeOnMain(() -> {
            final var available = audio.getAudioDevices();
            if (available.contains(device)
                    && !available.contains(CallIntegration.AudioDevice.BLUETOOTH)
                    && !available.contains(CallIntegration.AudioDevice.WIRED_HEADSET)) {
                audio.setDefaultAudioDevice(device);
            }
        });
    }

    private void endSession(final JingleRtpConnection connection) {
        try {
            connection.endCall();
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "muji: failed to end session", e);
        }
    }

    /**
     * Build + send a directed MUC presence to our occupant JID carrying an optional {@code <muji>}.
     */
    private void announce(final Conference conference, final Element muji) {
        final Account account = conference.account;
        // Address our CURRENT occupant JID: a presence to a stale nick would be a rename request
        // and silently undo a nick change made during the call.
        Jid ourOccupant = null;
        boolean nonAnonymous = true;
        final Conversation conversation = service.find(account, Jid.of(conference.room));
        if (conversation != null) {
            final MucOptions mucOptions = conversation.getMucOptions();
            if (!mucOptions.online()) {
                return; // not an occupant (any more): a directed presence would (re)join
            }
            ourOccupant = mucOptions.getSelf().getFullJid();
            nonAnonymous = mucOptions.nonanonymous();
        }
        if (ourOccupant == null) {
            ourOccupant = Jid.of(conference.ourOccupant);
        }
        final var presence =
                service.getPresenceGenerator()
                        .selfPresence(
                                account,
                                Presence.Status.ONLINE,
                                nonAnonymous,
                                ourOccupant.getResource());
        presence.setTo(ourOccupant);
        if (muji != null) {
            presence.addChild(muji);
        }
        service.sendPresencePacket(account, presence);
    }

    private static final class Conference {
        private final Account account;
        private final String room; // bare room JID

        /** Our current occupant JID (room/nick); follows nick changes. Tie-break input. */
        private volatile String ourOccupant;

        /** The room has rebroadcast our READY {@code <muji>}: we may start legs (XEP-0272). */
        private volatile boolean announced = false;

        /** Whether we send video in this call (from the start, or after upgrading). */
        private volatile boolean video;

        /** Whether this group call must be PQ OMEMO2-verified (placed with OMEMO2 lock active). */
        private final boolean verified;

        /** The media we send — new legs are set up with it. */
        private volatile Set<Media> media;

        /** Remote participant (real full JID) -> its per-pair Jingle session. */
        private final Map<String, JingleRtpConnection> members = new ConcurrentHashMap<>();

        /** Member key -> when its current leg (re)started connecting (millis), for the re-mesh. */
        private final Map<String, Long> legStartedAt = new ConcurrentHashMap<>();

        /** The periodic re-mesh task, cancelled when the conference ends. */
        private ScheduledFuture<?> remeshFuture;

        /** Audio routing for the whole call (one route — every leg plays through it). */
        private final AppRTCAudioManager audio;

        // volatile: also read lock-free by the call screen
        /** For the call log's timestamp: when we started / joined the call. */
        private final long startedAt = System.currentTimeMillis();

        /** For the call log: we joined a call others were already in (vs. started it). */
        private final boolean joinedRunningCall;

        /** When the first leg reached CONNECTED (0 = none yet), for the call-log duration. */
        private final AtomicLong connectedSince = new AtomicLong(0);

        private volatile boolean microphoneEnabled = true;
        private volatile boolean videoEnabled = true;

        private Conference(
                final Account account,
                final String room,
                final String ourOccupant,
                final boolean video,
                final boolean verified,
                final Set<Media> media,
                final AppRTCAudioManager audio,
                final boolean joinedRunningCall) {
            this.account = account;
            this.room = room;
            this.ourOccupant = ourOccupant;
            this.video = video;
            this.verified = verified;
            this.media = media;
            this.audio = audio;
            this.joinedRunningCall = joinedRunningCall;
        }
    }
}
