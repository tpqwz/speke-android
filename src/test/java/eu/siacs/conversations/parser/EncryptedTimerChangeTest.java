package eu.siacs.conversations.parser;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * A decrypted PQ OMEMO2 envelope is a disappearing-message timer change only when it carries
 * nothing else; every message of a chat with a timer is stamped with it.
 */
public class EncryptedTimerChangeTest {

    private static Element ephemeral() {
        return new Element("ephemeral", Namespace.EPHEMERAL).setAttribute("timer", "3600");
    }

    private static Element body(final String content) {
        final Element body = new Element("body", "jabber:client");
        body.setContent(content);
        return body;
    }

    private static boolean timerOnly(final Element... elements) {
        return MessageParser.isTimerChangeOnly(Arrays.asList(elements));
    }

    @Test
    public void ownMessagesFromOtherDevicesCountDownFromSending() {
        // from the archive (no carbon) and as our room reflection: both are ours
        Assert.assertEquals(
                1_000L + 60_000L,
                MessageParser.ownMessageExpiry(
                        eu.siacs.conversations.entities.Message.STATUS_SEND, 1_000L, 60));
        Assert.assertEquals(
                1_000L + 60_000L,
                MessageParser.ownMessageExpiry(
                        eu.siacs.conversations.entities.Message.STATUS_SEND_RECEIVED, 1_000L, 60));
    }

    @Test
    public void receivedMessagesAndNoTimerDoNotStartHere() {
        Assert.assertEquals(
                0L,
                MessageParser.ownMessageExpiry(
                        eu.siacs.conversations.entities.Message.STATUS_RECEIVED, 1_000L, 60));
        Assert.assertEquals(
                0L,
                MessageParser.ownMessageExpiry(
                        eu.siacs.conversations.entities.Message.STATUS_SEND, 1_000L, 0));
    }

    @Test
    public void bareTimerChangeIsTimerChange() {
        Assert.assertTrue(timerOnly(ephemeral()));
        Assert.assertTrue(timerOnly(body(""), new Element("i-want-out", Namespace.EPHEMERAL)));
    }

    @Test
    public void webxdcUpdateWithTimerIsNotATimerChange() {
        final Element webxdc = new Element("x", "urn:xmpp:webxdc:0");
        webxdc.addChild("data").setContent("AAAA");
        final Element thread = new Element("thread", "jabber:client");
        thread.setContent("abc");
        Assert.assertFalse(timerOnly(webxdc, thread, ephemeral()));
    }

    @Test
    public void textWithTimerIsNotATimerChange() {
        Assert.assertFalse(timerOnly(body("hi"), ephemeral()));
    }

    @Test
    public void onlyUsableTimersAreTakenFromTheWire() {
        Assert.assertTrue(MessageParser.isSaneEphemeralTimer(0));
        Assert.assertTrue(MessageParser.isSaneEphemeralTimer(7200));
        Assert.assertTrue(MessageParser.isSaneEphemeralTimer(MessageParser.MAX_EPHEMERAL_TIMER));
        Assert.assertFalse(MessageParser.isSaneEphemeralTimer(-1));
        Assert.assertFalse(
                MessageParser.isSaneEphemeralTimer(MessageParser.MAX_EPHEMERAL_TIMER + 1));
        Assert.assertFalse(MessageParser.isSaneEphemeralTimer(Integer.MAX_VALUE));
    }

    @Test
    public void contactOfOneToOneChatMayChangeTheTimer() {
        Assert.assertTrue(MessageParser.mayChangeEphemeralTimer(false, false));
    }

    @Test
    public void occupantOfPrivateNonAnonymousRoomMayChangeTheTimer() {
        Assert.assertTrue(MessageParser.mayChangeEphemeralTimer(true, true));
    }

    /** Applies to setting the timer and to switching it off ({@code <i-want-out/>}) alike. */
    @Test
    public void occupantOfOtherRoomMayNotChangeTheTimer() {
        Assert.assertFalse(MessageParser.mayChangeEphemeralTimer(true, false));
    }

    @Test
    public void nothingIsNotATimerChange() {
        Assert.assertFalse(MessageParser.isTimerChangeOnly(Collections.<Element>emptyList()));
        Assert.assertFalse(MessageParser.isTimerChangeOnly(null));
        final List<Element> bodyOnly = Collections.singletonList(body(""));
        Assert.assertFalse(MessageParser.isTimerChangeOnly(bodyOnly));
    }
}
