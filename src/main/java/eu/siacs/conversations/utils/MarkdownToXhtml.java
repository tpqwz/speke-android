package eu.siacs.conversations.utils;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.TextNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Renders the Markdown of a post as the XHTML {@code <div>} of an Atom {@code <content
 * type="xhtml">}, which is the only content Movim displays.
 *
 * <p>The tree is built from the parsed document rather than from rendered HTML, so the result is
 * well-formed by construction and text is escaped by the serializer. Raw HTML in the source is kept
 * as literal text, and links are limited to http(s)/xmpp. Every newline is kept as a line break,
 * matching how the feed view shows the text.
 */
public final class MarkdownToXhtml {

    public static final String XHTML = "http://www.w3.org/1999/xhtml";

    private MarkdownToXhtml() {}

    public static Element toXhtmlDiv(final String markdown) {
        final Element div = new Element("div", XHTML);
        if (markdown == null || markdown.trim().isEmpty()) {
            return div;
        }
        final Node document = Parser.builder().build().parse(markdown);
        document.accept(new Builder(div));
        return div;
    }

    private static boolean isSafeLink(final String href) {
        if (href == null) {
            return false;
        }
        final String lower = href.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://")
                || lower.startsWith("http://")
                || lower.startsWith("xmpp:");
    }

    private static final class Builder extends AbstractVisitor {

        private final Deque<Element> stack = new ArrayDeque<>();

        Builder(final Element root) {
            stack.push(root);
        }

        private Element current() {
            return stack.peek();
        }

        private void text(final String text) {
            if (text != null && !text.isEmpty()) {
                current().addChild(new TextNode(text));
            }
        }

        private void wrap(final String name, final Node node) {
            final Element element = current().addChild(name);
            stack.push(element);
            visitChildren(node);
            stack.pop();
        }

        @Override
        public void visit(final Paragraph paragraph) {
            // Paragraphs directly inside a list item (tight lists) don't get a <p>.
            if (paragraph.getParent() instanceof ListItem) {
                visitChildren(paragraph);
            } else {
                wrap("p", paragraph);
            }
        }

        @Override
        public void visit(final Text text) {
            text(text.getLiteral());
        }

        @Override
        public void visit(final SoftLineBreak softLineBreak) {
            current().addChild("br");
        }

        @Override
        public void visit(final HardLineBreak hardLineBreak) {
            current().addChild("br");
        }

        @Override
        public void visit(final Emphasis emphasis) {
            wrap("em", emphasis);
        }

        @Override
        public void visit(final StrongEmphasis strongEmphasis) {
            wrap("strong", strongEmphasis);
        }

        @Override
        public void visit(final Code code) {
            current().addChild("code").addChild(new TextNode(code.getLiteral()));
        }

        @Override
        public void visit(final FencedCodeBlock codeBlock) {
            current()
                    .addChild("pre")
                    .addChild("code")
                    .addChild(new TextNode(stripTrailingNewline(codeBlock.getLiteral())));
        }

        @Override
        public void visit(final IndentedCodeBlock codeBlock) {
            current()
                    .addChild("pre")
                    .addChild("code")
                    .addChild(new TextNode(stripTrailingNewline(codeBlock.getLiteral())));
        }

        @Override
        public void visit(final Heading heading) {
            wrap("h" + Math.max(1, Math.min(6, heading.getLevel())), heading);
        }

        @Override
        public void visit(final BlockQuote blockQuote) {
            wrap("blockquote", blockQuote);
        }

        @Override
        public void visit(final BulletList bulletList) {
            wrap("ul", bulletList);
        }

        @Override
        public void visit(final OrderedList orderedList) {
            final Element ol = current().addChild("ol");
            if (orderedList.getStartNumber() != 1) {
                ol.setAttribute("start", String.valueOf(orderedList.getStartNumber()));
            }
            stack.push(ol);
            visitChildren(orderedList);
            stack.pop();
        }

        @Override
        public void visit(final ListItem listItem) {
            wrap("li", listItem);
        }

        @Override
        public void visit(final Link link) {
            if (isSafeLink(link.getDestination())) {
                final Element a = current().addChild("a");
                a.setAttribute("href", link.getDestination().trim());
                stack.push(a);
                visitChildren(link);
                stack.pop();
                if (a.getChildNodes().isEmpty()) {
                    a.addChild(new TextNode(link.getDestination().trim()));
                }
            } else {
                visitChildren(link);
            }
        }

        @Override
        public void visit(final Image image) {
            // A link rather than an inline image: the attachment is shown separately, and remote
            // images should only load when the reader asks for them.
            if (isSafeLink(image.getDestination())) {
                final Element a = current().addChild("a");
                a.setAttribute("href", image.getDestination().trim());
                stack.push(a);
                visitChildren(image);
                stack.pop();
                if (a.getChildNodes().isEmpty()) {
                    a.addChild(new TextNode(image.getDestination().trim()));
                }
            } else {
                visitChildren(image);
            }
        }

        @Override
        public void visit(final ThematicBreak thematicBreak) {
            current().addChild("hr");
        }

        @Override
        public void visit(final HtmlInline htmlInline) {
            // Never pass raw HTML through; show it as written.
            text(htmlInline.getLiteral());
        }

        @Override
        public void visit(final HtmlBlock htmlBlock) {
            current()
                    .addChild("p")
                    .addChild(new TextNode(stripTrailingNewline(htmlBlock.getLiteral())));
        }

        @Override
        public void visit(final CustomBlock customBlock) {
            visitChildren(customBlock);
        }

        @Override
        public void visit(final CustomNode customNode) {
            visitChildren(customNode);
        }

        private static String stripTrailingNewline(final String s) {
            return s != null && s.endsWith("\n")
                    ? s.substring(0, s.length() - 1)
                    : (s == null ? "" : s);
        }
    }
}
