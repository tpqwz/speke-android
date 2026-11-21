package eu.siacs.conversations.entities;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;

/** A feed item's claimed author must be the party that actually published it. */
public class FeedAuthorshipTest {

    private static final String ATOM = "http://www.w3.org/2005/Atom";

    private static Element item(final String authorUri, final String publisher) {
        final Element item = new Element("item", "http://jabber.org/protocol/pubsub#event");
        item.setAttribute("id", "p1");
        if (publisher != null) {
            item.setAttribute("publisher", publisher);
        }
        final Element entry = item.addChild("entry", Namespace.ATOM);
        entry.addChild("title", ATOM).setContent("hello");
        if (authorUri != null) {
            entry.addChild("author", ATOM).addChild("uri", ATOM).setContent(authorUri);
        }
        return item;
    }

    @Test
    public void postFromItsAuthorIsAccepted() {
        final Post post =
                Post.fromElement(item("xmpp:alice@example.org", null), Jid.of("alice@example.org"));
        Assert.assertNotNull(post);
        Assert.assertEquals(Jid.of("alice@example.org"), post.getAuthor());
    }

    @Test
    public void postClaimingAnotherAuthorIsRejected() {
        Assert.assertNull(
                Post.fromElement(item("xmpp:alice@example.org", null), Jid.of("mallory@evil.org")));
    }

    @Test
    public void postWithoutAuthorIsAttributedToPublisherAndNormalised() {
        final Post post = Post.fromElement(item(null, null), Jid.of("alice@example.org/phone"));
        Assert.assertNotNull(post);
        Assert.assertEquals(Jid.of("alice@example.org"), post.getAuthor());
    }

    @Test
    public void postWithoutPublisherIsRejected() {
        Assert.assertNull(Post.fromElement(item("xmpp:alice@example.org", null), null));
    }

    @Test
    public void commentMustMatchStampedPublisher() {
        Assert.assertNull(Comment.fromElement(item("xmpp:alice@example.org", "mallory@evil.org")));
        final Comment ok =
                Comment.fromElement(item("xmpp:alice@example.org", "alice@example.org/web"));
        Assert.assertNotNull(ok);
        Assert.assertEquals(Jid.of("alice@example.org"), ok.getAuthor().asBareJid());
    }

    @Test
    public void commentWithoutAuthorTakesPublisher() {
        final Comment c = Comment.fromElement(item(null, "bob@example.org"));
        Assert.assertNotNull(c);
        Assert.assertEquals(Jid.of("bob@example.org"), c.getAuthor());
    }
}
