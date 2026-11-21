package eu.siacs.conversations.parser;

import eu.siacs.conversations.xml.Namespace;
import org.junit.Assert;
import org.junit.Test;

/**
 * What may be believed outside the encrypted part of a stanza. A PQ OMEMO2 sender puts all content
 * inside the SCE envelope, so anything of the kind found outside was added on the way.
 */
public class OuterPayloadPolicyTest {

    private static void assertContent(final String name, final String namespace) {
        Assert.assertTrue(
                "<" + name + " xmlns='" + namespace + "'/> must not be taken from outside",
                MessageParser.isOuterContentElement(name, namespace));
    }

    private static void assertTransport(final String name, final String namespace) {
        Assert.assertFalse(
                "<" + name + " xmlns='" + namespace + "'/> is routing/transport and stays",
                MessageParser.isOuterContentElement(name, namespace));
    }

    @Test
    public void processingHintsAreTransport() {
        // MessageGenerator puts them on the outer stanza of a PQ OMEMO2 message (the server
        // must read them: a WebXDC realtime packet's <no-store/>), so they must survive here
        assertTransport("store", Namespace.HINTS);
        assertTransport("no-store", Namespace.HINTS);
        assertTransport("no-permanent-store", Namespace.HINTS);
        assertTransport("no-copy", Namespace.HINTS);
    }

    @Test
    public void whatReplacesOrAltersTheShownTextIsContent() {
        assertContent("html", "http://jabber.org/protocol/xhtml-im");
        assertContent("fallback", "urn:xmpp:fallback:0");
        assertContent("unstyled", Namespace.STYLING);
        assertContent("subject", "jabber:client");
        assertContent("subject", null);
        assertContent("thread", "jabber:client");
        assertContent("reply", "urn:xmpp:reply:0");
        assertContent("Description", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        assertContent("attention", "urn:xmpp:attention:0");
    }

    @Test
    public void editsAndDeletionsAreContent() {
        assertContent("replace", "urn:xmpp:message-correct:0");
        assertContent("retract", "urn:xmpp:message-retract:1");
        assertContent("retract", "urn:xmpp:message-retract:0");
        assertContent("apply-to", "urn:xmpp:fasten:0");
        assertContent("moderated", "urn:xmpp:message-moderate:0");
        assertContent("moderate", "urn:xmpp:message-moderate:1");
    }

    @Test
    public void filesAreContent() {
        assertContent("file-sharing", Namespace.SFS);
        assertContent("x", Namespace.OOB);
        assertContent("reference", "urn:xmpp:reference:0");
    }

    @Test
    public void chatMetadataIsContent() {
        assertContent("composing", "http://jabber.org/protocol/chatstates");
        assertContent("active", "http://jabber.org/protocol/chatstates");
        assertContent("received", "urn:xmpp:receipts");
        assertContent("received", "urn:xmpp:chat-markers:0");
        assertContent("displayed", "urn:xmpp:chat-markers:0");
        assertContent("reactions", Namespace.REACTIONS);
        assertContent("ephemeral", Namespace.EPHEMERAL);
        assertContent("i-want-out", Namespace.EPHEMERAL);
        assertContent("x", "urn:xmpp:webxdc:0");
        assertContent("live-location-update", Namespace.LIVE_LOCATION);
        assertContent("x", "jabber:x:conference");
    }

    @Test
    public void routingAndTransportStay() {
        assertTransport("encrypted", Namespace.OMEMO2);
        assertTransport("body", "jabber:client");
        assertTransport("delay", "urn:xmpp:delay");
        assertTransport("stanza-id", "urn:xmpp:sid:0");
        assertTransport("origin-id", "urn:xmpp:sid:0");
        assertTransport("store", Namespace.HINTS);
        assertTransport("no-permanent-store", Namespace.HINTS);
        assertTransport("encryption", "urn:xmpp:eme:0");
        assertTransport("request", "urn:xmpp:receipts");
        assertTransport("markable", "urn:xmpp:chat-markers:0");
        assertTransport("x", Namespace.MUC_USER);
        assertTransport("occupant-id", "urn:xmpp:occupant-id:0");
    }

    /** Strict mode only drops metadata: an unencrypted message itself is still shown. */
    @Test
    public void strictModeDropsMetadataOnly() {
        Assert.assertTrue(
                MessageParser.isUnauthenticatedMetadata("displayed", "urn:xmpp:chat-markers:0"));
        Assert.assertTrue(
                MessageParser.isUnauthenticatedMetadata("reactions", Namespace.REACTIONS));
        Assert.assertTrue(
                MessageParser.isUnauthenticatedMetadata("ephemeral", Namespace.EPHEMERAL));
        Assert.assertFalse(MessageParser.isUnauthenticatedMetadata("body", "jabber:client"));
        Assert.assertFalse(
                MessageParser.isUnauthenticatedMetadata("markable", "urn:xmpp:chat-markers:0"));
        Assert.assertFalse(MessageParser.isUnauthenticatedMetadata("request", "urn:xmpp:receipts"));
        Assert.assertFalse(
                MessageParser.isUnauthenticatedMetadata("replace", "urn:xmpp:message-correct:0"));
        Assert.assertFalse(MessageParser.isUnauthenticatedMetadata("x", Namespace.OOB));
    }

    /** Legacy OMEMO and PGP encrypt only the body. */
    @Test
    public void bodyOnlyEncryptionRefusesXhtmlAndStrayFallbacks() {
        final String xhtml = "http://jabber.org/protocol/xhtml-im";
        final String fallback = "urn:xmpp:fallback:0";
        Assert.assertTrue(
                MessageParser.isRefusedBesideBodyOnlyEncryption("html", xhtml, null, true));
        Assert.assertTrue(
                MessageParser.isRefusedBesideBodyOnlyEncryption("html", xhtml, null, false));
        // what a legacy sender really emits stays usable
        Assert.assertFalse(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "fallback", fallback, "urn:xmpp:reply:0", true));
        Assert.assertFalse(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "fallback", fallback, "urn:xmpp:message-retract:1", true));
        Assert.assertFalse(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "reply", "urn:xmpp:reply:0", null, true));
        Assert.assertFalse(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "replace", "urn:xmpp:message-correct:0", null, true));
        // markers that would only cut text out of the decrypted body
        for (final String target :
                new String[] {
                    Namespace.OOB, Namespace.SFS, "http://jabber.org/protocol/address", null
                }) {
            Assert.assertTrue(
                    String.valueOf(target),
                    MessageParser.isRefusedBesideBodyOnlyEncryption(
                            "fallback", fallback, target, true));
        }
        // PGP file messages carry a jabber:x:oob fallback (generatePgpChat)
        Assert.assertFalse(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "fallback", fallback, Namespace.OOB, false));
    }
}
