package eu.siacs.conversations.entities;

import eu.siacs.conversations.utils.AtomText;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.TextNode;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** XEP-0277 entries as Movim and other clients publish them. */
public class PostParsingTest {

    private static final String XHTML = "http://www.w3.org/1999/xhtml";

    private static Element text(final Element parent, final String name, final String content) {
        // The XML reader sets the resolved namespace on every element; mirror that.
        final String ns = parent.getNamespace();
        final Element e =
                ns == null || XHTML.equals(ns) ? parent.addChild(name) : parent.addChild(name, ns);
        e.setContent(content);
        return e;
    }

    /** {@code <p>Hello <strong>world</strong>, see <a href=…>this</a></p><p>Second</p>} */
    private static Element xhtmlContent() {
        final Element content = new Element("content", Namespace.ATOM);
        content.setAttribute("type", "xhtml");
        final Element div = content.addChild("div", XHTML);
        final Element p1 = div.addChild("p");
        p1.addChild(new TextNode("Hello "));
        text(p1, "strong", "world");
        p1.addChild(new TextNode(", see "));
        text(p1, "a", "this").setAttribute("href", "https://example.org/x");
        final Element p2 = div.addChild("p");
        p2.addChild(new TextNode("Second\n   paragraph"));
        final Element bad = text(div, "a", "click");
        bad.setAttribute("href", "javascript:alert(1)");
        return content;
    }

    @Test
    public void xhtmlBecomesMarkdownWithParagraphsAndSafeLinks() {
        Assert.assertEquals(
                "Hello **world**, see [this](https://example.org/x)\n\nSecond paragraph\n\nclick",
                AtomText.toMarkdown(xhtmlContent()));
    }

    @Test
    public void textConstructIsTakenAsIs() {
        final Element title = new Element("title", Namespace.ATOM);
        title.setAttribute("type", "text");
        title.setContent("a *plain* title");
        Assert.assertEquals("a *plain* title", AtomText.toMarkdown(title));
    }

    @Test
    public void escapedHtmlKeepsBreaksAndDropsTags() {
        final Element content = new Element("content", Namespace.ATOM);
        content.setAttribute("type", "html");
        content.setContent("<p>One &amp; two</p><p>three<br/>four</p>");
        Assert.assertEquals("One & two\n\nthree\nfour", AtomText.toMarkdown(content));
    }

    private static Element movimItem() {
        final Element item = new Element("item");
        item.setAttribute("id", "my-post-abc");
        final Element entry = item.addChild("entry", Namespace.ATOM);
        text(entry, "title", "Hello from Movim").setAttribute("type", "text");
        entry.addChild(xhtmlContent());
        final Element author = entry.addChild("author", Namespace.ATOM);
        text(author, "name", "Juliet");
        text(author, "uri", "xmpp:juliet@capulet.lit");
        // No <published>: the date comes from <updated>.
        text(entry, "updated", "2026-10-01T12:00:00Z");
        entry.addChild("link", Namespace.ATOM)
                .setAttribute("rel", "alternate")
                .setAttribute(
                        "href",
                        "xmpp:juliet@capulet.lit?;node=urn:xmpp:microblog:0;item=my-post-abc");
        entry.addChild("link", Namespace.ATOM)
                .setAttribute("rel", "replies")
                .setAttribute("title", "other")
                .setAttribute("href", "xmpp:elsewhere.lit?;node=x");
        entry.addChild("link", Namespace.ATOM)
                .setAttribute("rel", "replies")
                .setAttribute("title", "comments")
                .setAttribute(
                        "href",
                        "xmpp:juliet@capulet.lit?;node=urn:xmpp:microblog:0:comments/my-post-abc");
        entry.addChild("link", Namespace.ATOM)
                .setAttribute("rel", "enclosure")
                .setAttribute("href", "https://upload.capulet.lit/a/photo.jpg");
        entry.addChild("link", Namespace.ATOM)
                .setAttribute("rel", "enclosure")
                .setAttribute("href", "https://upload.capulet.lit/b/second.png")
                .setAttribute("type", "image/png");
        entry.addChild("category", Namespace.ATOM).setAttribute("term", "Balcony");
        entry.addChild("category", Namespace.ATOM).setAttribute("term", "world");
        return item;
    }

    @Test
    public void movimEntryIsParsed() {
        final Post post = Post.fromElement(movimItem(), Jid.of("juliet@capulet.lit"));
        Assert.assertNotNull(post);
        Assert.assertEquals("my-post-abc", post.getId());
        Assert.assertEquals("Hello from Movim", post.getTitle());
        Assert.assertTrue(post.getContent().startsWith("Hello **world**"));
        // Categories become hashtags, without repeating one already in the text.
        Assert.assertTrue(post.getContent().endsWith("#Balcony #world"));
        Assert.assertEquals(
                "xmpp:juliet@capulet.lit?;node=urn:xmpp:microblog:0:comments/my-post-abc",
                post.getCommentsNode());
        Assert.assertEquals("https://upload.capulet.lit/a/photo.jpg", post.getAttachmentUrl());
        Assert.assertEquals("image/jpeg", post.getAttachmentType());
        Assert.assertNotNull(post.getPublished());
        Assert.assertEquals(Jid.of("juliet@capulet.lit"), post.getAuthor());
    }

    @Test
    public void entryOfAnotherAuthorIsRejected() {
        Assert.assertNull(Post.fromElement(movimItem(), Jid.of("mallory@evil.lit")));
    }

    @Test
    public void hashtagsAreExtractedOnce() {
        final List<String> tags =
                Post.extractHashtags(
                        "#XMPP rocks, #xmpp again, mail@#nothere, https://x.org/#anchor and"
                                + " #über_cool");
        Assert.assertEquals(List.of("xmpp", "über_cool"), tags);
    }

    @Test
    public void sameIdFromDifferentAuthorsAreDifferentPosts() {
        final Post a =
                new Post("hello", "t", "c", Jid.of("alice@a.lit"), null, null, null, null, null);
        final Post b =
                new Post("hello", "t", "c", Jid.of("bob@b.lit"), null, null, null, null, null);
        final Post edit =
                new Post("hello", "t2", "c2", Jid.of("alice@a.lit"), null, null, null, null, null);
        Assert.assertNotEquals(a, b);
        Assert.assertEquals(a, edit);
        Assert.assertEquals(a.hashCode(), edit.hashCode());
    }

    @Test
    public void markdownSourceWinsOverRenderedXhtml() {
        final Element item = new Element("item");
        item.setAttribute("id", "p1");
        final Element entry = item.addChild("entry", Namespace.ATOM);
        text(entry, "title", "Title");
        // As published by monocles and Movim: the rendered body first, the source second.
        final Element xhtml = entry.addChild("content", Namespace.ATOM);
        xhtml.setAttribute("type", "xhtml");
        xhtml.addChild(eu.siacs.conversations.utils.MarkdownToXhtml.toXhtmlDiv("a\nb"));
        text(entry, "content", "a\nb  *exact* source").setAttribute("type", "text");
        final Post post = Post.fromElement(item, Jid.of("juliet@capulet.lit"));
        Assert.assertNotNull(post);
        Assert.assertEquals("a\nb  *exact* source", post.getContent());
    }
}
