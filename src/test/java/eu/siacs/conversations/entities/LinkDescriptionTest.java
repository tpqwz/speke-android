package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.utils.LinkPreviewParser;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;

public class LinkDescriptionTest {

    @Test
    public void theSamePageIsOneCard() {
        final Message message = roomMessage();
        final Element first = card("https://www.bbc.co.uk/news/articles/cq36gj12n0l3o");
        final Element second = card("https://www.bbc.co.uk/news/articles/cq36gj12n0l3o");
        message.addPayload(first);
        message.addPayload(second);
        assertEquals(1, message.getLinkDescriptions().size());
        assertFalse(
                message.addLinkDescription(
                        card("https://www.bbc.co.uk/news/articles/cq36gj12n0l3o")));
        assertTrue(message.addLinkDescription(card("https://www.bbc.co.uk/news/articles/other")));
        assertEquals(2, message.getLinkDescriptions().size());
        message.clearLinkDescriptions();
        assertEquals(0, message.getLinkDescriptions().size());
    }

    private static Message roomMessage() {
        final Account account = new Account(Jid.of("alice@example.com"), "password");
        final Conversation room =
                new Conversation(
                        "room", account, Jid.of("room@muc.example.com"), Conversation.MODE_MULTI);
        return new Message(
                room,
                "https://www.bbc.co.uk/news/articles/cq36gj12n0l3o",
                Message.ENCRYPTION_NONE);
    }

    private static Element card(String url) {
        final Element el = new Element("Description", LinkPreviewParser.RDF_NS);
        el.addChild("url", LinkPreviewParser.OGP_NS).setContent(url);
        el.addChild("title", LinkPreviewParser.OGP_NS).setContent("A headline");
        return el;
    }
}
