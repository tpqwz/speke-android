package eu.siacs.conversations.utils;

import eu.siacs.conversations.xml.Element;
import org.junit.Assert;
import org.junit.Test;

/** The XHTML body published next to a post's Markdown (the only content Movim shows). */
public class MarkdownToXhtmlTest {

    private static String render(final String markdown) {
        return MarkdownToXhtml.toXhtmlDiv(markdown).toString();
    }

    @Test
    public void singleNewlinesBecomeLineBreaksAndBlankLinesParagraphs() {
        Assert.assertEquals(
                "<div xmlns=\"http://www.w3.org/1999/xhtml\"><p>first line<br/>second"
                        + " line</p><p>new paragraph</p></div>",
                render("first line\nsecond line\n\nnew paragraph"));
    }

    @Test
    public void inlineFormattingAndSafeLinks() {
        Assert.assertEquals(
                "<div xmlns=\"http://www.w3.org/1999/xhtml\"><p><strong>bold</strong> <em>it</em>"
                    + " <code>x&lt;y</code> <a href=\"https://example.org\">site</a> bad</p></div>",
                render(
                        "**bold** *it* `x<y` [site](https://example.org)"
                                + " [bad](javascript:alert(1))"));
    }

    @Test
    public void rawHtmlIsKeptAsText() {
        final String xml = render("hi <script>alert(1)</script> & bye");
        Assert.assertFalse(xml.contains("<script>"));
        Assert.assertTrue(xml.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; bye"));
    }

    @Test
    public void listsAndQuotes() {
        Assert.assertEquals(
                "<div xmlns=\"http://www.w3.org/1999/xhtml\"><ul><li>one</li><li>two</li></ul><blockquote><p>quoted</p></blockquote></div>",
                render("- one\n- two\n\n> quoted"));
    }

    @Test
    public void roundTripThroughAtomTextKeepsLineBreaks() {
        final Element content = new Element("content", "http://www.w3.org/2005/Atom");
        content.setAttribute("type", "xhtml");
        content.addChild(MarkdownToXhtml.toXhtmlDiv("line one\nline two\n\n**next**"));
        Assert.assertEquals("line one\nline two\n\n**next**", AtomText.toMarkdown(content));
    }

    @Test
    public void emptyInputGivesEmptyDiv() {
        Assert.assertEquals("<div xmlns=\"http://www.w3.org/1999/xhtml\"/>", render("  "));
    }
}
