package eu.siacs.conversations.parser;

import eu.siacs.conversations.entities.Message;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers {@link MessageParser#mayReplace} - the rule that decides whether an incoming correction,
 * retraction or moderation is allowed to rewrite a message already in the conversation.
 *
 * <p>Getting this wrong lets one participant edit or delete another's messages, so each clause is
 * pinned individually rather than only in combination.
 */
public class CorrectionAuthorizationTest {

    private static boolean directChat(final boolean fingerprintsMatch) {
        return MessageParser.mayReplace(
                fingerprintsMatch, false, false, false, /* conversationMultiMode= */ false, false);
    }

    private static boolean conference(
            final boolean fingerprintsMatch,
            final boolean trueCounters,
            final boolean occupantId,
            final boolean mucUser,
            final boolean fromRoomItself) {
        return MessageParser.mayReplace(
                fingerprintsMatch, trueCounters, occupantId, mucUser, true, fromRoomItself);
    }

    // ---- encryption has to line up in every case ----

    /** A plaintext stanza must not be able to rewrite an encrypted message. */
    @Test
    public void mismatchedFingerprintsBlockADirectChatCorrection() {
        Assert.assertFalse(directChat(false));
    }

    @Test
    public void mismatchedFingerprintsBlockEvenAFullyIdentifiedSender() {
        Assert.assertFalse(conference(false, true, true, true, true));
    }

    @Test
    public void mismatchedFingerprintsBlockModeration() {
        Assert.assertFalse(conference(false, false, false, false, true));
    }

    // ---- one to one ----

    /** The conversation is keyed by the peer, so identity is already established. */
    @Test
    public void directChatNeedsOnlyMatchingFingerprints() {
        Assert.assertTrue(directChat(true));
    }

    // ---- conference: identity must come from the room ----

    /**
     * The case that matters: a conference message with matching encryption but nothing tying the
     * sender to the original author. It must not be allowed to rewrite it.
     */
    @Test
    public void conferenceCorrectionWithoutAnyIdentitySignalIsRejected() {
        Assert.assertFalse(conference(true, false, false, false, false));
    }

    @Test
    public void occupantIdMatchAuthorisesACorrection() {
        Assert.assertTrue(conference(true, false, true, false, false));
    }

    @Test
    public void resolvedRealAddressMatchAuthorisesACorrection() {
        Assert.assertTrue(conference(true, true, false, false, false));
    }

    @Test
    public void roomSuppliedUserRecordAuthorisesACorrection() {
        Assert.assertTrue(conference(true, false, false, true, false));
    }

    /** XEP-0425 moderation arrives from the bare room address and may retract anybody's message. */
    @Test
    public void moderationFromTheRoomItselfIsAuthorised() {
        Assert.assertTrue(conference(true, false, false, false, true));
    }

    /** Each identity signal stands alone; none of them is required alongside another. */
    @Test
    public void anySingleIdentitySignalSuffices() {
        Assert.assertTrue(conference(true, true, false, false, false));
        Assert.assertTrue(conference(true, false, true, false, false));
        Assert.assertTrue(conference(true, false, false, true, false));
        Assert.assertTrue(conference(true, false, false, false, true));
    }

    /**
     * Exhaustive: with matching fingerprints in a conference, permission is exactly "at least one
     * identity signal". Guards against a future clause quietly widening the rule.
     */
    @Test
    public void conferencePermissionIsExactlyTheDisjunctionOfIdentitySignals() {
        for (int mask = 0; mask < 16; mask++) {
            final boolean trueCounters = (mask & 1) != 0;
            final boolean occupantId = (mask & 2) != 0;
            final boolean mucUser = (mask & 4) != 0;
            final boolean fromRoom = (mask & 8) != 0;
            final boolean expected = trueCounters || occupantId || mucUser || fromRoom;
            Assert.assertEquals(
                    "mask " + mask,
                    expected,
                    conference(true, trueCounters, occupantId, mucUser, fromRoom));
            Assert.assertFalse(
                    "fingerprint mismatch must dominate, mask " + mask,
                    conference(false, trueCounters, occupantId, mucUser, fromRoom));
        }
    }

    // ---- editFingerprintsMatch: which edits may touch an encrypted original ----

    @Test
    public void unencryptedOriginalAcceptsAnyEdit() {
        Assert.assertTrue(MessageParser.editFingerprintsMatch(null, null, false, false, false));
        Assert.assertTrue(MessageParser.editFingerprintsMatch(null, "fp", true, false, false));
    }

    @Test
    public void sameFingerprintMayCorrectAndRetract() {
        Assert.assertTrue(MessageParser.editFingerprintsMatch("fp", "fp", false, false, false));
        Assert.assertTrue(MessageParser.editFingerprintsMatch("fp", "fp", true, false, false));
    }

    /**
     * A cleartext (or foreign-key) retraction can't delete an encrypted message - OMEMO2 and legacy
     * OMEMO alike (legacy retractions carry an encrypted fallback body).
     */
    @Test
    public void foreignRetractionCannotDeleteAnEncryptedMessage() {
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", null, true, false, false));
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", "other", true, false, false));
    }

    @Test
    public void foreignCorrectionIsRejectedEvenFromTheRoom() {
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", null, false, false, false));
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", null, false, true, false));
    }

    /**
     * Our own message, sent from this phone, may be corrected or retracted from another of our own
     * devices: the edit then carries that device's fingerprint, not ours.
     */
    @Test
    public void ownDeviceMayEditOurEncryptedMessage() {
        Assert.assertTrue(MessageParser.editFingerprintsMatch("fp", "desktop", true, false, true));
        Assert.assertTrue(MessageParser.editFingerprintsMatch("fp", "desktop", false, false, true));
    }

    /** A cleartext own-side edit (forgeable by our server) still can't touch an encrypted one. */
    @Test
    public void cleartextOwnEditCannotTouchAnEncryptedMessage() {
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", null, true, false, true));
        Assert.assertFalse(MessageParser.editFingerprintsMatch("fp", null, false, false, true));
    }

    /** XEP-0425: the room may moderate any message, encrypted or not. */
    @Test
    public void roomModerationMayRetractAnEncryptedMessage() {
        Assert.assertTrue(MessageParser.editFingerprintsMatch("fp", null, true, true, false));
    }

    // ---- one-on-one: only the author's side ----

    /** The peer knows our message ids, but may not correct or retract our (plaintext) messages. */
    @Test
    public void peerCannotEditOurMessageInADirectChat() {
        Assert.assertFalse(
                MessageParser.sameAuthorSide(
                        false, Message.STATUS_SEND_RECEIVED, Message.STATUS_RECEIVED));
        Assert.assertFalse(
                MessageParser.sameAuthorSide(false, Message.STATUS_RECEIVED, Message.STATUS_SEND));
    }

    /** Their edits of their messages, and ours (from any of our devices) of ours. */
    @Test
    public void authorMayEditInADirectChat() {
        Assert.assertTrue(
                MessageParser.sameAuthorSide(
                        false, Message.STATUS_RECEIVED, Message.STATUS_RECEIVED));
        Assert.assertTrue(
                MessageParser.sameAuthorSide(
                        false, Message.STATUS_SEND_DISPLAYED, Message.STATUS_SEND));
        Assert.assertTrue(
                MessageParser.sameAuthorSide(false, Message.STATUS_WAITING, Message.STATUS_SEND));
    }

    /** In a group chat the occupant checks of mayReplace decide. */
    @Test
    public void sideCheckLeavesGroupChatsToMayReplace() {
        Assert.assertTrue(
                MessageParser.sameAuthorSide(
                        true, Message.STATUS_SEND_RECEIVED, Message.STATUS_RECEIVED));
    }
}
