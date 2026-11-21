package eu.siacs.conversations.parser;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

/** A stanza-id is only trusted when exactly one carries the expected {@code by}. */
public class StanzaIdUniquenessTest {

    private static final Jid ROOM = Jid.of("room@muc.example.org");

    private static Element message(final String... byIdPairs) {
        final Element message = new Element("message", "jabber:client");
        for (int i = 0; i < byIdPairs.length; i += 2) {
            message.addChild("stanza-id", Namespace.STANZA_IDS)
                    .setAttribute("by", byIdPairs[i])
                    .setAttribute("id", byIdPairs[i + 1]);
        }
        return message;
    }

    @Test
    public void singleMatchingStanzaIdIsUsed() {
        Assert.assertEquals(
                "real",
                MessageParser.extractStanzaId(message("room@muc.example.org", "real"), ROOM));
    }

    @Test
    public void otherByIsIgnored() {
        Assert.assertEquals(
                "real",
                MessageParser.extractStanzaId(
                        message("me@example.org", "acct", "room@muc.example.org", "real"), ROOM));
        Assert.assertNull(MessageParser.extractStanzaId(message("me@example.org", "acct"), ROOM));
    }

    @Test
    public void duplicateForSameByIsRejected() {
        Assert.assertNull(
                MessageParser.extractStanzaId(
                        message("room@muc.example.org", "forged", "room@muc.example.org", "real"),
                        ROOM));
    }
}
