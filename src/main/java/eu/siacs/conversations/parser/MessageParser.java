package eu.siacs.conversations.parser;

import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.util.Log;
import android.util.Pair;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import de.monocles.chat.BobTransfer;
import de.monocles.chat.WebxdcUpdate;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OtrService;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.BrokenSessionException;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.NotEncryptedForThisDeviceException;
import eu.siacs.conversations.crypto.axolotl.OutdatedSenderException;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Comment;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Post;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.entities.ReadByMarker;
import eu.siacs.conversations.entities.ReceiptRequest;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.entities.Story;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xmpp.pep.UserTune;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.axolotl.Encrypted;
import im.conversations.android.xmpp.model.carbons.Received;
import im.conversations.android.xmpp.model.carbons.Sent;
import im.conversations.android.xmpp.model.forward.Forwarded;
import im.conversations.android.xmpp.model.markers.Displayed;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.reactions.Reactions;
import io.ipfs.cid.Cid;
import java.io.File;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.java.otr4j.session.Session;
import net.java.otr4j.session.SessionStatus;

public class MessageParser extends AbstractParser
        implements Consumer<im.conversations.android.xmpp.model.stanza.Message> {

    private static final List<String> CLIENTS_SENDING_HTML_IN_OTR =
            Arrays.asList("Pidgin", "Adium", "Trillian");

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);

    private static final List<String> JINGLE_MESSAGE_ELEMENT_NAMES =
            Arrays.asList("accept", "propose", "proceed", "reject", "retract", "ringing", "finish");

    /** Upper bound on an inline Bits-of-Binary (XEP-0231) payload we will decode and store. */
    private static final int MAX_INLINE_BOB_BYTES = 1024 * 1024; // 1 MiB

    public MessageParser(final XmppConnectionService service, final Account account) {
        super(service, account);
    }

    private static String extractStanzaId(
            Element packet, boolean isTypeGroupChat, Conversation conversation) {
        final Jid by;
        final boolean safeToExtract;
        if (isTypeGroupChat) {
            by = conversation.getJid().asBareJid();
            safeToExtract = conversation.getMucOptions().hasFeature(Namespace.STANZA_IDS);
        } else {
            Account account = conversation.getAccount();
            by = account.getJid().asBareJid();
            safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        }
        return safeToExtract ? extractStanzaId(packet, by) : null;
    }

    private static String extractStanzaId(Account account, Element packet) {
        final boolean safeToExtract = account.getXmppConnection().getFeatures().stanzaIds();
        return safeToExtract ? extractStanzaId(packet, account.getJid().asBareJid()) : null;
    }

    /**
     * The stanza-id assigned by {@code by}, only if exactly one is present. A second one with the
     * same {@code by} means something (a sender, or a service that appends instead of replacing)
     * slipped in a stanza-id we can't tell apart from the real one - use neither (Conversations
     * 539b55d83). The same rule is applied to occupant-id via getOnlyExtension.
     */
    static String extractStanzaId(Element packet, Jid by) {
        String id = null;
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("stanza-id")
                    && Namespace.STANZA_IDS.equals(child.getNamespace())
                    && by.equals(Jid.Invalid.getNullForInvalid(child.getAttributeAsJid("by")))) {
                if (id != null) {
                    return null;
                }
                id = child.getAttribute("id");
                if (id == null) {
                    return null;
                }
            }
        }
        return id;
    }

    // Package-private rather than private: these three decide whether a stanza may speak for
    // our own account, and are covered directly by MucIdentityTest.
    static boolean isSelfInConference(
            final MucOptions mucOptions, final OccupantId occupant, final Jid counterpart) {
        if (mucOptions == null) {
            return false;
        }
        final String occupantId = occupant == null ? null : occupant.getId();
        if (occupantId != null && mucOptions.getSelf().getOccupantId() != null) {
            // Occupant ids are assigned per real user, so all of our own devices share ours even
            // when they joined the room under different nicks.
            return mucOptions.isSelf(occupantId);
        }
        return counterpart != null && mucOptions.isSelf(counterpart);
    }

    static boolean isSelfInConference(
            final MucOptions mucOptions,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Jid counterpart) {
        final OccupantId occupant =
                (mucOptions != null && mucOptions.occupantId() && packet != null)
                        ? packet.getOnlyExtension(OccupantId.class)
                        : null;
        return isSelfInConference(mucOptions, occupant, counterpart);
    }

    /**
     * Whether an incoming correction, retraction or moderation may rewrite an existing message.
     *
     * <p>Two things have to hold. The encryption must line up - a plaintext stanza cannot rewrite
     * an OMEMO message - and the sender must be the same party that wrote the original. In a
     * one-to-one chat the conversation is already keyed by the peer, so identity is implied. In a
     * conference it has to be established, and only the room's own bookkeeping can do that:
     * occupant id, the resolved real address, or the user record the room gave us.
     *
     * <p>{@code fromRoomItself} covers XEP-0425 moderation, which legitimately arrives from the
     * bare room address rather than from an occupant and may retract anybody's message.
     */
    static boolean mayReplace(
            final boolean fingerprintsMatch,
            final boolean trueCountersMatch,
            final boolean occupantIdMatch,
            final boolean mucUserMatches,
            final boolean conversationMultiMode,
            final boolean fromRoomItself) {
        if (!fingerprintsMatch) {
            return false;
        }
        if (!conversationMultiMode) {
            return true;
        }
        return trueCountersMatch || occupantIdMatch || mucUserMatches || fromRoomItself;
    }

    /**
     * One-on-one, only the author's side may correct or retract a message: the peer knows the id of
     * each of our messages and must not edit an unencrypted one of ours (nor we one of theirs). Our
     * own messages are everything not {@link Message#STATUS_RECEIVED}, including sent carbons from
     * our other devices. In a group chat {@link #mayReplace} decides by the occupant checks.
     */
    static boolean sameAuthorSide(
            final boolean conversationMultiMode, final int originalStatus, final int editStatus) {
        if (conversationMultiMode) {
            return true;
        }
        return (originalStatus == Message.STATUS_RECEIVED)
                == (editStatus == Message.STATUS_RECEIVED);
    }

    private void stopTransfer(final Message message, final String uuid, final String otherUuid) {
        final Transferable transferable = message.getTransferable();
        if (transferable != null) {
            transferable.cancel();
        }
        for (final String id : new String[] {uuid, otherUuid}) {
            if (id == null) continue;
            final File partial = mXmppConnectionService.getFileBackend().getPartialFile(id);
            if (partial.exists() && !partial.delete()) {
                Log.w(Config.LOGTAG, "could not delete partial file of retracted message");
            }
        }
    }

    /**
     * Removes what a retracted message still shows or stores locally (live location, file,
     * previews, cid thumbnails, payloads) and marks it retracted. The caller persists it.
     */
    private void retractLocally(
            final Message target,
            final Element retractElement,
            final String retractId,
            final String previewUuid) {
        final String liveSessionId =
                eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                        .getSessionIdForMessage(target.getUuid());
        if (liveSessionId != null) {
            eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                    .expireIncomingSession(liveSessionId);
        }
        // Files are stored by content hash, so the retracted message may share its file with
        // other messages (in any chat). Only delete what nothing else uses; otherwise anyone
        // could delete our copy of a file by sending the same content and retracting it.
        // A correction gives the message a fresh uuid before calling us while its row is still
        // stored under the previous one (previewUuid); neither row counts as "another message".
        // A download still running would write the file (decrypted) after we are done and give
        // the row its path back; stop it and drop what it wrote so far. Partial files are kept
        // by message uuid (to resume), the message's current one and the one it had before a
        // correction.
        stopTransfer(target, target.getUuid(), previewUuid);
        for (final Message attachment : target.getAttachments()) {
            stopTransfer(attachment, attachment.getUuid(), null);
        }
        // Its extra files (a multi-file message) are part of it, so they count as its own too.
        final List<String> ownUuids = new ArrayList<>(Arrays.asList(target.getUuid(), previewUuid));
        for (final Message attachment : target.getAttachments()) {
            ownUuids.add(attachment.getUuid());
        }
        final DownloadableFile targetFile = mXmppConnectionService.getFileBackend().getFile(target);
        // A message whose file matches one of our stickers points at the sticker itself; no
        // message owns a sticker, so a retraction never deletes it (same as for thumbnails below).
        if (eu.siacs.conversations.persistance.FileBackend.isStickerFile(
                mXmppConnectionService, targetFile)) {
            Log.d(Config.LOGTAG, "keeping sticker file of retracted message");
        } else if (mXmppConnectionService.databaseBackend.isFileUsedByOtherMessage(
                targetFile, ownUuids, null)) {
            Log.d(Config.LOGTAG, "keeping file of retracted message, still used elsewhere");
        } else {
            mXmppConnectionService.getFileBackend().deleteFile(target);
        }
        mXmppConnectionService.evictPreview(previewUuid);
        // The extra files of a multi-file message go with it: they are rows of their own, which
        // the caller (persisting only the message) would leave with their file, URL and key.
        for (final Message attachment : target.getAttachments()) {
            final DownloadableFile file =
                    mXmppConnectionService.getFileBackend().getFile(attachment);
            if (attachment.getRelativeFilePath() != null
                    && !eu.siacs.conversations.persistance.FileBackend.isStickerFile(
                            mXmppConnectionService, file)
                    && !mXmppConnectionService.databaseBackend.isFileUsedByOtherMessage(
                            file, ownUuids, null)) {
                if (file.delete()) {
                    mXmppConnectionService.getFileBackend().updateMediaScanner(file);
                }
            }
            mXmppConnectionService.evictPreview(file);
            attachment.clearPayloads();
            attachment.setFileParams(null);
            attachment.setRelativeFilePath(null);
            attachment.setDeleted(true);
            mXmppConnectionService.databaseBackend.updateMessage(attachment, false);
        }
        final List<Element> thumbs =
                target.getFileParams() != null ? target.getFileParams().getThumbnails() : null;
        if (thumbs != null && !thumbs.isEmpty()) {
            for (Element thumb : thumbs) {
                final String thumbUri = thumb.getAttribute("uri");
                // A <thumbnail/> without a uri is remote input; Uri.parse(null) would throw.
                if (thumbUri == null) continue;
                Uri uri = Uri.parse(thumbUri);
                if ("cid".equals(uri.getScheme())) {
                    Cid cid = BobTransfer.cid(uri);
                    if (cid == null) continue;
                    DownloadableFile f = mXmppConnectionService.getFileForCid(cid);
                    // the cid comes from the retracted (remote) message: it may name any
                    // file we hold, so only drop it when no other message uses it - and never
                    // a sticker, which no message owns (public packs have known hashes)
                    if (f != null
                            && !eu.siacs.conversations.persistance.FileBackend.isStickerFile(
                                    mXmppConnectionService, f)
                            && !mXmppConnectionService.databaseBackend.isFileUsedByOtherMessage(
                                    f, ownUuids, thumbUri)) {
                        mXmppConnectionService.evictPreview(f);
                        f.delete();
                    }
                }
            }
        }
        target.clearPayloads();
        target.setFileParams(null);
        target.addPayload(retractElement);

        target.setDeleted(true);
        target.setRetractId(retractId);
        // a pin keeps its own copy of the text (stored under either uuid, see ownUuids above)
        mXmppConnectionService.databaseBackend.unpinMessage(target.getUuid());
        if (previewUuid != null && !previewUuid.equals(target.getUuid())) {
            mXmppConnectionService.databaseBackend.unpinMessage(previewUuid);
        }
    }

    /**
     * The retraction that arrived before {@code target} and may remove it, now taken out of the
     * conversation's pending retractions - or null. Looked up by the ids a live retraction is
     * matched by ({@link Conversation#findSentMessageWithUuidOrRemoteId}); {@code arriving} is the
     * stanza that brought the target (itself, or a duplicate of a copy we already have).
     */
    @androidx.annotation.Nullable
    private Conversation.PendingRetraction takePendingRetraction(
            final Account account,
            final Conversation conversation,
            final Message target,
            final Message arriving) {
        final boolean multi = conversation.getMode() == Conversation.MODE_MULTI;
        final String[] ids = {
            arriving.getRemoteMsgId(),
            multi ? arriving.getServerMsgId() : null,
            target.getUuid(),
            target.getRemoteMsgId(),
            multi ? target.getServerMsgId() : null
        };
        for (final String id : ids) {
            for (final Conversation.PendingRetraction pending :
                    conversation.findPendingRetractions(id)) {
                if (mayApplyPendingRetraction(account, multi, target, pending)) {
                    conversation.removePendingRetraction(pending);
                    return pending;
                }
            }
        }
        return null;
    }

    /**
     * The sender check of a live retraction (see the XEP-0308/0424 block of the parser), for one
     * that arrived before its target. The room's user record of the time can't be compared with the
     * target's, so a group chat retraction needs the true counterpart or the occupant id.
     */
    private boolean mayApplyPendingRetraction(
            final Account account,
            final boolean multi,
            final Message target,
            final Conversation.PendingRetraction pending) {
        final Message retraction = pending.retraction;
        if (!sameAuthorSide(multi, target.getStatus(), retraction.getStatus())) {
            return false;
        }
        final boolean fingerprintsMatch =
                editFingerprintsMatch(
                        target.getFingerprint(),
                        retraction.getFingerprint(),
                        true,
                        pending.fromRoomItself,
                        isEditFromOwnDevice(account, target, retraction));
        final boolean trueCountersMatch =
                target.getTrueCounterpart() != null
                        && retraction.getTrueCounterpart() != null
                        && target.getTrueCounterpart()
                                .asBareJid()
                                .equals(retraction.getTrueCounterpart().asBareJid());
        final boolean occupantIdMatch =
                target.getOccupantId() != null
                        && target.getOccupantId().equals(retraction.getOccupantId());
        return mayReplace(
                fingerprintsMatch,
                trueCountersMatch,
                occupantIdMatch,
                false,
                multi,
                pending.fromRoomItself);
    }

    /**
     * Whether {@code replaceElement} is a XEP-0425 moderation (by a room moderator) rather than the
     * author's own retraction: the pre-0.3.0 fastened {@code <moderated/>}, a 0.3.0 {@code
     * <retract/>} carrying {@code <moderated/>}, or a bare {@code <moderate/>}.
     */
    static boolean isModeration(final Element replaceElement) {
        if (replaceElement == null) {
            return false;
        }
        final String name = replaceElement.getName();
        final String namespace = replaceElement.getNamespace();
        if ("moderated".equals(name) && Namespace.MESSAGE_MODERATE_LEGACY.equals(namespace)) {
            return true;
        }
        if ("moderate".equals(name) && Namespace.MESSAGE_MODERATE.equals(namespace)) {
            return true;
        }
        return "retract".equals(name)
                && Namespace.MESSAGE_RETRACT.equals(namespace)
                && replaceElement.findChild("moderated", Namespace.MESSAGE_MODERATE) != null;
    }

    /**
     * The sender/encryption checks for an incoming correction, retraction or moderation of {@code
     * replacedMessage} (see {@link #mayReplace} and {@link #sameAuthorSide}).
     */
    private boolean isAuthorizedReplacement(
            final Account account,
            final Message replacedMessage,
            final Message message,
            final Element replaceElement,
            final boolean conversationMultiMode,
            final Jid counterpart,
            final MessageArchiveService.Query query) {
        final boolean isRetraction =
                replaceElement != null && !replaceElement.getName().equals("replace");
        final boolean fingerprintsMatch =
                editFingerprintsMatch(
                        replacedMessage.getFingerprint(),
                        message.getFingerprint(),
                        isRetraction,
                        conversationMultiMode && counterpart.isBareJid(),
                        isEditFromOwnDevice(account, replacedMessage, message));
        final boolean trueCountersMatch =
                replacedMessage.getTrueCounterpart() != null
                        && message.getTrueCounterpart() != null
                        && replacedMessage
                                .getTrueCounterpart()
                                .asBareJid()
                                .equals(message.getTrueCounterpart().asBareJid());
        final boolean occupantIdMatch =
                replacedMessage.getOccupantId() != null
                        && replacedMessage.getOccupantId().equals(message.getOccupantId());
        final boolean mucUserMatches =
                query == null
                        && replacedMessage.sameMucUser(
                                message); // can not be checked when using mam
        return sameAuthorSide(
                        conversationMultiMode, replacedMessage.getStatus(), message.getStatus())
                && mayReplace(
                        fingerprintsMatch,
                        trueCountersMatch,
                        occupantIdMatch,
                        mucUserMatches,
                        conversationMultiMode,
                        counterpart.isBareJid());
    }

    /**
     * The encryption half of {@link #mayReplace}: may an edit carrying {@code editFingerprint}
     * rewrite a message that was received under {@code originalFingerprint}?
     *
     * <p>An unencrypted original may be edited by anything the sender rule admits. An encrypted one
     * only under the same OMEMO fingerprint, for corrections and retractions alike - otherwise the
     * sender's server could forge a cleartext stanza that rewrites or deletes it. This holds for
     * legacy OMEMO (v0.3) too: its retractions carry the fallback text encrypted (MessageGenerator
     * / AxolotlService.encrypt), which authenticates them. Cleartext legacy retractions (e.g. from
     * clients that don't encrypt them) are ignored; the message stays.
     *
     * <p>The one exemption is XEP-0425 moderation from the room itself: the room is the authority
     * over its own history and never holds the author's keys.
     *
     * <p>Our own messages may also be edited by any of our own devices ({@code editFromOwnDevice}):
     * a message we sent on this phone and deleted on the desktop arrives (as a sent carbon or our
     * own room reflection) under the desktop's fingerprint, not the one stamped on our copy. It
     * must still be encrypted, under a trusted and active device of our own JID (see {@link
     * #isEditFromOwnDevice}): decrypting alone proves nothing, since a replaced or never-seen key
     * is still decrypted (and filed undecided). A cleartext own-side edit could be forged by our
     * server and stays rejected.
     */
    static boolean editFingerprintsMatch(
            final String originalFingerprint,
            final String editFingerprint,
            final boolean isRetraction,
            final boolean moderation,
            final boolean editFromOwnDevice) {
        if (originalFingerprint == null || originalFingerprint.equals(editFingerprint)) {
            return true;
        }
        if (editFromOwnDevice && editFingerprint != null) {
            return true;
        }
        return isRetraction && moderation;
    }

    /**
     * Whether {@code edit} - a correction or retraction of {@code original} - was sent by our own
     * account from one of our own OMEMO devices: both are ours (sent carbon / own room reflection),
     * and the edit's fingerprint is a trusted (blind-trusted or verified) identity of our own JID
     * that is in our published device list - the same devices we encrypt to. Never the substituted
     * UNDECIDED default: the identity row has to exist. An undecided key (e.g. a replaced identity,
     * or one a malicious room slipped in under our JID) must not delete or rewrite our messages.
     */
    private boolean isEditFromOwnDevice(
            final Account account, final Message original, final Message edit) {
        final boolean ownEdit =
                edit.getStatus() == Message.STATUS_SEND
                        || edit.getStatus() == Message.STATUS_SEND_RECEIVED;
        if (!ownEdit || original.getStatus() == Message.STATUS_RECEIVED) {
            return false;
        }
        final String fingerprint = edit.getFingerprint();
        if (fingerprint == null) {
            return false;
        }
        final AxolotlService axolotlService = account.getAxolotlService();
        if (axolotlService == null) {
            return false;
        }
        final FingerprintStatus trust =
                axolotlService.getFingerprintStatusOrNull(
                        account.getJid().asBareJid().toString(), fingerprint);
        return trust != null && trust.isTrustedAndActive();
    }

    static Jid getTrueCounterpart(
            final Element mucUserElement,
            final Jid fallback,
            final Account account,
            final boolean senderIsSelf) {
        final Element item = mucUserElement == null ? null : mucUserElement.findChild("item");
        final Jid claimed =
                item == null ? null : Jid.Invalid.getNullForInvalid(item.getAttributeAsJid("jid"));
        if (claimed == null) {
            return fallback;
        }
        if (!senderIsSelf && claimed.asBareJid().equals(account.getJid().asBareJid())) {
            Log.w(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": ignoring message-supplied real JID that claims our own account"
                            + " without the room backing it up");
            return fallback;
        }
        return claimed;
    }

    private static boolean clientMightSendHtml(Account account, Jid from) {
        String resource = from.getResource();
        if (resource == null) {
            return false;
        }
        Presence presence =
                account.getRoster().getContact(from).getPresences().getPresencesMap().get(resource);
        ServiceDiscoveryResult disco =
                presence == null ? null : presence.getServiceDiscoveryResult();
        if (disco == null) {
            return false;
        }
        return hasIdentityKnowForSendingHtml(disco.getIdentities());
    }

    private static boolean hasIdentityKnowForSendingHtml(
            List<ServiceDiscoveryResult.Identity> identities) {
        for (ServiceDiscoveryResult.Identity identity : identities) {
            if (identity.getName() != null) {
                if (CLIENTS_SENDING_HTML_IN_OTR.contains(identity.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean extractChatState(
            Conversation c,
            final boolean isTypeGroupChat,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        ChatState state = ChatState.parse(packet);
        if (state != null && c != null) {
            final Account account = c.getAccount();
            final Jid from = packet.getFrom();
            if (from.asBareJid().equals(account.getJid().asBareJid())) {
                c.setOutgoingChatState(state);
                if (state == ChatState.ACTIVE || state == ChatState.COMPOSING) {
                    if (c.getContact().isSelf()) {
                        return false;
                    }
                    mXmppConnectionService.markRead(c);
                    activateGracePeriod(account);
                }
                return false;
            } else {
                if (isTypeGroupChat) {
                    MucOptions.User user = c.getMucOptions().findUserByFullJid(from);
                    if (user != null) {
                        return user.setChatState(state);
                    } else {
                        return false;
                    }
                } else {
                    return c.setIncomingChatState(state);
                }
            }
        }
        return false;
    }

    private Message parseOtrChat(String body, Jid from, String id, Conversation conversation) {
        String presence;
        if (from.isBareJid()) {
            presence = "";
        } else {
            presence = from.getResource();
        }
        if (body.matches("^\\?OTRv\\d{1,2}\\?.*")) {
            conversation.endOtrIfNeeded();
        }
        if (!conversation.hasValidOtrSession()) {
            conversation.startOtrSession(presence, false);
        } else {
            String foreignPresence = conversation.getOtrSession().getSessionID().getUserID();
            if (!foreignPresence.equals(presence)) {
                conversation.endOtrIfNeeded();
                conversation.startOtrSession(presence, false);
            }
        }
        try {
            conversation.setLastReceivedOtrMessageId(id);
            Session otrSession = conversation.getOtrSession();
            body = otrSession.transformReceiving(body);
            SessionStatus status = otrSession.getSessionStatus();
            if (body == null && status == SessionStatus.ENCRYPTED) {
                mXmppConnectionService.onOtrSessionEstablished(conversation);
                return null;
            } else if (body == null && status == SessionStatus.FINISHED) {
                conversation.resetOtrSession();
                mXmppConnectionService.updateConversationUi();
                return null;
            } else if (body == null || (body.isEmpty())) {
                return null;
            }
            if (body.startsWith(CryptoHelper.FILETRANSFER)) {
                String key = body.substring(CryptoHelper.FILETRANSFER.length());
                conversation.setSymmetricKey(CryptoHelper.hexToBytes(key));
                return null;
            }
            if (clientMightSendHtml(conversation.getAccount(), from)) {
                Log.d(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": received OTR message from bad behaving client. escaping"
                                + " HTML…");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
                } else {
                    body = Html.fromHtml(body).toString();
                }
            }

            final OtrService otrService = conversation.getAccount().getOtrService();
            Message finishedMessage =
                    new Message(
                            conversation, body, Message.ENCRYPTION_OTR, Message.STATUS_RECEIVED);
            finishedMessage.setFingerprint(
                    otrService.getFingerprint(otrSession.getRemotePublicKey()));
            conversation.setLastReceivedOtrMessageId(null);

            return finishedMessage;
        } catch (Exception e) {
            conversation.resetOtrSession();
            return null;
        }
    }

    private Message parseAxolotlChat(
            final Encrypted axolotlMessage,
            final Jid from,
            final Conversation conversation,
            final int status,
            final boolean checkedForDuplicates,
            final boolean postpone) {
        final AxolotlService service = conversation.getAccount().getAxolotlService();
        final XmppAxolotlMessage xmppAxolotlMessage;
        try {
            xmppAxolotlMessage = XmppAxolotlMessage.fromElement(axolotlMessage, from.asBareJid());
        } catch (final Exception e) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": invalid omemo message received "
                            + e.getMessage());
            return null;
        }
        if (xmppAxolotlMessage.hasPayload()) {
            final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage;
            try {
                plaintextMessage =
                        service.processReceivingPayloadMessage(xmppAxolotlMessage, postpone);
            } catch (BrokenSessionException e) {
                if (checkedForDuplicates) {
                    if (service.trustedOrPreviouslyResponded(from.asBareJid())) {
                        service.reportBrokenSessionException(e, postpone);
                        return new Message(
                                conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                "ignoring broken session exception because contact was not"
                                        + " trusted");
                        return new Message(
                                conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
                    }
                } else {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring broken session exception because checkForDuplicates failed");
                    return null;
                }
            } catch (NotEncryptedForThisDeviceException e) {
                return new Message(
                        conversation, "", Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE, status);
            } catch (OutdatedSenderException e) {
                return new Message(conversation, "", Message.ENCRYPTION_AXOLOTL_FAILED, status);
            }
            if (plaintextMessage != null) {
                Message finishedMessage =
                        new Message(
                                conversation,
                                plaintextMessage.getPlaintext(),
                                Message.ENCRYPTION_AXOLOTL,
                                status);
                finishedMessage.setFingerprint(plaintextMessage.getFingerprint());
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(finishedMessage.getConversation().getAccount())
                                + " Received Message with session fingerprint: "
                                + plaintextMessage.getFingerprint());
                return finishedMessage;
            }
        } else {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": received OMEMO key transport message");
            service.processReceivingKeyTransportMessage(xmppAxolotlMessage, postpone);
        }
        return null;
    }

    private Message parseOmemo2Chat(
            final Element omemo2Element,
            final Jid from,
            final Conversation conversation,
            final int status,
            final boolean checkedForDuplicates,
            final boolean postpone,
            final OccupantId occupant,
            final Jid counterpart,
            final String remoteMsgId,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Long stanzaTimestamp,
            final boolean historyReplay) {
        final AxolotlService service = conversation.getAccount().getAxolotlService();
        final eu.siacs.conversations.crypto.axolotl.XmppOmemo2Message omemo2Message =
                eu.siacs.conversations.crypto.axolotl.XmppOmemo2Message.fromElement(
                        omemo2Element, from.asBareJid());
        if (omemo2Message == null) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": invalid OMEMO2 message received");
            return null;
        }
        if (!omemo2Message.hasPayload()) {
            Log.d(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": received OMEMO2 key transport message (no payload)");
            return null;
        }
        // XEP-0420 §4.5: the SCE <to> must match the recipient JID.
        //  - MUC: the room bare JID (what every sender to that room sets)
        //  - 1:1 incoming: our account bare JID
        //  - 1:1 carbon-sent (our own outgoing message reflected to another own
        //    device): the counterpart bare JID, because the original send set
        //    <to> to the recipient
        final Jid ownBareJid = conversation.getAccount().getJid().asBareJid();
        final Jid expectedTo;
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            expectedTo = conversation.getJid().asBareJid();
        } else if (from.asBareJid().equals(ownBareJid)) {
            // carbon of our own outgoing message
            expectedTo = counterpart.asBareJid();
        } else {
            expectedTo = ownBareJid;
        }
        // A real OMEMO2 content message always carries the OMEMO fallback <body>
        // on the outer stanza; metadata-only stanzas (chat states, chat markers,
        // delivery receipts — sent via sendOmemo2Packet) never do. When we cannot
        // decrypt, only surface a "not for this device" / "failed" placeholder for
        // an actual content message. Otherwise every undecryptable typing
        // notification or read marker would spawn an empty message bubble (the
        // root cause of the "empty messages on first contact" bug while sessions
        // are still being established).
        final boolean isContentMessage = packet.getBody() != null;
        final eu.siacs.conversations.crypto.axolotl.XmppOmemo2Message.DecryptedSce decrypted;
        try {
            decrypted =
                    service.processReceivingOmemo2PayloadMessage(
                            omemo2Message, postpone, expectedTo, stanzaTimestamp);
        } catch (NotEncryptedForThisDeviceException e) {
            return isContentMessage
                    ? new Message(
                            conversation,
                            "",
                            Message.ENCRYPTION_AXOLOTL_OMEMO2_NOT_FOR_THIS_DEVICE,
                            status)
                    : null;
        } catch (BrokenSessionException e) {
            if (!checkedForDuplicates) {
                // A second copy (MAM catch-up, carbon, MUC history replay) of a
                // stanza we already hold: its message key was consumed by the first
                // copy, so the ratchet cannot open it again. Not a broken session —
                // drop it like the legacy stack does instead of adding a bogus
                // "failed to decrypt" bubble next to the readable original.
                Log.d(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": ignoring OMEMO2 broken session exception on possible duplicate"
                                + " from "
                                + from);
                return null;
            }
            service.reportBrokenSessionException(e, postpone, true);
            return isContentMessage
                    ? new Message(
                            conversation, "", Message.ENCRYPTION_AXOLOTL_OMEMO2_FAILED, status)
                    : null;
        } catch (OutdatedSenderException e) {
            return isContentMessage
                    ? new Message(
                            conversation, "", Message.ENCRYPTION_AXOLOTL_OMEMO2_FAILED, status)
                    : null;
        } catch (eu.siacs.conversations.crypto.axolotl.CryptoFailedException e) {
            if (e.getCause() instanceof org.signal.libsignal.protocol.DuplicateMessageException
                    || !checkedForDuplicates) {
                // libsignal refused a counter it has already consumed: this exact
                // ciphertext was decrypted before (live + MAM, carbon + archive,
                // stream-resumption redelivery). The original is already shown; a
                // redelivered copy carries nothing new, so there is nothing to warn
                // about — surfacing it produced the spurious "Failed to decrypt PQ
                // OMEMO2 message" bubbles. Same for any failure on a stanza whose
                // server/remote id we already hold.
                Log.d(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": dropping already-processed OMEMO2 message from "
                                + from
                                + ": "
                                + e.getMessage());
                return null;
            }
            // Generic decrypt failure: GCM tag mismatch, SCE from/to binding
            // violation, future-dated <time>, malformed envelope. The ratchet may
            // already have advanced, so the message is unrecoverable — surface a
            // visible failure placeholder for content messages instead of
            // dropping silently (silence would let tampering go unnoticed).
            Log.w(
                    Config.LOGTAG,
                    conversation.getAccount().getJid().asBareJid()
                            + ": OMEMO2 payload failed to decrypt from "
                            + from
                            + ": "
                            + e.getMessage());
            return isContentMessage
                    ? new Message(
                            conversation, "", Message.ENCRYPTION_AXOLOTL_OMEMO2_FAILED, status)
                    : null;
        }
        if (decrypted == null) return null;

        // Route SCE content elements that are handled outside normal message storage
        for (final eu.siacs.conversations.xml.Element el : decrypted.elements) {
            final String elName = el.getName();
            if ("live-location-update".equals(elName)) {
                processLiveLocationUpdate(el);
                return null;
            } else if ("live-location-stop".equals(elName)) {
                final String sessionId = el.getAttribute("id");
                if (sessionId != null) {
                    eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                            .expireIncomingSession(sessionId);
                    mXmppConnectionService.updateConversationUi();
                }
                return null;
            } else if ("reactions".equals(elName)
                    && Namespace.REACTIONS.equals(el.getNamespace())) {
                final Reactions reactionsModel = Reactions.to(el.getAttribute("id"));
                for (final eu.siacs.conversations.xml.Element child : el.getChildren()) {
                    if ("reaction".equals(child.getName()) && child.getContent() != null) {
                        reactionsModel.addExtension(
                                new im.conversations.android.xmpp.model.reactions.Reaction(
                                        child.getContent()));
                    }
                }
                final boolean isTypeGroupChat = conversation.getMode() == Conversational.MODE_MULTI;
                processReactions(
                        reactionsModel,
                        conversation,
                        isTypeGroupChat,
                        occupant,
                        counterpart,
                        from,
                        status,
                        null);
                return null;
            } else if (Namespace.EPHEMERAL.equals(el.getNamespace())
                    && ("ephemeral".equals(elName) || "i-want-out".equals(elName))
                    && isTimerChangeOnly(decrypted.elements)) {
                // A timer change on its own (no message with it). In a PQ OMEMO2 chat this is
                // the only form that is honoured: sent in the clear it could be forged.
                // Only when the envelope carries nothing else: every message of a chat with a
                // timer is stamped with it, and a body-less WebXDC update must still reach
                // processWebxdc below instead of being taken for a timer change.
                if (!historyReplay) {
                    if ("ephemeral".equals(elName)) {
                        try {
                            applyEphemeralTimer(
                                    conversation, from, Integer.parseInt(el.getAttribute("timer")));
                        } catch (final NumberFormatException ignored) {
                            // not a timer
                        }
                    } else {
                        switchOffEphemeralTimer(conversation);
                    }
                    mXmppConnectionService.updateConversationUi();
                }
                return null;
            }
        }

        // WebXDC status/realtime updates: process side effect even when the message
        // has no body (realtime data carries the payload inside <x xmlns="urn:xmpp:webxdc:0">).
        eu.siacs.conversations.xml.Element webxdcEl = null;
        eu.siacs.conversations.xml.Element threadEl = null;
        for (final eu.siacs.conversations.xml.Element el : decrypted.elements) {
            if ("x".equals(el.getName()) && "urn:xmpp:webxdc:0".equals(el.getNamespace())) {
                webxdcEl = el;
            } else if ("thread".equals(el.getName())) {
                threadEl = el;
            }
        }
        if (webxdcEl != null && threadEl != null) {
            processWebxdc(conversation, webxdcEl, threadEl, remoteMsgId, from, decrypted.body);
        }

        // Re-inject metadata elements that downstream handlers in receiveMessage()
        // expect to find on the outer packet — chat states, chat markers and
        // delivery receipts. The senders' SCE envelopes carry them inside the
        // encrypted content so the server never sees them; we copy them onto the
        // packet here so the existing extractChatState / processReceived /
        // processDisplayed code paths keep working unchanged.
        //
        // SECURITY: strip any pre-existing plaintext copies of these element
        // types FIRST. The downstream handlers use first-match semantics
        // (Iterables.find / hasChild), so a malicious server that prepends a
        // plaintext <displayed/> or <received/> on the outer stanza would win
        // precedence over the authentic decrypted element — letting the server
        // forge read-receipts, delivery receipts, and typing indicators that
        // the SCE work was designed to prevent.
        final java.util.List<eu.siacs.conversations.xml.Element> outerChildren =
                new java.util.ArrayList<>(packet.getChildren());
        for (final eu.siacs.conversations.xml.Element existing : outerChildren) {
            final String exName = existing.getName();
            final String exNs = existing.getNamespace();
            if ("http://jabber.org/protocol/chatstates".equals(exNs)
                    || ("received".equals(exName) && "urn:xmpp:receipts".equals(exNs))
                    || ("received".equals(exName) && "urn:xmpp:chat-markers:0".equals(exNs))
                    || ("displayed".equals(exName) && "urn:xmpp:chat-markers:0".equals(exNs))) {
                packet.removeChild(existing);
                Log.w(
                        Config.LOGTAG,
                        conversation.getAccount().getJid().asBareJid()
                                + ": stripped server-injected plaintext "
                                + exName
                                + " from "
                                + from
                                + " before SCE re-injection");
            }
        }
        for (final eu.siacs.conversations.xml.Element el : decrypted.elements) {
            final String elName = el.getName();
            final String elNs = el.getNamespace();
            if ("http://jabber.org/protocol/chatstates".equals(elNs)
                    || ("received".equals(elName) && "urn:xmpp:receipts".equals(elNs))
                    || ("received".equals(elName) && "urn:xmpp:chat-markers:0".equals(elNs))
                    || ("displayed".equals(elName) && "urn:xmpp:chat-markers:0".equals(elNs))) {
                packet.addChild(el);
            }
        }

        // An OMEMO2 content stanza whose SCE envelope carries no body — or an
        // *empty* <body></body>, which decodes to "" rather than null (see
        // Element.getContent(), which joins zero text nodes to an empty string) —
        // must never spawn a visible bubble. This is the remaining root cause of
        // the "empty messages on first contact" report: while sessions are being
        // established peers exchange OMEMO2 stanzas that decrypt to a blank body,
        // and "" slipped past the previous `== null` check. Metadata side effects
        // (chat states, markers, webxdc, reactions, live-location) are already
        // handled above, so dropping here loses nothing renderable.
        // Exception: a subject-only message (<subject> + <thread>, no <body>)
        // is renderable content — mirror the plaintext acceptance condition in
        // receiveMessage(). Requiring BOTH keeps session-setup blanks dropped.
        boolean sceHasSubject = false;
        boolean sceHasThread = false;
        for (final eu.siacs.conversations.xml.Element el : decrypted.elements) {
            if ("subject".equals(el.getName())) sceHasSubject = true;
            else if ("thread".equals(el.getName())) sceHasThread = true;
        }
        if ((decrypted.body == null || decrypted.body.isEmpty())
                && !(sceHasSubject && sceHasThread)) return null;

        final Message finishedMessage =
                new Message(
                        conversation, decrypted.body, Message.ENCRYPTION_AXOLOTL_OMEMO2, status);
        finishedMessage.setFingerprint(decrypted.fingerprint);

        for (final eu.siacs.conversations.xml.Element el : decrypted.elements) {
            final String elName = el.getName();
            final String elNs = el.getNamespace();
            if ("reply".equals(elName) && "urn:xmpp:reply:0".equals(elNs)) {
                finishedMessage.addPayload(el);
                final String replyId = el.getAttribute("id");
                if (replyId != null) {
                    for (final var parent :
                            mXmppConnectionService
                                    .getMessageFuzzyIds(conversation, List.of(replyId))
                                    .entrySet()) {
                        finishedMessage.setInReplyTo(parent.getValue());
                    }
                }
            } else if ("fallback".equals(elName) && "urn:xmpp:fallback:0".equals(elNs)) {
                finishedMessage.addPayload(el);
            } else if ("subject".equals(elName)) {
                finishedMessage.setSubject(el.getContent());
            } else if ("thread".equals(elName)) {
                finishedMessage.addPayload(el);
            } else if ("unstyled".equals(elName) && Namespace.STYLING.equals(elNs)) {
                finishedMessage.addPayload(el);
            } else if ("replace".equals(elName) && "urn:xmpp:message-correct:0".equals(elNs)) {
                finishedMessage.addPayload(el);
            } else if ("retract".equals(elName) && "urn:xmpp:message-retract:1".equals(elNs)) {
                finishedMessage.addPayload(el);
            } else if ("ephemeral".equals(elName)
                    && eu.siacs.conversations.xml.Namespace.EPHEMERAL.equals(elNs)) {
                try {
                    final int timer = Integer.parseInt(el.getAttribute("timer"));
                    if (isSaneEphemeralTimer(timer)) {
                        finishedMessage.setEphemeralTimer(timer);
                    }
                    // This is the sender's timer for this one message. It does not change
                    // the chat's setting: that takes an explicit encrypted change (the
                    // body-less form handled above) or the user. A client that only stamps
                    // its messages (2.3.1) therefore cannot move our setting, and nothing
                    // has to be guessed from a stamp that is present or missing.
                } catch (final NumberFormatException ignored) {
                }
            } else if ("i-want-out".equals(elName)
                    && eu.siacs.conversations.xml.Namespace.EPHEMERAL.equals(elNs)) {
                if (!historyReplay) {
                    switchOffEphemeralTimer(conversation);
                }
            } else if ("live-location".equals(elName) && Namespace.LIVE_LOCATION.equals(elNs)) {
                finishedMessage.addPayload(el);
            } else if ("html".equals(elName)
                    && "http://jabber.org/protocol/xhtml-im".equals(elNs)) {
                if (el.findChild("body", "http://www.w3.org/1999/xhtml") != null) {
                    finishedMessage.addPayload(el);
                }
            } else if ("Description".equals(elName)
                    && "http://www.w3.org/1999/02/22-rdf-syntax-ns#".equals(elNs)) {
                // OGP / RDF link-description embedded by the sender's
                // showLinkPreviews flow. Carried inside the SCE envelope, so
                // here it has been decrypted and authenticated. Forward to
                // Message.getLinkDescriptions() for MessageAdapter rendering.
                finishedMessage.addPayload(el);
            } else if ("data".equals(elName) && "urn:xmpp:bob".equals(elNs)) {
                final String cidAttr = el.getAttribute("cid");
                final String contentType = el.getAttribute("type");
                final String base64Content = el.getContent();
                if (cidAttr != null && base64Content != null) {
                    try {
                        final Cid cid = BobTransfer.cid(cidAttr);
                        if (cid != null && mXmppConnectionService.getFileForCid(cid) == null) {
                            String fileExt = "dat";
                            if (contentType != null) {
                                final String ext =
                                        eu.siacs.conversations.utils.MimeUtils
                                                .guessExtensionFromMimeType(contentType);
                                if (ext != null) fileExt = ext;
                            }
                            // Defensive size cap: a BoB sticker/thumbnail is small. Refuse to
                            // decode/write anything whose base64 alone already exceeds the
                            // decoded-byte cap (~4/3 of it), so a malformed or hostile inline
                            // <data> cannot force a large heap allocation and disk write.
                            final String trimmed = base64Content.trim();
                            if ((long) trimmed.length() * 3 / 4 > MAX_INLINE_BOB_BYTES) {
                                Log.w(
                                        Config.LOGTAG,
                                        "ignoring oversized inline OMEMO2 BoB data (~"
                                                + ((long) trimmed.length() * 3 / 4)
                                                + " bytes) from "
                                                + from);
                            } else {
                                final byte[] bytes =
                                        android.util.Base64.decode(
                                                trimmed, android.util.Base64.DEFAULT);
                                if (bytes.length > MAX_INLINE_BOB_BYTES) {
                                    Log.w(
                                            Config.LOGTAG,
                                            "ignoring oversized inline OMEMO2 BoB data ("
                                                    + bytes.length
                                                    + " bytes) from "
                                                    + from);
                                } else {
                                    final java.io.File file =
                                            mXmppConnectionService
                                                    .getFileBackend()
                                                    .getStorageLocation(
                                                            null,
                                                            new java.io.ByteArrayInputStream(bytes),
                                                            fileExt);
                                    file.getParentFile().mkdirs();
                                    if (!file.exists()) file.createNewFile();
                                    eu.siacs.conversations.persistance.MediaCrypto.store(
                                            mXmppConnectionService,
                                            new java.io.ByteArrayInputStream(bytes),
                                            file);
                                } // end inner size-check else
                            } // end outer size-check else
                        }
                    } catch (final Exception e) {
                        Log.w(Config.LOGTAG, "failed to save inline OMEMO2 BoB sticker: " + e);
                    }
                }
            } else if ("reference".equals(elName) && "urn:xmpp:reference:0".equals(elNs)) {
                if (el.findChild("media-sharing", "urn:xmpp:sims:1") != null) {
                    finishedMessage.setFileParams(new Message.FileParams(el));
                    if (CryptoHelper.isPgpEncryptedUrl(finishedMessage.getFileParams().url)) {
                        finishedMessage.setEncryption(Message.ENCRYPTION_DECRYPTED);
                    }
                }
            } else if ("file-sharing".equals(elName) && Namespace.SFS.equals(elNs)) {
                // XEP-0447 file description carried inside the encrypted SCE envelope. Only
                // accepted from the decrypted content — a <file-sharing/> on the outer stanza
                // of an OMEMO2 message is attacker-controlled and is ignored (see
                // parseAxolotlChat callers), exactly like <subject>. A message may describe
                // several files; the ones after the first become attached messages.
                final Message.FileParams fileParams = new Message.FileParams(el);
                if (fileParams.url != null) {
                    addFileToMessage(finishedMessage, fileParams);
                }
            } else if ("x".equals(elName) && Namespace.OOB.equals(elNs)) {
                // File share carried inside the encrypted SCE envelope: the <x
                // xmlns='jabber:x:oob'><url> holds the file URL while decrypted.body keeps
                // the caption (the <fallback for='oob'> payload above strips the URL span
                // from the body for display). Mirrors the plaintext OOB handling.
                // Goes through addFileToMessage so that it does not replace the richer
                // <file-sharing/> description of the same URL with a URL-only one.
                final Message.FileParams fileParams = new Message.FileParams(el);
                if (fileParams.url != null) {
                    addFileToMessage(finishedMessage, fileParams);
                    if (CryptoHelper.isPgpEncryptedUrl(fileParams.url)) {
                        finishedMessage.setEncryption(Message.ENCRYPTION_DECRYPTED);
                    }
                }
            } else if ("x".equals(elName) && "urn:xmpp:webxdc:0".equals(elNs)) {
                finishedMessage.addPayload(el);
            }
        }

        return finishedMessage;
    }

    private void processWebxdc(
            final Conversation conversation,
            final Element webxdc,
            final Element thread,
            final String remoteMsgId,
            final Jid sender,
            final String body) {
        Jid webxdcSender = sender.asBareJid();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            if (conversation.getMucOptions().nonanonymous()) {
                // `sender` is an occupant JID (room@server/nick) on the plaintext path but
                // the already-resolved real JID on the OMEMO2 path. getTrueCounterpart()
                // only understands the former and returns null for everything else — and a
                // null sender crashes in WebxdcUpdate.getContentValues(). Resolve when we
                // can, otherwise keep what we were handed.
                final Jid trueCounterpart = conversation.getMucOptions().getTrueCounterpart(sender);
                webxdcSender = trueCounterpart != null ? trueCounterpart : sender;
            } else {
                webxdcSender = sender;
            }
        }
        if (webxdcSender == null) return;
        final var document = webxdc.findChildContent("document", "urn:xmpp:webxdc:0");
        final var summary = webxdc.findChildContent("summary", "urn:xmpp:webxdc:0");
        final var payload = webxdc.findChildContent("json", "urn:xmpp:json:0");
        if (document != null || summary != null || payload != null) {
            mXmppConnectionService.insertWebxdcUpdate(
                    new WebxdcUpdate(
                            conversation,
                            remoteMsgId,
                            webxdcSender,
                            thread,
                            body,
                            document,
                            summary,
                            payload));
        }
        final var notifyJson = webxdc.findChildContent("notify", "urn:xmpp:webxdc:0");
        if (notifyJson != null
                && !webxdcSender.equals(conversation.getAccount().getJid().asBareJid())) {
            final String notifyText = resolveWebxdcNotify(notifyJson, conversation);
            if (notifyText != null) {
                mXmppConnectionService
                        .getNotificationService()
                        .pushWebxdc(conversation, notifyText);
            }
        }
        final var realtime = webxdc.findChildContent("data", "urn:xmpp:webxdc:0");
        if (realtime != null) conversation.webxdcRealtimeData(thread, realtime);
        mXmppConnectionService.updateConversationUi();
    }

    private String resolveWebxdcNotify(final String notifyJson, final Conversation conversation) {
        try {
            final org.json.JSONObject obj = new org.json.JSONObject(notifyJson);
            final java.util.List<String> keys = new java.util.ArrayList<>();
            final var bare = conversation.getAccount().getJid().asBareJid().toString();
            keys.add("xmpp:" + android.net.Uri.encode(bare, "@/+"));
            keys.add(bare);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                final var self = conversation.getMucOptions().getSelf();
                if (self != null && self.getOccupantId() != null) keys.add(self.getOccupantId());
            }
            keys.add("*");
            for (final String k : keys) {
                final String t = obj.optString(k, null);
                if (t != null && !t.isEmpty()) return t;
            }
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "webxdc notify parse: " + e);
        }
        return null;
    }

    private Invite extractInvite(final im.conversations.android.xmpp.model.stanza.Message message) {
        final Element mucUser = message.findChild("x", Namespace.MUC_USER);
        if (mucUser != null) {
            final Element invite = mucUser.findChild("invite");
            if (invite != null) {
                final String password = mucUser.findChildContent("password");
                final Jid from = Jid.Invalid.getNullForInvalid(invite.getAttributeAsJid("from"));
                if (from == null) {
                    // an outgoing mediated invite (only 'to'), or one without an inviter at all.
                    // Neither is something we act on, and Invite.execute needs the inviter.
                    Log.d(Config.LOGTAG, "do not parse mediated invite without inviter " + message);
                    return null;
                }
                // mediated invites are only valid from the bare jid of the room and type normal
                final Jid room = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"));
                if (room == null
                        || room.isFullJid()
                        || message.getType()
                                != im.conversations.android.xmpp.model.stanza.Message.Type.NORMAL) {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring mediated invite not from bare room jid " + message);
                    return null;
                }
                return new Invite(room, password, false, from);
            }
        }
        final Element conference = message.findChild("x", "jabber:x:conference");
        if (conference != null) {
            Jid from = Jid.Invalid.getNullForInvalid(message.getAttributeAsJid("from"));
            Jid room = Jid.Invalid.getNullForInvalid(conference.getAttributeAsJid("jid"));
            if (room == null) {
                return null;
            }
            return new Invite(room, conference.getAttribute("password"), true, from);
        }
        return null;
    }

    private static final String COMMENTS_NODE_PREFIX = Namespace.MICROBLOG + ":comments/";

    /**
     * One item of a microblog or comments node published by {@code service}. Comments are
     * recognised by their node (as XEP-0277 and Movim do): comment entries carry no reference to
     * the post, the listeners match the node against the post's comments link.
     */
    private void handleFeedItem(
            final Account account, final Jid service, final String node, final Element item) {
        if (item.findChild("entry", Namespace.ATOM) == null) {
            return;
        }
        try {
            if (node.startsWith(COMMENTS_NODE_PREFIX)) {
                // fromElement() takes the <item>: it checks the service-stamped publisher.
                final Comment comment = Comment.fromElement(item);
                if (comment != null) {
                    mXmppConnectionService.notifyOnCommentReceived(service, node, comment);
                }
            } else {
                // Items on someone's feed must be authored by them (Post.fromElement checks).
                final Post post = Post.fromElement(item, service);
                if (post != null) {
                    mXmppConnectionService.onPostReceived(post, account);
                }
            }
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "error creating post/comment from pubsub item", e);
        }
    }

    private void parseEvent(final Element event, final Jid from, final Account account) {
        final Element items = event.findChild("items");
        final String node = items == null ? null : items.getAttribute("node");
        if (Namespace.AVATAR_METADATA.equals(node)) {
            Avatar avatar = Avatar.parseMetadata(items);
            if (avatar != null) {
                avatar.owner = from.asBareJid();
                if (mXmppConnectionService.getFileBackend().isAvatarCached(avatar)) {
                    if (account.getJid().asBareJid().equals(from)) {
                        if (account.setAvatar(avatar.getFilename())) {
                            mXmppConnectionService.databaseBackend.updateAccount(account);
                            mXmppConnectionService.notifyAccountAvatarHasChanged(account);
                        }
                        mXmppConnectionService.getAvatarService().clear(account);
                        mXmppConnectionService.updateConversationUi();
                        mXmppConnectionService.updateAccountUi();
                    } else {
                        final Contact contact = account.getRoster().getContact(from);
                        if (contact.setAvatar(avatar)) {
                            mXmppConnectionService.syncRoster(account);
                            mXmppConnectionService.getAvatarService().clear(contact);
                            mXmppConnectionService.updateConversationUi();
                            mXmppConnectionService.updateRosterUi(
                                    XmppConnectionService.UpdateRosterReason.AVATAR);
                        }
                    }
                } else if (mXmppConnectionService.isDataSaverDisabled()) {
                    mXmppConnectionService.fetchAvatar(account, avatar);
                }
            }
        } else if (Namespace.NICK.equals(node)) {
            final Element i = items.findChild("item");
            final String nick = i == null ? null : i.findChildContent("nick", Namespace.NICK);
            if (nick != null) {
                setNick(account, from, nick);
            }
        } else if (AxolotlService.PEP_DEVICE_LIST.equals(node)) {
            final AxolotlService axolotlService = account.getAxolotlService();
            final Element item = IqParser.itemCarrying(items, "list", null);
            if (item == null) {
                // A retraction or a notification without payload says nothing about the
                // devices: registering it would replace the known list with an empty one.
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(account)
                                + "PEP device list notification without a list from "
                                + from
                                + ", re-fetching");
                axolotlService.fetchDeviceIds(from);
                return;
            }
            final Set<Integer> deviceIds = IqParser.deviceIds(item);
            Log.d(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account)
                            + "Received PEP device list "
                            + deviceIds
                            + " update from "
                            + from
                            + ", processing... ");
            axolotlService.registerDevices(from, deviceIds, false);
        } else if (AxolotlService.PEP_OMEMO2_DEVICE_LIST.equals(node)) {
            final Element item = IqParser.itemCarrying(items, "devices", Namespace.OMEMO2);
            if (item == null) {
                // Same as above, for the OMEMO2 node.
                Log.d(
                        Config.LOGTAG,
                        AxolotlService.getLogprefix(account)
                                + "OMEMO2 device list notification without a list from "
                                + from
                                + ", re-fetching");
                account.getAxolotlService().refetchOmemo2DeviceIds(from);
                return;
            }
            final Set<Integer> deviceIds = IqParser.omemo2DeviceIds(item);
            Log.d(
                    Config.LOGTAG,
                    AxolotlService.getLogprefix(account)
                            + "Received OMEMO2 PEP device list "
                            + deviceIds
                            + " from "
                            + from);
            account.getAxolotlService().registerOmemo2Devices(from, deviceIds);
        } else if (Namespace.BOOKMARKS.equals(node) && account.getJid().asBareJid().equals(from)) {
            final var connection = account.getXmppConnection();
            if (connection.getFeatures().bookmarksConversion()) {
                if (connection.getFeatures().bookmarks2()) {
                    Log.w(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": received storage:bookmark notification even though we"
                                    + " opted into bookmarks:1");
                }
                final Element i = items.findChild("item");
                final Element storage =
                        i == null ? null : i.findChild("storage", Namespace.BOOKMARKS);
                final Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                mXmppConnectionService.processBookmarksInitial(account, bookmarks, true);
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": processing bookmark PEP event");
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring bookmark PEP event because bookmark conversion was"
                                + " not detected");
            }
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            final Element retract = items.findChild("retract");
            if (item != null) {
                final Bookmark bookmark = Bookmark.parseFromItem(item, account);
                if (bookmark != null) {
                    account.putBookmark(bookmark);
                    mXmppConnectionService.processModifiedBookmark(bookmark);
                    mXmppConnectionService.updateConversationUi();
                }
            }
            if (retract != null) {
                final Jid id = Jid.Invalid.getNullForInvalid(retract.getAttributeAsJid("id"));
                if (id != null) {
                    account.removeBookmark(id);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid() + ": deleted bookmark for " + id);
                    mXmppConnectionService.processDeletedBookmark(account, id);
                    mXmppConnectionService.updateConversationUi();
                }
            }
        } else if (Config.MESSAGE_DISPLAYED_SYNCHRONIZATION
                && Namespace.MDS_DISPLAYED.equals(node)
                && account.getJid().asBareJid().equals(from)) {
            final Element item = items.findChild("item");
            mXmppConnectionService.processMdsItem(account, item);
        } else if (Namespace.PUBSUB_STORIES.equals(node)) {
            // One event can carry several retracts (e.g. expired items) and items together.
            final List<Element> children = items.getChildren();
            if (children != null) {
                for (final Element child : children) {
                    if ("retract".equals(child.getName())) {
                        final String id = child.getAttribute("id");
                        if (id != null) {
                            mXmppConnectionService.onStoryRetracted(from, id);
                        }
                    } else if ("item".equals(child.getName())) {
                        final Story story = Story.fromElement(child, from);
                        if (story != null) {
                            mXmppConnectionService.onStoryReceived(story);
                        }
                    }
                }
            }
        } else if (Namespace.USER_TUNE.equals(node)) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid());
            if (conversation != null) { // Check if conversation exists
                final Contact contact = conversation.getContact();
                if (contact != null) { // Check if contact exists
                    final UserTune lastTune = contact.getUserTune();
                    final UserTune thisTune = UserTune.parse(items);

                    if (!Objects.equals(lastTune, thisTune)) {
                        contact.setUserTune(UserTune.parse(items));
                        mXmppConnectionService.updateConversationUi();
                    }
                } else {
                    Log.w(
                            Config.LOGTAG,
                            "Contact not found for conversation: " + conversation.getJid());
                }
            } else {
                Log.w(Config.LOGTAG, "Conversation not found for JID: " + from.asBareJid());
            }
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + " received pubsub notification for node="
                            + node);
        }
    }

    /**
     * The publisher of a pubsub/PEP notification, or null if it must be ignored. Notifications are
     * always sent from a bare JID (the account, a contact, a room or a pubsub service), and a
     * missing {@code from} is our own account. Anything else, e.g. a MUC occupant's full JID, would
     * let that sender publish an avatar, nick or OMEMO device list in the room's name
     * (Conversations 8027b3be2), so it is refused rather than stripped to its bare part.
     */
    static Jid pubsubEventOrigin(final Jid from, final Jid accountBare) {
        if (from == null) {
            return accountBare;
        }
        if (!Jid.Invalid.isValid(from) || !from.isBareJid()) {
            return null;
        }
        return from;
    }

    /**
     * A device-list node ({@code node}) was deleted or purged by its owner {@code from}, which is
     * definitive - unlike a notification without payload - so it means "no devices" on that stack.
     * For our own account, this device re-announces itself (re-reading path, see {@link
     * AxolotlService#onOwnDeviceListCleared}); for a contact, the empty list is registered, so
     * their sessions go inactive and the trust guard fails closed until they publish again. Returns
     * whether {@code node} was a device-list node.
     */
    private boolean handleDeviceListCleared(
            final String node, final Jid from, final Account account) {
        final boolean omemo2;
        if (AxolotlService.PEP_OMEMO2_DEVICE_LIST.equals(node)) {
            omemo2 = true;
        } else if (AxolotlService.PEP_DEVICE_LIST.equals(node)) {
            omemo2 = false;
        } else {
            return false;
        }
        final AxolotlService axolotlService = account.getAxolotlService();
        Log.d(
                Config.LOGTAG,
                AxolotlService.getLogprefix(account)
                        + (omemo2 ? "OMEMO2" : "legacy")
                        + " device list node cleared by "
                        + from);
        if (account.getJid().asBareJid().equals(from)) {
            axolotlService.onOwnDeviceListCleared(omemo2);
        } else if (omemo2) {
            axolotlService.registerOmemo2Devices(from, new HashSet<>());
        } else {
            axolotlService.registerDevices(from, new HashSet<>(), false);
        }
        return true;
    }

    private void parseDeleteEvent(final Element event, final Jid from, final Account account) {
        final Element delete = event.findChild("delete");
        final String node = delete == null ? null : delete.getAttribute("node");
        if (handleDeviceListCleared(node, from, account)) {
            return;
        }
        if (Namespace.NICK.equals(node)) {
            Log.d(Config.LOGTAG, "parsing nick delete event from " + from);
            setNick(account, from, null);
        } else if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted bookmarks node");
            deleteAllBookmarks(account);
        } else if (Namespace.AVATAR_METADATA.equals(node)
                && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": deleted avatar metadata node");
        }
    }

    private void parsePurgeEvent(final Element event, final Jid from, final Account account) {
        final Element purge = event.findChild("purge");
        final String node = purge == null ? null : purge.getAttribute("node");
        if (handleDeviceListCleared(node, from, account)) {
            return;
        }
        if (Namespace.BOOKMARKS2.equals(node) && account.getJid().asBareJid().equals(from)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": purged bookmarks");
            deleteAllBookmarks(account);
        }
    }

    private void deleteAllBookmarks(final Account account) {
        final var previous = account.getBookmarkedJids();
        account.setBookmarks(Collections.emptyMap());
        mXmppConnectionService.processDeletedBookmarks(account, previous);
    }

    private void setNick(final Account account, final Jid user, final String nick) {
        if (user.asBareJid().equals(account.getJid().asBareJid())) {
            account.setDisplayName(nick);
            if (QuickConversationsService.isQuicksy()) {
                mXmppConnectionService.getAvatarService().clear(account);
            }
            mXmppConnectionService.checkMucRequiresRename();
        } else {
            Contact contact = account.getRoster().getContact(user);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateAccountUi();
    }

    private boolean handleErrorMessage(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        if (packet.getType() == im.conversations.android.xmpp.model.stanza.Message.Type.ERROR) {
            if (packet.fromServer(account)) {
                final var forwarded =
                        getForwardedMessagePacket(packet, "received", Namespace.CARBONS);
                if (forwarded != null) {
                    return handleErrorMessage(account, forwarded.first);
                }
            }
            final Jid from = packet.getFrom();
            final String id = packet.getId();
            if (from != null && id != null) {
                final Message message =
                        mXmppConnectionService.markMessage(
                                account,
                                from.asBareJid(),
                                packet.getId(),
                                Message.STATUS_SEND_FAILED,
                                extractErrorMessage(packet));
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                    final String sessionId =
                            id.substring(
                                    JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                    mXmppConnectionService
                            .getJingleConnectionManager()
                            .updateProposedSessionDiscovered(
                                    account,
                                    from,
                                    sessionId,
                                    JingleConnectionManager.DeviceDiscoveryState.FAILED);
                    return true;
                }
                if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX)) {
                    final String sessionId =
                            id.substring(
                                    JingleRtpConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX.length());
                    final String errorMessage = extractErrorMessage(packet);
                    mXmppConnectionService
                            .getJingleConnectionManager()
                            .failProceed(account, from, sessionId, errorMessage);
                    return true;
                }
                mXmppConnectionService.markMessage(
                        account,
                        from.asBareJid(),
                        id,
                        Message.STATUS_SEND_FAILED,
                        extractErrorMessage(packet));
                final Element error = packet.findChild("error");
                final boolean pingWorthyError =
                        error != null
                                && (error.hasChild("not-acceptable")
                                        || error.hasChild("remote-server-timeout")
                                        || error.hasChild("remote-server-not-found"));
                if (pingWorthyError) {
                    Conversation conversation = mXmppConnectionService.find(account, from);
                    if (conversation != null
                            && conversation.getMode() == Conversational.MODE_MULTI) {
                        if (conversation.getMucOptions().online()) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": received ping worthy error for seemingly online"
                                            + " muc at "
                                            + from);
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                        }
                    }
                }
                if (message != null) {
                    if (message.getEncryption() == Message.ENCRYPTION_OTR) {
                        Conversation conversation = (Conversation) message.getConversation();
                        conversation.endOtrIfNeeded();
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void processLiveLocationUpdate(Element updateElement) {
        final String sessionId = updateElement.getAttribute("id");
        if (sessionId != null) {
            try {
                final double lat = Double.parseDouble(updateElement.getAttribute("lat"));
                final double lon = Double.parseDouble(updateElement.getAttribute("lon"));
                final var mgr = eu.siacs.conversations.utils.LiveLocationManager.getInstance();
                final var session = mgr.getSession(sessionId);
                if (session == null) {
                    // Recover session after restart: 1 hour default expiry
                    mgr.registerIncomingSession(
                            sessionId,
                            null,
                            null,
                            lat,
                            lon,
                            System.currentTimeMillis() + 3600_000L);
                }
                mgr.updateIncomingPosition(sessionId, lat, lon);
                final var updatedSession = mgr.getSession(sessionId);
                if (updatedSession != null
                        && updatedSession.conversationUuid != null
                        && updatedSession.messageUuid != null) {
                    mXmppConnectionService.updateMessageGeoPayload(
                            updatedSession.conversationUuid, updatedSession.messageUuid, lat, lon);
                }
                if (mgr.isPreviewRefreshDue(sessionId, 300_000L)) {
                    mXmppConnectionService.updateConversationUi();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Whether a decrypted PQ OMEMO2 envelope is a disappearing-message timer change and nothing
     * else: at least one {@code <ephemeral/>} or {@code <i-want-out/>}, and besides those at most
     * an empty {@code <body/>}. Anything more (a WebXDC update, a file, text) is a message that
     * merely carries the sender's timer for itself.
     */
    static boolean isTimerChangeOnly(final List<Element> elements) {
        if (elements == null) {
            return false;
        }
        boolean control = false;
        for (final Element el : elements) {
            final String name = el.getName();
            if (Namespace.EPHEMERAL.equals(el.getNamespace())) {
                if ("ephemeral".equals(name) || "i-want-out".equals(name)) {
                    control = true;
                }
            } else if ("body".equals(name)) {
                final String content = el.getContent();
                if (content != null && !content.isEmpty()) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return control;
    }

    /**
     * Who may change a conversation's disappearing-message timer: the contact of a 1:1 chat, or an
     * occupant of a private, non-anonymous room. In any other room an occupant must not be able to
     * set the timer - nor switch it off, which would leave our messages in place.
     */
    public static boolean mayChangeEphemeralTimer(
            final boolean groupChat, final boolean privateAndNonAnonymous) {
        return !groupChat || privateAndNonAnonymous;
    }

    private static boolean mayChangeEphemeralTimer(final Conversation conversation) {
        return mayChangeEphemeralTimer(
                conversation.getMode() == Conversation.MODE_MULTI,
                conversation.isPrivateAndNonAnonymous());
    }

    /** {@code <i-want-out/>}: the contact (or an occupant, see above) switched the timer off. */
    private void switchOffEphemeralTimer(final Conversation conversation) {
        if (!mayChangeEphemeralTimer(conversation)) {
            return;
        }
        conversation.setEphemeralTimer(0);
        mXmppConnectionService.databaseBackend.updateConversation(conversation);
    }

    /** Longest disappearing-message timer taken from the wire: one year (as the desktop). */
    static final int MAX_EPHEMERAL_TIMER = 365 * 24 * 3600;

    /**
     * Whether a timer from the wire is a usable one (0 = off). Anything else is ignored rather than
     * stored: it would be shown as some other duration and could be saved back as that.
     */
    /**
     * When one of our own messages from another device disappears: its timer after it was sent,
     * whether it came as a carbon, from the archive or as our room reflection (XEP-0466 monocles
     * profile, Countdown). 0 for a received message, which counts down from when it is read ({@link
     * XmppConnectionService#markRead}), and for one without a timer.
     */
    static long ownMessageExpiry(final int status, final long timeSent, final int timer) {
        if (timer <= 0 || status == Message.STATUS_RECEIVED) {
            return 0;
        }
        return timeSent + timer * 1000L;
    }

    static boolean isSaneEphemeralTimer(final int timer) {
        return timer >= 0 && timer <= MAX_EPHEMERAL_TIMER;
    }

    /** A contact (or, in a private non-anonymous room, an occupant) set the timer. */
    private void applyEphemeralTimer(
            final Conversation conversation, final Jid from, final int timer) {
        if (!mayChangeEphemeralTimer(conversation) || !isSaneEphemeralTimer(timer)) {
            return;
        }
        conversation.setEphemeralTimer(timer);
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            conversation.setEphemeralBy(from.isBareJid() ? null : from.getResource());
        } else {
            conversation.setEphemeralBy(null);
        }
        mXmppConnectionService.databaseBackend.updateConversation(conversation);
    }

    private static final String NS_XHTML_IM = "http://jabber.org/protocol/xhtml-im";
    private static final String NS_FALLBACK = "urn:xmpp:fallback:0";
    private static final String NS_REPLY = "urn:xmpp:reply:0";
    private static final String NS_CHAT_STATES = "http://jabber.org/protocol/chatstates";
    private static final String NS_CHAT_MARKERS = "urn:xmpp:chat-markers:0";

    /**
     * Elements that say something about a conversation without being a message of their own:
     * typing, delivery and read state, reactions, the disappearing-message timer, WebXDC and
     * live-location updates. Sent in the clear they are the server's word, not the contact's.
     */
    static boolean isUnauthenticatedMetadata(final String name, final String namespace) {
        if (name == null || namespace == null) {
            return false;
        }
        return NS_CHAT_STATES.equals(namespace)
                || ("received".equals(name)
                        && ("urn:xmpp:receipts".equals(namespace)
                                || NS_CHAT_MARKERS.equals(namespace)))
                || ("displayed".equals(name) && NS_CHAT_MARKERS.equals(namespace))
                || Namespace.REACTIONS.equals(namespace)
                || Namespace.EPHEMERAL.equals(namespace)
                || Namespace.LIVE_LOCATION.equals(namespace)
                || ("x".equals(name) && "urn:xmpp:webxdc:0".equals(namespace));
    }

    /**
     * Everything that carries content or changes how content is shown. A PQ OMEMO2 sender puts all
     * of it inside the encrypted SCE envelope (see MessageGenerator.preparePacket and
     * AxolotlService.encryptOmemo2), so on the outer stanza of such a message it can only have been
     * added on the way - and must never be applied to the decrypted message.
     *
     * <p>What stays outside is routing and transport: delay, stanza-id, origin-id, processing
     * hints, the encryption hint, the receipt request, markable, the MUC {@code <x/>}, occupant-id
     * and the constant fallback body.
     */
    static boolean isOuterContentElement(final String name, final String namespace) {
        if (name == null) {
            return false;
        }
        if (isUnauthenticatedMetadata(name, namespace)) {
            return true;
        }
        final String ns = namespace == null ? "" : namespace;
        return switch (name) {
            case "html" -> NS_XHTML_IM.equals(ns);
            case "replace" -> "urn:xmpp:message-correct:0".equals(ns);
            case "retract", "moderate", "moderated", "apply-to" ->
                    ns.startsWith("urn:xmpp:message-retract:")
                            || ns.startsWith("urn:xmpp:message-moderate:")
                            || ns.startsWith("urn:xmpp:fasten:");
            case "fallback" -> NS_FALLBACK.equals(ns);
            case "reply" -> NS_REPLY.equals(ns);
            case "thread", "subject" -> ns.isEmpty() || "jabber:client".equals(ns);
            case "attention" -> "urn:xmpp:attention:0".equals(ns);
            case "unstyled" -> Namespace.STYLING.equals(ns);
            case "Description" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#".equals(ns);
            case "file-sharing" -> Namespace.SFS.equals(ns);
            case "reference" -> "urn:xmpp:reference:0".equals(ns);
            case "x" -> Namespace.OOB.equals(ns) || "jabber:x:conference".equals(ns);
            case "query" -> "http://jabber.org/protocol/disco#items".equals(ns);
            default -> false;
        };
    }

    /**
     * Legacy OMEMO (v0.3) and PGP encrypt the body only. What may never ride along: XHTML (it is
     * shown instead of the decrypted body), a link preview card (a link of the server's choosing
     * under the lock) and an attention request. For legacy OMEMO the fallback markers are limited
     * to the two its senders actually emit (reply quote, retraction); any other marker would only
     * serve to cut text out of the decrypted body. The reply quote marker is checked against the
     * decrypted body later ({@link #isQuoteFallback}).
     */
    static boolean isRefusedBesideBodyOnlyEncryption(
            final String name,
            final String namespace,
            final String fallbackFor,
            final boolean legacyOmemo) {
        if ("html".equals(name) && NS_XHTML_IM.equals(namespace)) {
            return true;
        }
        if ("Description".equals(name)
                && "http://www.w3.org/1999/02/22-rdf-syntax-ns#".equals(namespace)) {
            return true;
        }
        if ("attention".equals(name) && "urn:xmpp:attention:0".equals(namespace)) {
            return true;
        }
        if (legacyOmemo && "fallback".equals(name) && NS_FALLBACK.equals(namespace)) {
            return !NS_REPLY.equals(fallbackFor)
                    && !"urn:xmpp:message-retract:1".equals(fallbackFor);
        }
        return false;
    }

    /**
     * Whether {@code [start, end)} (code points) of a decrypted body is a reply quote as senders
     * write it: from the very start, ending inside the body, and made only of quoted lines ({@code
     * >} or {@code »}) and blank lines. A reply fallback marker sits outside the encryption of
     * legacy OMEMO / PGP; anything else under it would let the server cut real text out of the
     * decrypted message.
     */
    static boolean isQuoteFallback(final String body, final String start, final String end) {
        if (body == null || start == null || end == null) {
            return false;
        }
        final int from;
        final int to;
        try {
            from = Integer.parseInt(start);
            to = Integer.parseInt(end);
        } catch (final NumberFormatException e) {
            return false;
        }
        if (from != 0 || to <= 0 || to > body.codePointCount(0, body.length())) {
            return false;
        }
        final String cut = body.substring(0, body.offsetByCodePoints(0, to));
        boolean quoted = false;
        for (final String line : cut.split("\n", -1)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith(">") && !trimmed.startsWith("\u00bb")) {
                return false;
            }
            quoted = true;
        }
        return quoted;
    }

    /** Whether every span of a reply {@code <fallback/>} is a quote of {@code body}. */
    static boolean isQuoteFallback(final String body, final Element fallback) {
        boolean any = false;
        for (final Element span : fallback.getChildren()) {
            if (!"body".equals(span.getName())) {
                continue;
            }
            if (!isQuoteFallback(body, span.getAttribute("start"), span.getAttribute("end"))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Whether a decrypted legacy-OMEMO body is a retraction notice. The {@code <retract/>} and its
     * target id ride outside the encryption of legacy OMEMO; requiring the encrypted body to be the
     * retraction text keeps a server from stapling a {@code <retract/>} onto an ordinary encrypted
     * message of the same device and turning it into a deletion.
     */
    static boolean isLegacyRetractionBody(final String decryptedBody) {
        if (decryptedBody == null) {
            return false;
        }
        final String body = decryptedBody.trim();
        return eu.siacs.conversations.generator.MessageGenerator.RETRACTION_FALLBACK_BODY.equals(
                        body)
                // the fallback XEP-0424 (0.4.2+) recommends, sent by other clients
                || body.startsWith("/me retracted a previous message");
    }

    /** Whether the chat this stanza belongs to is set to PQ OMEMO2. */
    private boolean isOmemo2Chat(final im.conversations.android.xmpp.model.stanza.Message packet) {
        final Jid peer = packet.fromAccount(account) ? packet.getTo() : packet.getFrom();
        if (peer == null || !Jid.Invalid.isValid(peer)) {
            return false;
        }
        final Conversation conversation = mXmppConnectionService.find(account, peer.asBareJid());
        return conversation != null
                && conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2;
    }

    /**
     * Removes from an incoming stanza whatever must not be believed, before any handler sees it:
     *
     * <ul>
     *   <li>PQ OMEMO2 message: every content element outside {@code <encrypted/>}.
     *   <li>legacy OMEMO / PGP message: XHTML and stray fallback markers.
     *   <li>unencrypted stanza in a chat set to PQ OMEMO2: typing, receipts, read markers,
     *       reactions, timer changes, WebXDC and live-location updates. The contact's PQ client
     *       sends all of these encrypted; in the clear they could be forged.
     * </ul>
     */
    private void dropUntrustedOuterElements(
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        final boolean omemo2 = packet.findChild("encrypted", Namespace.OMEMO2) != null;
        final boolean legacyOmemo =
                !omemo2 && packet.findChild("encrypted", AxolotlService.PEP_PREFIX) != null;
        final boolean pgp = !omemo2 && packet.findChild("x", "jabber:x:encrypted") != null;
        final boolean strict = !omemo2 && isOmemo2Chat(packet);
        if (!omemo2 && !legacyOmemo && !pgp && !strict) {
            return;
        }
        for (final Element child : new ArrayList<>(packet.getChildren())) {
            final String name = child.getName();
            final String namespace = child.getNamespace();
            final boolean drop;
            if (omemo2) {
                drop = isOuterContentElement(name, namespace);
            } else {
                drop =
                        ((legacyOmemo || pgp)
                                        && isRefusedBesideBodyOnlyEncryption(
                                                name,
                                                namespace,
                                                child.getAttribute("for"),
                                                legacyOmemo))
                                || (strict && isUnauthenticatedMetadata(name, namespace));
            }
            if (drop) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring unauthenticated <"
                                + name
                                + "/> from "
                                + packet.getFrom());
                packet.removeChild(child);
            }
        }
    }

    @Override
    public void accept(final im.conversations.android.xmpp.model.stanza.Message original) {
        if (handleErrorMessage(account, original)) {
            return;
        }

        final im.conversations.android.xmpp.model.stanza.Message packet;
        Long timestamp = null;
        final boolean isForwarded;
        boolean isCarbon = false;
        String serverMsgId = null;
        final Element fin =
                original.findChild("fin", MessageArchiveService.Version.MAM_0.namespace);
        if (fin != null) {
            mXmppConnectionService
                    .getMessageArchiveService()
                    .processFinLegacy(fin, original.getFrom());
            return;
        }
        final Element result = MessageArchiveService.Version.findResult(original);
        final String queryId = result == null ? null : result.getAttribute("queryid");
        final MessageArchiveService.Query query =
                queryId == null
                        ? null
                        : mXmppConnectionService.getMessageArchiveService().findQuery(queryId);
        final boolean offlineMessagesRetrieved =
                account.getXmppConnection().isOfflineMessagesRetrieved();
        if (query != null && query.validFrom(original.getFrom())) {
            final var f = getForwardedMessagePacket(original, "result", query.version.namespace);
            if (f == null) {
                return;
            }
            timestamp = f.second;
            packet = f.first;
            isForwarded = true;
            serverMsgId = result.getAttribute("id");
            query.incrementMessageCount();

            if (query.isImplausibleFrom(packet.getFrom())) {
                Log.d(Config.LOGTAG, "found implausible from in MUC MAM archive");
                return;
            }

            if (handleErrorMessage(account, packet)) {
                return;
            }
            final var contact =
                    packet.getFrom() == null || packet.getFrom() instanceof Jid.Invalid
                            ? null
                            : account.getRoster().getContact(packet.getFrom());
            if (contact != null && contact.isBlocked()) {
                Log.d(Config.LOGTAG, "Got MAM result from blocked contact, ignoring...");
                return;
            }
        } else if (query != null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": received mam result with invalid from ("
                            + original.getFrom()
                            + ") or queryId ("
                            + queryId
                            + ")");
            return;
        } else if (original.fromServer(account)
                && original.getType()
                        != im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT) {
            Pair<im.conversations.android.xmpp.model.stanza.Message, Long> f;
            f = getForwardedMessagePacket(original, Received.class);
            f = f == null ? getForwardedMessagePacket(original, Sent.class) : f;
            packet = f != null ? f.first : original;
            if (handleErrorMessage(account, packet)) {
                return;
            }
            timestamp = f != null ? f.second : null;
            isCarbon = f != null;
            isForwarded = isCarbon;
        } else {
            packet = original;
            isForwarded = false;
        }

        if (timestamp == null) {
            timestamp =
                    AbstractParser.parseTimestamp(original, AbstractParser.parseTimestamp(packet));
        }

        // Before anything below reads the stanza: drop what the server could have written.
        dropUntrustedOuterElements(packet);

        final Element mucUserElement = packet.findChild("x", Namespace.MUC_USER);
        final boolean isTypeGroupChat =
                packet.getType()
                        == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
        final var encrypted =
                packet.getOnlyExtension(im.conversations.android.xmpp.model.pgp.Encrypted.class);
        final String pgpEncrypted = encrypted == null ? null : encrypted.getContent();

        Element replaceElement = packet.findChild("replace", "urn:xmpp:message-correct:0");
        Set<Message.FileParams> attachments = new LinkedHashSet<>();
        for (Element child : packet.getChildren()) {
            // XEP-0447 first, then SIMS, so the richest description wins the set (entries
            // are deduplicated by URL, so the same file described twice is added once).
            if (child.getName().equals("file-sharing")
                    && Namespace.SFS.equals(child.getNamespace())) {
                final Message.FileParams fileParams = new Message.FileParams(child);
                if (fileParams.url != null) attachments.add(fileParams);
            }
        }
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("reference")
                    && "urn:xmpp:reference:0".equals(child.getNamespace())) {
                if (child.findChild("media-sharing", "urn:xmpp:sims:1") != null) {
                    attachments.add(new Message.FileParams(child));
                }
            }
        }
        for (Element child : packet.getChildren()) {
            if (child.getName().equals("x") && Namespace.OOB.equals(child.getNamespace())) {
                attachments.add(new Message.FileParams(child));
            }
        }
        String replacementId = replaceElement == null ? null : replaceElement.getAttribute("id");
        if (replacementId == null) {
            final Element fasten = packet.findChild("apply-to", "urn:xmpp:fasten:0");
            if (fasten != null) {
                replaceElement = fasten.findChild("retract", "urn:xmpp:message-retract:0");
                if (replaceElement == null)
                    replaceElement = fasten.findChild("moderated", "urn:xmpp:message-moderate:0");
            }
            if (replaceElement == null)
                replaceElement = packet.findChild("retract", "urn:xmpp:message-retract:1");
            if (replaceElement == null)
                replaceElement = packet.findChild("moderate", "urn:xmpp:message-moderate:1");
            if (replaceElement != null) {
                // Only a moderation carries a reason; a plain retraction has nothing to show.
                String reason = null;
                if (isModeration(replaceElement)) {
                    reason =
                            replaceElement.findChildContent(
                                    "reason", Namespace.MESSAGE_MODERATE_LEGACY);
                    // XEP-0425 v0.3.0 puts <reason/> next to <moderated/>, inside <retract/>
                    if (reason == null)
                        reason =
                                replaceElement.findChildContent(
                                        "reason", Namespace.MESSAGE_RETRACT);
                    if (reason == null)
                        reason =
                                replaceElement.findChildContent(
                                        "reason", Namespace.MESSAGE_MODERATE);
                }
                replacementId = (fasten == null ? replaceElement : fasten).getAttribute("id");
                packet.setBody(reason == null ? "" : reason); // TODO: fix this
            }
        }
        LocalizedContent body = packet.getBody();

        final var reactions = packet.getExtension(Reactions.class);

        final var axolotlEncrypted = packet.getOnlyExtension(Encrypted.class);
        final Element omemo2Encrypted = packet.findChild("encrypted", Namespace.OMEMO2);
        int status;
        final Jid counterpart;
        final Jid to = packet.getTo();
        final Jid from = packet.getFrom();
        final Element originId = packet.findChild("origin-id", Namespace.STANZA_IDS);
        final String remoteMsgId;
        if (originId != null && originId.getAttribute("id") != null) {
            remoteMsgId = originId.getAttribute("id");
        } else {
            remoteMsgId = packet.getId();
        }
        boolean notify = false;

        Element html = packet.findChild("html", "http://jabber.org/protocol/xhtml-im");
        if (html != null && html.findChild("body", "http://www.w3.org/1999/xhtml") == null) {
            html = null;
        }

        if (from == null || !Jid.Invalid.isValid(from) || !Jid.Invalid.isValid(to)) {
            Log.e(Config.LOGTAG, "encountered invalid message from='" + from + "' to='" + to + "'");
            return;
        }
        if (query != null && !query.muc() && isTypeGroupChat) {
            Log.e(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": received groupchat ("
                            + from
                            + ") message on regular MAM request. skipping");
            return;
        }
        final Jid mucTrueCounterPart;
        final OccupantId occupant;
        if (isTypeGroupChat) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid());
            final Jid mucTrueCounterPartByPresence;
            final boolean senderIsSelf;
            if (conversation != null) {
                final var mucOptions = conversation.getMucOptions();
                occupant =
                        mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
                final var user =
                        occupant == null
                                ? null
                                : mucOptions.findUserByOccupantId(occupant.getId(), from);
                mucTrueCounterPartByPresence = user == null ? null : user.getRealJid();
                senderIsSelf = isSelfInConference(mucOptions, occupant, from);
            } else {
                occupant = null;
                mucTrueCounterPartByPresence = null;
                senderIsSelf = false;
            }
            mucTrueCounterPart =
                    getTrueCounterpart(
                            (query != null && query.safeToExtractTrueCounterpart())
                                    ? mucUserElement
                                    : null,
                            mucTrueCounterPartByPresence,
                            account,
                            senderIsSelf);
        } else if (mucUserElement != null) {
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid());
            if (conversation != null) {
                final var mucOptions = conversation.getMucOptions();
                occupant =
                        mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
            } else {
                occupant = null;
            }
            mucTrueCounterPart = null;
        } else {
            mucTrueCounterPart = null;
            occupant = null;
        }
        boolean isProperlyAddressed =
                (to != null) && (!to.isBareJid() || account.countPresences() == 0);
        boolean isMucStatusMessage =
                Jid.Invalid.hasValidFrom(packet)
                        && from.isBareJid()
                        && mucUserElement != null
                        && mucUserElement.hasChild("status");
        boolean selfAddressed;
        if (packet.fromAccount(account)) {
            status = Message.STATUS_SEND;
            selfAddressed = to == null || account.getJid().asBareJid().equals(to.asBareJid());
            if (selfAddressed) {
                counterpart = from;
            } else {
                counterpart = to != null ? to : account.getJid();
            }
        } else {
            status = Message.STATUS_RECEIVED;
            counterpart = from;
            selfAddressed = false;
        }

        final Invite invite = extractInvite(packet);
        if (invite != null) {
            if (invite.jid.asBareJid().equals(account.getJid().asBareJid())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignore invite to "
                                + invite.jid
                                + " because it matches account");
            } else if (isTypeGroupChat) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring invite to "
                                + invite.jid
                                + " because it was received as group chat");
            } else if (invite.direct
                    && (mucUserElement != null
                            || invite.inviter == null
                            || mXmppConnectionService.isMuc(account, invite.inviter))) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring direct invite to "
                                + invite.jid
                                + " because it was received in MUC");
            } else {
                invite.execute(account);
                return;
            }
        }

        final boolean conversationIsProbablyMuc =
                isTypeGroupChat
                        || mucUserElement != null
                        || account.getXmppConnection()
                                .getMucServersWithholdAccount()
                                .contains(counterpart.getDomain().toString());

        final Element ephemeralElement = packet.findChild("ephemeral", Namespace.EPHEMERAL);
        final boolean hasIWantOut = packet.hasChild("i-want-out", Namespace.EPHEMERAL);
        // An old timer change seen while paging back through history must not override the
        // current one; live stanzas and the catch-up after reconnecting still apply.
        final boolean historyReplay = query != null && !query.isCatchup();
        if ((ephemeralElement != null || hasIWantOut) && !historyReplay) {
            final Conversation conversation =
                    mXmppConnectionService.findOrCreateConversation(
                            account,
                            counterpart.asBareJid(),
                            conversationIsProbablyMuc,
                            false,
                            query,
                            false);
            if (ephemeralElement != null) {
                try {
                    applyEphemeralTimer(
                            conversation,
                            from,
                            Integer.parseInt(ephemeralElement.getAttribute("timer")));
                } catch (Exception e) {
                    // ignore
                }
            } else {
                switchOffEphemeralTimer(conversation);
            }
            mXmppConnectionService.updateConversationUi();
        }

        final Element webxdc = packet.findChild("x", "urn:xmpp:webxdc:0");
        final Element thread = packet.findChild("thread");
        if (webxdc != null && thread != null) {
            final Conversation conversation =
                    mXmppConnectionService.findOrCreateConversation(
                            account,
                            counterpart.asBareJid(),
                            conversationIsProbablyMuc,
                            false,
                            query,
                            false);
            processWebxdc(
                    conversation,
                    webxdc,
                    thread,
                    remoteMsgId,
                    counterpart,
                    body == null ? null : body.content);
        }

        // Basic visibility for voice requests
        Jid voiceRequester = null;
        Jid voiceRequesterRealJid = null;
        if (body == null
                && html == null
                && pgpEncrypted == null
                && axolotlEncrypted == null
                && omemo2Encrypted == null
                && !isMucStatusMessage) {
            final Element formEl = packet.findChild("x", "jabber:x:data");
            if (formEl != null) {
                final Data form = Data.parse(formEl);
                final String role = form.getValue("muc#role");
                final String nick = form.getValue("muc#roomnick");
                // Only the room relays voice requests (XEP-0045 §8.6), from its bare jid; an
                // occupant could otherwise forge one in another occupant's name.
                if ("http://jabber.org/protocol/muc#request".equals(form.getFormType())
                        && "participant".equals(role)
                        && from.isBareJid()
                        && mXmppConnectionService.isMuc(account, from)) {
                    body =
                            new LocalizedContent(
                                    ""
                                            + nick
                                            + " "
                                            + mXmppConnectionService.getString(
                                                    R.string.is_requesting_to_speak),
                                    "en",
                                    1);
                    // attribute the notification to the requesting occupant so moderators
                    // can manage the requester directly from the message avatar
                    if (nick != null && !nick.trim().isEmpty()) {
                        try {
                            voiceRequester = counterpart.asBareJid().withResource(nick);
                        } catch (final IllegalArgumentException e) {
                            Log.w(Config.LOGTAG, "voice request with unusable nick: " + nick);
                        }
                    }
                    final String requesterJid = form.getValue("muc#jid");
                    if (requesterJid != null) {
                        try {
                            voiceRequesterRealJid = Jid.of(requesterJid).asBareJid();
                        } catch (final IllegalArgumentException e) {
                            // ignore, nick attribution is enough
                        }
                    }
                }
            }
        }

        // Plaintext live-location handlers were removed. Live-location updates
        // and stops are only accepted from inside a decrypted OMEMO2 SCE
        // envelope (see parseOmemo2Chat). Accepting them from the outer
        // (unauthenticated, server-readable) stanza would let any federated
        // JID register/hijack incoming sessions on the recipient's map —
        // bypassing the SCE metadata-encryption guarantee.
        if (status == Message.STATUS_RECEIVED && query == null) {
            if (packet.findChild("live-location-update", Namespace.LIVE_LOCATION) != null
                    || packet.findChild("live-location-stop", Namespace.LIVE_LOCATION) != null) {
                Log.w(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": dropping plaintext live-location element from "
                                + from
                                + " (must arrive inside an OMEMO2 SCE envelope)");
                return;
            }
        }

        if (reactions == null
                && (body != null
                        || pgpEncrypted != null
                        || (axolotlEncrypted != null && axolotlEncrypted.hasChild("payload"))
                        || (omemo2Encrypted != null && omemo2Encrypted.hasChild("payload"))
                        || !attachments.isEmpty()
                        || html != null
                        || (packet.hasChild("subject") && packet.hasChild("thread")))
                && !isMucStatusMessage) {
            final Conversation conversation =
                    mXmppConnectionService.findOrCreateConversation(
                            account,
                            counterpart.asBareJid(),
                            conversationIsProbablyMuc,
                            false,
                            query,
                            false);
            final boolean conversationMultiMode = conversation.getMode() == Conversation.MODE_MULTI;

            if (serverMsgId == null) {
                serverMsgId = extractStanzaId(packet, isTypeGroupChat, conversation);
            }

            if (selfAddressed) {
                // don’t store serverMsgId on reflections for edits
                final var reflectedServerMsgId =
                        Strings.isNullOrEmpty(replacementId) ? serverMsgId : null;
                if (mXmppConnectionService.markMessage(
                        conversation,
                        remoteMsgId,
                        Message.STATUS_SEND_RECEIVED,
                        reflectedServerMsgId)) {
                    return;
                }
                status = Message.STATUS_RECEIVED;
                if (remoteMsgId != null
                        && conversation.findMessageWithRemoteId(remoteMsgId, counterpart) != null) {
                    return;
                }
            }

            if (isTypeGroupChat) {
                if (conversation.getMucOptions().isSelf(counterpart)) {
                    status = Message.STATUS_SEND_RECEIVED;
                    isCarbon = true; // not really carbon but received from another resource
                    // don’t store serverMsgId on reflections for edits
                    final var reflectedServerMsgId =
                            Strings.isNullOrEmpty(replacementId) ? serverMsgId : null;
                    if (mXmppConnectionService.markMessage(
                            conversation,
                            remoteMsgId,
                            status,
                            reflectedServerMsgId,
                            body,
                            html,
                            packet.findChildContent("subject"),
                            packet.findChild("thread"),
                            attachments)) {
                        return;
                    } else if (remoteMsgId == null || Config.IGNORE_ID_REWRITE_IN_MUC) {
                        if (body != null) {
                            Message message = conversation.findSentMessageWithBody(body.content);
                            if (message != null) {
                                mXmppConnectionService.markMessage(message, status);
                                return;
                            }
                        }
                    }
                } else {
                    status = Message.STATUS_RECEIVED;
                }
            }
            final Message message;
            if (body != null && body.content.startsWith("?OTR") && Config.supportOtr()) {
                if (!isForwarded
                        && !isTypeGroupChat
                        && isProperlyAddressed
                        && !conversationMultiMode) {
                    message = parseOtrChat(body.content, from, remoteMsgId, conversation);
                    if (message == null) {
                        return;
                    }
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": ignoring OTR message from "
                                    + from
                                    + " isForwarded="
                                    + Boolean.toString(isForwarded)
                                    + ", isProperlyAddressed="
                                    + Boolean.valueOf(isProperlyAddressed));
                    message =
                            new Message(
                                    conversation, body.content, Message.ENCRYPTION_NONE, status);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                }
            } else if (pgpEncrypted != null && Config.supportOpenPgp()) {
                message = new Message(conversation, pgpEncrypted, Message.ENCRYPTION_PGP, status);
            } else if (axolotlEncrypted != null && omemo2Encrypted != null) {
                // Stanza-level downgrade defence: no legitimate sender produces
                // both OMEMO2 and legacy containers on the same message. A
                // malicious server can append one to force the recipient onto
                // the weaker stack (or silently drop the OMEMO2 ciphertext via
                // the legacy "not for this device" path). Reject the entire
                // stanza rather than picking either branch.
                Log.w(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": rejecting stanza from "
                                + from
                                + " containing BOTH OMEMO2 and legacy <encrypted> elements"
                                + " (likely downgrade attempt)");
                return;
            } else if (omemo2Encrypted != null && Config.supportOmemo()) {
                Jid origin;
                if (conversationMultiMode) {
                    final Jid fallback =
                            conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin =
                            getTrueCounterpart(
                                    // Only the room's own archive vouches for <item jid>; in a
                                    // user archive (MUC PMs) the sender's client wrote it.
                                    (query != null && query.safeToExtractTrueCounterpart())
                                            ? mucUserElement
                                            : null,
                                    fallback,
                                    account,
                                    isSelfInConference(
                                            conversation.getMucOptions(), occupant, counterpart));
                    if (origin == null) {
                        Log.d(
                                Config.LOGTAG,
                                "OMEMO2 message in anonymous conference, no origin found");
                        return;
                    }
                } else {
                    origin = from;
                }
                final boolean liveMessage =
                        query == null && !isTypeGroupChat && mucUserElement == null;
                final boolean checkedForDuplicates =
                        liveMessage
                                || (serverMsgId != null
                                        && remoteMsgId != null
                                        && !conversation.possibleDuplicate(
                                                serverMsgId, remoteMsgId));
                // Reference for the SCE <time> affix (replay detection). It must be
                // the sending time as WE can establish it, never a stamp the sender
                // asserted about their own stanza: `timestamp` honours any <delay/>
                // on the stanza, so a peer replaying an old ciphertext could attach a
                // matching <delay/> and walk the check back with it.
                //
                // MAM results and carbons are wrapped by our own server, and groupchat
                // history is replayed by the room with a <delay/> that carries the
                // room's own JID (indistinguishable from one an occupant forged, and
                // required to read a backlog older than the window) — those keep the
                // stanza timestamp. Everything else is a live 1:1 stanza: honour only
                // delays from an intermediary (offline storage stamps the server's
                // JID) and otherwise fall back to the receive time.
                final Long sceReference =
                        (query != null || isCarbon || isTypeGroupChat)
                                ? timestamp
                                : AbstractParser.parseTimestampIgnoringSenderDelay(
                                        packet, System.currentTimeMillis(), packet.getFrom());
                message =
                        parseOmemo2Chat(
                                omemo2Encrypted,
                                origin,
                                conversation,
                                status,
                                checkedForDuplicates,
                                query != null,
                                occupant,
                                counterpart,
                                remoteMsgId,
                                packet,
                                sceReference,
                                query != null && !query.isCatchup());
                if (message == null) {
                    // OMEMO2-wrapped metadata-only stanza (chat state, chat marker,
                    // delivery receipt). parseOmemo2Chat() has re-injected the relevant
                    // SCE child elements onto `packet`; run the metadata handlers now,
                    // since the normal post-message processing below is skipped.
                    extractChatState(conversation, isTypeGroupChat, packet);
                    final var injectedReceived =
                            packet.getExtension(
                                    im.conversations.android.xmpp.model.receipts.Received.class);
                    if (injectedReceived != null) {
                        processReceived(injectedReceived, packet, query, from);
                    }
                    final var injectedDisplayed = packet.getExtension(Displayed.class);
                    if (injectedDisplayed != null) {
                        processDisplayed(
                                injectedDisplayed,
                                packet,
                                selfAddressed,
                                counterpart,
                                query,
                                isTypeGroupChat,
                                conversation,
                                mucUserElement,
                                from);
                    }
                    return;
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin);
                }
                // <replace> (correction) and <retract> (XEP-0424 deletion) come from the
                // decrypted SCE content — expose them to the outer message-correction logic
                // which reads replaceElement / replacementId. A non-"replace" element name
                // makes that logic treat it as a retraction (see isRetraction below).
                for (final Element p : message.getPayloads()) {
                    if ("replace".equals(p.getName())
                            && "urn:xmpp:message-correct:0".equals(p.getNamespace())) {
                        replaceElement = p;
                        replacementId = p.getAttribute("id");
                        break;
                    }
                    if ("retract".equals(p.getName())
                            && "urn:xmpp:message-retract:1".equals(p.getNamespace())) {
                        replaceElement = p;
                        replacementId = p.getAttribute("id");
                        break;
                    }
                }
            } else if (axolotlEncrypted != null && Config.supportOmemo()) {
                Jid origin;
                Set<Jid> fallbacksBySourceId = Collections.emptySet();
                if (conversationMultiMode) {
                    final Jid fallback =
                            conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin =
                            getTrueCounterpart(
                                    // Only the room's own archive vouches for <item jid>; in a
                                    // user archive (MUC PMs) the sender's client wrote it.
                                    (query != null && query.safeToExtractTrueCounterpart())
                                            ? mucUserElement
                                            : null,
                                    fallback,
                                    account,
                                    isSelfInConference(
                                            conversation.getMucOptions(), occupant, counterpart));
                    if (origin == null) {
                        try {
                            fallbacksBySourceId =
                                    account.getAxolotlService()
                                            .findCounterpartsBySourceId(
                                                    XmppAxolotlMessage.parseSourceId(
                                                            axolotlEncrypted));
                        } catch (IllegalArgumentException e) {
                            // ignoring
                        }
                    }
                    if (origin == null && fallbacksBySourceId.isEmpty()) {
                        Log.d(
                                Config.LOGTAG,
                                "axolotl message in anonymous conference received and no possible"
                                        + " fallbacks");
                        return;
                    }
                } else {
                    fallbacksBySourceId = Collections.emptySet();
                    origin = from;
                }

                final boolean liveMessage =
                        query == null && !isTypeGroupChat && mucUserElement == null;
                final boolean checkedForDuplicates =
                        liveMessage
                                || (serverMsgId != null
                                        && remoteMsgId != null
                                        && !conversation.possibleDuplicate(
                                                serverMsgId, remoteMsgId));

                if (origin != null) {
                    message =
                            parseAxolotlChat(
                                    axolotlEncrypted,
                                    origin,
                                    conversation,
                                    status,
                                    checkedForDuplicates,
                                    query != null);
                } else {
                    Message trial = null;
                    for (Jid fallback : fallbacksBySourceId) {
                        trial =
                                parseAxolotlChat(
                                        axolotlEncrypted,
                                        fallback,
                                        conversation,
                                        status,
                                        checkedForDuplicates && fallbacksBySourceId.size() == 1,
                                        query != null);
                        if (trial != null) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": decoded muc message using fallback");
                            origin = fallback;
                            break;
                        }
                    }
                    message = trial;
                }
                if (message == null) {
                    if (query == null
                            && extractChatState(
                                    mXmppConnectionService.find(account, counterpart.asBareJid()),
                                    isTypeGroupChat,
                                    packet)) {
                        mXmppConnectionService.updateConversationUi();
                    }
                    if (query != null && status == Message.STATUS_SEND && remoteMsgId != null) {
                        Message previouslySent = conversation.findSentMessageWithUuid(remoteMsgId);
                        if (previouslySent != null
                                && previouslySent.getServerMsgId() == null
                                && serverMsgId != null) {
                            previouslySent.setServerMsgId(serverMsgId);
                            mXmppConnectionService.databaseBackend.updateMessage(
                                    previouslySent, false);
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": encountered previously sent OMEMO message without"
                                            + " serverId. updating...");
                        }
                    }
                    return;
                }
                if (conversationMultiMode) {
                    message.setTrueCounterpart(origin);
                }
            } else if (body == null && !attachments.isEmpty()) {
                message = new Message(conversation, "", Message.ENCRYPTION_NONE, status);
            } else {
                message =
                        new Message(
                                conversation,
                                body == null ? null : body.content,
                                Message.ENCRYPTION_NONE,
                                status);
                if (body != null && body.count > 1) {
                    message.setBodyLanguage(body.language);
                }
            }

            Element addresses = packet.findChild("addresses", "http://jabber.org/protocol/address");
            if (status == Message.STATUS_RECEIVED && addresses != null) {
                for (Element address : addresses.getChildren()) {
                    if (!address.getName().equals("address")
                            || !"http://jabber.org/protocol/address".equals(address.getNamespace()))
                        continue;

                    if ("ofrom".equals(address.getAttribute("type"))
                            && address.getAttribute("jid") != null) {
                        Jid ofrom = address.getAttributeAsJid("jid");
                        if (Jid.Invalid.isValid(ofrom)
                                && ofrom.getDomain().equals(counterpart.getDomain())
                                && conversation
                                        .getAccount()
                                        .getRoster()
                                        .getContact(counterpart.getDomain())
                                        .getPresences()
                                        .anySupport("http://jabber.org/protocol/address")) {

                            message.setTrueCounterpart(ofrom);
                        }
                    }
                }
            }

            if (html != null) message.addPayload(html);
            // For OMEMO2 the subject arrives inside the encrypted SCE envelope and was
            // set by parseOmemo2Chat; never read it from the outer stanza there — a
            // malicious server could inject a plaintext <subject> (and a null here
            // would clear the decrypted one).
            if (omemo2Encrypted == null) {
                message.setSubject(packet.findChildContent("subject"));
            }
            message.setCounterpart(voiceRequester != null ? voiceRequester : counterpart);
            if (voiceRequester != null && voiceRequesterRealJid != null && conversationMultiMode) {
                message.setTrueCounterpart(voiceRequesterRealJid);
            }
            message.setRemoteMsgId(remoteMsgId);
            message.setServerMsgId(serverMsgId);
            message.setCarbon(isCarbon);
            message.setTime(timestamp);

            if (ephemeralElement != null) {
                try {
                    int timer = Integer.parseInt(ephemeralElement.getAttribute("timer"));
                    if (isSaneEphemeralTimer(timer)) {
                        message.setEphemeralTimer(timer);
                    }
                } catch (Exception e) {
                    // ignore invalid timer
                }
            }

            final long ownExpiry =
                    ownMessageExpiry(status, message.getTimeSent(), message.getEphemeralTimer());
            if (ownExpiry > 0) {
                message.setExpireAt(ownExpiry);
            }

            // For OMEMO2 the file description arrives inside the encrypted SCE envelope and
            // was set by parseOmemo2Chat; never take it from the outer stanza there, where a
            // malicious server could inject a <file-sharing/> or <x jabber:x:oob/> pointing
            // at a file of its choosing. Same rule as <subject> above.
            if (!attachments.isEmpty() && omemo2Encrypted == null) {
                for (final Message.FileParams fileParams : attachments) {
                    // Legacy OMEMO encrypts the file URL in the body; an outer description of
                    // any other file would put the server's file under the lock.
                    if (axolotlEncrypted != null
                            && (fileParams.url == null
                                    || message.getRawBody() == null
                                    || !fileParams.url.equals(message.getRawBody().trim()))) {
                        continue;
                    }
                    addFileToMessage(message, fileParams);
                }
            }
            // The extra files of a multi-file message are rows of their own; they inherit
            // everything that identifies the message they were sent with.
            for (final Message attachment : message.getAttachments()) {
                attachment.setTime(message.getTimeSent());
                attachment.setStatus(message.getStatus());
                attachment.setEncryption(message.getEncryption());
                attachment.setCounterpart(message.getCounterpart());
                attachment.setTrueCounterpart(message.getTrueCounterpart());
                attachment.setCarbon(message.isCarbon());
                attachment.setParentUuid(message.getUuid());
                // Ephemeral messages are wiped by a query over expire_at, so every row of the
                // message needs the timer — otherwise the extra files would outlive it.
                attachment.setEphemeralTimer(message.getEphemeralTimer());
                attachment.setExpireAt(message.getExpireAt());
            }
            message.markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
            for (Element el : packet.getChildren()) {
                if ((el.getName().equals("query")
                                && "http://jabber.org/protocol/disco#items"
                                        .equals(el.getNamespace())
                                && "http://jabber.org/protocol/commands"
                                        .equals(el.getAttribute("node")))
                        || (el.getName().equals("fallback")
                                && "urn:xmpp:fallback:0".equals(el.getNamespace()))) {
                    message.addPayload(el);
                }
                if (el.getName().equals("thread")
                        && (el.getNamespace() == null
                                || "jabber:client".equals(el.getNamespace()))) {
                    el.setAttribute("xmlns", "jabber:client");
                    message.addPayload(el);
                }
                if (el.getName().equals("reply")
                        && el.getNamespace() != null
                        && "urn:xmpp:reply:0".equals(el.getNamespace())) {
                    message.addPayload(el);
                    if (el.getAttribute("id") != null) {
                        for (final var parent :
                                mXmppConnectionService
                                        .getMessageFuzzyIds(
                                                conversation, List.of(el.getAttribute("id")))
                                        .entrySet()) {
                            message.setInReplyTo(parent.getValue());
                        }
                    }
                }
                if (el.getName().equals("attention")
                        && el.getNamespace() != null
                        && "urn:xmpp:attention:0".equals(el.getNamespace())) {
                    message.addPayload(el);
                }
                if (el.getName().equals("unstyled")
                        && Namespace.STYLING.equals(el.getNamespace())) {
                    message.addPayload(el);
                }
                if (el.getName().equals("Description")
                        && el.getNamespace() != null
                        && "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                                .equals(el.getNamespace())) {
                    message.addPayload(el);
                }
            }
            if (axolotlEncrypted != null || pgpEncrypted != null) {
                // Body-only encryption: a reply marker from outside may only hide the quote
                // the decrypted body really starts with. PGP is decrypted later, so its
                // marker can't be checked and is dropped (the quote then shows in full).
                for (final Element fallback : message.getFallbacks(NS_REPLY)) {
                    if (pgpEncrypted != null || !isQuoteFallback(message.getRawBody(), fallback)) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": ignoring reply fallback that is not a quote of the"
                                        + " decrypted body");
                        message.removePayload(fallback);
                    }
                }
            }
            if (conversationMultiMode) {
                final var mucOptions = conversation.getMucOptions();
                if (occupant != null) {
                    message.setOccupantId(occupant.getId());
                }
                message.setMucUser(mucOptions.findUserByFullJid(counterpart));
                final Jid fallback = mucOptions.getTrueCounterpart(counterpart);
                Jid trueCounterpart;
                if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                    trueCounterpart = message.getTrueCounterpart();
                } else if (query != null && query.safeToExtractTrueCounterpart()) {
                    trueCounterpart =
                            getTrueCounterpart(
                                    mucUserElement,
                                    fallback,
                                    account,
                                    isSelfInConference(mucOptions, occupant, counterpart));
                } else {
                    trueCounterpart = fallback;
                }
                if (trueCounterpart != null && isTypeGroupChat) {
                    if (trueCounterpart.asBareJid().equals(account.getJid().asBareJid())) {
                        status =
                                isTypeGroupChat
                                        ? Message.STATUS_SEND_RECEIVED
                                        : Message.STATUS_SEND;
                    } else {
                        status = Message.STATUS_RECEIVED;
                        message.setCarbon(false);
                    }
                }
                message.setStatusOfReflectedMessage(status);
                message.setTrueCounterpart(trueCounterpart);
                if (!isTypeGroupChat) {
                    message.setType(Message.TYPE_PRIVATE);
                }
            } else {
                updateLastseen(account, from);
            }

            if (replacementId != null
                    && axolotlEncrypted != null
                    && replaceElement != null
                    && !"replace".equals(replaceElement.getName())
                    && !isModeration(replaceElement)
                    && !isLegacyRetractionBody(message.getRawBody())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring <retract/> on a legacy OMEMO message that is not a"
                                + " retraction");
                message.removePayload(replaceElement);
                replaceElement = null;
                replacementId = null;
            }
            if (replacementId != null
                    && replaceElement != null
                    && !"replace".equals(replaceElement.getName())
                    && !mXmppConnectionService.allowMessageCorrection()) {
                // XEP-0424: the body of a retraction is the sender's fallback text and is
                // never shown, also when retractions aren't applied.
                return;
            }
            if (replacementId != null && mXmppConnectionService.allowMessageCorrection()) {
                final boolean moderation = isModeration(replaceElement);
                // XEP-0425: a moderation is only legitimate from the room itself, and it names
                // the message by the stanza id the room assigned - never by the sender's ids.
                final boolean legitimateModeration =
                        moderation && conversationMultiMode && counterpart.isBareJid();
                final Message replacedMessage;
                if (moderation && !legitimateModeration) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": discarding moderation not sent by the room: "
                                    + counterpart);
                    replacedMessage = null;
                } else if (moderation) {
                    replacedMessage = conversation.findMessageWithServerMsgId(replacementId);
                } else {
                    // Several messages can carry the same id (ids are chosen by senders): take the
                    // first one this sender may replace, so a reused id can't shadow the real
                    // target (Conversations db963e5b1).
                    final List<Message> found =
                            conversation.findSentMessagesWithUuidOrRemoteId(
                                    replacementId, true, true);
                    // A correction never targets a retracted message (see below); with a reused
                    // id, prefer the live message carrying it. If all of them are retracted,
                    // keep them, so the "retracted is final" check below drops the correction.
                    final boolean isCorrection =
                            replaceElement != null && "replace".equals(replaceElement.getName());
                    final List<Message> live = new ArrayList<>();
                    for (final Message candidate : found) {
                        if (!isCorrection || !candidate.isDeleted()) {
                            live.add(candidate);
                        }
                    }
                    final List<Message> candidates = live.isEmpty() ? found : live;
                    Message authorized = null;
                    for (final Message candidate : candidates) {
                        if (isAuthorizedReplacement(
                                account,
                                candidate,
                                message,
                                replaceElement,
                                conversationMultiMode,
                                counterpart,
                                query)) {
                            authorized = candidate;
                            break;
                        }
                    }
                    replacedMessage =
                            authorized != null
                                    ? authorized
                                    : (candidates.isEmpty() ? null : candidates.get(0));
                }
                if (replacedMessage != null
                        && replacedMessage.isDeleted()
                        && replaceElement != null
                        && "replace".equals(replaceElement.getName())) {
                    // Retracted is final: a correction arriving late (archive catch-up, a
                    // re-delivered stanza) must not write content back into it, and must not
                    // show up as a message of its own either.
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": ignoring correction of a retracted message");
                    return;
                }
                if (replacedMessage != null) {
                    final boolean duplicate = conversation.hasDuplicateMessage(message);
                    if (isAuthorizedReplacement(
                                    account,
                                    replacedMessage,
                                    message,
                                    replaceElement,
                                    conversationMultiMode,
                                    counterpart,
                                    query)
                            && !duplicate) {
                        synchronized (replacedMessage) {
                            final String uuid = replacedMessage.getUuid();
                            replacedMessage.setUuid(UUID.randomUUID().toString());
                            replacedMessage.setBody(message.getBody());
                            replacedMessage.setSubject(message.getSubject());
                            replacedMessage.setThread(message.getThread());
                            // The first entry names the original by its wire id, which every
                            // later correction (ours or theirs) refers to. Our own message sent
                            // from here has no remoteMsgId: it went out under its uuid. Only
                            // ours: a received one without an id keeps none (dedup and markers
                            // go by it, and our uuid is not an id its sender ever used).
                            if (!replacedMessage.edited()) {
                                if (replacedMessage.getRemoteMsgId() == null
                                        && replacedMessage.getStatus() != Message.STATUS_RECEIVED) {
                                    replacedMessage.setRemoteMsgId(uuid);
                                }
                                replacedMessage.putEdited(
                                        replacedMessage.getRemoteMsgId(),
                                        replacedMessage.getServerMsgId());
                            }
                            if (replaceElement != null
                                    && !replaceElement.getName().equals("replace")) {
                                retractLocally(
                                        replacedMessage, replaceElement, replacementId, uuid);
                                mXmppConnectionService.updateMessage(
                                        replacedMessage, replacedMessage.getUuid());
                                mXmppConnectionService
                                        .getNotificationService()
                                        .markRetracted(replacedMessage);
                            } else {
                                replacedMessage.clearPayloads();
                                for (final var p : message.getPayloads()) {
                                    replacedMessage.addPayload(p);
                                }
                            }
                            replacedMessage.setInReplyTo(message.getInReplyTo());

                            // we store the IDs of the replacing message. This is essentially unused
                            // today (only the fact that there are _some_ edits causes the edit icon
                            // to appear)
                            replacedMessage.putEdited(
                                    message.getRemoteMsgId(), message.getServerMsgId());

                            // we used to call
                            // `replacedMessage.setServerMsgId(message.getServerMsgId());` so during
                            // catchup we could start from the edit; not the original message
                            // however this caused problems for things like reactions that refer to
                            // the serverMsgId

                            replacedMessage.setEncryption(message.getEncryption());
                            if (replacedMessage.getStatus() == Message.STATUS_RECEIVED) {
                                replacedMessage.markUnread();
                            }
                            extractChatState(
                                    mXmppConnectionService.find(account, counterpart.asBareJid()),
                                    isTypeGroupChat,
                                    packet);
                            mXmppConnectionService.updateMessage(replacedMessage, uuid);
                            if (mXmppConnectionService.confirmMessages()
                                    && replacedMessage.getStatus() == Message.STATUS_RECEIVED
                                    && (replacedMessage.trusted()
                                            || replacedMessage
                                                    .isPrivateMessage()) // TODO do we really want
                                    // to send receipts for all
                                    // PMs?
                                    && remoteMsgId != null
                                    && !selfAddressed
                                    && !isTypeGroupChat) {
                                processMessageReceipts(account, packet, remoteMsgId, query);
                            }
                            if (replacedMessage.getEncryption() == Message.ENCRYPTION_PGP) {
                                conversation
                                        .getAccount()
                                        .getPgpDecryptionService()
                                        .discard(replacedMessage);
                                conversation
                                        .getAccount()
                                        .getPgpDecryptionService()
                                        .decrypt(replacedMessage, false);
                            }
                        }
                        mXmppConnectionService.getNotificationService().updateNotification();
                        return;
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": received message correction but verification didn't"
                                        + " check out");
                    }
                } else {
                    // A retraction before its message (paging back through an archive): apply it
                    // when the message arrives, under the same sender check (see
                    // takePendingRetraction). Moderations name the room's stanza id and are
                    // only ever applied to a message we have.
                    if (!moderation
                            && replaceElement != null
                            && !replaceElement.getName().equals("replace")) {
                        conversation.addPendingRetraction(
                                new Conversation.PendingRetraction(
                                        replacementId,
                                        message,
                                        replaceElement,
                                        conversationMultiMode && counterpart.isBareJid()));
                    }
                    if (message.getBody() == null
                            || message.getBody().equals("")
                            || message.getBody().equals(" ")) {
                        return;
                    }
                }
                if (replaceElement != null && !replaceElement.getName().equals("replace")) return;
            }

            boolean checkForDuplicates =
                    (isTypeGroupChat && packet.hasChild("delay", "urn:xmpp:delay"))
                            || message.isPrivateMessage()
                            || message.getServerMsgId() != null
                            || (query == null
                                    && mXmppConnectionService
                                            .getMessageArchiveService()
                                            .isCatchupInProgress(conversation));
            if (checkForDuplicates) {
                final Message duplicate = conversation.findDuplicateMessage(message);
                if (duplicate != null) {
                    final boolean serverMsgIdUpdated;
                    if (duplicate.getStatus() != Message.STATUS_RECEIVED
                            && duplicate.getUuid().equals(message.getRemoteMsgId())
                            && duplicate.getServerMsgId() == null
                            && message.getServerMsgId() != null) {
                        duplicate.setServerMsgId(message.getServerMsgId());
                        if (mXmppConnectionService.databaseBackend.updateMessage(
                                duplicate, false)) {
                            serverMsgIdUpdated = true;
                        } else {
                            serverMsgIdUpdated = false;
                            Log.e(Config.LOGTAG, "failed to update message");
                        }
                    } else {
                        serverMsgIdUpdated = false;
                    }
                    // e.g. our own message, whose room stanza id a retraction addressed before
                    // this reflection brought it
                    final Conversation.PendingRetraction pendingRetraction =
                            takePendingRetraction(account, conversation, duplicate, message);
                    if (pendingRetraction != null) {
                        synchronized (duplicate) {
                            retractLocally(
                                    duplicate,
                                    pendingRetraction.element,
                                    pendingRetraction.id,
                                    duplicate.getUuid());
                            duplicate.setBody("");
                            duplicate.putEdited(
                                    pendingRetraction.retraction.getRemoteMsgId(),
                                    pendingRetraction.retraction.getServerMsgId());
                            mXmppConnectionService.updateMessage(duplicate, duplicate.getUuid());
                            mXmppConnectionService
                                    .getNotificationService()
                                    .markRetracted(duplicate);
                        }
                        mXmppConnectionService.getNotificationService().updateNotification();
                    }
                    Log.d(
                            Config.LOGTAG,
                            "skipping duplicate message with "
                                    + message.getCounterpart()
                                    + ". serverMsgIdUpdated="
                                    + serverMsgIdUpdated);
                    return;
                }
            }

            // Retracted before it got here: stored as retracted (hidden, like any retracted
            // message), never notified or counted unread.
            final Conversation.PendingRetraction pendingRetraction =
                    takePendingRetraction(account, conversation, message, message);
            if (pendingRetraction != null) {
                message.setBody("");
                message.setOob(false);
                message.clearPayloads();
                message.setFileParams(null);
                message.addPayload(pendingRetraction.element);
                message.setDeleted(true);
                message.setRetractId(pendingRetraction.id);
                // its extra files (multi-file message) are retracted too: never stored, never
                // downloaded (storeAndDownloadAttachments below would keep their URL and key)
                for (final Message attachment : new ArrayList<>(message.getAttachments())) {
                    message.removeAttachment(attachment);
                }
            }

            if (query != null
                    && query.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
                conversation.prepend(query.getActualInThisQuery(), message);
            } else {
                conversation.add(message);
            }
            if (query != null) {
                query.incrementActualMessageCount();
            }

            if (pendingRetraction != null) {
                notify = false;
            } else if (query == null || query.isCatchup()) { // either no mam or catchup
                if (status == Message.STATUS_SEND || status == Message.STATUS_SEND_RECEIVED) {
                    mXmppConnectionService.markRead(conversation);
                    if (query == null) {
                        activateGracePeriod(account);
                    }
                } else {
                    message.markUnread();
                    notify = true;
                }
            }

            if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                notify =
                        conversation
                                .getAccount()
                                .getPgpDecryptionService()
                                .decrypt(message, notify);
            } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE
                    || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED
                    || message.getEncryption()
                            == Message.ENCRYPTION_AXOLOTL_OMEMO2_NOT_FOR_THIS_DEVICE
                    || message.getEncryption() == Message.ENCRYPTION_AXOLOTL_OMEMO2_FAILED) {
                notify = false;
            }

            if (query == null) {
                extractChatState(
                        mXmppConnectionService.find(account, counterpart.asBareJid()),
                        isTypeGroupChat,
                        packet);
                mXmppConnectionService.updateConversationUi();
            }

            if (mXmppConnectionService.confirmMessages()
                    && message.getStatus() == Message.STATUS_RECEIVED
                    && (message.trusted() || message.isPrivateMessage())
                    && remoteMsgId != null
                    && !selfAddressed
                    && !isTypeGroupChat) {
                processMessageReceipts(account, packet, remoteMsgId, query);
            }

            if (message.getFileParams() != null) {
                for (Cid cid : message.getFileParams().getCids()) {
                    File f = mXmppConnectionService.getFileForCid(cid);
                    if (f != null && f.canRead()) {
                        message.setRelativeFilePath(f.getAbsolutePath());
                        mXmppConnectionService
                                .getFileBackend()
                                .updateFileParams(message, null, false);
                        break;
                    }
                }
            }

            if (message.getStatus() == Message.STATUS_RECEIVED
                    && conversation.getOtrSession() != null
                    && !conversation
                            .getOtrSession()
                            .getSessionID()
                            .getUserID()
                            .equals(message.getCounterpart().getResource())) {
                conversation.endOtrIfNeeded();
            }

            // Register incoming live-location sessions
            if (status == Message.STATUS_RECEIVED && message.isGeoUri()) {
                eu.siacs.conversations.xml.Element liveLocEl =
                        packet.findChild("live-location", Namespace.LIVE_LOCATION);
                if (liveLocEl == null) {
                    // For OMEMO2, the element was inside the SCE payload and is now in message
                    // payloads
                    for (final eu.siacs.conversations.xml.Element pl : message.getPayloads()) {
                        if ("live-location".equals(pl.getName())
                                && Namespace.LIVE_LOCATION.equals(pl.getNamespace())) {
                            liveLocEl = pl;
                            break;
                        }
                    }
                }
                if (liveLocEl != null) {
                    final String sessionId = liveLocEl.getAttribute("id");
                    final String expiresAtStr = liveLocEl.getAttribute("expires");
                    if (sessionId != null && expiresAtStr != null) {
                        try {
                            long expiresAt =
                                    eu.siacs.conversations.parser.AbstractParser.parseTimestamp(
                                            expiresAtStr);
                            final java.util.regex.Matcher geoMatcher =
                                    eu.siacs.conversations.utils.GeoHelper.GEO_URI.matcher(
                                            message.getRawBody());
                            if (geoMatcher.matches()) {
                                final double lat = Double.parseDouble(geoMatcher.group(1));
                                final double lon = Double.parseDouble(geoMatcher.group(2));
                                eu.siacs.conversations.utils.LiveLocationManager.getInstance()
                                        .registerIncomingSession(
                                                sessionId,
                                                message.getConversation().getUuid(),
                                                message.getUuid(),
                                                lat,
                                                lon,
                                                expiresAt);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (!message.getPayloads().contains(liveLocEl)) {
                        message.addPayload(liveLocEl);
                    }
                }
            }

            mXmppConnectionService.databaseBackend.createMessage(message);
            storeAndDownloadAttachments(message);
            if (message.isEphemeral()) {
                mXmppConnectionService.scheduleNextExpiry();
            }
            final HttpConnectionManager manager =
                    this.mXmppConnectionService.getHttpConnectionManager();
            if (message.trusted()
                    && message.treatAsDownloadable()
                    && manager.getAutoAcceptFileSize() > 0) {
                if (message.getOob() != null
                        && "cid".equalsIgnoreCase(message.getOob().getScheme())) {
                    if (message.getEncryption() == Message.ENCRYPTION_NONE) {
                        try {
                            BobTransfer transfer =
                                    new BobTransfer.ForMessage(message, mXmppConnectionService);
                            message.setTransferable(transfer);
                            transfer.start();
                        } catch (URISyntaxException e) {
                            Log.d(Config.LOGTAG, "BobTransfer failed to parse URI");
                        }
                    }
                } else {
                    manager.createNewDownloadConnection(message);
                }
            } else if (notify) {
                if (query != null && query.isCatchup()) {
                    mXmppConnectionService.getNotificationService().pushFromBacklog(message);
                } else {
                    mXmppConnectionService.getNotificationService().push(message);
                }
            }
        } else if (!packet.hasChild("body")) { // no body
            final Conversation conversation =
                    mXmppConnectionService.find(account, from.asBareJid());
            if (axolotlEncrypted != null) {
                Jid origin;
                if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                    final Jid fallback =
                            conversation.getMucOptions().getTrueCounterpart(counterpart);
                    origin =
                            getTrueCounterpart(
                                    // Only the room's own archive vouches for <item jid>; in a
                                    // user archive (MUC PMs) the sender's client wrote it.
                                    (query != null && query.safeToExtractTrueCounterpart())
                                            ? mucUserElement
                                            : null,
                                    fallback,
                                    account,
                                    isSelfInConference(
                                            conversation.getMucOptions(), occupant, counterpart));
                    if (origin == null) {
                        Log.d(
                                Config.LOGTAG,
                                "omemo key transport message in anonymous conference received");
                        return;
                    }
                } else if (isTypeGroupChat) {
                    return;
                } else {
                    origin = from;
                }
                try {
                    final XmppAxolotlMessage xmppAxolotlMessage =
                            XmppAxolotlMessage.fromElement(axolotlEncrypted, origin.asBareJid());
                    account.getAxolotlService()
                            .processReceivingKeyTransportMessage(xmppAxolotlMessage, query != null);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": omemo key transport message received from "
                                    + origin);
                } catch (Exception e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": invalid omemo key transport message received "
                                    + e.getMessage());
                    return;
                }
            }

            if (query == null
                    && extractChatState(
                            mXmppConnectionService.find(account, counterpart.asBareJid()),
                            isTypeGroupChat,
                            packet)) {
                mXmppConnectionService.updateConversationUi();
            }

            if (isTypeGroupChat) {
                if (packet.hasChild("subject")
                        && !packet.hasChild("thread")) { // We already know it has no body per above
                    if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                        conversation.setHasMessagesLeftOnServer(conversation.countMessages() > 0);
                        final LocalizedContent subject =
                                packet.findInternationalizedChildContentInDefaultNamespace(
                                        "subject");
                        if (subject != null
                                && conversation.getMucOptions().setSubject(subject.content)) {
                            mXmppConnectionService.updateConversation(conversation);
                        }
                        mXmppConnectionService.updateConversationUi();
                        return;
                    }
                }
            }
            // Room status messages (configuration / affiliation changes) only count live: a MAM
            // page or carbon would replay stale affiliations over the current member list.
            if (conversation != null
                    && conversation.getMode() == Conversation.MODE_MULTI
                    && !isForwarded
                    && mucUserElement != null
                    && Jid.Invalid.hasValidFrom(packet)
                    && from.isBareJid()) {
                for (Element child : mucUserElement.getChildren()) {
                    if ("status".equals(child.getName())) {
                        try {
                            int code = Integer.parseInt(child.getAttribute("code"));
                            if ((code >= 170 && code <= 174) || (code >= 102 && code <= 104)) {
                                mXmppConnectionService.fetchConferenceConfiguration(conversation);
                                break;
                            }
                        } catch (Exception e) {
                            // ignored
                        }
                    } else if ("item".equals(child.getName())) {
                        final var user = AbstractParser.parseItem(conversation, child);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid()
                                        + ": changing affiliation for "
                                        + user.getRealJid()
                                        + " to "
                                        + user.getAffiliation()
                                        + " in "
                                        + conversation.getJid().asBareJid());
                        if (!user.realJidMatchesAccount()) {
                            final var mucOptions = conversation.getMucOptions();
                            final boolean isNew = mucOptions.updateUser(user);
                            final var avatarService = mXmppConnectionService.getAvatarService();
                            if (Strings.isNullOrEmpty(mucOptions.getAvatar())) {
                                avatarService.clear(mucOptions);
                            }
                            avatarService.clear(user);
                            mXmppConnectionService.updateMucRosterUi();
                            mXmppConnectionService.updateConversationUi();
                            Contact contact = user.getContact();
                            if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                                Jid jid = user.getRealJid();
                                List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                                if (cryptoTargets.remove(user.getRealJid())) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": removed "
                                                    + jid
                                                    + " from crypto targets of "
                                                    + conversation.getName());
                                    conversation.setAcceptedCryptoTargets(cryptoTargets);
                                    mXmppConnectionService.updateConversation(conversation);
                                }
                            } else if (isNew
                                    && user.getRealJid() != null
                                    && conversation.getMucOptions().isPrivateAndNonAnonymous()
                                    && (contact == null || !contact.mutualPresenceSubscription())
                                    && account.getAxolotlService()
                                            .hasEmptyDeviceList(user.getRealJid())) {
                                account.getAxolotlService().fetchDeviceIds(user.getRealJid());
                            }
                        }
                    }
                }
            }
            if (!isTypeGroupChat) {
                for (Element child : packet.getChildren()) {
                    if (Namespace.JINGLE_MESSAGE.equals(child.getNamespace())
                            && JINGLE_MESSAGE_ELEMENT_NAMES.contains(child.getName())) {
                        final String action = child.getName();
                        final String sessionId = child.getAttribute("id");
                        if (sessionId == null) {
                            break;
                        }
                        if (query == null && offlineMessagesRetrieved) {
                            if (serverMsgId == null) {
                                serverMsgId = extractStanzaId(account, packet);
                            }
                            mXmppConnectionService
                                    .getJingleConnectionManager()
                                    .deliverMessage(
                                            account,
                                            packet.getTo(),
                                            packet.getFrom(),
                                            child,
                                            remoteMsgId,
                                            serverMsgId,
                                            timestamp);
                            final Contact contact = account.getRoster().getContact(from);
                            // this is the same condition that is found in JingleRtpConnection for
                            // the 'ringing' response. Responding with delivery receipts predates
                            // the 'ringing' spec'd
                            final boolean sendReceipts =
                                    contact.showInContactList()
                                            || Config.JINGLE_MESSAGE_INIT_STRICT_OFFLINE_CHECK;
                            if (remoteMsgId != null && !contact.isSelf() && sendReceipts) {
                                processMessageReceipts(account, packet, remoteMsgId, null);
                            }
                        } else if ((query != null && query.isCatchup())
                                || !offlineMessagesRetrieved) {
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace =
                                        description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c =
                                            mXmppConnectionService.findOrCreateConversation(
                                                    account, counterpart.asBareJid(), false, false);
                                    final Message preExistingMessage =
                                            c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message =
                                            new Message(
                                                    c, status, Message.TYPE_RTP_SESSION, sessionId);
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(false, 0).toString());
                                    message.markUnread();
                                    c.add(message);
                                    mXmppConnectionService
                                            .getNotificationService()
                                            .possiblyMissedCall(c.getUuid() + sessionId, message);
                                    if (query != null) query.incrementActualMessageCount();
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                    if (message.isEphemeral()) {
                                        mXmppConnectionService.scheduleNextExpiry();
                                    }
                                }
                            } else if ("proceed".equals(action)) {
                                // status needs to be flipped to find the original propose
                                final Conversation c =
                                        mXmppConnectionService.findOrCreateConversation(
                                                account, counterpart.asBareJid(), false, false);
                                final int s =
                                        packet.fromAccount(account)
                                                ? Message.STATUS_RECEIVED
                                                : Message.STATUS_SEND;
                                final Message message = c.findRtpSession(sessionId, s);
                                if (message != null) {
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    if (serverMsgId != null) {
                                        message.setServerMsgId(serverMsgId);
                                    }
                                    message.setTime(timestamp);
                                    message.markRead();
                                    mXmppConnectionService
                                            .getNotificationService()
                                            .possiblyMissedCall(c.getUuid() + sessionId, message);
                                    if (query != null) query.incrementActualMessageCount();
                                    mXmppConnectionService.updateMessage(message, true);
                                } else {
                                    Log.d(
                                            Config.LOGTAG,
                                            "unable to find original rtp session message for"
                                                    + " received propose");
                                }

                            } else if ("finish".equals(action)) {
                                Log.d(
                                        Config.LOGTAG,
                                        "received JMI 'finish' during MAM catch-up. Can be used to"
                                                + " update success/failure and duration");
                            }
                        } else {
                            // MAM reloads (non catchups
                            if ("propose".equals(action)) {
                                final Element description = child.findChild("description");
                                final String namespace =
                                        description == null ? null : description.getNamespace();
                                if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                                    final Conversation c =
                                            mXmppConnectionService.findOrCreateConversation(
                                                    account, counterpart.asBareJid(), false, false);
                                    final Message preExistingMessage =
                                            c.findRtpSession(sessionId, status);
                                    if (preExistingMessage != null) {
                                        preExistingMessage.setServerMsgId(serverMsgId);
                                        mXmppConnectionService.updateMessage(preExistingMessage);
                                        break;
                                    }
                                    final Message message =
                                            new Message(
                                                    c, status, Message.TYPE_RTP_SESSION, sessionId);
                                    message.setServerMsgId(serverMsgId);
                                    message.setTime(timestamp);
                                    message.setBody(new RtpSessionStatus(true, 0).toString());
                                    if (query.getPagingOrder()
                                            == MessageArchiveService.PagingOrder.REVERSE) {
                                        c.prepend(query.getActualInThisQuery(), message);
                                    } else {
                                        c.add(message);
                                    }
                                    if (query != null) query.incrementActualMessageCount();
                                    mXmppConnectionService.databaseBackend.createMessage(message);
                                    if (message.isEphemeral()) {
                                        mXmppConnectionService.scheduleNextExpiry();
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }

            final var received =
                    packet.getExtension(
                            im.conversations.android.xmpp.model.receipts.Received.class);
            if (received != null) {
                processReceived(received, packet, query, from);
            }
            final var displayed = packet.getExtension(Displayed.class);
            if (displayed != null) {
                processDisplayed(
                        displayed,
                        packet,
                        selfAddressed,
                        counterpart,
                        query,
                        isTypeGroupChat,
                        conversation,
                        mucUserElement,
                        from);
            }

            // end no body
        }

        if (reactions != null) {
            processReactions(
                    reactions,
                    mXmppConnectionService.find(account, counterpart.asBareJid()),
                    isTypeGroupChat,
                    occupant,
                    counterpart,
                    mucTrueCounterPart,
                    status,
                    packet);
        }

        final Element event =
                original.findChild("event", "http://jabber.org/protocol/pubsub#event");
        final Jid eventFrom = pubsubEventOrigin(original.getFrom(), account.getJid().asBareJid());
        if (event != null && eventFrom == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": ignoring pubsub event from non-bare or invalid "
                            + original.getFrom());
        } else if (event != null) {
            final Element items = event.findChild("items");
            if (items != null) {
                final String node = items.getAttribute("node");
                if (node != null
                        && (node.startsWith(Namespace.MICROBLOG)
                                || node.startsWith(Namespace.PUBSUB_SOCIAL_FEED))) {
                    final Jid feedService = eventFrom;
                    for (Element child : items.getChildren()) {
                        if ("item".equals(child.getName())) {
                            if (child.findChild("entry", Namespace.ATOM) != null) {
                                handleFeedItem(account, feedService, node, child);
                            } else if (child.getAttribute("id") != null && feedService != null) {
                                // XEP-0472 / Movim nodes notify without payload
                                // (deliver_payloads=false): fetch the announced item.
                                mXmppConnectionService.fetchPubsubItems(
                                        account,
                                        feedService,
                                        node,
                                        child.getAttribute("id"),
                                        new XmppConnectionService.OnPubsubItemsFetched() {
                                            @Override
                                            public void onPubsubItemsFetched(final Element pubsub) {
                                                final Element fetched = pubsub.findChild("items");
                                                if (fetched == null
                                                        || !node.equals(
                                                                fetched.getAttribute("node"))) {
                                                    return;
                                                }
                                                for (final Element item : fetched.getChildren()) {
                                                    if ("item".equals(item.getName())) {
                                                        handleFeedItem(
                                                                account, feedService, node, item);
                                                    }
                                                }
                                            }

                                            @Override
                                            public void onPubsubItemsFetchFailed() {
                                                Log.d(
                                                        Config.LOGTAG,
                                                        account.getJid().asBareJid()
                                                                + ": unable to fetch announced item"
                                                                + " on "
                                                                + node
                                                                + " from "
                                                                + feedService);
                                            }
                                        });
                            }
                        } else if ("retract".equals(child.getName())) {
                            final String postId = child.getAttribute("id");
                            if (postId != null) {
                                mXmppConnectionService.onPostRetracted(feedService, node, postId);
                            }
                        }
                    }
                } else {
                    parseEvent(event, eventFrom, account);
                }
            } else if (event.hasChild("delete")) {
                parseDeleteEvent(event, eventFrom, account);
            } else if (event.hasChild("purge")) {
                parsePurgeEvent(event, eventFrom, account);
            }
        }

        final String nick = packet.findChildContent("nick", Namespace.NICK);
        if (nick != null && Jid.Invalid.hasValidFrom(original)) {
            if (mXmppConnectionService.isMuc(account, from)) {
                return;
            }
            final Contact contact = account.getRoster().getContact(from);
            if (contact.setPresenceName(nick)) {
                mXmppConnectionService.syncRoster(account);
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
    }

    private void processReceived(
            final im.conversations.android.xmpp.model.receipts.Received received,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final MessageArchiveService.Query query,
            final Jid from) {
        final var id = received.getId();
        if (packet.fromAccount(account)) {
            if (query != null && id != null && packet.getTo() != null) {
                query.removePendingReceiptRequest(new ReceiptRequest(packet.getTo(), id));
            }
        } else if (id != null) {
            if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                final String sessionId =
                        id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                mXmppConnectionService
                        .getJingleConnectionManager()
                        .updateProposedSessionDiscovered(
                                account,
                                from,
                                sessionId,
                                JingleConnectionManager.DeviceDiscoveryState.DISCOVERED);
            } else {
                mXmppConnectionService.markMessage(
                        account, from.asBareJid(), id, Message.STATUS_SEND_RECEIVED);
            }
        }
    }

    private void processDisplayed(
            final Displayed displayed,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final boolean selfAddressed,
            final Jid counterpart,
            final MessageArchiveService.Query query,
            final boolean isTypeGroupChat,
            Conversation conversation,
            Element mucUserElement,
            Jid from) {
        final var id = displayed.getId();
        // TODO we don’t even use 'sender' any more. Remove this!
        final Jid sender = Jid.Invalid.getNullForInvalid(displayed.getAttributeAsJid("sender"));
        if (packet.fromAccount(account) && !selfAddressed) {
            final Conversation c = mXmppConnectionService.find(account, counterpart.asBareJid());
            final Message message =
                    (c == null || id == null) ? null : c.findReceivedWithRemoteId(id);
            if (message != null && (query == null || query.isCatchup())) {
                mXmppConnectionService.markReadUpTo(c, message);
            }
            if (query == null) {
                activateGracePeriod(account);
            }
        } else if (isTypeGroupChat) {
            final Message message;
            if (conversation != null && id != null) {
                if (sender != null) {
                    message = conversation.findMessageWithRemoteId(id, sender);
                } else {
                    message = conversation.findMessageWithServerMsgId(id);
                }
            } else {
                message = null;
            }
            if (message != null) {
                // TODO use occupantId to extract true counterpart from presence
                final Jid fallback = conversation.getMucOptions().getTrueCounterpart(counterpart);
                // TODO try to externalize mucTrueCounterpart
                final Jid trueJid =
                        getTrueCounterpart(
                                (query != null && query.safeToExtractTrueCounterpart())
                                        ? mucUserElement
                                        : null,
                                fallback,
                                account,
                                isSelfInConference(
                                        conversation.getMucOptions(), packet, counterpart));
                final boolean trueJidMatchesAccount =
                        account.getJid()
                                .asBareJid()
                                .equals(trueJid == null ? null : trueJid.asBareJid());
                if (trueJidMatchesAccount || conversation.getMucOptions().isSelf(counterpart)) {
                    if (!message.isRead()
                            && (query == null || query.isCatchup())) { // checking if message is
                        // unread fixes race conditions
                        // with reflections
                        mXmppConnectionService.markReadUpTo(conversation, message);
                    }
                } else if (!counterpart.isBareJid() && trueJid != null) {
                    final ReadByMarker readByMarker = ReadByMarker.from(counterpart, trueJid);
                    if (message.addReadByMarker(readByMarker)) {
                        final var mucOptions = conversation.getMucOptions();
                        final var everyone = ImmutableSet.copyOf(mucOptions.getMembers(false));
                        final var readyBy = message.getReadyByTrue();
                        final var mStatus = message.getStatus();
                        if (mucOptions.isPrivateAndNonAnonymous()
                                && (mStatus == Message.STATUS_SEND_RECEIVED
                                        || mStatus == Message.STATUS_SEND)
                                && readyBy.containsAll(everyone)) {
                            message.setStatus(Message.STATUS_SEND_DISPLAYED);
                        }
                        mXmppConnectionService.updateMessage(message, false);
                    }
                }
            }
        } else {
            final Message displayedMessage =
                    mXmppConnectionService.markMessage(
                            account, from.asBareJid(), id, Message.STATUS_SEND_DISPLAYED);
            Message message = displayedMessage == null ? null : displayedMessage.prev();
            while (message != null
                    && message.getStatus() == Message.STATUS_SEND_RECEIVED
                    && message.getTimeSent() < displayedMessage.getTimeSent()) {
                mXmppConnectionService.markMessage(message, Message.STATUS_SEND_DISPLAYED);
                message = message.prev();
            }
            if (displayedMessage != null && selfAddressed) {
                dismissNotification(account, counterpart, query, id);
            }
        }
    }

    private void processReactions(
            final Reactions reactions,
            final Conversation conversation,
            final boolean isTypeGroupChat,
            final OccupantId occupant,
            final Jid counterpart,
            final Jid mucTrueCounterPart,
            final int status,
            final im.conversations.android.xmpp.model.stanza.Message packet) {
        final String reactingTo = reactions.getId();
        if (conversation != null && reactingTo != null) {
            if (isTypeGroupChat && conversation.getMode() == Conversational.MODE_MULTI) {
                final var mucOptions = conversation.getMucOptions();
                final var occupantId = occupant == null ? null : occupant.getId();
                if (occupantId != null) {
                    final boolean isReceived = !mucOptions.isSelf(occupantId);
                    final Message message;
                    final var inMemoryMessage = conversation.findMessageWithServerMsgId(reactingTo);
                    if (inMemoryMessage != null) {
                        message = inMemoryMessage;
                    } else {
                        message =
                                mXmppConnectionService.databaseBackend.getMessageWithServerMsgId(
                                        conversation, reactingTo);
                    }
                    if (message != null) {
                        if (message.isDeleted()) {
                            Log.d(
                                    Config.LOGTAG,
                                    "ignoring reaction to deleted/expired message " + reactingTo);
                            return;
                        }
                        final var newReactions = new HashSet<>(reactions.getReactions());
                        newReactions.removeAll(
                                message.getReactions().stream()
                                        .filter(r -> occupantId.equals(r.occupantId))
                                        .map(r -> r.reaction)
                                        .collect(Collectors.toList()));
                        final var combinedReactions =
                                Reaction.withOccupantId(
                                        message.getReactions(),
                                        reactions.getReactions(),
                                        isReceived,
                                        counterpart,
                                        mucTrueCounterPart,
                                        occupantId,
                                        message.getRemoteMsgId());
                        message.setReactions(combinedReactions);
                        mXmppConnectionService.updateMessage(message, false);
                        if (isReceived)
                            mXmppConnectionService
                                    .getNotificationService()
                                    .push(message, counterpart, occupantId, newReactions);
                    } else {
                        Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                    }
                } else {
                    Log.d(Config.LOGTAG, "received reaction in channel w/o occupant ids. ignoring");
                }
            } else {
                final Message message;
                final var inMemoryMessage = conversation.findMessageWithUuidOrRemoteId(reactingTo);
                if (inMemoryMessage != null) {
                    message = inMemoryMessage;
                } else {
                    message =
                            mXmppConnectionService.databaseBackend.getMessageWithUuidOrRemoteId(
                                    conversation, reactingTo);
                }
                if (message == null) {
                    Log.d(Config.LOGTAG, "message with id " + reactingTo + " not found");
                    return;
                }
                if (message.isDeleted()) {
                    Log.d(
                            Config.LOGTAG,
                            "ignoring reaction to deleted/expired message " + reactingTo);
                    return;
                }
                final boolean isReceived;
                final Jid reactionFrom;
                if (conversation.getMode() == Conversational.MODE_MULTI) {
                    Log.d(Config.LOGTAG, "received reaction as MUC PM. triggering validation");
                    final var mucOptions = conversation.getMucOptions();
                    final var occupantId = occupant == null ? null : occupant.getId();
                    if (occupantId == null) {
                        Log.d(
                                Config.LOGTAG,
                                "received reaction via PM channel w/o occupant ids. ignoring");
                        return;
                    }
                    isReceived = !mucOptions.isSelf(occupantId);
                    if (isReceived) {
                        reactionFrom = counterpart;
                    } else {
                        if (!occupantId.equals(message.getOccupantId())) {
                            Log.d(
                                    Config.LOGTAG,
                                    "reaction received via MUC PM did not pass validation");
                            return;
                        }
                        reactionFrom = account.getJid().asBareJid();
                    }
                } else {
                    final boolean fromOwnAccount =
                            packet != null
                                    ? packet.fromAccount(account)
                                    : mucTrueCounterPart != null
                                            && mucTrueCounterPart
                                                    .asBareJid()
                                                    .equals(account.getJid().asBareJid());
                    if (fromOwnAccount) {
                        isReceived = false;
                        reactionFrom = account.getJid().asBareJid();
                    } else {
                        isReceived = true;
                        reactionFrom = counterpart;
                    }
                }
                final var newReactions = new HashSet<>(reactions.getReactions());
                newReactions.removeAll(
                        message.getReactions().stream()
                                .filter(r -> reactionFrom.equals(r.from))
                                .map(r -> r.reaction)
                                .collect(Collectors.toList()));
                final var combinedReactions =
                        Reaction.withFrom(
                                message.getReactions(),
                                reactions.getReactions(),
                                isReceived,
                                reactionFrom,
                                message.getRemoteMsgId());
                message.setReactions(combinedReactions);
                mXmppConnectionService.updateMessage(message, false);
                if (status < Message.STATUS_SEND)
                    mXmppConnectionService
                            .getNotificationService()
                            .push(message, counterpart, null, newReactions);
            }
        }
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message, Long>
            getForwardedMessagePacket(
                    final im.conversations.android.xmpp.model.stanza.Message original,
                    Class<? extends Extension> clazz) {
        final var extension = original.getExtension(clazz);
        final var forwarded = extension == null ? null : extension.getExtension(Forwarded.class);
        if (forwarded == null) {
            return null;
        }
        final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
        final var forwardedMessage = forwarded.getMessage();
        if (forwardedMessage == null) {
            return null;
        }
        return new Pair<>(forwardedMessage, timestamp);
    }

    private static Pair<im.conversations.android.xmpp.model.stanza.Message, Long>
            getForwardedMessagePacket(
                    final im.conversations.android.xmpp.model.stanza.Message original,
                    final String name,
                    final String namespace) {
        final Element wrapper = original.findChild(name, namespace);
        final var forwardedElement =
                wrapper == null ? null : wrapper.findChild("forwarded", Namespace.FORWARD);
        if (forwardedElement instanceof Forwarded forwarded) {
            final Long timestamp = AbstractParser.parseTimestamp(forwarded, null);
            final var forwardedMessage = forwarded.getMessage();
            if (forwardedMessage == null) {
                return null;
            }
            return new Pair<>(forwardedMessage, timestamp);
        }
        return null;
    }

    private void dismissNotification(
            Account account, Jid counterpart, MessageArchiveService.Query query, final String id) {
        final Conversation conversation =
                mXmppConnectionService.find(account, counterpart.asBareJid());
        if (conversation != null && (query == null || query.isCatchup())) {
            final String displayableId = conversation.findMostRecentRemoteDisplayableId();
            if (displayableId != null && displayableId.equals(id)) {
                mXmppConnectionService.markRead(conversation);
            } else {
                Log.w(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": received dismissing display marker that did not match our last"
                                + " id in that conversation");
            }
        }
    }

    private void processMessageReceipts(
            final Account account,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final String remoteMsgId,
            MessageArchiveService.Query query) {
        final boolean markable = packet.hasChild("markable", "urn:xmpp:chat-markers:0");
        final boolean request = packet.hasChild("request", "urn:xmpp:receipts");
        if (query == null) {
            final ArrayList<String> receiptsNamespaces = new ArrayList<>();
            if (markable) {
                receiptsNamespaces.add("urn:xmpp:chat-markers:0");
            }
            if (request) {
                receiptsNamespaces.add("urn:xmpp:receipts");
            }
            if (receiptsNamespaces.size() > 0) {
                mXmppConnectionService.sendDeliveryReceipt(
                        account,
                        packet.getFrom(),
                        remoteMsgId,
                        receiptsNamespaces,
                        packet.getType());
            }
        } else if (query.isCatchup()) {
            if (request) {
                query.addPendingReceiptRequest(new ReceiptRequest(packet.getFrom(), remoteMsgId));
            }
        }
    }

    /**
     * Adds one described file (XEP-0447 / SIMS / OOB) to {@code message}. The first file is the
     * message's own; every further file of the same message becomes an attached message, so it
     * keeps its own row and can be downloaded, opened and deleted individually.
     */
    private static void addFileToMessage(
            final Message message, final Message.FileParams fileParams) {
        if (fileParams == null || fileParams.url == null) {
            return;
        }
        final Message.FileParams current = message.getFileParams();
        if (current == null || current.url == null) {
            message.setFileParams(fileParams);
            if (CryptoHelper.isPgpEncryptedUrl(message.getFileParams().url)) {
                message.setEncryption(Message.ENCRYPTION_DECRYPTED);
            }
            return;
        }
        if (current.url.equals(fileParams.url)) {
            // the same file described twice (jabber:x:oob next to file-sharing): keep the
            // description that carries name, size and type, in whichever order they arrive
            if (current.sims == null && fileParams.sims != null) {
                message.setFileParams(fileParams);
            }
            return;
        }
        for (final Message existing : message.getAttachments()) {
            final Message.FileParams params = existing.getFileParams();
            if (params != null && fileParams.url.equals(params.url)) {
                return;
            }
        }
        final Message attachment =
                new Message(
                        message.getConversation(),
                        "",
                        message.getEncryption(),
                        message.getStatus());
        attachment.setFileParams(fileParams);
        message.addAttachment(attachment);
    }

    /** Persists the extra files of a multi-file message and downloads them like any other file. */
    private void storeAndDownloadAttachments(final Message message) {
        if (!message.hasAttachments()) {
            return;
        }
        final HttpConnectionManager manager = mXmppConnectionService.getHttpConnectionManager();
        for (final Message attachment : message.getAttachments()) {
            mXmppConnectionService.databaseBackend.createMessage(attachment);
            if (attachment.trusted()
                    && attachment.treatAsDownloadable()
                    && manager.getAutoAcceptFileSize() > 0) {
                manager.createNewDownloadConnection(attachment);
            }
        }
    }

    private void activateGracePeriod(Account account) {
        long duration =
                mXmppConnectionService.getLongPreference(
                                "grace_period_length", R.integer.grace_period)
                        * 1000;
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": activating grace period till "
                        + TIME_FORMAT.format(new Date(System.currentTimeMillis() + duration)));
        account.activateGracePeriod(duration);
    }

    private class Invite {
        final Jid jid;
        final String password;
        final boolean direct;
        final Jid inviter;

        Invite(Jid jid, String password, boolean direct, Jid inviter) {
            this.jid = jid;
            this.password = password;
            this.direct = direct;
            this.inviter = inviter;
        }

        public boolean execute(final Account account) {
            if (this.jid == null) {
                return false;
            }
            if (this.inviter == null) {
                return false;
            }
            if (account.isBlocked(this.jid.asBareJid())
                    || account.isBlocked(this.jid.getDomain())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignore invite to "
                                + jid
                                + " because room is blocked");
                return false;
            }
            final Contact contact =
                    account.getRoster().getContactFromContactList(this.inviter.asBareJid());
            if (account.isBlocked(this.inviter.asBareJid())
                    || account.isBlocked(this.inviter.getDomain())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignore invite from "
                                + inviter
                                + " because contact is blocked");
                return false;
            }
            final boolean fromContact = contact != null && contact.showInContactList();
            if (!fromContact
                    && !mXmppConnectionService.getAppSettings().isAcceptInvitesFromStrangers()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring invite from "
                                + inviter
                                + " because we are not accepting invites from strangers");
                return false;
            }
            // An invite names an arbitrary address. Never let it turn a 1:1 chat into a group
            // chat, and never send a join (with the invite's password) to something that is not
            // a room.
            if (jid.isFullJid()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": ignoring invite to full jid " + jid);
                return false;
            }
            Conversation existing = mXmppConnectionService.find(account, jid);
            if (existing == null) {
                existing = mXmppConnectionService.databaseBackend.findConversation(account, jid);
            }
            if (existing != null) {
                if (existing.getMode() != Conversation.MODE_MULTI) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": ignoring invite to "
                                    + jid
                                    + " because it is a 1:1 chat");
                    return false;
                }
                join(account, fromContact);
                return true;
            }
            final im.conversations.android.xmpp.model.stanza.Iq request =
                    mXmppConnectionService.getIqGenerator().queryDiscoInfo(jid);
            mXmppConnectionService.sendIqPacket(
                    account,
                    request,
                    response -> {
                        boolean room = false;
                        if (response.getType()
                                == im.conversations.android.xmpp.model.stanza.Iq.Type.RESULT) {
                            try {
                                room = isMultiUserChat(new ServiceDiscoveryResult(response));
                            } catch (final RuntimeException e) {
                                Log.d(Config.LOGTAG, "invalid disco#info for " + jid, e);
                            }
                        }
                        if (room) {
                            join(account, fromContact);
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": ignoring invite to "
                                            + jid
                                            + " because it does not identify as a group chat");
                        }
                    });
            return true;
        }

        private void join(final Account account, final boolean fromContact) {
            final Conversation conversation =
                    mXmppConnectionService.findOrCreateConversation(account, jid, true, false);
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return;
            }
            conversation.setAttribute("inviter", inviter.toString());
            if (conversation.getMucOptions().online()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": received invite to "
                                + jid
                                + " but muc is considered to be online");
                mXmppConnectionService.mucSelfPingAndRejoin(conversation);
            } else {
                conversation.getMucOptions().setPassword(password);
                mXmppConnectionService.databaseBackend.updateConversation(conversation);
                mXmppConnectionService.joinMuc(conversation, fromContact);
                mXmppConnectionService.updateConversationUi();
            }
        }
    }

    /** Whether a disco#info result describes a XEP-0045 room (any conference type, e.g. IRC). */
    static boolean isMultiUserChat(final ServiceDiscoveryResult disco) {
        return disco.hasIdentity("conference", null) && disco.getFeatures().contains(Namespace.MUC);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
