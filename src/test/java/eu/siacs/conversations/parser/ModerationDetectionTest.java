package eu.siacs.conversations.parser;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

/**
 * Covers {@link MessageParser#isModeration} - telling a XEP-0425 moderation (which only the room
 * may send, and which may target anybody's message) apart from an author's own XEP-0424 retraction.
 * Both protocol generations are covered.
 */
public class ModerationDetectionTest {

    private static Message roomMessage() {
        final Account account = new Account(Jid.of("alice@example.com"), "password");
        final Conversation room =
                new Conversation(
                        "room", account, Jid.of("room@muc.example.com"), Conversation.MODE_MULTI);
        return new Message(room, "spam", Message.ENCRYPTION_NONE);
    }

    /** XEP-0425 v0.3.0: the room's broadcast is a retraction carrying {@code <moderated/>}. */
    @Test
    public void currentModerationIsRecognised() {
        final Element retract = new Element("retract", Namespace.MESSAGE_RETRACT);
        retract.setAttribute("id", "stanza-id-1");
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE)
                .setAttribute("by", "room@muc.example.com/macbeth");
        Assert.assertTrue(MessageParser.isModeration(retract));
    }

    /** Pre-0.3.0: {@code <moderated/>} inside a XEP-0422 {@code <apply-to/>}. */
    @Test
    public void legacyModerationIsRecognised() {
        Assert.assertTrue(
                MessageParser.isModeration(
                        new Element("moderated", Namespace.MESSAGE_MODERATE_LEGACY)));
    }

    @Test
    public void bareModerateElementIsRecognised() {
        Assert.assertTrue(
                MessageParser.isModeration(new Element("moderate", Namespace.MESSAGE_MODERATE)));
    }

    /** An author's own retraction is not a moderation. */
    @Test
    public void ownRetractionIsNotAModeration() {
        Assert.assertFalse(
                MessageParser.isModeration(new Element("retract", Namespace.MESSAGE_RETRACT)));
        Assert.assertFalse(
                MessageParser.isModeration(
                        new Element("retract", Namespace.MESSAGE_RETRACT_LEGACY)));
    }

    /** A {@code <moderated/>} in the wrong namespace must not turn a retraction into one. */
    @Test
    public void moderatedInWrongNamespaceIsIgnored() {
        final Element retract = new Element("retract", Namespace.MESSAGE_RETRACT);
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE_LEGACY);
        Assert.assertFalse(MessageParser.isModeration(retract));
    }

    @Test
    public void correctionsAndNullAreNotModerations() {
        Assert.assertFalse(
                MessageParser.isModeration(new Element("replace", "urn:xmpp:message-correct:0")));
        Assert.assertFalse(MessageParser.isModeration(null));
    }

    /** The UI recognises a v0.3.0 moderation stored on a message, not just the legacy form. */
    @Test
    public void storedCurrentModerationIsShownAsModerated() {
        final Message message = roomMessage();
        final Element retract = new Element("retract", Namespace.MESSAGE_RETRACT);
        retract.addChild("moderated", Namespace.MESSAGE_MODERATE);
        message.addPayload(retract);
        Assert.assertNotNull(message.getModerated());
    }

    @Test
    public void storedOwnRetractionIsNotShownAsModerated() {
        final Message message = roomMessage();
        message.addPayload(new Element("retract", Namespace.MESSAGE_RETRACT));
        Assert.assertNull(message.getModerated());
    }
}
